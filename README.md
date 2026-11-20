# LEMO-A311D-FanControl

菜鸟（LEMO CORE）A311D 盒子风扇控制，Android TV 版。

源码由 `com.local.fanctl` v8.0 反编译重建为可编译的标准 Gradle 工程，仅作学习与兼容研究用途。

## 关于设备：菜鸟（LEMO CORE）A311D 盒子

菜鸟物流场景流出的 Android TV 终端盒子，二手市场俗称「菜鸟盒子」，机身/固件内部标识为 **LEMO CORE**。这类机器原本是物流网点、驿站用的显示/播放终端，批量淘汰后流入二手市场，价格低、免维护，成了折腾 TV 盒子的热门起点。

- **主控**：晶晨 Amlogic A311D（4×Cortex-A73 + 2×Cortex-A53，带 NPU）
- **系统**：Android 9，内核 4.9.x
- **内存**：常见 2GB 版本；玩家会自行换 LPDDR4 颗粒扩到 4GB，**扩容后必须刷配套固件**，否则容易卡 `androidtv` logo 循环
- **存储**：eMMC，容量以实物为准
- **固件来源**：原厂没有公开固件下载站，底包基本靠拆机备份和玩家分流（数码之家、ZNDS 等），社区常见「萌新底包」这类基于原厂底包的精简包
- **刷新方式**：Amlogic USB Burning Tool + 双公头 USB 线，需短接主板进 MaskROM
- **板级定制**：风扇、灯带、喇叭都是这套硬件特有的，原厂底包内置专用控制 APP；换别的 A311D 通用包（如 Khadas VIM3 固件）会出现风扇暴走、灯带不亮、遥控失灵

> 本仓库只做**风扇**这一条线。灯带、喇叭的控制走原厂/定制底包里的内置 APP。

## 功能

- 智能温控风扇：起步 / 中速 / 全速三档温度阈值，5 秒轮询
- 手动风量 0–100%，与自动模式一键切换
- 安装开机自启脚本到 `/data/media/0/init.d/90fan`
- 遥控器操作：上下移动焦点，左右键调数值，OK 确认

## 默认参数

| 项 | 默认值 |
|---|---|
| 起步温度 S | 34 ℃（低于停转） |
| 中速温度 M | 50 ℃ |
| 全速温度 H | 63 ℃ |
| PWM 周期 | 40000 ns（25 kHz） |
| 轮询间隔 | 5 秒 |

占空比**低有效**：duty 越大转速越低，风量 100% 对应 duty 0。

## 硬件路径（板级定制，换板需重新核对）

| 用途 | 路径 |
|---|---|
| PWM | `/sys/class/pwm/pwmchip4/pwm1` |
| 温度 | `/sys/class/thermal/thermal_zone0/temp` |
| 引脚复用 | `/sys/kernel/debug/aml_reg/paddr`（写 `ff6346c0`～`ff6346fc` 共 17 个寄存器） |
| 手动标记 | `/data/local/fan_manual` |
| 阈值配置 | `/data/local/fan_cfg`（一行 `S M H`） |
| 运行日志 | `/data/local/fan.log` |

## 运行前提

- **必须有 root**：所有硬件操作通过 `su -c` 执行 shell
- 开机自启能否生效，取决于底包是否自带 init.d 执行器；APK 未注册开机广播组件

## 编译

推送到 `main` 分支后 GitHub Actions 自动构建，产物在 Actions 页面 Artifacts 下载。

本地构建：

```bash
gradle assembleDebug
```

## 与原版的差异

- 去掉了 dex 里的单字节 XOR 路径混淆与常量别名，全部还原为可读常量
- **移除了原版的签名校验**：原 `verifySign()` 在反编译包中未被任何流程调用（死代码），且硬编码哈希与当前证书不一致；重建版不再保留该逻辑
- 补齐 Manifest、图标、Gradle 配置

## 许可与声明

反编译重建版本，原作者署名「海东青 · FanCtl · 2026」。仅用于个人盒子折腾与学习，请勿用于商业分发。
