# Touchfix validation on Huawei Mate 30, 2026-10-05

Test package: `com.gitee.connect_screen.touchfix`; official package retained.
Source branch: `touch-rotation-fix`. Phone logical portrait reference:
1080x1920, phone rotation0. External panel: ILITEK ILITEK-TP, USB direct MT.

## Physical evidence

- Pure quarter-turn1 at visual90: user reported taps, hold and drag correct.
- Pure quarter-turn3 at visual270: user reported vertical mirroring; rejected.
- Five measured raw/target pairs at visual270: affine fit RMSE 20.9327 px;
  subsequent user reply was “正确”. Evidence backup: calibration-mate30.json.
- Phone and monitor turned together into landscape: WindowManager phone Display0
  changed to 1920x1080/rotation3 while external Display30 stayed rotation1.
  Version38 service reported phoneGeometry=[1920,1080,3], effective transform0.
- User was asked to test corners, center, hold, drag in landscape and then
  portrait without touch-direction buttons; reply: “测试通过”.
- Home/background check retained the same relay PID4098 and UserService PID3398.

## Automated and device checks

- Debug APK assembled; all 9 JUnit cases passed, including portrait/landscape
  coordinate range, both landscape directions, resized displays, affine offset
  preservation, landscape-reference calibration and degenerate-fit rejection.
- RotationDialog -> ChangeRotation tested visual0,180,270,90,-1,90 with
  automatic following enabled. Each request saved the requested visual angle
  and expected measured base transform; each forced angle had one relay.
  “不强制” saved transform=-1 and left no relay. These are control/lifecycle
  checks; physical 0/180 accuracy was not separately confirmed.
- APP force-stop terminated the active relay. Relaunch preserved configuration
  without automatically grabbing input.
- `adb shell pkill -f touch_relay` removed the active relay; the service reported
  its unexpected exit and the UI reported the proxy stopped.
- “恢复默认触控” removed the relay, cleared touch preferences and unchecked
  automatic following. Phone/external logical sizes and rotations were unchanged.
  Measured calibration was backed up before this test and restored afterward
  with automatic following disabled and transform=-1, without grabbing input.
- User then confirmed “原始触摸已恢复”. Automatic following was explicitly
  enabled again in the UI; final phone geometry was 1920x1080/rotation3,
  effective transform0. Relay PID7411 survived the final Home/background check.
- Native tests rejected internal event2, rejected concurrent EVIOCGRAB and
  verified release after SIGTERM and parent death. A disconnected-device read
  failure was observed in an earlier test and also released its fd.

Shizuku Binder loss and injection-failure handling are implemented but were not
independently fault-injected during this validation. No multi-finger support.
The original version38 did not implement boot or foreground-service ownership.

## Desktop and persistent service validation, 2026-10-06

Version40 (1.3.3-touchfix.8) adds the enable-only launcher entry, pinned desktop
shortcut, foreground connected-device service and default-enabled boot receiver.

- Built debug APK and passed the same 9 JUnit cases (0 failures/errors).
- Square Home accepted pinned shortcut enable_touch_fix, label 开启触控修正.
  It is visible after scrolling down the desktop, alongside 小红书 and 设置.
- Five successive enable launches retained relay PID27695 and the same enable
  generation; version40 repeat launches similarly retained PID31833.
- Before configuring OEM permissions, Huawei task removal killed the app and
  relay. Sticky foreground restart was rejected with
  ForegroundServiceStartNotAllowedException. The service now handles this
  rejection without a crash loop or new grab.
- Set Huawei startup management for only the test app to manual; self-start,
  associated start and background activity all verified enabled. Added test app
  and Shizuku to deviceidle's battery optimization whitelist.
- With those permissions, removing the test app's actual recent-task card kept
  the same relay PID31049; the version40 check kept PID31833. The task disappeared
  while its foreground service remained active. Clearing all recent tasks also
  retained PID31833 and isForeground=true.
- Emergency pkill left no relay for at least 12 seconds and persisted
  touch_rotation_fault_paused=true. Desktop enable cleared the pause and started
  PID31303. No automatic respawn undid the emergency stop.
- Killed Shizuku server: relay exited. Restarted Shizuku with its displayed ADB
  starter command; foreground service reconnected and started PID31466 without
  another shortcut/settings interaction, paused=false. Binder-query races now
  follow the same reconnection path rather than latching an emergency pause.
