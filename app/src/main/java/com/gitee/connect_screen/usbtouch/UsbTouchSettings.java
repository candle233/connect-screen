package com.gitee.connect_screen.usbtouch;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import com.gitee.connect_screen.TouchRotationController;
import com.gitee.connect_screen.shizuku.TouchRotationTransform;
import org.json.JSONArray;

public final class UsbTouchSettings {
    private static final String SELECTED = "usb_touch_selected", ENABLED = "usb_touch_enabled";
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("usb_touch", Context.MODE_PRIVATE); }
    public static boolean selected(Context c) { return prefs(c).getBoolean(SELECTED, false); }
    public static boolean enabled(Context c) { return selected(c) && prefs(c).getBoolean(ENABLED, false); }
    public static void setEnabled(Context c, boolean enabled) {
        SharedPreferences.Editor edit=prefs(c).edit().putBoolean(SELECTED, true).putBoolean(ENABLED, enabled);
        if (enabled) edit.remove("last_error");
        edit.commit();
        UsbTouchAccessibilityService.refresh();
    }
    public static void useLegacy(Context c) {
        prefs(c).edit().putBoolean(SELECTED, false).putBoolean(ENABLED, false).commit();
        UsbTouchAccessibilityService.refresh();
    }
    public static boolean accessibilityEnabled(Context c) {
        String names = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ComponentName target = new ComponentName(c, UsbTouchAccessibilityService.class);
        if (names != null) for (String name : names.split(":")) {
            if (target.equals(ComponentName.unflattenFromString(name))) return true;
        }
        return false;
    }
    public static final class Mapping {
        final int rotation, width, height, phoneRotation;
        final double[] affine;
        public Mapping(Context c) throws Exception {
            SharedPreferences p = TouchRotationController.preferences(c);
            int visual = TouchRotationController.savedVisualRotation(c);
            if (visual < 0) throw new IllegalStateException("请先在触控修正版保存画面旋转和校准");
            String suffix = "_" + visual * 90;
            rotation = p.getInt("touch_rotation_mapping" + suffix,
                    TouchRotationController.visualRotationToTouchRotation(visual));
            if (rotation < 0) throw new IllegalStateException("此画面方向还没有通过触控标定");
            width = p.getInt("touch_rotation_reference_width" + suffix, 1080);
            height = p.getInt("touch_rotation_reference_height" + suffix, 1920);
            phoneRotation = p.getInt("touch_rotation_reference_phone" + suffix, 0);
            String raw = p.getString("touch_rotation_affine" + suffix, null);
            affine = raw == null ? null : new double[6];
            if (raw != null) {
                JSONArray values = new JSONArray(raw);
                if (values.length() != 6) throw new IllegalStateException("校准矩阵无效");
                for (int i=0;i<6;i++) {
                    affine[i]=values.getDouble(i);
                    if (!Double.isFinite(affine[i])) throw new IllegalStateException("校准矩阵无效");
                }
            }
            map(0, 0, 1080, 1920, 0); // Validate dimensions and rotation before claiming USB.
        }
        public float[] map(int x, int y, int w, int h, int r) {
            return TouchRotationTransform.mapAdaptive(x,y,rotation,affine,width,height,phoneRotation,w,h,r);
        }
    }
    private UsbTouchSettings() {}
}
