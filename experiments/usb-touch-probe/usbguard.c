/* No libc dependency: Linux ARM64 ioctl, callable through JNI on Android. */
struct usb_ioctl { int ifno; int ioctl_code; void *data; };
__attribute__((visibility("default"))) int
Java_com_gitee_connect_1screen_usbprobe_UsbNative_reconnect(void *env, void *clazz, int fd, int iface) {
    (void)env; (void)clazz;
    struct usb_ioctl command = { iface, 0x5517, (void *)0 };
    register long x0 __asm__("x0") = fd;
    register long x1 __asm__("x1") = 0xc0105512UL;
    register long x2 __asm__("x2") = (long)&command;
    register long x8 __asm__("x8") = 29;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8) : "memory");
    return (int)x0;
}
