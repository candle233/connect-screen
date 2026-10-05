package com.gitee.connect_screen;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.widget.Toast;

import com.gitee.connect_screen.shizuku.IUserService;
import com.gitee.connect_screen.shizuku.TouchAffineCalibration;
import rikka.shizuku.Shizuku;
import org.json.JSONArray;

/** Source of truth is the rotation successfully requested by Connect Screen. */
public final class TouchRotationController {
    private static final String PREFS = "touch_rotation";
    public static final String ENABLED = "touch_rotation_enabled";
    public static final String VISUAL = "touch_rotation_visual";
    public static final String TRANSFORM = "touch_rotation_transform";
    // Physical Mate 30 test on 2026-10-05: visual 90 + transform 90 passed.
    private static final int TOUCH_ROTATION_FOR_VISUAL_90 = Surface.ROTATION_90;
    // Enable this only after testing the 270-degree picture on the external touchscreen.
    private static final int TOUCH_ROTATION_FOR_VISUAL_270 = -1;
    private static final Binder CLIENT_TOKEN = new Binder();

    static {
        Shizuku.addBinderDeadListener(() -> {
            IUserService service = State.userService;
            State.userService = null;
            if (service != null) new Thread(() -> {
                try { service.stopTouchRotation(); }
                catch (Exception e) { android.util.Log.e("TouchRotation", "Shizuku disconnected", e); }
            }, "touch-shizuku-disconnected").start();
        });
    }

