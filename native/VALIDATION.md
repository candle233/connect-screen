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
  reboot and user wireless-Shizuku startup confirmation are pending.
- Force-stopping the test package removed the relay. Enable entry subsequently
  started PID32468. System force-stop intentionally remains effective.
- Original official APK still installed; SHA256 unchanged:
  6fe516d1445a705f2796727d3366a7422926ebec79e285cca70187e8f48b2b35.

Previously measured calibration is preserved. Physical coordinate accuracy was
not remeasured by the developer in this iteration. Unplug/injection failure uses
a safe native-exit pause; it does not promise unattended recovery from every
hardware/OS failure. Non-root Shizuku still needs startup after reboot.
