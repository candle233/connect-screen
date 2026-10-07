package com.gitee.connect_screen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class TouchBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (com.gitee.connect_screen.usbtouch.UsbTouchSettings.selected(context)) {
            // Android binds the user-enabled accessibility service; it owns USB recovery.
            com.gitee.connect_screen.usbtouch.UsbTouchAccessibilityService.refresh();
            return;
        }
        if (!TouchKeepAliveService.isBootEnabled(context)) return;
        try { TouchKeepAliveService.enable(context); }
        catch (Exception e) { Log.e("TouchKeepAlive", "Boot foreground start unavailable", e); }
    }
}
