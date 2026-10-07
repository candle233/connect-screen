package com.gitee.connect_screen.usbprobe;
public final class UsbNative {
    static { System.loadLibrary("usbguard"); }
    public static native int reconnect(int fd, int iface);
}
