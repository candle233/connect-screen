package com.gitee.connect_screen.usbtouch;

import android.hardware.usb.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** Owns one exact USB interface. Every exit path returns it to the kernel. */
final class UsbTouchReader extends Thread {
    interface Listener {
        void ready();
        void report(List<IlitekReport.Contact> contacts);
        void closed(String error, int reconnectResult);
    }
    final UsbDevice device;
    private final UsbManager manager;
    private final Listener listener;
    private volatile boolean stopping;
    UsbTouchReader(UsbManager manager, UsbDevice device, Listener listener) {
        super("ilitek-usb-reader"); this.manager=manager; this.device=device; this.listener=listener;
    }
    static boolean matches(UsbDevice d) {
        return d.getVendorId()==0x222a && d.getProductId()==1
                && "ILITEK".equals(d.getManufacturerName()) && "ILITEK-TP".equals(d.getProductName());
    }
    static UsbDevice find(UsbManager manager) {
        for (UsbDevice d : manager.getDeviceList().values()) if (matches(d)) return d;
        return null;
    }
    void requestStop() { stopping=true; }
    @Override public void run() {
        UsbDeviceConnection connection=null;
        UsbInterface iface=null;
        UsbRequest request=null;
        boolean claimed=false;
        int reconnect=0;
        String error=null;
        try {
            UsbDriver.verifyAvailable();
            if (!matches(device) || !manager.hasPermission(device)) throw new IOException("尚未授权此触屏");
            if (device.getInterfaceCount()!=1) throw new IOException("USB 接口与已验证触屏不符");
            iface=device.getInterface(0);
            if (iface.getId()!=0 || iface.getInterfaceClass()!=UsbConstants.USB_CLASS_HID)
                throw new IOException("USB HID 接口与已验证触屏不符");
            UsbEndpoint endpoint=null;
            for (int i=0;i<iface.getEndpointCount();i++) {
                UsbEndpoint e=iface.getEndpoint(i);
                if (e.getDirection()==UsbConstants.USB_DIR_IN && e.getType()==UsbConstants.USB_ENDPOINT_XFER_INT
                        && e.getMaxPacketSize()==64 && e.getAddress()==129) endpoint=e;
            }
            if (endpoint==null) throw new IOException("USB 输入端点与已验证触屏不符");
            connection=manager.openDevice(device);
            if (connection==null) throw new IOException("无法打开已授权的触屏");
            if (stopping) return;
            claimed=connection.claimInterface(iface, true);
            if (!claimed) throw new IOException("触屏正被其他应用占用");
            byte[] descriptor=new byte[4096];
            int length=connection.controlTransfer(0x81,6,0x2200,iface.getId(),descriptor,descriptor.length,2000);
            if (length!=850) throw new IOException("触屏描述符长度与实测不符");
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); digest.update(descriptor,0,length);
            StringBuilder hash=new StringBuilder();
            for (byte b:digest.digest()) hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            if (!IlitekReport.DESCRIPTOR_SHA256.equals(hash.toString())) throw new IOException("触屏协议与实测不符");
            request=new UsbRequest();
            if (!request.initialize(connection,endpoint)) throw new IOException("无法建立触屏读取请求");
            ByteBuffer buffer=ByteBuffer.allocateDirect(64);
            listener.ready();
            while (!stopping) {
                buffer.clear();
                if (!request.queue(buffer)) throw new IOException("无法排队读取触屏");
                boolean complete=false;
                while (!stopping && !complete) {
                    try {
                        if (connection.requestWait(500)!=request) throw new IOException("触屏已断开");
                        complete=true;
                    } catch (TimeoutException idle) { /* Keep this request queued while the panel is idle. */ }
                }
                if (stopping) break;
                int size=buffer.position();
                byte[] data=new byte[size]; buffer.flip(); buffer.get(data);
                List<IlitekReport.Contact> contacts=IlitekReport.decode(data,size);
                if (contacts!=null) listener.report(contacts);
            }
        } catch (Throwable failure) {
            if (!stopping) error=failure.getMessage()==null ? failure.toString() : failure.getMessage();
        } finally {
            if (request!=null) {
                try { request.cancel(); } catch (RuntimeException ignored) {}
                try { request.close(); } catch (RuntimeException ignored) {}
            }
            if (connection!=null) {
                try {
                    if (claimed) {
                        connection.releaseInterface(iface);
                        reconnect=UsbDriver.reconnect(connection.getFileDescriptor(),iface.getId());
                    }
                } catch (Throwable cleanup) {
                    if (error==null) error="恢复触屏驱动失败，请重新插接触屏："+cleanup;
                } finally { connection.close(); }
            }
            listener.closed(error,reconnect);
        }
    }
}
