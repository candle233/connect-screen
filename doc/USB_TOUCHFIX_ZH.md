# Mate 30 免电脑触控修正

当前开发版：`1.3.3-touchfix.11-usb`（独立触屏实测通过，用户确认重启后使用正常）。
包名沿用 `com.gitee.connect_screen.touchfix`，原有校准保留。

## 使用入口

桌面新增“免电脑触控修正”。首次启用同名无障碍服务，并在 USB 授权框中选择
“默认情况下用于该 USB 设备”。之后使用应用内的“触控修正”开关。
保持开关开启时，系统重新连接无障碍服务后会尝试恢复；关闭时释放 USB 接口并恢复系统输入。
原桌面的“开启触控修正”在选择此模式后，也会进入这个开关页面。

首次授权和重启后单开关恢复是不同验收阶段。如果重启后仍需重新授权，不能把本目标标为完成。

## 实现边界

- USB 读取与无障碍手势输出均在普通应用 UID 下运行，不使用 ADB、Shizuku 或 root。
- 仅接管 `222a:0001` / `ILITEK` / `ILITEK-TP` 的 HID 接口 0；确认 850 字节描述符
  SHA-256 与实测一致后才处理报告。手机内屏、键鼠、其他 USB 设备不被读取。
- 复用已保存的画面方向和仿射校准，按照手机逻辑显示区域及旋转更新坐标。
- 可解析最多 10 个触点。保留按下、抬起、触点变化；输出跟不上时合并连续移动帧，
  保留转接边界。服务断开、设备断开、关闭开关、锁屏或输入异常都会释放接口。
- 无障碍服务只声明手势能力，不读取屏幕内容。服务由系统管理；应用不绕过首次启用授权。
- 进入此模式后停止原触控常驻服务，并让开机广播跳过 Shizuku 启动逻辑。
- 本模式修正触摸坐标。系统投屏画面方向的设置是已有独立功能；重启时的实际画面和触控
  是否仍对应，必须一并做物理验收。

## 已验证

1. 20 个本地单元测试通过：既有映射及仿射校准、USB 报告解析、移动合并和边界保留。
2. 用新的 Java 解析器回放此前采集的全部 595 个报告，无错误；包含 16 个双指报告及
   7 个抬起记录。之前“触点数与 tip 位不一致”的 7 项由抬起记录解释，不应当成坏包。
3. Mate 30 上的空白画布实际收到了无障碍输出：2 次 DOWN、2 次 UP、4 次 MOVE，
   最大同时 2 个触点，0 次取消；10 个输出片段全部完成。此项是合成输入检查，不能
   代替外接触屏物理多指操作和重启测试。
4. 停止 Shizuku server 和旧 touch_relay 后，用户实际操作外接屏并确认“位置准确，点击
   拖动正常”。测试画布记录 6 次 DOWN、6 次 UP、57 次 MOVE、0 次取消；独立读取
   215 个报告、输出 69 个片段，运行 UID 为普通应用 10246。此轮最大触点数为 1，
   因此不据此宣称物理双指或长按已验收。证据：`diagnostics/usb-touch-before-reboot.json`。
5. 关闭应用开关后，USB 读取停止，`usbhid` 重新绑定，ILITEK 在系统输入列表中恢复。
   `usb_touch_enabled=false`，`reconnect_result=-16` 表示驱动已绑定；Shizuku 和旧
   relay 均未启动。证据：`diagnostics/usb-touch-off-before-reboot.json`。
6. 通过 ADB 执行真实重启，命令正常结束；之后连接原无线调试地址得到连接被拒绝
   （10061）。按“解锁、恢复投屏、打开免电脑触控修正开关并测试”的步骤请求验收，
   用户回复“使用正常”。此项为用户在手机上的实际反馈；没有重新连接电脑采集日志。

重启前的机器记录对应开机编号 23，boot_id 为 `BOOT_ID_REDACTED`。
用户未提供重启后的编号，亦未单独说明有无再次授权，因此不能把旧记录当作重启后日志，
也不把“使用正常”扩大为已证明零授权提示或无人操作自动恢复。

Android 12 会拒绝无位移且无触点变化的空续接；实现保留按压并等待真实变化。
新增第二根手指时，在续接初始点之后加入新触点，确保旧触点匹配。

## 尚待验收

- 外接屏物理长按、多指及更长时间使用。
- 确认用户这次重启恢复是否只开启一个开关，以及是否出现重复授权；已发出简短追问。
- 保持开关开启后的无人操作恢复、连续多次重启；本轮重启前开关已主动关闭。

## 构建与回退

本机 JDK 17：`C:/Users/LOCAL_USER/Desktop/android-sdk/jdk17/jdk-17.0.20.1+1`。
更新本地 USB 释放库：`tools/Build-UsbTouchDriver.ps1`（WSL Zig）。
构建并测试：`gradlew.bat :app:assembleDebug :app:testDebugUnitTest --offline`。
APK：`app/build/outputs/apk/debug/app-debug.apk`。
保留的独立安装包：`dist/connect-screen-mate30-usb-touchfix-1.3.3-touchfix.11-usb.apk`。
SHA-256：`06649c16c84ce9e8638ee6abe5cd7e1a3b338a97aeb567e1f17158c269a6b426`。

应用里“切回原来的 Shizuku 模式”会先释放 USB，再进入旧启动入口。
安装前的旧 APK 已保存到本机 `diagnostics/mate30-before-usb.apk`，未改写既有校准。

参考：[Android USB host](https://developer.android.com/develop/connectivity/usb/host)、
[手势续接](https://developer.android.com/reference/android/accessibilityservice/GestureDescription.StrokeDescription)、
[Android 12 MotionEventInjector 源码](https://github.com/aosp-mirror/platform_frameworks_base/blob/android12-release/services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java)。
