package com.gitee.connect_screen.usbtouch;

import android.accessibilityservice.AccessibilityService;
import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.usb.*;
import android.os.*;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.gitee.connect_screen.R;

/** USB and gesture owner, bound by Android after the user enables accessibility. No shell API. */
public final class UsbTouchAccessibilityService extends AccessibilityService {
    private static final int NOTIFICATION=2007;
    private static final String CHANNEL="usb_touch_fix";
    private static UsbTouchAccessibilityService instance;
    private final Handler main=new Handler(Looper.getMainLooper());
    private UsbTouchReader reader;
    private GestureStream gestures;
    private GestureStream diagnostic;
    private UsbTouchSettings.Mapping mapping;
    private boolean connected, ready, foreground;
    private int width, height, rotation;
    private long reports, lastReportAt;
    private long outputGeneration;
    private String status="等待开启触控修正";
    private final Runnable supervision=new Runnable() {
        @Override public void run() { if (connected) { tick(); main.postDelayed(this,1000); } }
    };
    public static boolean connected() { return instance!=null && instance.connected; }
    public static String status() { return connected() ? instance.status : "请先启用“免电脑触控修正”无障碍服务"; }
    public static void refresh() { if (instance!=null) instance.main.post(instance::tick); }
    public static void reloadMapping() {
        if (instance!=null) instance.main.post(() -> { if (instance!=null) { instance.stopReader(); instance.tick(); } });
    }
    /** Only called from our non-exported test canvas, while USB correction is off. */
    public static boolean checkOutput(int w,int h) {
        UsbTouchAccessibilityService owner=instance;
        if (owner==null || !owner.connected || UsbTouchSettings.enabled(owner) || owner.reader!=null || owner.diagnostic!=null) return false;
        GestureStream stream=new GestureStream(owner,new GestureStream.Listener() {
            @Override public void fault(String reason) { owner.fail(reason); owner.disableSelf(); }
            @Override public void cancelled() { Log.w("UsbTouch","Output check cancelled"); }
        });
        owner.diagnostic=stream;
        // A drag, followed by a two-pointer gesture and independent release, all inside the blank canvas.
        int[] times={0,120,240,360,480,650,800,950,1100,1250};
        float[][] points={{.2f,.4f,1},{.4f,.4f,1},{.6f,.5f,1},{.8f,.6f,1},{.8f,.6f,0},
                {.3f,.5f,1},{.3f,.5f,2},{.25f,.5f,2},{.25f,.5f,1},{.25f,.5f,0}};
        for (int i=0;i<times.length;i++) {
            final float[] p=points[i];
            owner.main.postDelayed(() -> {
                if (owner.diagnostic!=stream) return;
                Map<Integer,TouchFrameQueue.Point> map=new TreeMap<>();
                map.put(0,new TouchFrameQueue.Point(w*p[0],h*p[1],p[2]>0));
                if (p[2]==2) map.put(1,new TouchFrameQueue.Point(w*(1-p[0]),h*.5f,true));
                stream.offer(new TouchFrameQueue.Frame(map));
            },times[i]);
        }
        owner.main.postDelayed(() -> {
            if (owner.diagnostic!=stream) return;
            stream.stop(); owner.diagnostic=null;
            UsbTouchSettings.prefs(owner).edit().putLong("output_check_segments",stream.completed)
                    .putLong("output_check_cancelled",stream.cancelled).putLong("output_check_at",System.currentTimeMillis()).apply();
        },1800);
        return true;
    }
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance=this; connected=true;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL,"免电脑触控修正",NotificationManager.IMPORTANCE_LOW));
        UsbTouchSettings.prefs(this).edit().putInt("service_boot",Settings.Global.getInt(getContentResolver(),Settings.Global.BOOT_COUNT,-1))
                .putInt("service_uid",Process.myUid()).putLong("service_started",System.currentTimeMillis()).apply();
        Log.i("UsbTouch","Accessibility connected uid="+Process.myUid());
        main.post(supervision);
    }
    private void tick() {
        try {
            if (!connected) return;
            if (!UsbTouchSettings.enabled(this)) { stopReader(); update("已关闭 · 外接触屏使用系统原始输入"); return; }
            if (diagnostic!=null) { diagnostic.stop(); diagnostic=null; }
            PowerManager power=getSystemService(PowerManager.class);
            if (!power.isInteractive() || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                stopReader(); update("等待手机解锁后恢复触控修正"); return;
            }
            UsbManager usb=getSystemService(UsbManager.class);
            UsbDevice device=UsbTouchReader.find(usb);
            if (reader!=null) {
                if (device==null || !reader.device.getDeviceName().equals(device.getDeviceName())) {
                    stopReader(); update("等待重新连接 ILITEK 外接触屏");
                } else if (ready) {
                    update("触控修正运行中 · 无需 Shizuku");
                    UsbTouchSettings.prefs(this).edit().putLong("reports",reports)
                            .putLong("last_report_at",lastReportAt)
                            .putLong("gesture_segments",gestures==null?0:gestures.completed)
                            .putLong("cancelled_gestures",gestures==null?0:gestures.cancelled).apply();
                }
                return;
            }
            if (device==null) { update("等待连接 ILITEK 外接触屏"); return; }
            if (!usb.hasPermission(device)) { update("请打开开关页面，允许访问 USB 触屏"); return; }
            mapping=new UsbTouchSettings.Mapping(this);
            update("正在连接外接触屏…");
            startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            foreground=true;
            reports=0; lastReportAt=0;
            final long generation=++outputGeneration;
            gestures=new GestureStream(this,new GestureStream.Listener() {
                @Override public void fault(String reason) {
                    if (outputGeneration!=generation) return;
                    fail(reason);
                    // Disconnecting this accessibility service also cancels any stuck injected pointer.
                    disableSelf();
                }
                @Override public void cancelled() { Log.i("UsbTouch","Gesture cancelled; wait for physical lift"); }
            });
            final GestureStream stream=gestures;
            width=height=0;
            UsbTouchReader next=new UsbTouchReader(usb,device,new UsbTouchReader.Listener() {
                @Override public void ready() { main.post(() -> {
                    if (gestures!=stream) return;
                    ready=true;
                    UsbTouchSettings.prefs(UsbTouchAccessibilityService.this).edit()
                            .putInt("capture_boot",Settings.Global.getInt(getContentResolver(),Settings.Global.BOOT_COUNT,-1))
                            .putInt("capture_uid",Process.myUid()).putLong("capture_started",System.currentTimeMillis()).apply();
                    Log.i("UsbTouch","USB claimed, verified descriptor, uid="+Process.myUid());
                }); }
                @Override public void report(List<IlitekReport.Contact> contacts) { main.post(() -> {
                    if (gestures!=stream || !UsbTouchSettings.enabled(UsbTouchAccessibilityService.this)) return;
                    try { onReport(contacts); } catch (RuntimeException e) { fail(e.getMessage()); }
                }); }
                @Override public void closed(String error,int reconnect) { main.post(() -> {
                    if (gestures!=stream) return;
                    stream.stop(); ready=false; reader=null; gestures=null;
                    stopForeground(STOP_FOREGROUND_REMOVE); foreground=false;
                    Log.i("UsbTouch","USB released reconnect="+reconnect+" error="+error);
                    UsbTouchSettings.prefs(UsbTouchAccessibilityService.this).edit().putInt("reconnect_result",reconnect)
                            .putLong("capture_stopped",System.currentTimeMillis()).putLong("reports",reports)
                            .putLong("gesture_segments",stream.completed).putLong("cancelled_gestures",stream.cancelled).apply();
                    // ENODEV is expected on physical unplug; EBUSY means the driver is already bound.
                    if (reconnect!=0 && reconnect!=-16 && reconnect!=-19) fail("请重新插接触屏，USB 驱动未能恢复（"+reconnect+"）");
                    else if (error!=null && UsbTouchReader.find(getSystemService(UsbManager.class))!=null) fail(error);
                    else tick();
                }); }
            });
            reader=next; next.start();
        } catch (Throwable error) { fail(error.getMessage()==null?error.toString():error.getMessage()); }
    }
    private void onReport(List<IlitekReport.Contact> contacts) {
        Display display=getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display==null) throw new IllegalStateException("手机显示区域不可用");
        Point size=new Point(); display.getRealSize(size); int r=display.getRotation();
        if (width!=0 && (width!=size.x || height!=size.y || rotation!=r)) gestures.endAndSuppress();
        width=size.x; height=size.y; rotation=r;
        Map<Integer,TouchFrameQueue.Point> mapped=new TreeMap<>();
        for (IlitekReport.Contact c:contacts) {
            float[] p=mapping.map(c.x,c.y,width,height,rotation);
            mapped.put(c.id,new TouchFrameQueue.Point(p[0],p[1],c.down));
        }
        reports++; lastReportAt=System.currentTimeMillis();
        gestures.offer(new TouchFrameQueue.Frame(mapped));
    }
    private void stopReader() {
        if (reader!=null) { reader.requestStop(); if (gestures!=null) gestures.stop(); }
        else if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground=false; }
        ready=false;
    }
    private void fail(String message) {
        Log.e("UsbTouch",message);
        UsbTouchSettings.prefs(this).edit().putBoolean("usb_touch_enabled",false).putString("last_error",message).commit();
        stopReader(); update("已停止："+message);
    }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,UsbTouchActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getBroadcast(this,1,new Intent(this,UsbTouchPermissionReceiver.class)
                .setAction(UsbTouchPermissionReceiver.STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_touch_enable)
                .setContentTitle("免电脑触控修正").setContentText(status).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null,"关闭修正",stop).build()).build();
    }
    private void update(String message) {
        if (status.equals(message)) return;
        status=message; Log.i("UsbTouch",message);
        UsbTouchSettings.prefs(this).edit().putString("status",message).apply();
        if (foreground) getSystemService(NotificationManager.class).notify(NOTIFICATION,notification());
    }
    private void disconnect() {
        if (diagnostic!=null) { diagnostic.stop(); diagnostic=null; }
        connected=false; main.removeCallbacks(supervision); stopReader();
        if (instance==this) instance=null;
    }
    @Override public boolean onUnbind(Intent intent) { disconnect(); return super.onUnbind(intent); }
    @Override public void onDestroy() { disconnect(); super.onDestroy(); }
    @Override public void onInterrupt() { if (gestures!=null) gestures.endAndSuppress(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* No screen-content access. */ }
}
