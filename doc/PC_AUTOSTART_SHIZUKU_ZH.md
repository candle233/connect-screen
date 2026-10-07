# 电脑开机自动启动 Shizuku（Windows 侧）

`shizuku-autostart/` 目录提供一套 Windows 计划任务：电脑登录后自动检测已连接
（无线或 USB）的手机，若 Shizuku 服务未运行就通过官方启动器拉起它。配合修正版
的"开机自动开启触控修正"，在电脑已登录且手机 ADB 重启后仍可连接的前提下，
可以自动恢复 Shizuku。触控接管还需要外接触屏、授权和有效标定；不能据此承诺
任何手机都能独立完成开机恢复。

## 安装（一次性）

在 `shizuku-autostart/` 目录中执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./Register-ShizukuAutoStart.ps1 -Serial BEMZBUFMGY9DZ57H
```

- `-Serial` 指定目标手机的序列号（可只写片段）；当前手机是 OPPO Reno8 5G
  （`BEMZBUFMGY9DZ57H`），同时匹配 USB 序列号和无线 mDNS 设备名。换手机时用
  新序列号重新执行一次注册即可（任务会覆盖重建）。
- 不加 `-Serial` 时默认取第一台在线设备；电脑上连有模拟器或其他设备时建议带上。

注册成功的任务名为 `ShizukuAutoStart`：

- 触发器：当前用户登录时运行，之后每 15 分钟重复一次（登录期间持续有效）；
- 运行方式：通过 `run-hidden.vbs` 隐藏窗口运行，不弹黑框；
- 手机不在线时脚本静默等待至多 4 分钟后退出，等下个周期再试；
- 日志：`%LOCALAPPDATA%\ShizukuAutoStart\autostart.log`（超过 1MB 自动轮转）。

卸载：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./Unregister-ShizukuAutoStart.ps1
```

## 工作原理

1. 轮询 `adb devices`，等待出现 `device` 状态的目标手机。无线场景依赖 adb 对
   已配对设备的 mDNS 自动重连（`adb-BEMZBUFMGY9DZ57H-…. _adb-tls-connect._tcp`），
   手机重启后无线调试端口变化也不需要重新配对。
2. 检查 `ps -A` 中是否已有 `shizuku_server` 进程。**已在运行就直接退出**：
   官方启动器每次执行都会先杀掉旧的 `shizuku_server` 再启动新的，无条件执行
   会打断正在使用的触控修正会话。
3. 未运行时，从 `pm path` 推导 Shizuku 管理器 APK 目录下的
   `lib/arm64/libshizuku.so` 并通过 adb 执行。这是 Shizuku 13.x 官方的
   "通过无线调试 - 连接电脑启动" 机制本体（启动器内部会自行 `pm path` 取
   APK 作为 classpath 并 fork `app_process`）。
4. 轮询确认进程出现（约 20 秒内），成功或失败都写入日志并返回对应退出码。

Shizuku 服务的授权记录保存在管理器应用中，服务重启后各应用无需重新授权；
触控修正的常驻服务（`TouchKeepAliveService`）会持续等待 Shizuku，恢复后自动
重新连接并恢复触控接管。

## 覆盖与不覆盖的场景

| 场景 | 结果 |
| --- | --- |
| 重启电脑（Shizuku 仍在手机上运行） | 任务检测到已运行，不做任何事。注：Shizuku 运行在手机端，重启电脑本身不会杀死它 |
| 重启手机 | 开机后无线调试若保持开启，adb 自动重连，任务在下个周期拉起 Shizuku；触控修正随 keep-alive 自动恢复 |
| Shizuku 被 ColorOS 后台清理杀掉 | 下个周期自动拉起 |
| 手机关闭了无线调试 / 换了 Wi-Fi 不在同一网络 | 无法连接，跳过；恢复网络后下个周期自动继续 |
| 电脑重装系统 / 删除 `%USERPROFILE%\.android` | 配对凭据丢失，需重新 `adb pair` 配对 |
| 电脑停留在登录界面未登录 | 不运行（任务属于当前用户，adb 凭据也在用户目录，这是刻意选择） |

## 手动验证