- Updating to version40 triggered MY_PACKAGE_REPLACED: foreground service and
  relay PID31833 resumed without opening any app Activity. Real BOOT_COMPLETED
  cannot be simulated by non-root shell (protected broadcast); actual phone
  reboot was subsequently performed by the user. The previous Wi-Fi ADB
  connection disappeared. The user reported that wireless debugging could not
  be opened/found after reboot, so Shizuku was unavailable and automatic touch
  restoration could not be validated. USB ADB recovery and inspection of this
  Huawei's available debugging modes are pending. Fully unattended boot is not
  established; the app cannot provide Shizuku's privileged bootstrap itself.
- Force-stopping the test package removed the relay. Enable entry subsequently
  started PID32468. System force-stop intentionally remains effective.
- Original official APK still installed; SHA256 unchanged:
  6fe516d1445a705f2796727d3366a7422926ebec79e285cca70187e8f48b2b35.

Previously measured calibration is preserved. Physical coordinate accuracy was
not remeasured by the developer in this iteration. Unplug/injection failure uses
a safe native-exit pause; it does not promise unattended recovery from every
hardware/OS failure. Without the optional bootstrap below, non-root Shizuku
still needs startup after reboot.

## Local Shizuku bootstrap validation, 2026-10-06

Version42 (1.3.3-touchfix.10) adds optional local authenticated ADB bootstrap.

- USB recovery after the first reboot found the foreground touch service already
  running (app PID9515), proving its boot receiver started. With the external
  USB touchscreen unplugged during PC connection, verified ILITEK discovery
  rejected startup and did not grab the internal phone touchscreen.
- Huawei reports Wi-Fi ADB support but its wireless-debugging settings Activity
  cannot be opened. Its actual AdbManager implementation was inspected. Only the
  current access point was explicitly trusted; no wildcard network or persistent
  system-property change was made. Existing WRITE_SECURE_SETTINGS permission and
  adb_allowed_connection_time=0 were confirmed, not newly changed.
- Generated the app-owned AndroidKeyStore RSA key. Only its public key was
  exported. The native Android ADB authorization dialog fingerprint matched
  ADB_PUBLIC_KEY_FINGERPRINT_REDACTED, and its persistent
  authorization was verified with existing keys retained.
- Killed Shizuku and enabled the optional bootstrap. The phone's local ADB client
  started Shizuku. After persistent key approval, killing Shizuku again led to
  automatic server PID15803 and UserService PID15834 without a PC starter command.
  This exercises the real RSA token signature, ADB packet exchange and fixed
  installed Shizuku starter; it does not prove post-reboot ADB availability.
- Version42 ignores an obsolete UserService disconnect callback while the current
  replacement Binder remains alive. Debug build and all 9 existing transformation
  and calibration JUnit cases passed. Version42 installed successfully over the
  test package, preserving calibration; the official package was not replaced.
- User was asked to disconnect PC USB, reconnect the external touch panel,
  reboot, unlock and connect the original Wi-Fi without opening Shizuku or the
  shortcut. After the user's reply “好了”, `adb devices` and mDNS discovery were
  empty, and 192.0.2.10:5555 refused connection. Physical touch result and the
  foreground notification status have been requested. Fully unattended boot is
  not established by this result; further diagnosis depends on that feedback.
- A temporary location-service diagnostic did not resolve wireless availability;
  location_mode was restored to its original value 0. No wm size/density,
  system rotation, IDC or /system modifications were made.

## OPPO installation and capability checks, 2026-10-06

- Connected target: OPPO PGBM10 / Android 14 / API34 / arm64-v8a. Original
  com.gitee.connect_screen package retained; installed only the distinct
  com.gitee.connect_screen.touchfix package, versionCode42 / 1.3.3-touchfix.10.
- ColorOS app installer required the observed Continue installation button.
  After confirming it for the named touchfix app, APK installation succeeded.
- Deployed /data/local/tmp/touch_relay, mode755. Remote SHA256 matched the local
  verified binary: 6cc20dbe401333c9ff317620a6d83420a2b8e411fa534b1bc3d1b0f89d85dd6a.
  Executing it with an invalid argument returned the expected exit2 rejection;
  this checked executable startup without grabbing an input device.
- Approved the touchfix-specific Shizuku permission prompt. UI showed authorized
  and user service connected; the shell UserService process was present.
  Granted notification permission for its foreground service status.
- External panel appeared at event6: ILITEK ILITEK-TP, USB bus, DIRECT,
  MT slot, X/Y minimum0 maximum16384. Phone internal touch remained event2.
  USB identity was read from dumpsys input (bus0x0003/vendor0x222a/product0x0001);
  ColorOS denied shell reads of /proc/bus/input/devices and the sysfs bus file.
  The existing read-only checker therefore reports bus verification UNCONFIRMED;
  this is a diagnostic limitation, not evidence of native-grab success or failure.
