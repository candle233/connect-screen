# 手机重启后触控修正：可行性核验（2026-10-06）

新增开发版 USB 方案的当前进展见 [免电脑触控修正](USB_TOUCHFIX_ZH.md)。
下面的既有 Shizuku 重启限制仍成立；新的 USB 模式已实现，Shizuku 停止后的实际点击、
拖动和关闭恢复均已通过；执行真实重启后，用户反馈“使用正常”。是否还出现授权步骤
正单独确认，详细证据与边界见上述记录。

核验设备为华为 Mate 30 5G，TAS-AN00 / Android 12，重启前连接地址
`192.0.2.10:5555`，本轮重启后此地址拒绝 ADB 连接。通过既有非 root 方案，不能实现手机独立重启后全自动
启动 Shizuku。触控应用的开机启动已生效，缺失环节是重启后可用的 ADB 权限通道。

## 前期在线核验（USB 修正版安装前）

- `TouchKeepAliveService` 正在运行，`isForeground=true`，系统记录明确显示
  `code:BOOT_COMPLETED`；这是触控应用已由开机广播启动的证据。
- 当时 Shizuku 已运行，PID 13913；这不能证明它在重启后自动启动。新版独立实测前
  已停止此进程，并确认不存在 Shizuku server 或旧 touch_relay。
- `service.adb.tcp.port=5555`，`persist.adb.tcp.port` 为空。
- 尝试 `settings put global adb_wifi_enabled 1` 后读回仍是 0，TLS 调试端口为空；
  随后显式恢复原值 0 并核验。写设置值不能在这台 ROM 上开启 Android 无线调试。
- 未找到 `su`；设备报告 verified boot 为 green、flash locked 为 1。
- 已接入 ILITEK 外接触屏，并由普通应用通过系统 USB 授权对准确设备读取 HID 报告。
  用户在外接屏上完成触摸后，30 秒窗口采到 595 个有效报告（64 字节，报告 ID 4）：
  589 个含按下触点，解析出 6 次按下、583 次移动、6 次抬起；单指坐标范围 X=4674–11422、
  Y=3227–11819；另有 16 个双指报告（触点 ID 0 和 1）。由此验证普通应用能在这台
  Mate 30 读取单指轨迹及双指报告，无需 Shizuku。
- HID 声明的触点数范围为 1–2；7 个报告包含 tip=0 的抬起记录。后续 Java 解析器已完整
  回放这些数据，确认不能将触点总数与按下数的差异当作坏包。此窗口只读取数据，
  尚未注入修正后的触摸，所以不能据此认定触控修正或开机恢复已成功。
- 测试完成后 USB 接口已释放，`usbhid` 驱动仍绑定，系统输入设备重新出现；随后已请求恢复
  现有 Shizuku 触控服务。此操作不是重启测试。
- 此阶段 boot_id 为 `BOOT_ID_REDACTED`。

## 既有受控重启证据

`native/VALIDATION.md` 的 Mate 30 受控测试记录显示：执行重启后 TCP 调试端口
清空，无线连接被拒绝，Shizuku 未运行；触控前台服务已启动。插 USB 并运行电脑
启动器可以恢复 Shizuku。该记录是旧方案的实验；新版独立触控的重启验收单独记录在
[免电脑触控修正](USB_TOUCHFIX_ZH.md) 中。

本地 bootstrap 连接的是 `127.0.0.1:5555`。端口重启后关闭时，它不能凭自身权限
恢复 ADB，也不能凭后台运行或开机广播获得 shell 权限。

## 当前可行路径与验证边界

1. Mate 30：保持已授权的 USB ADB 连接到已登录的 Windows 电脑，使用
   `shizuku-autostart/Start-Shizuku.ps1` 拉起 Shizuku；这是已有恢复验证的路径。
   现有 `ShizukuAutoStart` 任务仍指向 OPPO `BEMZBUFMGY9DZ57H`，本次未更改目标。
   如将任务改为 Mate 30，需指定 `DEVICE_SERIAL`；会覆盖现有任务。
2. OPPO：已有无线 ADB 启动器恢复记录，但没有充分证据证明无线调试跨重启保留。
   需设备重新上线后记录重启前后 boot_id，再验证自动连接、Shizuku、relay 和实际触摸。
3. 本机已实现的新方案：USB host 普通应用读取 ILITEK 报告，再由用户显式启用的无障碍服务
   注入手势，避开重启后缺失的 Shizuku。首次配置需要 USB 访问授权和无障碍启用；
   USB 临时授权只到设备断开为止，不能把本次同意等同于永久授权。需要让新版注册为
   此触屏的默认 USB 处理应用，并实测重启后系统是否自动授予访问权限。
   独立开关与 USB/无障碍输出已实现，合成输出检查、实际触屏点击拖动、关闭恢复均已
   通过。用户确认位置准确，并在真实重启后反馈“使用正常”；重复授权有无出现尚未单独确认。
4. 已有 root 的设备：可进一步验证 Shizuku 的 root 开机启动。当前设备没有完成
   此配置与测试；本次没有解锁、root 或刷机。

官方说明：[Shizuku 启动指南](https://shizuku.rikka.app/guide/setup/)
指出非 root 的无线调试启动步骤需要在重启后再次执行。电脑自动脚本能代为执行
启动步骤的前提是 ADB 已可连接，无法替代系统没有开放的调试通道。

## 不依赖 Shizuku 的启动流程（已实现，用户确认重启后正常）

首次配置新版时，用户启用触控修正无障碍服务，选择此应用为 ILITEK USB 设备的默认
处理应用，并在华为应用启动管理中允许自启动和后台活动。应用保存经验证的坐标标定。

预期流程为：重启并首次解锁 → 系统连接已启用的无障碍服务 / 应用接收开机广播 →
查找已连接的 ILITEK 并检查 USB 权限 → 加载标定 → 读取原始坐标 → 通过无障碍手势
接口输出修正后的点击和拖动。无障碍服务由系统管理，应用不能用开机广播自行启用授权。

如果重启后 USB 权限没有自动恢复，应显示系统授权提示；此时仍可免电脑，但不能称为
零操作全自动。锁屏未解锁时的工作、多指操作、延迟和系统重新绑定均是额外验证项。
选择新版“免电脑触控修正”后，原桌面“开启触控修正”会进入独立开关页面，开机广播
跳过 Shizuku 路径。未选择新模式时保留原流程。

依据：[Android USB 权限说明](https://developer.android.com/reference/android/hardware/usb/UsbManager)、
[无障碍服务生命周期](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)、
[华为应用后台运行设置](https://consumer.huawei.com/cn/support/content/zh-cn00428704/)。