- 平时检查：双击 `start-shizuku.cmd`（或直接运行 `Start-Shizuku.ps1`）。
  服务已运行时输出 `Shizuku server already running`。
- 验证冷启动：在手机 Shizuku 应用里点"停止"，随后运行 `start-shizuku.cmd`，
  应看到 `Shizuku server started (pid …)`；触控修正若开着会自动恢复接管。
- 验证开机链路：重启电脑并登录后，查看
  `%LOCALAPPDATA%\ShizukuAutoStart\autostart.log` 应有登录时刻的记录。

## 已知边界

- 计划任务每 15 分钟一次的重复依赖登录会话；用户注销即停止（下次登录恢复）。
- 脚本不做重试风暴控制之外的重试：若启动器执行失败（例如 ColorOS 权限监控
  拦截 adb 注入），日志会记录原因，修复手机侧设置后等下个周期即可。
- 旧版本"手机自行启动 Shizuku（本地 5555 实验）"（`WirelessShizukuBootstrap`）
  与本方案无关，OPPO 上应保持关闭；本方案不写入 ADB 密钥、不改变无线调试端口。

## 2026-10-06 重启实测记录

### OPPO（PGBM10，部署目标机）——电脑自动恢复已验证，重启保留待验证

`ShizukuAutoStart` 日志记录了电脑通过无线 ADB 发现 Shizuku 未运行，随后
自动拉起服务。该记录证明电脑启动器能够恢复 Shizuku；进程消失也可能由停止、
后台清理或 adbd 重启引起，不能作为手机重启证据。因此，无线调试开关跨重启
保留、重启后自动重连及实际触控恢复仍需受控重启验证。
验证应记录重启前后不同的 `/proc/sys/kernel/random/boot_id`，并确认没有手动
开启无线调试或 Shizuku，再分别检查服务、relay 进程和实际触摸。

### 华为 Mate 30 5G（TAS-AN00，测试机）——链路不成立

- EMUI 13 开发者选项**没有**"无线调试"开关（全列表扫描确认）；
  `settings put global adb_wifi_enabled 1` 被系统拒绝，Android 11+ 无线
  调试在该设备上不存在。
- 传统 `adb tcpip 5555` 可用（USB 密钥直接复用，无需重新配对），但
  `persist.adb.tcp.port` 被 SELinux 拒绝，TCP 模式**重启即失效**。
- 实测重启后：无线 adb 拒连、`shizuku_server` 未自启（应用内本地 bootstrap
  依赖 127.0.0.1:5555，重启后同样失效，无法自举）；touchfix 应用自身的
  开机自启正常（前台服务 isForeground=true）。
- PC 兜底恢复可用：插 USB 后计划任务/脚本自动拉起 Shizuku（已验证），再
  执行 `adb tcpip 5555` 恢复无线。注意顺序：**先 tcpip 再拉 Shizuku**，
  否则 adbd 重启会杀掉 shell 派生的 shizuku_server。
- 额外坑：**插拔 USB 也会重启 adbd 并杀死 shizuku_server**（无线 5555
  监听会自行恢复，但 Shizuku 需要重新拉起）。所以 Mate 30 上每次动线缆
  之后都要重新跑一次启动脚本。
- 手机侧无人值守在 Mate 30 上不可行（已逐项验证，与代码无关）：adbd 没有
  抽象套接字（/proc/net/unix 无 @adb，不存在 localabstract:adb 通道），
  /dev/socket/adbd 连 shell 都无法访问（应用更不可能），重启后 TCP 关闭且
  persist.adb.tcp.port 无权设置，EMUI 也没有 Android 11+ 无线调试。重启后
  应用与 adbd 之间不存在任何通道。
- 后续路径：①若手机已有 root，可研究 Shizuku 的 root 开机启动方式，尚未实测；
  ②让 Mate 30 保持 USB 连接电脑，
  由 `ShizukuAutoStart` 计划任务自动恢复。任务当前指向 OPPO 序列号（单任务
  设计会覆盖重建），如需改指向 Mate 30：`Register-ShizukuAutoStart.ps1
  -Serial DEVICE_SERIAL`。
