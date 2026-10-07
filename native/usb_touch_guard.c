/* No libc dependency. USBDEVFS_CONNECT on the already-authorized USB fd only. */
struct usb_ioctl { int ifno; int ioctl_code; void *data; };
__attribute__((visibility("default"))) int
Java_com_gitee_connect_1screen_usbtouch_UsbDriver_reconnect(void *env, void *clazz, int fd, int iface) {
    (void)env; (void)clazz;
    struct usb_ioctl command = { iface, 0x5517, (void *)0 };
#if defined(__aarch64__)
    register long x0 __asm__("x0") = fd;
    register long x1 __asm__("x1") = 0xc0105512UL;
    register long x2 __asm__("x2") = (long)&command;
    register long x8 __asm__("x8") = 29;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8) : "memory");
    return (int)x0;
#elif defined(__arm__)
    register long r0 __asm__("r0") = fd;
    register long r1 __asm__("r1") = 0xc00c5512UL;
    register long r2 __asm__("r2") = (long)&command;
    register long r7 __asm__("r7") = 54;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r7) : "memory");
    return (int)r0;
#else
#error Unsupported architecture
#endif
}
