package com.local.fanctl;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

/**
 * A311D 盒子（菜鸟 / LEMO CORE 一系）风扇控制。
 *
 * 反编译自 com.local.fanctl v8.0（原作者：海东青 · FanCtl · 2026，二次整理：G.W.J），并做了以下工程化整理：
 *  1. 去掉单字节 XOR 混淆，路径改为可读常量；
 *  2. 去掉 ROW_NONE / ROW_MAN 之类的常量别名，恢复原值；
 *  3. 补齐资源、Gradle 工程结构，使其可直接在 CI 编译出包。
 *
 * 运行前提：盒子必须有 root，所有硬件操作都通过 su -c 执行 shell。
 */
public class FanCtlActivity extends Activity {

    /** 手动模式标记文件：存在时后台脚本暂停温控 */
    private static final String P_MAN = "/data/local/fan_manual";
    /** 配置：一行 "S M H PS PM PH"（温度阈值 + 对应转速百分比，后三项缺省为 20 60 100） */
    private static final String P_CFG = "/data/local/fan_cfg";
    /** 后台脚本日志 */
    private static final String P_LOG = "/data/local/fan.log";
    /** 开机脚本落地目录 */
    private static final String P_INIT = "/data/media/0/init.d";
    /** 开机脚本路径 */
    private static final String P_SCRIPT = P_INIT + "/90fan";
    /** 底包用来触发 user scripts 的属性 */
    private static final String P_PROP = "persist.sys.user_scripts";
    /** AML 寄存器读写入口（debugfs） */
    private static final String P_PADDR = "/sys/kernel/debug/aml_reg/paddr";
    /** PWM 控制器（板级定制，换板需重新核对） */
    private static final String P_PWM = "/sys/class/pwm/pwmchip4";
    /** PWM 周期 40000ns = 25kHz */
    private static final int PWM_PERIOD = 40000;

    private static final int ROW_NONE = 0;
    private static final int ROW_MAN = 1;
    private static final int ROW_START = 2;
    private static final int ROW_FULL = 3;
    private static final int ROW_MID = 4;
    private static final int ROW_PS = 5;
    private static final int ROW_PM = 6;
    private static final int ROW_PH = 7;
    /** 转速百分比调整步长 */
    private static final int PCT_STEP = 5;

    private static final int FOCUS_BG = Color.rgb(47, 111, 181);
    /** 按钮常态底色：深板岩灰，白字可读 */
    private static final int BTN_BG = Color.rgb(52, 62, 78);
    /** 按钮聚焦底色：琥珀橙，与深色界面对比最强 */
    private static final int BTN_FOCUS_BG = Color.rgb(255, 167, 38);

    private final Handler handler = new Handler();
    private SharedPreferences prefs;
    private TextView status;
    private TextView sub;
    private TextView tip;
    private TextView manLbl;
    private TextView startLbl;
    private TextView psLbl;
    private TextView midLbl;
    private TextView pmLbl;
    private TextView fullLbl;
    private TextView phLbl;

    private boolean running = false;
    private boolean rebootArmed = false;

    private int cfgS = 30;   // 默认起步 30℃
    private int cfgM = 45;   // 默认中速 45℃
    private int cfgH = 60;   // 默认全速 60℃
    private int cfgPS = 20;  // 起步温度处的转速 %
    private int cfgPM = 60;  // 中速温度处的转速 %
    private int cfgPH = 100; // 全速温度处的转速 %
    private int manPct = 50;
    private int focusedRow = ROW_NONE;

    private final Runnable saveRun = new Runnable() {
        @Override
        public void run() {
            saveCfg();
        }
    };

    private final Runnable manRun = new Runnable() {
        @Override
        public void run() {
            tip.setText("手动模式：温控已暂停，按「自动」恢复");
            applyManual();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            refresh();
            handler.postDelayed(this, 3000L);
        }
    };

    // ------------------------------------------------------------------ 工具

    private int clamp(int v, int min, int max) {
        if (v < min) {
            return min;
        }
        return v > max ? max : v;
    }

