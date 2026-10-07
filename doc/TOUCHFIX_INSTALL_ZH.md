# OPPO Reno8 5G / Android 14 触控修正版安装说明

本包面向你的 OPPO Reno8 5G、Android 14 和原来的 ILITEK 外接 USB 触屏（画面 1080×1920）。它修复的是**外接触屏与旋转画面的坐标不一致**，不修复手机内屏硬件故障。当前保留已经在 Mate 30 上验证过的 42 版实现，没有导入旧手机的标定或 ADB 密钥。

2026-10-06 已在 OPPO PGBM10 / Android 14 上安装 APK 与 native 程序，确认 Shizuku 授权及 UserService 连接，并识别到符合条件的 ILITEK 触屏。新手机的五点标定与物理触摸精度尚未验证。1080×1920 是画面尺寸；驱动要求的触屏原始坐标范围是 X/Y 均为 0..16384，两者不同。

## 包内文件

| 文件 | 用途 |
| --- | --- |
| `touchfix.apk` | 屏连触控修正版，1.3.3-touchfix.10 / versionCode 42 |
| `native/touch_relay` | ARM64 静态触控读取程序，必须安装到 `/data/local/tmp/touch_relay` |
| `install.cmd` | Windows 双击安装 APK 和 native 程序并校验结果 |
| `check-device.cmd` | 只读取设备信息与触屏能力，输出 `reports/` 报告 |
| `stop-touchfix.cmd` | 强制停止修正版并释放触控代理，保留标定 |
| `platform-tools/` | Windows ADB 及依赖，无需另装 Android Studio |
| `manifest.json`、`SHA256SUMS.txt` | 版本、来源、文件 SHA256 |
| `project-source.zip` | 该包对应的项目源码、预编译依赖和构建工具脚本 |
| `PROJECT_ZH.md` | 实现结构、构建方法和限制 |
| `validation/` | APK 签名检查、单元测试报告、原有 Mate 30 实测记录 |

包名是 `com.gitee.connect_screen.touchfix`，与原版屏连可以并存。APK 使用现有开发签名；以后更新须保持相同签名。包内没有签名私钥、Shizuku APK、个人调试日志或旧手机应用数据。

## 第一次安装

