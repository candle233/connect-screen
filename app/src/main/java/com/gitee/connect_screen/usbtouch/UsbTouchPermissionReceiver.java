package com.gitee.connect_screen.usbtouch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class UsbTouchPermissionReceiver extends BroadcastReceiver {
    public static final String PERMISSION="com.gitee.connect_screen.touchfix.USB_TOUCH_PERMISSION";
    public static final String STOP="com.gitee.connect_screen.touchfix.USB_TOUCH_STOP";
    @Override public void onReceive(Context context,Intent intent) {
        if (STOP.equals(intent.getAction())) UsbTouchSettings.setEnabled(context,false);
        else if (PERMISSION.equals(intent.getAction())) UsbTouchAccessibilityService.refresh();
    }
}
