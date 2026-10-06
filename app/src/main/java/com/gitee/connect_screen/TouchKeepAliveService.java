package com.gitee.connect_screen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.gitee.connect_screen.shizuku.IUserService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/** Persistent owner of touch correction, independent of the settings Activity task. */
public class TouchKeepAliveService extends Service {
    public static final String REQUESTED = "touch_rotation_keep_alive";
    public static final String BOOT = "touch_rotation_boot_enabled";
    public static final String PAUSED = "touch_rotation_fault_paused";
    private static final String GENERATION = "touch_rotation_enable_generation";
    private static final String ENABLE = "com.gitee.connect_screen.touchfix.ENABLE_TOUCH";
    private static final String STOP = "com.gitee.connect_screen.touchfix.STOP_TOUCH";
    private static final String CHANNEL = "external_touch_running";
    private static final int NOTIFICATION = 2006;
    private static volatile TouchKeepAliveService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private IBinder previousService;
    private boolean foregroundReady;
    private boolean wasRunning;
    private long bindAttempt, lastGeneration = -1;
    private volatile long nextAttempt;
    private volatile String status = "正在连接触控服务…";
    private final Shizuku.OnBinderReceivedListener binderReceived = () -> nextAttempt = 0;
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (code, result) -> nextAttempt = 0;

