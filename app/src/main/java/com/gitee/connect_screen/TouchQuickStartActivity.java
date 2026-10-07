package com.gitee.connect_screen;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

/** Dedicated launcher/desktop entry: enable only, never toggle an active proxy off. */
public class TouchQuickStartActivity extends Activity {
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (code, result) -> finish();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (com.gitee.connect_screen.usbtouch.UsbTouchSettings.selected(this)) {
            startActivity(new Intent(this, com.gitee.connect_screen.usbtouch.UsbTouchActivity.class));
            finish(); return;
        }
        if (getIntent().getBooleanExtra("prepare_wireless", false)) {
            new Thread(() -> {
                try { WirelessShizukuBootstrap.prepare(getApplicationContext()); }
                catch (Exception e) { android.util.Log.e("TouchBootstrap", "Prepare key", e); }
                finally { runOnUiThread(this::finish); }
            }, "touch-bootstrap-prepare").start();
            return;
        }
        if (getIntent().getBooleanExtra("enable_wireless", false)) {
            TouchRotationController.preferences(this).edit()
                    .putBoolean(WirelessShizukuBootstrap.ENABLED, true).commit();
        }
        if (getIntent().getBooleanExtra("pin_shortcut", false)) {
            pinShortcut(this); finish(); return;
        }
        try {
            if (TouchKeepAliveService.isRunning() && TouchKeepAliveService.isRequested(this)
                    && State.userService != null && State.userService.isTouchRotationActive()) {
                finish(); return;
            }
            TouchKeepAliveService.enable(this);
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.addRequestPermissionResultListener(permissionResult);
                Shizuku.requestPermission(1001);
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, "触控开启失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
    public static void pinShortcut(Activity activity) {
        ShortcutManager manager = activity.getSystemService(ShortcutManager.class);
        if (!manager.isRequestPinShortcutSupported()) {
            Toast.makeText(activity, "请从应用列表将“开启触控修正”图标拖到桌面", Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutInfo shortcut = new ShortcutInfo.Builder(activity, "enable_touch_fix")
                .setShortLabel("开启触控修正").setLongLabel("一键开启外接触控修正")
                .setIcon(Icon.createWithResource(activity, R.drawable.ic_touch_enable))
                .setIntent(new Intent(activity, TouchQuickStartActivity.class).setAction(Intent.ACTION_MAIN))
                .build();
        manager.requestPinShortcut(shortcut, null);
    }
    @Override public void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        super.onDestroy();
    }
}