1. **在 Windows 电脑上解压整个 ZIP。** 不要在压缩包内直接双击脚本。文件夹和路径可以有空格。
2. OPPO 手机开启开发者选项和 USB 调试，解锁后连接电脑，在手机上允许这台电脑的 USB 调试。一次只连接目标手机。
3. 双击 `install.cmd`。看到 `APK and ARM64 relay installed and verified` 即完成文件安装。安装不会启动触控接管，也不会开启旧版的本地 Shizuku 自启动实验功能。
4. 安装官方 [Shizuku](https://shizuku.rikka.app/download/)。在手机上按 [官方启动手册](https://shizuku.rikka.app/guide/setup/) 使用无线调试配对并启动服务。Android 14 可使用 Android 11+ 的无线调试方式。需要在同一 Wi-Fi 下完成配对。
5. 在 Shizuku 的已授权应用中允许“屏连触控修正版”；打开修正版的设置界面确认 Shizuku 已连接。

若电脑已连接多台设备，打开此目录的 PowerShell，先运行 `./platform-tools/adb.exe devices`，再指定目标：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/Install-TouchFix.ps1 -Serial 手机序列号
```

不要将示例里的“手机序列号”原样输入。无线 ADB 的设备标识也可用于 `-Serial`。脚本在存在多台设备、未授权、Android API <29 或不支持 ARM64 时停止。

## OPPO / ColorOS 配置

[Shizuku 官方手册的 ColorOS 条目](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging-start-by-connecting-to-a-computer-the-permission-of-adb-is-limited)指出：若 ADB 权限受限，在开发者选项中关闭 **“权限监控 / Permission monitoring”**。若当前系统没有该选项，不要套用其他品牌的设置命令；保留错误信息用于排查。

允许修正版与 Shizuku 在后台运行，并在系统提供的应用电池管理、自启动设置中允许相应权限。不同 ColorOS 小版本的菜单名称可能不同。允许修正版的通知权限，以便看到常驻服务状态和停止按钮。先完成标定和前台测试，再测试清理最近任务、熄屏和重启。

**不要开启“手机自行启动Shizuku（需一次性授权）”。** 这一项使用 Mate 30 上观察到的本地端口 5555 和一次性授权流程，不适用于直接迁移到 OPPO。本安装包不授予 `WRITE_SECURE_SETTINGS`、不写入 ADB 密钥、不改变无线调试端口。

## 接屏检查（不会接管触控）

连接原来的外接 USB 触屏和投屏画面。若 USB 口被触屏占用，可先把电脑通过系统无线调试连接到手机；电脑连接方法见 [Android 官方 ADB 文档](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi)。电脑必须仍能在 `adb devices` 中看到目标手机，再运行 `check-device.cmd`。

报告中的 `assessment.txt` 有以下几种结果：

- `CANDIDATE OK`：观察到一个名称完全匹配、USB 总线、DIRECT、MT slot、X/Y 0..16384 的候选设备。这只是能力检查，不证明 native 可执行、事件注入成功或物理触控精度。
- `NOT READY`：未接屏或同名设备不唯一；检查 USB 连接，只接一块目标触屏。
- `BLOCKED`：设备能力不符合当前程序，或候选设备恰好分配为 `event2`。此基线仍明确拒绝 `event2`；须先做设备适配，不要修改命令去绕过检查。
- `UNCONFIRMED` / `UNAVAILABLE`：权限或系统接口限制使检查无法完成；保留报告，先核对 Shizuku 和 ColorOS 权限。

每次开启修正都会自动重新扫描外接设备，不会沿用 Mate 30 的 `event12`。检查脚本只执行有界的 `getevent -lp`，不使用持续读取手势的 `getevent -lt`，不会启动 native 代理。

## 新手机重新标定

1. 在修正版“屏幕”页打开正在投屏的外接屏详情，使用本应用的旋转设置选择实际画面角度。第一次操作先保持“触控自动跟随画面旋转”关闭。
2. 用手机内屏操作设置。按“测试90°”或“测试270°”启动临时单指触控代理。这个测试按钮控制的是触控变换，**不会替你旋转画面**；选能让测试画布接收到触屏点击的一项。
3. 点击“五点标定当前画面触控”。用**外接触屏单指**依次点击橙色目标并抬手，共五点。不能用手机内屏或 ADB 点击代替采样。当前标定界面使用固定 38.4 px RMSE 上限，与这套 1080×1920 基线一致。
4. 显示“标定已应用”后，在“触控验证画布”中验证四角、中心、长按、拖动。若误差过大或点按位置异常，停止代理，检查画面设置后重标定。
5. 每个需要使用的画面角度独立标定并复测。270° 默认没有已验证映射，必须先按测试按钮启动临时代理、取得实测标定，之后才可自动跟随。
6. 标定成功并通过实际测试后，再开启“触控自动跟随画面旋转”，添加桌面“开启触控修正”按钮。五点标定本身不会开启自动跟随。

不要导入 `native/calibration-mate30.json` 或 Mate 30 的 SharedPreferences。即使手机和屏幕分辨率相同，旋转、坐标参考系和 ROM 行为也可能不同。

## 安装后验收

依次确认：五点准确；长按与拖动正确；手机与屏幕转为横屏后准确；转回竖屏准确；按 Home 和清理最近任务后仍有正确状态；通知里的停止按钮能恢复原始触摸。当前只支持单指，不支持双指缩放。

“开机自动开启触控修正”默认开启，但它只能等待已启动并已授权的 Shizuku。非 root 的 Shizuku 通常需要在每次重启后重新启动；[官方无线调试启动说明](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging)明确写有这个限制。不要把“触控服务开机启动”等同于“Shizuku 开机自动启动”。固定搭配一台 Windows 电脑使用时，可以注册 `shizuku-autostart/` 中的计划任务，让电脑登录后自动检测并拉起 Shizuku，见 [电脑开机自动启动 Shizuku](PC_AUTOSTART_SHIZUKU_ZH.md)。

本次交付验证了构建、现有 9 项坐标/标定单元测试、19 项模拟 ADB／设备识别检查、APK 签名和分发包完整性。OPPO 已完成实际安装、Shizuku 授权及 UserService 连接；新机标定、事件注入和实际触摸尚需验收，不能用 Mate 30 的既有测试代替。ColorOS 安装器可能弹出“继续安装”，需在手机上确认；Windows PowerShell 的 ADB 成功进度误报问题已修复。

## 停止、恢复与卸载

正常停止：在屏幕详情点击“关闭触控修正”或通知里的“停止触控修正”。“恢复默认触控”还会清除标定并关闭自动跟随、开机启动和可选 bootstrap。

无法从界面停止时，保持 ADB 连接，运行 `stop-touchfix.cmd`。它只强制停止测试包并按精确进程名停止 `touch_relay`；看到没有该进程后，仍要实际确认外接屏原始触摸恢复。它保留标定；再次启动桌面按钮才会请求恢复修正。

卸载前先停止并确认原始触控恢复，然后执行：

```powershell
./platform-tools/adb.exe -s 手机序列号 uninstall com.gitee.connect_screen.touchfix
./platform-tools/adb.exe -s 手机序列号 shell rm /data/local/tmp/touch_relay
```

这些命令只删除触控测试包和它的 native 程序。保留原版屏连与 Shizuku。若遇到 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，说明同包名的已有应用签名不同；不要自动清数据卸载，应先确认原有标定是否需要保留。
