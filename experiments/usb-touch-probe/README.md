# Mate 30 USB host feasibility experiment

This is a separate bounded diagnostic APK, not a working replacement touch mapper.
Build locally with `powershell -File build.ps1` (existing Android SDK/JDK and WSL Zig).
It has no Shizuku dependency, accessibility service, input injection, or boot receiver.

## Verified on 2026-10-06

- Huawei TAS-AN00, firmware TAS-AN00 3.0.0.166(C00E160R8P8), Android 12.
- A normal application (UID 10163) obtains permission through Android's USB dialog.
  The "default for this device" checkbox was selected for the exact ILITEK device.
- Exact target: vendor 0x222a / product 0x0001, manufacturer ILITEK, product ILITEK-TP.
  No other USB device or the phone's internal touch input is opened.
- `claimInterface(interface0, true)` succeeds from the ordinary app; the HID report
  descriptor becomes readable only after claiming. Descriptor is 850 bytes and saved
  as `hid-report.bin`. `describe-hid.mjs` produces a diagnostic field listing.
- `releaseInterface` succeeds. The extra reconnect ioctl returns -EBUSY, and live
  `/sys/bus/usb/devices/2-1.1:1.0/driver` points to `usbhid`; the ILITEK input device
  reappears in `dumpsys input`. Therefore -EBUSY here means the driver is already bound.
- In the user-started 30-second window, the ordinary app captured 595 valid 64-byte
  report-ID-4 packets (38,675 bytes including framing). 589 reports had a tip contact;
  the sequence contained 6 down, 583 move, and 6 up transitions. The active contact's
  raw range was X=4674–11422, Y=3227–11819. The report stream contained 16 two-finger
  packets (contact IDs 0 and 1) as well as single-contact motion.
- The HID contact-count field ranged from 1 to 2. The 7 count/tip-bit differences are
  release records: contact count includes contacts whose tip bit is now zero. The new
  Java decoder replays all 595 reports, including 16 two-finger frames and 7 release
  records. They must not be classified as malformed packets.

## Test operation and remaining gates

Launch `com.gitee.connect_screen.usbprobe/.ProbeActivity`, tap the start button on the
phone, then tap and draw on the external touchscreen. During the 30-second window the
external panel is read only; it does not click Android UI. The phone screen remains usable.
At completion the probe releases USB and requests the existing touchfix quick-start
activity, so the previous Shizuku-based service can resume. If interrupted, unplugging
and reconnecting the external touchscreen returns it to the kernel driver.

Outputs in the probe's private files: `probe.txt`, `hid-report.bin`, `reports.bin`.
The report file has repeated records: one byte length followed by that many raw bytes.
The native library uses only Linux ARM64 ioctl, with no root or shell privileges.

The main touchfix APK now implements USB reading and accessibility output. Its blank-canvas
synthetic check passed for dragging and two pointers. See `../../doc/USB_TOUCHFIX_ZH.md`.
Remaining gates: compare corrected
touch positions and dragging against the existing Shizuku mapper; preserve stop semantics;
then verify USB permission, accessibility-service reconnection, and restoration after a
real reboot with Shizuku absent. Raw report capture alone does not prove corrected input.

The optional `UsbPermission` shell probe sees no devices on this Huawei and was not
used to grant access. Permission was approved through Android's normal USB dialog.

Sources: Android [USB host](https://developer.android.com/develop/connectivity/usb/host),
[UsbDeviceConnection](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection),
[accessibility gesture API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler)).
