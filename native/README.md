# Mate 30 single-finger touch relay

The touchfix APK uses Shizuku's shell UserService to read this relay's stdout and
inject rotated touchscreen MotionEvents into Display 0 (1080 x 1920).
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
override the chosen visual state. The saved preferences contain degrees in
touch_rotation_visual / touch_rotation_transform and the enabled flag.
APP/UserService startup only attaches lifecycle guards; it never resumes a grab
from saved preferences. A later rotation request or explicit switch action
verifies the external device again before starting.

测试90° / 测试270° are debug controls, and disable automatic following to avoid
unexpected restarts during a test. 关闭触控修正 terminates/reaps the relay and sends
ACTION_CANCEL if needed. 恢复默认触控 additionally kills stale relay processes,
clears touch preferences and turns the automatic switch off.
Missing UserService, failed grab, failed injection and unexpected relay exit
are reported in the UI/logcat. Rotation uses raw 0..16384, the requested four
formulas, and final clamping to width-1/height-1.

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
`adb shell am force-stop com.gitee.connect_screen.touchfix` also triggers the
APP Binder death guard. To remove the experiment, first release and verify
touch, then uninstall **only** com.gitee.connect_screen.touchfix. Optionally
remove /data/local/tmp/touch_relay. Never uninstall the official package.

All changes are on `touch-rotation-fix`; the original branch is
`feature/force-screen-off-v1.3.3`. Commit the changes before switching branches
to make rollback independent of uncommitted working-tree files.
