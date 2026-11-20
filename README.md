# FanCtl-A311D

A311D 盒子（菜鸟 / LEMO CORE 一系）风扇控制，Android TV 版。

源码由 `com.local.fanctl` v8.0 反编译重建为可编译的标准 Gradle 工程，仅作学习与兼容研究用途。

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
