package com.gitee.connect_screen.usbtouch;

final class UsbDriver {
    static { System.loadLibrary("touchusb"); }
    static native int reconnect(int fd, int iface);
    static void verifyAvailable() { /* Trigger loading before detaching the kernel driver. */ }
    private UsbDriver() {}
}
