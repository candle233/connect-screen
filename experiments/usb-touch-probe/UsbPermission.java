import android.content.Context;
import android.hardware.usb.*;
import android.os.*;
import java.lang.reflect.*;

/** One-time, exact ILITEK permission provisioning through authorized ADB shell. */
public class UsbPermission {
    public static void main(String[] args) throws Exception {
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object instance = thread.getMethod("systemMain").invoke(null);
        Context system = (Context)thread.getMethod("getSystemContext").invoke(instance);
        Context shell = system.createPackageContext("com.android.shell", 0);
        UsbManager manager = shell.getSystemService(UsbManager.class);
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "usb");
        Object service = Class.forName("android.hardware.usb.IUsbManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> api = Class.forName("android.hardware.usb.IUsbManager");
        Bundle devices = new Bundle();
        api.getMethod("getDeviceList", Bundle.class).invoke(service, devices);
        System.out.println("devices=" + devices.keySet());
        int uid = shell.getPackageManager().getApplicationInfo(args[0], 0).uid;
        for (String name : devices.keySet()) {
            UsbDevice device = devices.getParcelable(name);
            System.out.println("device=" + device);
            if (device.getVendorId() != 0x222a || device.getProductId() != 1
                || !"ILITEK".equals(device.getManufacturerName()) || !"ILITEK-TP".equals(device.getProductName())) continue;
            if (args.length > 1 && args[1].equals("persistent")) {
                api.getMethod("setDevicePersistentPermission", UsbDevice.class, int.class, UserHandle.class, boolean.class)
                    .invoke(service, device, uid, android.os.Process.myUserHandle(), true);
                System.out.println("Persistent exact-device permission granted uid=" + uid);
            } else {
                api.getMethod("grantDevicePermission", UsbDevice.class, int.class).invoke(service, device, uid);
                System.out.println("Temporary exact-device permission granted uid=" + uid);
            }
        }
        System.exit(0);
    }
}
