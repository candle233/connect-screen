# Mate 30 single-finger touch relay

The touchfix APK uses Shizuku's shell UserService to read this relay's stdout and
inject rotated touchscreen MotionEvents into Display 0 using its current size.
The test application ID is `com.gitee.connect_screen.touchfix`.

`touch_relay auto` scans `/dev/input/event*` and requires one device with the
exact name `ILITEK ILITEK-TP`, USB bus, INPUT_PROP_DIRECT, slot support and both
MT position axes in 0..16384. It rechecks the opened fd before grabbing it.
Internal event2 is rejected before opening. It reads slot 0 only and commits D/M/U at
SYN_REPORT. stdout is line buffered; diagnostics and the READY handshake use
stderr. SIGTERM, SIGINT, parent death, disconnected input and lost input events
release the grab. The Java service cancels any injected gesture on stop/failure.
It links death recipients to the APP token and actual Shizuku server Binder,
so force-stopping the APP or losing Shizuku also terminates the native relay.

Build from WSL with `sh native/build.sh`. Zig builds static ARM64 musl binaries;
the fallback GCC requires a complete AArch64 libc sysroot. Deploy:

```text
adb push native/bin/touch_relay /data/local/tmp/touch_relay
adb shell chmod 755 /data/local/tmp/touch_relay
```

Build the application with JDK 17 and Android SDK platforms 28/34, build-tools
36.1.0. Set JAVA_HOME and the ignored local.properties sdk.dir, then run the
project wrapper: `gradlew.bat assembleDebug :app:testDebugUnitTest`.
Use the PC's existing proxy JVM properties if direct Maven TLS downloads fail.
Install only `app/build/outputs/apk/debug/app-debug.apk`; keep the official app.

In the test app, authorize Shizuku and open the Miracast display under 屏幕.
触控自动跟随画面旋转 defaults off. When enabled, successful RotationDialog ->
ChangeRotation requests update the touch transform automatically. 不强制 stops
the relay. InputReader orientation and Display.getRotation are never used to
override the chosen visual state. Separately, the shell UserService polls the
PHONE's Display 0 logical size and rotation every 100 ms while the relay runs.
The Mate 30 was measured switching 1080x1920/rotation0 to
1920x1080/rotation3; the external rotation stayed 1. The proxy composes that
phone coordinate-frame change with the saved panel map/affine calibration,
scales into the current logical range, and cancels an ongoing gesture when the
frame changes. It does not restart/grab another device on a phone rotation.
MainActivity configuration recreation retains the existing UserService. The
foreground touch service owns the connection independently of the Activity;
closing the settings or removing its recent task does not intentionally stop it.
Runtime status shows current size and effective map.
The saved preferences contain degrees in
touch_rotation_visual / touch_rotation_transform and the enabled flag.
The desktop shortcut **开启触控修正** enables the foreground service and automatic
following. An already active proxy is left untouched. **开机自动开启触控修正**
defaults on. After boot/unlock the service waits for Shizuku and authorization,
then resumes the saved measured configuration. Every new native start scans and
verifies the external USB touch device again; saved event numbers are never used.
Non-root Shizuku must still be started after reboot using wireless debugging (or
ADB). The app cannot silently start the privileged Shizuku server itself. See
[Shizuku setup](https://shizuku.rikka.app/guide/setup/) and Android's
[foreground service start exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

On this Mate 30, Huawei application startup management must be manual with
self-start, associated start and background activity all allowed. The test app
and Shizuku have also been added to the system's battery optimization whitelist.
Without that whitelist Huawei killed even a foreground service when its task was
removed, and rejected its sticky restart. This configuration is a prerequisite,
not a guarantee against all Android/OEM kills. A sticky service retries binding
after process/Shizuku loss; force-stop in system settings remains an explicit stop.

测试90° / 测试270° are debug controls, and disable automatic following to avoid
unexpected restarts during a test. 关闭触控修正 terminates/reaps the relay and sends
ACTION_CANCEL if needed. 恢复默认触控 additionally kills stale relay processes,
clears touch preferences and turns automatic following and boot startup off.
Missing UserService, failed grab, failed injection and unexpected relay exit
are reported in the UI/logcat. Rotation uses raw 0..16384, the requested four
formulas, and final clamping to current width-1/height-1. Each new calibration
also saves its reference phone size/rotation. Older measured profiles retain
their original portrait reference (1080x1920, phone rotation0).

Physical tests on 2026-10-05 passed visual 90 with transform 1. Pure transform 3
at visual 270 was mirrored. The five-target calibration captured matched native
raw DOWN and injected DOWN timestamps, fitted an affine map with 20.93 px RMSE,
and passed the user's subsequent taps/hold/drag test. Those measurements select
transform 1 for visual 270, with its measured affine correction retained in
SharedPreferences. No viewport rotation or guessed offset replaces that fit.
Use 五点标定当前画面触控 to recalibrate after resetting preferences or changing
the display setup. Visual 270 remains unavailable until a measured profile exists.
No multi-finger support is implemented. Never enable Bridge for this test.

Emergency release: `adb shell pkill -f touch_relay`. Verify `adb shell ps -A`
contains no touch_relay, then physically confirm native touch works again.
An unexpected native exit is latched as paused, so the foreground service does
not undo this emergency stop. Click the desktop enable shortcut to resume.
USB read/injection failures use the same safe pause. Shizuku Binder loss instead
waits for Shizuku to return, with the old grab released immediately.
`adb shell am force-stop com.gitee.connect_screen.touchfix` also triggers the
APP Binder death guard. To remove the experiment, first release and verify
touch, then uninstall **only** com.gitee.connect_screen.touchfix. Optionally
remove /data/local/tmp/touch_relay. Never uninstall the official package.

All changes are on `touch-rotation-fix`; the original branch is
`feature/force-screen-off-v1.3.3`. Commit the changes before switching branches
to make rollback independent of uncommitted working-tree files.
