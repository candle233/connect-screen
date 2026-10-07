package com.gitee.connect_screen.usbprobe;

import android.app.Activity;
import android.hardware.usb.*;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;
import java.io.*;
import java.nio.ByteBuffer;

/** Bounded, single-device probe. Never touches phone input or other USB devices. */
public final class ProbeActivity extends Activity {
    private TextView output;
    private volatile boolean stopping;
    private volatile boolean busy;
    private synchronized void record(String s) {
        Log.i("UsbTouchProbe", s);
        try (FileWriter w = new FileWriter(new File(getFilesDir(), "probe.txt"), true)) { w.write(s + "\n"); }
        catch (IOException e) { Log.e("UsbTouchProbe", "log", e); }
        runOnUiThread(() -> output.append(s + "\n"));
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(1); layout.setPadding(24,24,24,24);
        TextView title = new TextView(this); title.setTextSize(22);
        title.setText("USB触屏读取测试\n在手机自带屏幕点下方按钮，然后在外接屏点击、画线。测试结束会恢复原触摸。");
        layout.addView(title);
        android.widget.Button start = new android.widget.Button(this); start.setText("开始读取测试（30秒）");
        start.setOnClickListener(v -> { if (!busy) { getIntent().putExtra("claim", true); new Thread(this::probe, "usb-touch-probe").start(); } });
        layout.addView(start);
        output = new TextView(this); output.setTextSize(16);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(output); layout.addView(scroll);
        setContentView(layout);
        if (getIntent().getBooleanExtra("claim", false)) new Thread(this::probe, "usb-touch-probe").start();
    }
    private void probe() {
        busy = true;
        try { runProbe(); } finally { busy = false; }
    }
    private void runProbe() {
        UsbManager manager = getSystemService(UsbManager.class);
        record("uid=" + android.os.Process.myUid() + " claim=" + getIntent().getBooleanExtra("claim", false));
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() != 0x222a || device.getProductId() != 1
                    || !"ILITEK".equals(device.getManufacturerName()) || !"ILITEK-TP".equals(device.getProductName())) continue;
            record("target=" + device.getDeviceName() + " permission=" + manager.hasPermission(device));
            if (!manager.hasPermission(device)) {
                manager.requestPermission(device, android.app.PendingIntent.getActivity(this, 0,
                    new android.content.Intent(this, ProbeActivity.class), android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT));
                return;
            }
            UsbDeviceConnection c = manager.openDevice(device);
            if (c == null) { record("open failed"); return; }
            UsbInterface iface = device.getInterface(0);
            boolean claimed = false;
            try {
                byte[] report = new byte[4096];
                int count = c.controlTransfer(0x81, 6, 0x2200, iface.getId(), report, report.length, 2000);
                record("HID descriptor length=" + count);
                if (count > 0) { try (FileOutputStream f = openFileOutput("hid-report.bin", 0)) { f.write(report, 0, count); } }
                if (getIntent().getBooleanExtra("restore", false)) { record("restore=" + UsbNative.reconnect(c.getFileDescriptor(), iface.getId())); return; }
                if (!getIntent().getBooleanExtra("claim", false)) return;
                claimed = c.claimInterface(iface, true);
                record("claim=" + claimed + " iface=" + iface.getId());
                if (!claimed) return;
                count = c.controlTransfer(0x81, 6, 0x2200, iface.getId(), report, report.length, 2000);
                record("claimed HID descriptor length=" + count);
                if (count > 0) { try (FileOutputStream f = openFileOutput("hid-report.bin", 0)) { f.write(report, 0, count); } }
                UsbEndpoint input = null;
                for (int i = 0; i < iface.getEndpointCount(); i++) if (iface.getEndpoint(i).getDirection() == UsbConstants.USB_DIR_IN) input = iface.getEndpoint(i);
                if (input == null) throw new IOException("No input endpoint");
                UsbRequest request = new UsbRequest();
                try (FileOutputStream raw = openFileOutput("reports.bin", 0)) {
                    if (!request.initialize(c, input)) throw new IOException("Cannot initialize request");
                    ByteBuffer buffer = ByteBuffer.allocateDirect(input.getMaxPacketSize());
                    long deadline = SystemClock.elapsedRealtime() + 30000;
                    int n = 0;
                    while (!stopping && SystemClock.elapsedRealtime() < deadline) {
                        buffer.clear(); if (!request.queue(buffer)) throw new IOException("Cannot queue request");
                        try {
                            if (c.requestWait(500) != request) throw new IOException("Unexpected request");
                            int size = buffer.position(); byte[] bytes = new byte[size]; buffer.flip(); buffer.get(bytes);
                            raw.write(size); raw.write(bytes); n++;
                            if (n <= 8) record("report=" + hex(bytes));
                        } catch (java.util.concurrent.TimeoutException ignored) { request.cancel(); c.requestWait(1000); }
                    }
                    record("reports=" + n);
                } finally { request.cancel(); request.close(); }
            } catch (Throwable e) { record("ERROR " + e); }
            finally {
                try {
                    if (claimed) { record("release=" + c.releaseInterface(iface)); record("reconnect=" + UsbNative.reconnect(c.getFileDescriptor(), iface.getId())); }
                } finally { c.close(); record("closed"); }
                if (claimed) runOnUiThread(() -> {
                    try {
                        startActivity(new android.content.Intent().setClassName("com.gitee.connect_screen.touchfix", "com.gitee.connect_screen.TouchQuickStartActivity"));
                        record("已请求恢复现有触控修正");
                    } catch (RuntimeException e) { record("请手动点原有开启触控修正：" + e); }
                });
            }
        }
        record("DONE");
    }
    private static String hex(byte[] data) { StringBuilder s = new StringBuilder(); for (byte b : data) s.append(String.format("%02x", b & 255)); return s.toString(); }
    @Override public void onDestroy() { stopping = true; super.onDestroy(); }
}