    public static boolean isRequested(Context context) {
        return TouchRotationController.preferences(context).getBoolean(REQUESTED, false);
    }
    public static boolean isBootEnabled(Context context) {
        return TouchRotationController.preferences(context).getBoolean(BOOT, true);
    }
    public static boolean isRunning() { return instance != null; }
    public static void enable(Context context) {
        synchronized (TouchRotationController.class) {
            long generation = TouchRotationController.preferences(context).getLong(GENERATION, 0);
            TouchRotationController.preferences(context).edit().putBoolean(REQUESTED, true)
                    .putBoolean(TouchRotationController.ENABLED, true).putBoolean(PAUSED, false)
                    .putLong(GENERATION, generation + 1).commit();
        }
        // Starting an already running foreground service does not restart the native relay.
        context.startForegroundService(new Intent(context, TouchKeepAliveService.class).setAction(ENABLE));
    }
    public static void disable(Context context) {
        TouchRotationController.preferences(context).edit().putBoolean(REQUESTED, false).commit();
        context.stopService(new Intent(context, TouchKeepAliveService.class));
    }
    public static String currentStatus() {
        TouchKeepAliveService active = instance;
        return active == null ? "触控常驻服务未运行" : active.status;
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "外接触控常驻服务",
                NotificationManager.IMPORTANCE_LOW));
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION, notification());
            foregroundReady = true;
        } catch (IllegalStateException | SecurityException blocked) {
            // Some OEM cleaners also revoke the foreground-start exemption. Do
            // not crash-loop or grab input without an actual foreground owner.
            Log.e("TouchKeepAlive", "Foreground start blocked by system background policy", blocked);
            instance = null;
            return;
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        worker.scheduleWithFixedDelay(this::tickSafely, 0, 1, TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foregroundReady) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && STOP.equals(intent.getAction())) {
            worker.execute(() -> {
                try { TouchRotationController.stop(getApplicationContext()); }
                catch (Exception e) { Log.e("TouchKeepAlive", "Explicit stop", e); }
            });
            return START_NOT_STICKY;
        }
        if (!isRequested(this)) { stopSelf(); return START_NOT_STICKY; }
        nextAttempt = 0;
        return START_STICKY;
    }
    private void tickSafely() {
        try {
            // Do not mistake a deliberate rotation switch's short stop/start for an emergency exit.
            synchronized (TouchRotationController.class) { tick(); }
        }
        catch (Throwable error) {
            Log.e("TouchKeepAlive", "Touch supervision failed", error);
            // Binder loss can race with a status query. Wait for reconnection rather
            // than treating Shizuku's restart as an external emergency pkill.
            IUserService disconnected = State.userService;
            if (!Shizuku.pingBinder() || disconnected == null
                    || !disconnected.asBinder().isBinderAlive()) {
                wasRunning = false; previousService = null; bindAttempt = 0;
                update("等待Shizuku启动；启动后自动恢复触控");
                return;
            }
            TouchRotationController.preferences(this).edit().putBoolean(PAUSED, true).commit();
            IUserService service = State.userService;
            if (service != null) try { service.stopTouchRotation(); } catch (Exception ignored) {}
            update("触控已安全停止，点击桌面“开启触控修正”重试");
        }
    }
    private void tick() throws Exception {
        if (!isRequested(this)) { main.post(this::stopSelf); return; }
        long generation = TouchRotationController.preferences(this).getLong(GENERATION, 0);
        if (generation != lastGeneration) { lastGeneration = generation; wasRunning = false; nextAttempt = 0; }
        if (!Shizuku.pingBinder()) {
            wasRunning = false; previousService = null; bindAttempt = 0;
            update("等待Shizuku启动；启动后自动恢复触控"); return;
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            wasRunning = false;
            update("等待Shizuku授权，请点击桌面“开启触控修正”"); return;
        }
        IUserService service = State.userService;
        if (service == null || !service.asBinder().isBinderAlive()) {
            wasRunning = false; previousService = null;
            long now = SystemClock.elapsedRealtime();
            if (bindAttempt == 0 || now-bindAttempt > 10000) {
                bindAttempt = now;
                main.post(() -> {
                    try { Shizuku.bindUserService(State.userServiceArgs, State.userServiceConnection); }
                    catch (Exception e) { Log.e("TouchKeepAlive", "Bind UserService", e); }
                });
            }
            update("正在连接Shizuku触控服务…"); return;
        }
        bindAttempt = 0;
        if (previousService != service.asBinder()) { previousService = service.asBinder(); wasRunning = false; }
        boolean running = service.isTouchRotationActive();
        // An external pkill/injection fault is an emergency stop, not a restart request.
        if (wasRunning && !running) {
            TouchRotationController.preferences(this).edit().putBoolean(PAUSED, true).commit();
        }
        wasRunning = running;
        if (TouchRotationController.preferences(this).getBoolean(PAUSED, false)) {
            update("触控已停止，点击桌面“开启触控修正”重试"); return;
        }
        if (running) {
            int[] frame = service.getTouchRotationGeometry();
            update("触控运行中 · " + frame[0] + "×" + frame[1] + " · 清理最近任务不会关闭");
            return;
        }
        if (TouchRotationController.savedVisualRotation(this) < 0) {
            update("请先在修正版设置画面旋转方向"); return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < nextAttempt) return;
        nextAttempt = now + 10000;
        try {
            TouchRotationController.resumePersistent(this);
            wasRunning = service.isTouchRotationActive();
            if (wasRunning) update("触控已自动开启，正在跟随手机旋转");
        } catch (Exception e) {
            Log.w("TouchKeepAlive", "Verified touch device not ready", e);
            update("等待已核验的ILITEK触屏/配置：" + e.getMessage());
        }
    }
    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        if (status.startsWith("等待Shizuku启动")) {
            Intent shizuku = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (shizuku != null) open = shizuku;
        }
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, TouchKeepAliveService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_touch_enable)
                .setContentTitle("外接触控修正常驻运行").setContentText(status)
                .setStyle(new Notification.BigTextStyle().bigText(status)).setContentIntent(content)
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止触控修正", stop).build()).build();
    }
    private void update(String message) {
        if (message.equals(status)) return;
        status = message;
        Log.i("TouchKeepAlive", message);
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        Log.i("TouchKeepAlive", "Settings task removed; foreground touch service retained");
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy() {
        instance = null;
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        worker.shutdownNow();
        // If Android destroys the actual foreground service, safely release the grab.
        // Explicit stop/debug paths already performed cleanup before stopping this service.
        if (isRequested(this)) {
            IUserService service = State.userService;
            if (service != null) new Thread(() -> {
                try { service.stopTouchRotation(); } catch (Exception ignored) {}
            }, "touch-foreground-destroy").start();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
