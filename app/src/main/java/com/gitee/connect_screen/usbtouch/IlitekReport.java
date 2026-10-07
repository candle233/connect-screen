package com.gitee.connect_screen.usbtouch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Layout verified against the 850-byte report descriptor of 222a:0001 ILITEK-TP. */
public final class IlitekReport {
    public static final String DESCRIPTOR_SHA256 = "6bd4bfc0037d9eba401361e652c956bdd4e6a38be592b952ba7f5d3118980cdd";
    public static final class Contact {
        public final int id, x, y;
        public final boolean down;
        public Contact(int id, int x, int y, boolean down) {
            this.id = id; this.x = x; this.y = y; this.down = down;
        }
    }
    public static List<Contact> decode(byte[] data, int length) {
        if (length < 1 || length > data.length) throw new IllegalArgumentException("触屏报告不完整");
        // This verified descriptor also has vendor and mouse reports; never reinterpret them.
        if ((data[0] & 255) != 4) return null;
        if (length != 64) throw new IllegalArgumentException("触屏报告长度不匹配");
        int count = data[55] & 255;
        if (count > 10) throw new IllegalArgumentException("触屏触点数量不匹配");
        List<Contact> contacts = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        // Contact count includes release records. A tip=0 record with count=1 is a valid UP.
        for (int i = 0; i < count; i++) {
            int at = 1 + i * 5;
            int id = data[at] & 63;
            int x = (data[at+1] & 255) | ((data[at+2] & 255) << 8);
            int y = (data[at+3] & 255) | ((data[at+4] & 255) << 8);
            if (!seen.add(id) || x > 16384 || y > 16384)
                throw new IllegalArgumentException("触屏坐标或触点编号不匹配");
            contacts.add(new Contact(id, x, y, (data[at] & 64) != 0));
        }
        for (int i = count; i < 10; i++) {
            if ((data[1+i*5] & 64) != 0) throw new IllegalArgumentException("触屏报告有未声明触点");
        }
        return Collections.unmodifiableList(contacts);
    }
    private IlitekReport() {}
}
