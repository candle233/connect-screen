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
- Native tests rejected internal event2, rejected concurrent EVIOCGRAB and
  verified release after SIGTERM and parent death. A disconnected-device read
  failure was observed in an earlier test and also released its fd.

Shizuku Binder loss and injection-failure handling are implemented but were not
independently fault-injected during this validation. No multi-finger support.
No boot-autostart or permanent background-survival guarantee is provided.