    /** 以 root 执行一条 shell，返回合并后的 stdout + stderr */
    private String sh(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (true) {
                String line = out.readLine();
                if (line == null) {
                    break;
                }
                sb.append(line).append('\n');
            }
            BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while (true) {
                String line = err.readLine();
                if (line == null) {
                    p.waitFor();
                    return sb.toString().trim();
                }
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ 硬件

    /** 初始化 PWM：挂 debugfs、写 17 个寄存器做引脚复用，再开 PWM 通道 */
    private String initPwmCmd() {
        return "mount -t debugfs none /sys/kernel/debug 2>/dev/null;"
                + "G=" + P_PADDR + ";"
                + "echo \"ff6346c0 0x11111111\" > $G 2>/dev/null;"
                + "echo \"ff6346c4 0x22102101\" > $G 2>/dev/null;"
                + "echo \"ff6346c8 0x0\" > $G 2>/dev/null;"
                + "echo \"ff6346cc 0x111111\" > $G 2>/dev/null;"
                + "echo \"ff6346d0 0x11111112\" > $G 2>/dev/null;"
                + "echo \"ff6346d4 0x1\" > $G 2>/dev/null;"
                + "echo \"ff6346d8 0x11111111\" > $G 2>/dev/null;"
                + "echo \"ff6346dc 0x33111111\" > $G 2>/dev/null;"
                + "echo \"ff6346e0 0x0\" > $G 2>/dev/null;"
                + "echo \"ff6346e4 0x0\" > $G 2>/dev/null;"
                + "echo \"ff6346e8 0x0\" > $G 2>/dev/null;"
                + "echo \"ff6346ec 0x335111\" > $G 2>/dev/null;"
                + "echo \"ff6346f0 0x0\" > $G 2>/dev/null;"
                + "echo \"ff6346f4 0x11030001\" > $G 2>/dev/null;"
                + "echo \"ff6346f8 0x222301\" > $G 2>/dev/null;"
                + "echo \"ff6346fc 0x0\" > $G 2>/dev/null;"
                + "C=" + P_PWM + ";"
                + "[ -d $C/pwm1 ] || echo 1 > $C/export;"
                + "echo " + PWM_PERIOD + " > $C/pwm1/period;"
                + "echo 1 > $C/pwm1/enable;";
    }

    /** 手动设定占空比（写入后 touch 标记文件，进入手动模式） */
    private void setSpeed(final int duty) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                sh(initPwmCmd() + "echo " + duty + " > $C/pwm1/duty_cycle;touch " + P_MAN);
                refresh();
            }
        }).start();
    }

    /** 回到自动温控 */
    private void setAuto() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                sh("rm -f " + P_MAN);
                refresh();
            }
        }).start();
    }

    /** duty 低有效：风量 100% 对应 duty 0，风量 0% 对应 duty 40000 */
    private void applyManual() {
        setSpeed(((100 - manPct) * PWM_PERIOD) / 100);
    }

    private void saveCfg() {
        // 温度阈值必须严格递增，且留出 1℃ 间隔
        if (cfgM < cfgS + ROW_FULL) {
            cfgM = cfgS + ROW_FULL;
        }
        if (cfgM > 70) {
            cfgM = 70;
        }
        if (cfgH < cfgM + ROW_FULL) {
            cfgH = cfgM + ROW_FULL;
        }
        if (cfgH > 85) {
            cfgH = 85;
        }
        // 转速百分比同样要递增：0 ≤ 起步 < 中速 < 全速 ≤ 100
        cfgPS = clamp(cfgPS, 0, 90);
        if (cfgPM < cfgPS + PCT_STEP) {
            cfgPM = cfgPS + PCT_STEP;
        }
        if (cfgPM > 95) {
            cfgPM = 95;
        }
        if (cfgPH < cfgPM + PCT_STEP) {
            cfgPH = cfgPM + PCT_STEP;
        }
        cfgPH = clamp(cfgPH, 0, 100);

        final int fs = cfgS;
        final int fm = cfgM;
        final int fh = cfgH;
        final int ps = cfgPS;
        final int pm = cfgPM;
        final int ph = cfgPH;
        prefs.edit().putInt("cfgS", fs).putInt("cfgM", fm).putInt("cfgH", fh)
                .putInt("cfgPS", ps).putInt("cfgPM", pm).putInt("cfgPH", ph).commit();
        tip.setText("已保存：" + fs + "℃→" + ps + "% / " + fm + "℃→" + pm + "% / " + fh + "℃→" + ph + "%，5秒内生效");
        new Thread(new Runnable() {
            @Override
            public void run() {
                sh("echo '" + fs + " " + fm + " " + fh + " " + ps + " " + pm + " " + ph + "' > " + P_CFG);
                refresh();
            }
        }).start();
    }

    /** 开机自启脚本内容（每行一条，逐行 echo 写入目标文件） */
    private String[] fanScript() {
        return new String[]{
                "#!/system/bin/sh",
                "mount -t debugfs none /sys/kernel/debug 2>/dev/null",
                "G=" + P_PADDR,
                "echo \"ff6346c0 0x11111111\" > $G",
                "echo \"ff6346c4 0x22102101\" > $G",
                "echo \"ff6346c8 0x0\" > $G",
                "echo \"ff6346cc 0x111111\" > $G",
                "echo \"ff6346d0 0x11111112\" > $G",
                "echo \"ff6346d4 0x1\" > $G",
                "echo \"ff6346d8 0x11111111\" > $G",
                "echo \"ff6346dc 0x33111111\" > $G",
                "echo \"ff6346e0 0x0\" > $G",
                "echo \"ff6346e4 0x0\" > $G",
                "echo \"ff6346e8 0x0\" > $G",
                "echo \"ff6346ec 0x335111\" > $G",
                "echo \"ff6346f0 0x0\" > $G",
                "echo \"ff6346f4 0x11030001\" > $G",
                "echo \"ff6346f8 0x222301\" > $G",
                "echo \"ff6346fc 0x0\" > $G",
                "C=" + P_PWM,
                "[ -d $C/pwm1 ] || echo 1 > $C/export",
                "P=$C/pwm1",
                "echo " + PWM_PERIOD + " > $P/period",
                "echo 32000 > $P/duty_cycle",
                "echo 1 > $P/enable",
                "(",
                "while :; do",
                "Mn=" + P_MAN,
                "[ -f $Mn ] && sleep 5 && continue",
                "CF=" + P_CFG,
                // 默认值与 App 保持一致：温度 30/45/60，转速 20/60/100
                "S=30; Md=45; H=60; PS=20; PM=60; PH=100",
                "[ -f $CF ] && read S Md H PS PM PH < $CF",
                "[ -z $H ] && H=60",
                "[ -z $PS ] && PS=20",
                "[ -z $PM ] && PM=60",
                "[ -z $PH ] && PH=100",
                // 阈值必须严格递增，否则下面的除法会出错
                "[ $Md -le $S ] && Md=$((S+1))",
                "[ $H -le $Md ] && H=$((Md+1))",
                // 转速百分比做一次兜底校正：0 <= PS < PM < PH <= 100
                "[ $PS -lt 0 ] && PS=0",
                "[ $PM -le $PS ] && PM=$((PS+1))",
                "[ $PH -le $PM ] && PH=$((PM+1))",
                "[ $PH -gt 100 ] && PH=100",
                "T=$(cat /sys/class/thermal/thermal_zone0/temp)",
                "T=$((T/1000))",
                // 风量：S 处 PS%，M 处 PM%，H 处 PH%，区间内按温度线性过渡
                "P=0",
                "if [ $T -ge $H ]; then P=$PH;",
                "elif [ $T -ge $Md ]; then P=$((PM + (PH - PM) * (T - Md) / (H - Md)));",
                "elif [ $T -ge $S ]; then P=$((PS + (PM - PS) * (T - S) / (Md - S)));",
                "fi",
                // duty 低有效：风量 P% => duty (100-P)*period/100
                "D=$(((100 - P) * " + PWM_PERIOD + " / 100))",
                "echo $D > $P/duty_cycle",
                "sleep 5",
                "done",
                ") &",
                "echo \"$(date) 90fan ok\" >> " + P_LOG
        };
    }

    /** 写开机自启脚本，需 root */
    private boolean doInstall() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su"});
            DataOutputStream out = new DataOutputStream(p.getOutputStream());
            out.writeBytes("mkdir -p " + P_INIT + "\n");
            out.writeBytes("F=" + P_SCRIPT + "\n");
            out.writeBytes("> $F\n");
            String[] script = fanScript();
            for (String line : script) {
                out.writeBytes("echo '" + line + "' >> $F\n");
            }
            out.writeBytes("chmod 755 $F\n");
            out.writeBytes("setprop " + P_PROP + " 1\n");
            out.writeBytes("[ -x $F ] && echo INSTALL_OK\n");
            out.writeBytes("exit\n");
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            while (true) {
                String line = in.readLine();
                if (line == null) {
                    p.waitFor();
                    return sb.toString().contains("INSTALL_OK");
                }
                sb.append(line);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void installBoot() {
        tip.setText("正在安装开机自启脚本...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = doInstall();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        tip.setText(ok
                                ? "安装成功！点「重启盒子」生效（重启后开机自动转）"
                                : "安装失败：请先允许 root 授权再试");
                    }
                });
            }
        }).start();
    }

    private void rebootBox() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                sh("reboot");
            }
        }).start();
    }

    // ------------------------------------------------------------------ 状态

    private void updLbls() {
        manLbl.setText("手动风量\u3000" + manPct + "%\u3000（0=停 100=全速）");
        startLbl.setText("起步温度\u3000" + cfgS + "℃\u3000（低于→停转）");
        psLbl.setText("起步转速\u3000" + cfgPS + "%\u3000（到起步温度时）");
        midLbl.setText("中速温度\u3000" + cfgM + "℃");
        pmLbl.setText("中速转速\u3000" + cfgPM + "%\u3000（到中速温度时）");
        fullLbl.setText("全速温度\u3000" + cfgH + "℃");
        phLbl.setText("全速转速\u3000" + cfgPH + "%\u3000（到全速温度及以上）");
    }

    private void loadCfg() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String[] parts = sh("cat " + P_CFG + " 2>/dev/null").trim().split("\\s+");
                    cfgS = clamp(Integer.parseInt(parts[0]), 30, 55);
                    cfgM = clamp(Integer.parseInt(parts[1]), cfgS + ROW_FULL, 70);
                    cfgH = parts.length > 2
                            ? clamp(Integer.parseInt(parts[2]), cfgM + ROW_FULL, 85)
                            : clamp(((cfgM + cfgH) / 2) + ROW_FULL, cfgM + ROW_FULL, 85);
                    // 老版本配置文件只有 "S M H" 三项，缺省按 20/60/100
                    cfgPS = parts.length > 3 ? clamp(Integer.parseInt(parts[3]), 0, 90) : 20;
                    cfgPM = parts.length > 4 ? clamp(Integer.parseInt(parts[4]), cfgPS + PCT_STEP, 95) : 60;
                    cfgPH = parts.length > 5 ? clamp(Integer.parseInt(parts[5]), cfgPM + PCT_STEP, 100) : 100;
                    prefs.edit().putInt("cfgS", cfgS).putInt("cfgM", cfgM).putInt("cfgH", cfgH)
                            .putInt("cfgPS", cfgPS).putInt("cfgPM", cfgPM).putInt("cfgPH", cfgPH).apply();
                } catch (Exception ignored) {
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        updLbls();
                    }
                });
            }
        }).start();
    }

    private void refresh() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String out = sh("T=$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null);"
                        + "D=$(cat " + P_PWM + "/pwm1/duty_cycle 2>/dev/null);"
                        + "if [ -f " + P_MAN + " ]; then M=s; else M=a; fi;"
                        + "echo \"$T|$D|$M\"");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        showStatus(out);
                    }
                });
            }
        }).start();
    }

    private void showStatus(String raw) {
        try {
            String[] parts = raw.split("\\|");
            int temp = Integer.parseInt(parts[0].trim()) / 1000;
            int duty = Integer.parseInt(parts[1].trim());
            int pct = 0;
            if (duty < PWM_PERIOD) {
                pct = Math.max(0, Math.min(100, (PWM_PERIOD - duty) / 400));
            }
            String mode = "s".equals(parts[2])
                    ? "手动（" + manPct + "%）"
                    : "自动（" + cfgS + "℃→" + cfgPS + "% → " + cfgM + "℃→" + cfgPM + "% → " + cfgH + "℃→" + cfgPH + "%）";
            status.setText("温度 " + temp + "°C    风量约 " + pct + "%");
            sub.setText("模式：" + mode);
        } catch (Exception e) {
            status.setText("暂时读不到状态");
            sub.setText(raw.length() == 0 ? "（无输出，请确认 root 授权已允许）" : raw);
        }
    }

    // ------------------------------------------------------------------ UI

    /**
     * 生成带焦点态的圆角背景：常态深色细描边，聚焦亮色填充 + 白色粗描边，
     * 远距离（电视/投影）也能一眼看出当前选中项。
     */
    private Drawable stateBg(int normalColor, int focusColor, float radiusDp, int normalStrokeDp, int focusStrokeDp) {
        float d = getResources().getDisplayMetrics().density;

        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(radiusDp * d);
        normal.setColor(normalColor);
        normal.setStroke(Math.round(normalStrokeDp * d), Color.rgb(122, 134, 154));

        GradientDrawable focus = new GradientDrawable();
        focus.setShape(GradientDrawable.RECTANGLE);
        focus.setCornerRadius(radiusDp * d);
        focus.setColor(focusColor);
        focus.setStroke(Math.max(1, Math.round(focusStrokeDp * d)), Color.WHITE);

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focus);
        sl.addState(new int[]{android.R.attr.state_pressed}, focus);
        sl.addState(new int[]{}, normal);
        return sl;
    }

    /** 统一按钮外观：所有按钮共用同一套常态/聚焦配色与描边，避免出现有的按钮高亮明显、有的发灰 */
    private void styleBtn(Button b) {
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(22.0f);
        b.setBackground(stateBg(BTN_BG, BTN_FOCUS_BG, 16.0f, 1, 4));
        // 聚焦时文字转深色（亮橙底上黑字最清楚），常态白字
        b.setTextColor(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_pressed},
                        new int[]{}
                },
                new int[]{Color.rgb(26, 26, 26), Color.rgb(26, 26, 26), Color.WHITE}));
    }

    private Button btn(String text, final int duty) {
        Button b = new Button(this);
        b.setText(text);
        styleBtn(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ROW_NONE, -2, 1.0f);
        lp.setMargins(10, 10, 10, 10);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (duty < 0) {
                    setAuto();
                    tip.setText("已切回自动模式");
                } else {
                    setSpeed(duty);
                }
            }
        });
        return b;
    }

    private LinearLayout row(TextView label, final int id) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        // 行也有明显焦点态：常态透明，聚焦时蓝色填充 + 白色描边
        ll.setBackground(stateBg(Color.TRANSPARENT, FOCUS_BG, 10.0f, 0, 3));
        ll.setPadding(20, 14, 20, 14);
        ll.addView(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(10, 6, 10, 6);
        ll.setLayoutParams(lp);
        ll.setTag(Integer.valueOf(id));
        ll.setFocusable(true);
        ll.setFocusableInTouchMode(true);
        ll.setClickable(true);
        ll.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                focusedRow = hasFocus ? ((Integer) v.getTag()).intValue() : ROW_NONE;
            }
        });
        ll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (id == ROW_MAN) {
                    manRun.run();
                } else {
                    saveCfg();
                }
            }
        });
        return ll;
    }

    private TextView label(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(20.0f);
        tv.setTextColor(color);
        return tv;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int delta = code == KeyEvent.KEYCODE_DPAD_RIGHT ? ROW_MAN : -1;
            if (focusedRow == ROW_MAN) {
                manPct = clamp(manPct + (delta * 5), 0, 100);
                prefs.edit().putInt("manPct", manPct).commit();
                updLbls();
                handler.removeCallbacks(manRun);
                handler.postDelayed(manRun, 600L);
                return true;
            }
            if (focusedRow == ROW_START) {
                cfgS = clamp(cfgS + delta, 30, 55);
                cfgM = clamp(cfgM, cfgS + ROW_FULL, 70);
                cfgH = clamp(cfgH, cfgM + ROW_FULL, 85);
                updLbls();
                handler.removeCallbacks(saveRun);
                handler.postDelayed(saveRun, 1000L);
                return true;
            }
            if (focusedRow == ROW_MID) {
                cfgM = clamp(cfgM + delta, cfgS + ROW_FULL, 70);
                cfgH = clamp(cfgH, cfgM + ROW_FULL, 85);
                updLbls();
                handler.removeCallbacks(saveRun);
                handler.postDelayed(saveRun, 1000L);
                return true;
            }
            if (focusedRow == ROW_FULL) {
                cfgH = clamp(cfgH + delta, cfgM + ROW_FULL, 85);
                updLbls();
                handler.removeCallbacks(saveRun);
                handler.postDelayed(saveRun, 1000L);
                return true;
            }
            if (focusedRow == ROW_PS) {
                cfgPS = clamp(cfgPS + delta * PCT_STEP, 0, cfgPM - PCT_STEP);
                updLbls();
                handler.removeCallbacks(saveRun);
                handler.postDelayed(saveRun, 1000L);
                return true;
            }
            if (focusedRow == ROW_PM) {
                cfgPM = clamp(cfgPM + delta * PCT_STEP, cfgPS + PCT_STEP, cfgPH - PCT_STEP);
                updLbls();
                handler.removeCallbacks(saveRun);
                handler.postDelayed(saveRun, 1000L);
                return true;
            }
            if (focusedRow == ROW_PH) {
                cfgPH = clamp(cfgPH + delta * PCT_STEP, cfgPM + PCT_STEP, 100);
                updLbls();
                handler.removeCallbacks(saveRun);
                handler.postDelayed(saveRun, 1000L);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("fanctl", MODE_PRIVATE);
        cfgS = clamp(prefs.getInt("cfgS", 30), 30, 55);
        cfgM = clamp(prefs.getInt("cfgM", 45), cfgS + ROW_FULL, 70);
        cfgH = clamp(prefs.getInt("cfgH", 60), cfgM + ROW_FULL, 85);
        manPct = clamp(prefs.getInt("manPct", 50), 0, 100);
        cfgPS = clamp(prefs.getInt("cfgPS", 20), 0, 90);
        cfgPM = clamp(prefs.getInt("cfgPM", 60), cfgPS + PCT_STEP, 95);
        cfgPH = clamp(prefs.getInt("cfgPH", 100), cfgPM + PCT_STEP, 100);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(96, 72, 96, 72);
        root.setBackgroundColor(Color.rgb(16, 20, 28));

        // 左栏：标题 + 实时状态
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(ROW_NONE, -1, 1.0f);
        lpLeft.setMargins(ROW_NONE, ROW_NONE, 56, ROW_NONE);
        left.setLayoutParams(lpLeft);

        TextView title = new TextView(this);
        title.setText("风扇控制");
        title.setTextSize(48.0f);
        title.setTextColor(Color.WHITE);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(ROW_NONE, ROW_NONE, ROW_NONE, 36);
        left.addView(title);

        status = new TextView(this);
        status.setTextSize(38.0f);
        status.setTextColor(Color.rgb(80, 220, 120));
        status.setGravity(android.view.Gravity.CENTER);
        left.addView(status);

        sub = new TextView(this);
        sub.setTextSize(22.0f);
        sub.setTextColor(Color.rgb(204, 204, 204));
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setPadding(ROW_NONE, 16, ROW_NONE, ROW_NONE);
        left.addView(sub);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(ROW_NONE, ROW_NONE, 1.0f));
        left.addView(spacer);

        tip = new TextView(this);
        tip.setText("上下移动焦点，左右键调数值，OK 确认");
        tip.setTextSize(18.0f);
        tip.setTextColor(Color.rgb(136, 136, 136));
        tip.setGravity(android.view.Gravity.CENTER);
        tip.setPadding(ROW_NONE, 24, ROW_NONE, 16);
        left.addView(tip);

        TextView author = new TextView(this);
        author.setText("制作：G.W.J");
        author.setTextSize(22.0f);
        author.setTextColor(Color.rgb(250, 199, 117));
        author.setGravity(android.view.Gravity.CENTER);
        left.addView(author);

        // 右栏：控制项
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(ROW_NONE, -1, 1.0f));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button autoBtn = btn("自动", -1);
        btnRow.addView(autoBtn);
        btnRow.addView(btn("全速", ROW_NONE));
        btnRow.addView(btn("停止", PWM_PERIOD));
        right.addView(btnRow);

        manLbl = label("", Color.rgb(120, 200, 255));
        right.addView(row(manLbl, ROW_MAN));
        startLbl = label("", Color.rgb(255, 200, 100));
        right.addView(row(startLbl, ROW_START));
        psLbl = label("", Color.rgb(255, 214, 140));
        right.addView(row(psLbl, ROW_PS));
        midLbl = label("", Color.rgb(255, 170, 90));
        right.addView(row(midLbl, ROW_MID));
        pmLbl = label("", Color.rgb(255, 186, 120));
        right.addView(row(pmLbl, ROW_PM));
        fullLbl = label("", Color.rgb(255, 130, 100));
        right.addView(row(fullLbl, ROW_FULL));
        phLbl = label("", Color.rgb(255, 150, 120));
        right.addView(row(phLbl, ROW_PH));
        updLbls();

        Button saveBtn = new Button(this);
        saveBtn.setText("保存温度与转速");
        styleBtn(saveBtn);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCfg();
            }
        });
        // 与下方按钮行的间距取原值的 1.5 倍（12→18、10→15），避免按键挤在一起
        LinearLayout.LayoutParams lpSave = new LinearLayout.LayoutParams(-1, -2);
        lpSave.setMargins(15, 18, 15, ROW_NONE);
        saveBtn.setLayoutParams(lpSave);
        right.addView(saveBtn);

        Button installBtn = new Button(this);
        installBtn.setText("安装开机自启");
        styleBtn(installBtn);
        installBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installBoot();
            }
        });

        Button rebootBtn = new Button(this);
        rebootBtn.setText("重启盒子");
        styleBtn(rebootBtn);
        rebootBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!rebootArmed) {
                    rebootArmed = true;
                    tip.setText("再按一次「重启盒子」确认（防误触）");
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            rebootArmed = false;
                        }
                    }, 5000L);
                } else {
                    rebootArmed = false;
                    tip.setText("正在重启，盒子回来后风扇就全自动了");
                    rebootBox();
                }
            }
        });

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        // 两个按钮等宽等高、同一条基线对齐：只给左右内间距，上下间距交给行本身
        LinearLayout.LayoutParams lpInstall = new LinearLayout.LayoutParams(0, -2, 1.0f);
        lpInstall.setMargins(ROW_NONE, ROW_NONE, 8, ROW_NONE);
        installBtn.setLayoutParams(lpInstall);

        LinearLayout.LayoutParams lpReboot = new LinearLayout.LayoutParams(0, -2, 1.0f);
        lpReboot.setMargins(8, ROW_NONE, ROW_NONE, ROW_NONE);
        rebootBtn.setLayoutParams(lpReboot);

        LinearLayout.LayoutParams lpBottom = new LinearLayout.LayoutParams(-1, -2);
        lpBottom.setMargins(15, 18, 15, 10);
        bottomRow.setLayoutParams(lpBottom);
        bottomRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        bottomRow.addView(installBtn);
        bottomRow.addView(rebootBtn);
        right.addView(bottomRow);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ROW_NONE, -1, 1.0f));
        scroll.setVerticalScrollBarEnabled(false);
        right.setLayoutParams(new FrameLayout.LayoutParams(-1, -2));
        scroll.addView(right);

        root.addView(left);
        root.addView(scroll);
        setContentView(root);

        autoBtn.requestFocus();
        loadCfg();
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacks(tick);
    }
}
