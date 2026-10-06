package com.gitee.connect_screen;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

/** Mate 30 bootstrap: one-time USB key approval, then local authenticated ADB. */
public final class WirelessShizukuBootstrap {
    public static final String ENABLED = "touch_rotation_wireless_bootstrap";
    private static final String KEY_ALIAS = "touchfix_bootstrap_adb";
    private static final int CNXN = command("CNXN"), AUTH = command("AUTH");
    private static final int OPEN = command("OPEN"), OKAY = command("OKAY");
    private static final int WRTE = command("WRTE"), CLSE = command("CLSE");
    // This Huawei exposes legacy authenticated ADB on 5555 when Wi-Fi ADB is enabled.
    private static final int PORT = 5555;
    private WirelessShizukuBootstrap() {}

    public static boolean isEnabled(Context context) {
        return TouchRotationController.preferences(context).getBoolean(ENABLED, false);
    }
    public static synchronized String prepare(Context context) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setUserAuthenticationRequired(false).build());
            generator.generateKeyPair();
        }
        RSAPublicKey key = (RSAPublicKey) store.getCertificate(KEY_ALIAS).getPublicKey();
        BigInteger modulus = key.getModulus();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        int inverse = modulus.mod(two32).modInverse(two32).negate().mod(two32).intValue();
        ByteBuffer encoded = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        encoded.putInt(64).putInt(inverse);
        encoded.put(littleEndian(modulus, 256));
        encoded.put(littleEndian(BigInteger.ONE.shiftLeft(4096).mod(modulus), 256));
        encoded.putInt(key.getPublicExponent().intValue());
        String publicKey = Base64.encodeToString(encoded.array(), Base64.NO_WRAP) + " touchfix@phone";
        // Only the public key is exported for the one-time USB approval. Private
        // signing material stays inside AndroidKeyStore and is never exported.
        try (OutputStream output = context.openFileOutput("touch_bootstrap_adb.pub", Context.MODE_PRIVATE)) {
            output.write(publicKey.getBytes(StandardCharsets.US_ASCII));
        }
        return publicKey;
    }
    private static byte[] littleEndian(BigInteger number, int size) {
        byte[] big = number.toByteArray(), little = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) little[i] = big[big.length - 1 - i];
        return little;
    }

    /** Called only with an enabled foreground owner and Shizuku currently absent. */
    public static String start(Context context) throws Exception {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "免电脑启动尚缺一次性设置权限";
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities network = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        if (network == null || !network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            return "等待连接已授权的Wi-Fi，随后自动启动Shizuku";
        if (Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 0) {
            // The OS still validates its trusted-network list. No wildcard AP
            // authorization, persistent system-property writes or root are used.
            Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
            return "正在开启已授权Wi-Fi的无线调试…";
        }
        ApplicationInfo shizuku = context.getPackageManager().getApplicationInfo("moe.shizuku.privileged.api", 0);
        String starter = shizuku.nativeLibraryDir + "/libshizuku.so";
        if (!starter.matches("/data/app/[A-Za-z0-9_./=+~\\-]+/libshizuku\\.so"))
            throw new IllegalStateException("Shizuku启动文件路径不受支持");
        String publicKey = prepare(context);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 2000);
            socket.setSoTimeout(4000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            send(output, CNXN, 0x01000000, 4096, bytes("host::"));
            Packet packet = receive(input);
            if (packet.command == AUTH && packet.arg0 == 1) {
                if (packet.data.length != 20) throw new IllegalStateException("ADB认证挑战长度错误");
                send(output, AUTH, 2, 0, sign(packet.data));
                packet = receive(input);
                if (packet.command == AUTH && packet.arg0 == 1) {
                    // First installation (or a revoked key) uses Android's normal
                    // ADB authorization path. No silent replacement of other keys.
                    socket.setSoTimeout(15000);
                    send(output, AUTH, 3, 0, bytes(publicKey));
                    packet = receive(input);
                    socket.setSoTimeout(4000);
                }
            }
            if (packet.command != CNXN)
                throw new IllegalStateException("本机ADB密钥未授权，需要完成一次性USB配置");
            // Fixed installed Shizuku starter only; no caller-provided commands.
            send(output, OPEN, 1, 0, bytes("shell:exec '" + starter + "'"));
            long deadline = android.os.SystemClock.elapsedRealtime() + 8000;
            boolean opened = false;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                packet = receive(input);
                if (packet.arg1 != 1) throw new IllegalStateException("ADB通道编号错误");
                if (packet.command == OKAY) opened = true;
                else if (packet.command == WRTE) send(output, OKAY, 1, packet.arg0, new byte[0]);
                else if (packet.command == CLSE) {
                    send(output, CLSE, 1, packet.arg0, new byte[0]);
                    if (!opened) throw new IllegalStateException("ADB启动通道被拒绝");
                    return "已通过手机本机启动Shizuku，正在连接触控服务…";
                } else throw new IllegalStateException("ADB启动通道响应错误");
            }
            throw new IllegalStateException("Shizuku本机启动超时");
        }
    }
    private static byte[] sign(byte[] challenge) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        PrivateKey key = (PrivateKey) store.getKey(KEY_ALIAS, null);
        // ADB's AUTH token is already a SHA-1 digest. Sign DigestInfo directly,
        // rather than hashing the token a second time with SHA1withRSA.
        byte[] prefix = {0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(key); signature.update(prefix); signature.update(challenge);
        return signature.sign();
    }
    private static int command(String name) {
        return ByteBuffer.wrap(name.getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
    private static byte[] bytes(String text) { return (text + '\0').getBytes(StandardCharsets.UTF_8); }
    private static void send(OutputStream output, int command, int arg0, int arg1, byte[] data) throws Exception {
        int checksum = 0; for (byte value : data) checksum += value & 255;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum).putInt(command ^ -1);
        output.write(header.array()); output.write(data); output.flush();
    }
    private static Packet receive(DataInputStream input) throws Exception {
        byte[] raw = new byte[24]; input.readFully(raw);
        ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int command = header.getInt(), arg0 = header.getInt(), arg1 = header.getInt();
        int length = header.getInt(), checksum = header.getInt(), magic = header.getInt();
        if (magic != (command ^ -1) || length < 0 || length > 1024 * 1024)
            throw new IllegalStateException("ADB数据包头错误");
        byte[] data = new byte[length]; input.readFully(data);
        // Modern peers may omit checksum; legacy peers always send it.
        int actual = 0; for (byte value : data) actual += value & 255;
        if (checksum != 0 && checksum != actual) throw new IllegalStateException("ADB数据包校验失败");
        return new Packet(command, arg0, arg1, data);
    }
    private static final class Packet {
        final int command, arg0, arg1; final byte[] data;
        Packet(int command, int arg0, int arg1, byte[] data) {
            this.command = command; this.arg0 = arg0; this.arg1 = arg1; this.data = data;
        }
    }
}