- Phone had an existing wm size override1080x1920, currently landscape1920x1080
  with phone rotation3; Miracast display6 was1920x1080. No resolution, density
  or forced rotation changes were made during installation.
- Opened external display6 details. The proxy remained inactive; no Mate30
  preferences or measured calibration were imported. New-phone calibration,
  injection, physical accuracy, background persistence and reboot remain untested.
- Fixed Windows PowerShell5 native-stderr handling: ADB push progress with exit0
  must not abort installation. Added a regression case with successful stderr.

## Controlled post-reboot wireless and Shizuku test, 2026-10-06

Question under test: after a phone reboot, can wireless debugging reopen by
itself and can Shizuku start without manual action?

### Huawei TAS-AN00 / Mate 30 5G (EMUI 13 / Android 12, USB-connected test unit)

- EMUI 13 developer options contain no "无线调试" entry (full list scanned via
  uiautomator). `settings put global adb_wifi_enabled 1` is not honored: the
  value read back 0 and `service.adb.tls.port` stayed empty. Android 11+
  wireless debugging is unavailable on this device.
- Legacy Wi-Fi ADB was exercised instead. Over USB, `adb tcpip 5555` put adbd
  in TCP mode and `adb connect 192.0.2.10:5555` authenticated immediately
  with the existing USB keys. `setprop persist.adb.tcp.port 5555` as shell is
  denied (SELinux), and `service.adb.tcp.port` is volatile, so TCP mode cannot
  be made reboot-persistent without root.
- Controlled reboot (`adb reboot`, boot completed in ~38 s): `adb_wifi_enabled`
  reset to 0, `service.adb.tcp.port` cleared, `adb connect` refused (10061),
  `shizuku_server` absent. The version42 local bootstrap could not help: it
  dials `127.0.0.1:5555`, which is dead until USB adb re-enables tcpip — the
  phone-side bootstrap cannot lift itself after reboot on this EMUI.
- The touchfix app itself recovered: after reboot the process was alive and
  `TouchKeepAliveService` reported `isForeground=true` (boot receiver works);
  only the ADB/Shizuku layer was missing.
- PC-side recovery verified twice with `shizuku-autostart/Start-Shizuku.ps1`:
  over USB right after boot (pid 10145), and — after re-enabling tcpip — over
  the wireless transport (pid 10505). Note: switching adbd into TCP mode
  restarts adbd and kills a shell-spawned Shizuku server; the recovery order
  must be tcpip first, starter second.
- Later, with USB unplugged (wireless transport only): `service.adb.tcp.port`
  stayed 5555 and the wireless listener survived, but `shizuku_server` was
  gone again — the USB state change restarted adbd and its shell-spawned
  children. On this Mate 30 any adbd restart (tcpip switch, USB plug/unplug)
  kills the Shizuku server, so the PC starter is also needed after cable
  changes, not just after reboots.

Verdict: the tested non-root ADB/bootstrap methods do not provide unattended
phone-side post-reboot recovery on this Mate 30. Verified boundaries: adbd
exposes no abstract unix socket (`/proc/net/unix` has no @adb entry, so the
proposed `localabstract:adb` rework has no target to connect to), the
init-created `/dev/socket/adbd` denies even `shell` a stat (untrusted apps
cannot use that socket), legacy TCP is off after reboot with
`persist.adb.tcp.port` writes denied, and Android 11+ wireless debugging is
absent. Further paths: investigate Shizuku's root boot mode on an already-rooted
phone (not tested), or keep the phone USB-tethered to this PC where a scheduled
`ShizukuAutoStart` task restores Shizuku automatically — the task currently
targets the OPPO serial; re-register with `-Serial DEVICE_SERIAL` to
retarget it (single-task design, rebuilds the task).

### OPPO PGBM10 (Reno8 5G, Android 14, deployment target, wireless debugging)

- The `ShizukuAutoStart` log recorded automatic server recovery: at 16:32–16:34
  the OPPO was online wirelessly with Shizuku (pid 1263); at 16:48 the script
  found the server gone and started Shizuku (pid 28780) over wireless ADB.
  Server absence does not prove a phone reboot: process termination or an adbd
  restart can also cause it. This proves PC-side server startup, but does not
  establish wireless-debugging persistence, mDNS reconnection after reboot,
  or physical touch recovery. A controlled reboot with a changed boot_id and
  no manual phone-side startup is required for those claims.
- A second controlled reboot from the PC could not be run this session: the
  OPPO left mDNS minutes into the session (screen-off suppression suspected,
  possibly the phone being handled) and did not re-announce within ~20 minutes.
  Toggle persistence remains unverified; re-test when
  the OPPO is awake and visible in `adb mdns services` again.
