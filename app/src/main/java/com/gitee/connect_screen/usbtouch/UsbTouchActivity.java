package com.gitee.connect_screen.usbtouch;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import com.gitee.connect_screen.TouchQuickStartActivity;
import com.gitee.connect_screen.TouchRotationController;
import com.gitee.connect_screen.TouchRotationTestActivity;

/** One persistent switch; setup controls are shown only for missing user grants. */
public final class UsbTouchActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private Switch toggle;
    private TextView status,details;
    private Button accessibility,usb;
    private boolean rendering;
    private String pendingPermissionDevice;
    private final Runnable refresh=new Runnable() { @Override public void run() { render(); main.postDelayed(this,700); } };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction()) && UsbTouchSettings.selected(this)) {
            UsbTouchAccessibilityService.refresh();
            if (!UsbTouchSettings.enabled(this) || UsbTouchAccessibilityService.connected()) { finish(); return; }
        }
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(24*getResources().getDisplayMetrics().density); box.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(this); title.setText("免电脑触控修正"); title.setTextSize(26); box.addView(title);
        TextView help=new TextView(this); help.setText("首次完成下面的系统授权。以后重启手机并解锁，开启这个开关即可。保持开启时会尝试自动恢复。");
        help.setTextSize(16); box.addView(help);
        toggle=new Switch(this); toggle.setText("触控修正"); toggle.setTextSize(22); toggle.setPadding(0,pad,0,pad); box.addView(toggle);
        status=new TextView(this); status.setTextSize(18); box.addView(status);
        accessibility=new Button(this); accessibility.setText("首次设置：启用无障碍服务");
        accessibility.setOnClickListener(v -> openAccessibility()); box.addView(accessibility);
        usb=new Button(this); usb.setText("首次设置：允许使用 USB 触屏");
        usb.setOnClickListener(v -> { pendingPermissionDevice=null; requestUsbPermission(); }); box.addView(usb);
        Button test=new Button(this); test.setText("测试点击和拖动");
        test.setOnClickListener(v -> startActivity(new Intent(this,TouchRotationTestActivity.class))); box.addView(test);
        Button outputCheck=new Button(this); outputCheck.setText("检查系统触控输出");
        outputCheck.setOnClickListener(v -> {
            if (UsbTouchSettings.enabled(this) || !UsbTouchAccessibilityService.connected()) {
                Toast.makeText(this,"先启用无障碍服务，保持触控修正开关关闭，再运行此检查",Toast.LENGTH_LONG).show(); return;
            }
            startActivity(new Intent(this,TouchRotationTestActivity.class).putExtra("usb_output_check",true));
        }); box.addView(outputCheck);
        details=new TextView(this); details.setTextSize(14); details.setPadding(0,pad,0,pad); box.addView(details);
        Button legacy=new Button(this); legacy.setText("切回原来的 Shizuku 模式");
        legacy.setOnClickListener(v -> {
            UsbTouchSettings.useLegacy(this);
            // Let the USB owner return the interface before the original relay starts.
            main.postDelayed(() -> { startActivity(new Intent(this,TouchQuickStartActivity.class)); finish(); },1000);
        }); box.addView(legacy);
        ScrollView scroll=new ScrollView(this); scroll.addView(box); setContentView(scroll);
        toggle.setOnCheckedChangeListener((button,on) -> {
            if (rendering) return;
            if (on) {
                try { new UsbTouchSettings.Mapping(this); TouchRotationController.stop(this); }
                catch (Exception error) { Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show(); render(); return; }
            }
            UsbTouchSettings.setEnabled(this,on);
            if (on) {
                if (!UsbTouchSettings.accessibilityEnabled(this)) openAccessibility();
                else requestUsbPermission();
            }
            render();
        });
    }
    private void openAccessibility() {
        Intent intent=new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        intent.putExtra("android.intent.extra.COMPONENT_NAME",new ComponentName(this,UsbTouchAccessibilityService.class));
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
    }
    private void requestUsbPermission() {
        UsbManager manager=getSystemService(UsbManager.class); UsbDevice device=UsbTouchReader.find(manager);
        if (device==null || manager.hasPermission(device) || device.getDeviceName().equals(pendingPermissionDevice)) return;
        pendingPermissionDevice=device.getDeviceName();
        PendingIntent result=PendingIntent.getBroadcast(this,0,new Intent(this,UsbTouchPermissionReceiver.class)
                .setAction(UsbTouchPermissionReceiver.PERMISSION),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        manager.requestPermission(device,result);
    }
    private void render() {
        if (toggle==null) return;
        rendering=true;
        if (toggle.isChecked()!=UsbTouchSettings.enabled(this)) toggle.setChecked(UsbTouchSettings.enabled(this));
        rendering=false;
        boolean a11y=UsbTouchSettings.accessibilityEnabled(this);
        UsbManager manager=getSystemService(UsbManager.class); UsbDevice device=UsbTouchReader.find(manager);
        boolean granted=device!=null && manager.hasPermission(device);
        accessibility.setVisibility(a11y ? android.view.View.GONE : android.view.View.VISIBLE);
        usb.setVisibility(device!=null && !granted ? android.view.View.VISIBLE : android.view.View.GONE);
        setTextIfChanged(status,UsbTouchAccessibilityService.status());
        SharedPreferences p=UsbTouchSettings.prefs(this);
        int boot=Settings.Global.getInt(getContentResolver(),Settings.Global.BOOT_COUNT,-1);
        setTextIfChanged(details,"无障碍："+(a11y?"已启用":"待启用")+"\nUSB 触屏："+(device==null?"未连接":granted?"已授权":"待授权")
                +"\n本次开机编号："+boot+" · 最近运行编号："+p.getInt("capture_boot",-1)
                +"\n触屏报告："+p.getLong("reports",0)+" · 输出片段："+p.getLong("gesture_segments",0)
                +"\n关闭开关会恢复系统原始触摸。可始终用手机自带屏幕关闭。"
                +(p.contains("last_error")?"\n上次提示："+p.getString("last_error",""):""));
    }
    private static void setTextIfChanged(TextView view,String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }
    @Override protected void onResume() {
        super.onResume(); if (toggle==null) return;
        main.post(refresh);
        if (UsbTouchSettings.enabled(this) && UsbTouchSettings.accessibilityEnabled(this)) requestUsbPermission();
        UsbTouchAccessibilityService.refresh();
    }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
}
