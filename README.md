# 安卓屏连

### 介绍

安卓屏连让安卓手机通过有线和无线的方式连接屏幕或者电脑，增强投屏时的细节体验。

更多技术文档请见 `doc/` 目录。

* 一些手机把usb3硬件阉割成了usb2（比如红米），安卓屏连可以通过增购displaylink扩展坞弥补usb2无法满屏投屏的缺憾。
* 一些手机操作系统软件上阉割了安卓原生的桌面模式（比如小米14），安卓屏连可以通过adb权限把双屏异显的体验做好。
* 很多手机都有投屏到电脑的配套软件，但是很难投屏到竞争对手的设备上，也只能用手机长条形的宽高比镜像模式投屏。

安卓屏连把手机厂商阉割掉的接屏幕的功能想办法加回来。

### 自媒体账号

* 用户手册：[https://connect-screen.com/](https://connect-screen.com/)
* 小红书：[安卓屏连](https://www.xiaohongshu.com/user/profile/602cc4c0000000000100be64)
* b站：[安卓屏连](https://space.bilibili.com/494726825)
* 抖音：[安卓屏连](https://www.douyin.com/user/MS4wLjABAAAAolJRQWuFI6KZwaBUvPfzDejygnorK2K-CY_6b1OuWQM)
* Youtube: [安卓屏连](https://www.youtube.com/@connect-screen)

### 安装方式

通过 QQ 加入群聊 577902537 获取 android apk 安装包

### 触控修正版（当前分支）

当前工作区包含外接 ILITEK USB 触屏旋转坐标修复，独立包名为
`com.gitee.connect_screen.touchfix`，可与原版并存。原 Shizuku 模式安装需要 APK 和
`/data/local/tmp/touch_relay` 两部分。Mate 30 新增 USB + 无障碍模式，仅需 APK 和首次
系统授权，已通过停止 Shizuku 后的物理点击、拖动测试，用户确认真实重启后使用正常。

- [OPPO Reno8 5G / Android 14 安装、标定与恢复](doc/TOUCHFIX_INSTALL_ZH.md)
- [实现结构、构建与打包](doc/TOUCHFIX_PROJECT.md)
- [Mate 30 免电脑触控修正与验收记录](doc/USB_TOUCHFIX_ZH.md)
- Windows 分发包：运行 `tools/Package-TouchFix.ps1`，产物位于 `dist/`。
- [电脑开机自动启动 Shizuku（Windows 计划任务）](doc/PC_AUTOSTART_SHIZUKU_ZH.md)，脚本位于 `shizuku-autostart/`。

现有物理验证来自 Mate 30；其他手机需重新标定并验收。

### 本应用不是 DisplayLink 官方应用

本应用使用了DisplayLink®的驱动程序(.so文件)用于支持DisplayLink®设备的连接功能。DisplayLink®是Synaptics Incorporated的注册商标。我们仅将其驱动程序用于实现与DisplayLink®设备的兼容性，未对驱动程序进行任何修改。

- DisplayLink®驱动程序的所有权利均属于Synaptics Incorporated
- 本应用仅将DisplayLink®驱动用于其预期用途，即支持DisplayLink®设备的连接
- 用户在使用DisplayLink®相关功能时应遵守Synaptics Incorporated的相关许可条款
- 本应用与Synaptics Incorporated没有任何官方关联，不代表或暗示与Synaptics Incorporated存在任何合作关系

如有任何与DisplayLink®相关的法律问题，请直接联系Synaptics Incorporated：www.synaptics.com

### 参考资料

* http://nightmare.press/
* https://github.com/eiyooooo/Easycontrol
* https://github.com/eiyooooo/Easycontrol_For_Car
* https://github.com/timschneeb/awesome-shizuku
* https://github.com/Genymobile/scrcpy
* https://github.com/AkiChase/scrcpy-mask/blob/master/README-zh.md
* https://github.com/dkrivoruchko/ScreenStream
* https://github.com/sdex/ActivityManager
* https://github.com/farmerbb/SecondScreen
* https://github.com/jiuqianyuan/gkd/blob/main/app/src/main/kotlin/li/songe/gkd/shizuku/UserService.kt
* https://github.com/Live-Block/Flyme-FreeForm/blob/flyme/app/src/main/java/com/sunshine/freeform/ui/freeform/FreeformService.kt
* https://github.com/MagicianGuo/Android-SettingTools
* https://github.com/jiesou/Android-Screener
* https://www.reddit.com/r/AndroidQuestions/comments/xra3hu/is_there_a_technical_reason_there_are_no_miracast/
* https://www.cnblogs.com/zuojie
* https://github.com/itzuo/SceenLive/blob/master/app/src/main/java/com/zxj/screenlive/VideoCodec.java
* https://github.com/WuDi-ZhanShen/AndroidUHidPureJava
* https://github.com/WuDi-ZhanShen/ScreenOff
* https://blog.csdn.net/liaosongmao1/article/details/136129774
* https://github.com/keymapperorg/KeyMapper/
