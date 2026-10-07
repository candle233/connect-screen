# 触控修复项目整理

## 交付基线

- 应用：屏连触控修正版，`com.gitee.connect_screen.touchfix`，42 / `1.3.3-touchfix.10`。
- 本次目标：OPPO Reno8 5G / PGBM10、Android 14、同一块 ILITEK 屏、1080×1920。已实机安装并连接 Shizuku UserService；新机标定和物理触摸尚未验收。
- 实测来源：Huawei Mate 30，详见 `native/VALIDATION.md`。不把历史实测当作 OPPO 兼容性证明。
- 保留当前触控实现与设备白名单；本次新增分发、安装、只读检查、紧急停止和中文文档。

## 实现链路

```text
外接 USB ILITEK → touch_relay（shell 用户读取并 EVIOCGRAB）
  → Shizuku UserService（旋转、仿射标定、手机 Display 0 坐标适配）
  → Android MotionEvent 注入 Display 0
```

`touch_relay` 静态 ARM64 文件独立于 APK，应用中固定执行 `/data/local/tmp/touch_relay auto`。安装脚本必须同时部署两者。APK 中的其他投屏和 X11 功能仍属于原项目，未拆成仅触控的独立 App。

| 位置 | 责任 |
| --- | --- |
| `native/touch_relay.c` | 校验设备、抓取单指 slot 0、输出 D/M/U、断连/信号/父进程死亡释放 |
| `app/.../shizuku/UserService.java` | shell 服务、进程生命周期、握手、坐标计算、事件注入、取消手势 |
| `app/.../shizuku/TouchRotationTransform.java` | 四向旋转、参考帧变化、缩放与边界约束 |
| `app/.../shizuku/TouchAffineCalibration.java` | 五点仿射拟合、RMSE、基础方向识别 |
| `app/.../TouchRotationController.java` | 每个画面角度的本机配置与标定 |
| `app/.../TouchRotationTestActivity.java` | 原始点与注入事件对齐采样、测试画布 |
| `app/.../TouchKeepAliveService.java`、`TouchBootReceiver.java` | 前台常驻、重启后等待 Shizuku、异常暂停 |
| `app/.../TouchQuickStartActivity.java` | 开启入口与桌面快捷方式 |
| `app/.../WirelessShizukuBootstrap.java` | Mate 30 可选实验功能；OPPO 安装说明要求保持关闭 |
| `deployment/` | 分发模板、Windows 安装/检查/停止脚本 |
| `tools/Package-TouchFix.ps1` | 构建、签名验证、源码归档、生成哈希清单与分发 ZIP |

表中 `app/...` 展开为 `app/src/main/java/com/gitee/connect_screen/`。

## 构建与重新打包

需要 JDK 17、Android SDK platforms 28/34、build-tools 36.1.0、Gradle wrapper 8.7。`local.properties` 中设置自己的 `sdk.dir`。本机 JDK/SDK 路径不进入分发源码。

```powershell
$env:JAVA_HOME = '你的 JDK 17 目录'
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

native 源码修改后，在具有 Zig 的 Linux/WSL 中执行 `sh native/build.sh`；无 Zig 时需要完整的 ARM64 交叉 GCC libc sysroot。native 必须先成功构建为 `native/bin/touch_relay`，打包器会检查其 ELF64 / AArch64 文件头。未修改 native 时可以保留已验证的二进制，但它不由 Gradle 自动生成。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/Package-TouchFix.ps1 -JavaHome '你的 JDK 17 目录' -SdkDirectory '你的 Android SDK 目录'
```

打包器默认执行构建和单元测试。依赖已经缓存时，可加 `-Offline`；已在当前源码上完成构建/测试时可加 `-SkipBuild`。若不在 PATH 中，可用 `-AdbDirectory` 指定 Google Platform Tools 目录。

产物写入 `dist/` 下带版本和时间的独立目录与 ZIP；不覆盖已存在的目录，不修改已连接手机。打包包括当前工作区源码、构建依赖和校验报告；排除 `.git`、IDE 配置、`local.properties`、诊断日志、应用数据、私钥和构建缓存。`project-source.zip` 内不含签名私钥，新环境重新构建会产生不同的调试签名；要覆盖已有安装应继续使用原来机器的签名环境。

`tools/Test-Deployment.ps1 -BundleDirectory 已生成的安装包目录` 用模拟 ADB 检查设备选择、权限/架构拒绝、安装失败、文件哈希、只读检查和停止流程，不调用真实手机。可将其生成的 `deployment-tests.json` 通过 `-DeploymentTestReport` 交给打包器；打包器核对报告中的脚本哈希后收录报告。这些检查不能替代 OPPO 的实机验收。

## 已知限制

驱动仍只接受一个名称为 `ILITEK ILITEK-TP`、USB 总线、DIRECT、MT slot、X/Y 都为 0..16384 的外接触屏，并明确拒绝 `event2`。只能注入手机 Display 0、只支持单指。若换屏、内屏故障、需要多指或要注入外接独立桌面 Display，都属于新的适配工作。

未标定时的旋转参考默认是 Mate 30 的 1080×1920/rotation 0。当前目标分辨率相同，但首次仍需在新手机上按实际方向标定。0°、180°虽有默认变换，其物理精度未独立验收；270°默认禁用自动映射，需采集新手机实测数据。

`check-device.cmd` 检查设备能力和文件状态，不尝试执行 relay，因此不能证明 SELinux 允许读输入、允许抓取或允许注入。实际开启失败时查看界面错误与 UserService 日志；不要为了通过检查放宽设备识别。

异常退出会暂停并释放接管；Shizuku 丢失后等待重新连接。系统 force-stop 是明确停止。后台持久性、重启和无线调试行为受 ROM 管理影响；本地 5555 bootstrap 没有可移植或完整重启成功的证明。