    private TouchRotationController() {}
    public static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public static boolean isAutoFollowEnabled(Context context) {
        return preferences(context).getBoolean(ENABLED, false);
    }
    public static int savedVisualRotation(Context context) {
        int degrees = preferences(context).getInt(VISUAL, -1);
        return degrees < 0 ? -1 : degrees / 90;
    }
    public static int visualRotationToTouchRotation(int visualRotation) {
        switch (visualRotation) {
            case Surface.ROTATION_0: return Surface.ROTATION_0;
            case Surface.ROTATION_90: return TOUCH_ROTATION_FOR_VISUAL_90;
            case Surface.ROTATION_180: return Surface.ROTATION_180;
            case Surface.ROTATION_270: return TOUCH_ROTATION_FOR_VISUAL_270;
            default: return -1;
        }
    }
    public static void attachClient(IUserService service) throws Exception {
        service.attachTouchRotationClient(CLIENT_TOKEN, Shizuku.getBinder());
        // Saved configuration is not a request to grab on APP/UserService startup.
    }
    private static IUserService requireService() {
        IUserService service = State.userService;
        if (service == null || !service.asBinder().isBinderAlive()) {
            throw new IllegalStateException("Shizuku UserService 未连接，请启动 Shizuku 并授权测试版");
        }
        return service;
    }
    public static synchronized void onVisualRotationApplied(Context context, int displayId,
            int visualRotation) throws Exception {
        preferences(context).edit().putInt(VISUAL, visualRotation < 0 ? -1 : visualRotation * 90)
                .putInt("touch_rotation_display", displayId).apply();
        if (visualRotation < 0) {
            stopProxy(context);
        } else if (isAutoFollowEnabled(context)) {
            try { applyTransform(context, visualRotation); }
            catch (Exception error) {
                try { stopProxy(context); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
        }
    }
    private static void applyTransform(Context context, int visualRotation) throws Exception {
        int transform = preferences(context).getInt("touch_rotation_mapping_" + visualRotation * 90,
                visualRotationToTouchRotation(visualRotation));
        // Earlier calibration builds stored the visual angle as a nominal label.
        // Derive that label from the measured raw/target pairs; retain the affine fit.
        String samples = preferences(context).getString("touch_rotation_samples_" + visualRotation * 90, null);
        if (samples != null && (visualRotation == 1 || visualRotation == 3)) {
            JSONArray rows = new JSONArray(samples);
            if (rows.length() < 3) throw new IllegalStateException("标定采样不足，请重新标定");
            double[][] measured = new double[rows.length()][4];
            for (int i = 0; i < measured.length; i++) {
                JSONArray row = rows.getJSONArray(i);
                if (row.length() != 4) throw new IllegalStateException("标定采样无效，请重新标定");
                for (int j = 0; j < 4; j++) measured[i][j] = row.getDouble(j);
            }
            transform = TouchAffineCalibration.nearestQuarterTurn(measured);
            preferences(context).edit().putInt("touch_rotation_mapping_" + visualRotation * 90, transform).apply();
        }
        if (transform < 0) {
            stopProxy(context);
            throw new IllegalStateException("该画面角度的触控映射尚未通过实测，已恢复原始触摸");
        }
        double[] affine = savedAffine(context, visualRotation);
        if (affine == null) requireService().startTouchRotation("auto", transform, 1080, 1920, 0);
        else requireService().startTouchRotationAffine("auto", transform, 1080, 1920, 0, affine);
        preferences(context).edit().putInt(TRANSFORM, transform * 90).apply();
    }
    public static synchronized void setAutoFollow(Context context, boolean enabled) throws Exception {
        if (!enabled) {
            try { stopProxy(context); }
            finally { preferences(context).edit().putBoolean(ENABLED, false).apply(); }
            return;
        }
        requireService();
        preferences(context).edit().putBoolean(ENABLED, true).apply();
        int visual = savedVisualRotation(context);
        try {
            if (visual >= 0) applyTransform(context, visual);
            else stopProxy(context);
        } catch (Exception e) {
            try { stopProxy(context); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
            preferences(context).edit().putBoolean(ENABLED, false).apply();
            throw e;
        }
    }
    public static synchronized void startDebug(Context context, int transform) throws Exception {
        preferences(context).edit().putBoolean(ENABLED, false).apply();
        requireService().startTouchRotation("auto", transform, 1080, 1920, 0);
        preferences(context).edit().putInt(TRANSFORM, transform * 90).apply();
    }
    private static double[] savedAffine(Context context, int visual) throws Exception {
        String stored = preferences(context).getString("touch_rotation_affine_" + visual * 90, null);
        if (stored == null) return null;
        JSONArray array = new JSONArray(stored);
        if (array.length() != 6) throw new IllegalStateException("标定矩阵无效，请恢复默认触控后重新标定");
        double[] coefficients = new double[6];
        for (int i = 0; i < 6; i++) coefficients[i] = array.getDouble(i);
        return coefficients;
    }
    public static synchronized void saveCalibration(Context context, int visual, int transform, double[][] samples,
            double[] coefficients) throws Exception {
        if (visual < 0 || visual > 3) throw new IllegalStateException("请先在本应用中选择画面角度");
        JSONArray matrix = new JSONArray(), points = new JSONArray();
        for (double c : coefficients) matrix.put(c);
        for (double[] sample : samples) {
            JSONArray row = new JSONArray();
            for (double value : sample) row.put(value);
            points.put(row);
        }
        requireService().startTouchRotationAffine("auto", transform, 1080, 1920, 0, coefficients);
        preferences(context).edit().putBoolean(ENABLED, false).putInt(TRANSFORM, transform * 90)
                .putInt("touch_rotation_mapping_" + visual * 90, transform)
                .putString("touch_rotation_affine_" + visual * 90, matrix.toString())
                .putString("touch_rotation_samples_" + visual * 90, points.toString()).apply();
    }
    private static void stopProxy(Context context) throws Exception {
        IUserService service = State.userService;
        try {
            if (service != null) service.stopTouchRotation();
        } finally {
            preferences(context).edit().putInt(TRANSFORM, -1).apply();
        }
    }
    public static synchronized void stop(Context context) throws Exception {
        try { stopProxy(context); }
        finally { preferences(context).edit().putBoolean(ENABLED, false).apply(); }
    }
    public static synchronized void reset(Context context) throws Exception {
        try { requireService().recoverDefaultTouch(); }
        finally { preferences(context).edit().clear().apply(); }
    }
    public static void report(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            State.log(message);
            Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_LONG).show();
        });
    }
    public static void onAppExit() {
        IUserService service = State.userService;
        if (service != null) {
            try { service.stopTouchRotation(); }
            catch (Exception e) { android.util.Log.e("TouchRotation", "APP exit cleanup", e); }
        }
    }
}
