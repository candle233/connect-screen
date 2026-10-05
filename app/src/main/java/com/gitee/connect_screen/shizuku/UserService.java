package com.gitee.connect_screen.shizuku;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.IDisplayManager;
import android.hardware.input.IInputManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import android.os.RemoteException;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.MotionEventHidden;

import androidx.annotation.Keep;

import com.gitee.connect_screen.State;
import com.gitee.connect_screen.BuildConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import dev.rikka.tools.refine.Refine;

import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class UserService extends IUserService.Stub  {
    private Context context;
    private volatile boolean listenVolumeKey = false;
    private Process listenVolumeKeyProcess;
    private Thread volumeKeyThread;
    private volatile boolean keepScreenOff = false;
    private volatile boolean userExited = false;
    private Thread screenOffLoopThread;
    private static final long SCREEN_OFF_CHECK_INTERVAL = 100; // 检查间隔（毫秒），缩短为100ms以更快响应系统唤醒
    private volatile boolean touchRotationRunning = false;
    private volatile Process touchRelayProcess;
    private Thread touchRelayThread;
    private Thread touchRelayErrorThread;
    private Thread touchDisplayThread;
    private IDisplayManager touchDisplayManager;
    private int[] touchGeometry;
    private int touchReferenceRotation, touchBaseRotation;
    private final Object touchEventLock = new Object();
    private long touchDownTime = 0;
    private float touchX, touchY;
    private int touchDisplayId;
    private IInputManager touchInputManager;
    private long[] lastTouchDownRaw;
    private IBinder touchClientToken, touchShizukuToken;
    private final IBinder.DeathRecipient touchOwnerDied = () -> {
        Log.w("UserService", "touch rotation owner/Shizuku died; releasing grab");
        stopTouchRotation();
    };

    private void installTouchCrashGuard() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try { stopTouchRotation(); }
            finally {
                if (previous != null) previous.uncaughtException(thread, error);
                else System.exit(1);
            }
        });
    }

    public UserService() {
        installTouchCrashGuard();
        Log.i("UserService", "constructor");
    }

    @Keep
    public UserService(Context context) {
        installTouchCrashGuard();
        this.context = context;
        Log.i("UserService", "constructor with Context: context=" + context.toString());
    }
    
    /**
     * Reserved destroy method
     */
    @Override
    public void destroy() {
        Log.i("UserService", "destroy");
        stopTouchRotation();
        System.exit(0);
    }

    @Override
    public void exit() {
        destroy();
    }

    @Override
    public String fetchLogs() throws RemoteException  {
        try {
            Process process = Runtime.getRuntime().exec("logcat -d -f /sdcard/Download/安卓屏连.log");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));

            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            reader.close();
            process.waitFor();

            return output.toString();
        } catch (Exception e) {
            Log.e("UserService", "logcat -d failed", e);
            throw new RemoteException("Failed to execute logcat -d: " + e.getMessage());
        }
    }

    @Override
    public String executeCommand(String command) throws RemoteException {
        try {
            Process process = Runtime.getRuntime().exec("dumpsys input");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            reader.close();
            process.waitFor();
            
            return output.toString();
        } catch (Exception e) {
            Log.e("UserService", "execute command failed: " + command, e);
            throw new RemoteException("Failed to execute command: " + command + " " + e.getMessage());
        }
    }

    public void setScreenPower(int powerMode) {
        Log.i("UserService", "try to setScreenPower: " + powerMode);
        IDisplayManager displayManager = IDisplayManager.Stub.asInterface(SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
        if (Build.VERSION.SDK_INT >= 35) {
            if (powerMode == SurfaceControl.POWER_MODE_OFF) {
                try {
                    displayManager.requestDisplayPower(Display.DEFAULT_DISPLAY, false);
                    Log.i("UserService", "requestDisplayPower by bool");
                } catch(Throwable e) {
                    Log.e("UserService", "failed to power off screen", e);
                    try {
                        displayManager.requestDisplayPower(Display.DEFAULT_DISPLAY, SurfaceControl.POWER_MODE_OFF);
                        Log.i("UserService", "requestDisplayPower by int");
                    } catch(Throwable e2) {
                        Log.e("UserService", "failed to power off screen", e2);
                    }
                }
            } else {
                try {
                    displayManager.requestDisplayPower(Display.DEFAULT_DISPLAY, true);
                    Log.i("UserService", "requestDisplayPower by bool");
                } catch (Throwable e) {
                    Log.e("UserService", "failed to power up screen", e);
                    try {
                        displayManager.requestDisplayPower(Display.DEFAULT_DISPLAY, SurfaceControl.POWER_MODE_NORMAL);
                        Log.i("UserService", "requestDisplayPower by int");
                    } catch(Throwable e2) {
                        Log.e("UserService", "failed to power up screen", e2);
                    }
                }
            }
        } else {
            IBinder d = SurfaceControl.getBuiltInDisplay();
            if (d == null) {
                Log.i("UserService", "Could not get built-in display");
            } else {
                SurfaceControl.setDisplayPowerMode(d, powerMode);
                Log.i("UserService", "setDisplayPowerMode success");
            }
        }
    }

    public void startListenVolumeKey() throws RemoteException {
        if (listenVolumeKey && keepScreenOff && screenOffLoopThread != null && screenOffLoopThread.isAlive()) {
            Log.i("UserService", "startListenVolumeKey: already listening and loop is running, keepScreenOff=" + keepScreenOff);
            return;
        }
        Log.i("UserService", "startListenVolumeKey: starting new loop, previous listenVolumeKey=" + listenVolumeKey + " keepScreenOff=" + keepScreenOff);
        userExited = false;
        listenVolumeKey = true;
        keepScreenOff = true;
        
        // 启动循环熄屏线程（先启动，确保即使音量键监听失败也能保持熄屏）
        screenOffLoopThread = new Thread(() -> {
            Log.i("UserService", "screen off loop started");
            while (keepScreenOff) {
                try {
                    setScreenPower(SurfaceControl.POWER_MODE_OFF);
                    Thread.sleep(SCREEN_OFF_CHECK_INTERVAL);
                } catch (InterruptedException e) {
                    Log.i("UserService", "screen off loop interrupted");
                    break;
                } catch (Throwable e) {
                    // 捕获所有异常，防止循环线程崩溃
                    Log.e("UserService", "screen off loop error, continuing...", e);
                    try {
                        Thread.sleep(SCREEN_OFF_CHECK_INTERVAL);
                    } catch (InterruptedException ie) {
                        Log.i("UserService", "screen off loop interrupted during error recovery");
                        break;
                    }
                }
            }
            Log.i("UserService", "screen off loop stopped");
        });
        screenOffLoopThread.start();
        
        // 启动音量键监听线程
        Thread thread = new Thread(() -> {
            try {
                listenVolumeKeyProcess = Runtime.getRuntime().exec("getevent");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(listenVolumeKeyProcess.getInputStream()));
                while (true) {
                    String line = reader.readLine();
                    if (line == null || !listenVolumeKey) {
                        break;
                    }
                    if (!line.endsWith("0000 0000 00000000") &&
                        (line.endsWith("0001 0072 00000001") || line.endsWith("0001 0073 00000001"))) {
                        Log.i("UserService", "volume key pressed, exiting pure black activity");
                        userExited = true;
                        keepScreenOff = false;
                        setScreenPower(SurfaceControl.POWER_MODE_NORMAL);
                        if (context != null) {
                            Intent intent = new Intent("com.gitee.connect_screen.EXIT_PURE_BLACK");
                            intent.setPackage(BuildConfig.APPLICATION_ID);
                            context.sendBroadcast(intent);
                        } else {
                            Log.i("UserService", "context is null, can not send EXIT_PURE_BLACK");
                        }
                    }
                }
                reader.close();
                listenVolumeKeyProcess.waitFor();
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    listenVolumeKeyProcess.destroyForcibly();
                } else {
                    listenVolumeKeyProcess.destroy();
                }
            } catch (Exception e) {
                Log.e("UserService", "Listen volume key failed", e);
                // 音量键监听失败不影响循环熄屏，只记录日志
            }
        });
        volumeKeyThread = thread;
        thread.start();
    }

    public void stopListenVolumeKey() {
        Log.i("UserService", "stopListenVolumeKey called");
        listenVolumeKey = false;
        keepScreenOff = false;
        
        // 停止循环熄屏线程
        if (screenOffLoopThread != null) {
            screenOffLoopThread.interrupt();
            try {
                screenOffLoopThread.join(1000);
            } catch (InterruptedException e) {
                Log.e("UserService", "join screenOffLoopThread failed", e);
            }
            screenOffLoopThread = null;
        }
        
        // 停止音量键监听进程和线程
        if (listenVolumeKeyProcess != null) {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                listenVolumeKeyProcess.destroyForcibly();
            } else {
                listenVolumeKeyProcess.destroy();
            }
            listenVolumeKeyProcess = null;
        }
        if (volumeKeyThread != null) {
            volumeKeyThread.interrupt();
            volumeKeyThread = null;
        }
    }

    public boolean isLoopActive() {
        return !userExited && listenVolumeKey;
    }

    @Override
    public synchronized void startTouchRotation(String devicePath, int rotation,
            int targetWidth, int targetHeight, int displayId) throws RemoteException {
        startTouchRotationInternal(devicePath, rotation, targetWidth, targetHeight, 0, displayId, null);
    }

    @Override
    public synchronized void startTouchRotationAffine(String devicePath, int rotation,
            int targetWidth, int targetHeight, int displayId, double[] coefficients) throws RemoteException {
        if (coefficients == null || coefficients.length != 6) throw new RemoteException("Invalid affine matrix");
        for (double value : coefficients) if (!Double.isFinite(value)) throw new RemoteException("Nonfinite affine matrix");
        startTouchRotationAdaptive(devicePath, rotation, targetWidth, targetHeight, 0, displayId, coefficients);
    }

    @Override
    public synchronized void startTouchRotationAdaptive(String devicePath, int rotation,
            int referenceWidth, int referenceHeight, int referencePhoneRotation, int displayId,
            double[] coefficients) throws RemoteException {
        if (coefficients != null) {
            if (coefficients.length != 6) throw new RemoteException("Invalid affine matrix");
            for (double value : coefficients) if (!Double.isFinite(value)) throw new RemoteException("Nonfinite affine matrix");
        }
        startTouchRotationInternal(devicePath, rotation, referenceWidth, referenceHeight,
                referencePhoneRotation, displayId, coefficients == null ? null : coefficients.clone());
    }

    private int[] readPhoneGeometry() {
        if (touchDisplayManager == null) touchDisplayManager = IDisplayManager.Stub.asInterface(
                SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
        DisplayInfo info = touchDisplayManager.getDisplayInfo(Display.DEFAULT_DISPLAY);
        if (info == null || info.logicalWidth <= 0 || info.logicalHeight <= 0
                || info.rotation < 0 || info.rotation > 3) {
            throw new IllegalStateException("Phone logical display is unavailable");
        }
        return new int[] {info.logicalWidth, info.logicalHeight, info.rotation};
    }

    @Override public int[] getTouchRotationGeometry() {
        synchronized (touchEventLock) {
            int[] geometry = touchGeometry == null ? readPhoneGeometry() : touchGeometry;
            return new int[] {geometry[0], geometry[1], geometry[2], touchRotationRunning
                    ? (touchBaseRotation + geometry[2] - touchReferenceRotation + 4) % 4 : -1};
        }
    }

    private void releaseFailedTouch(Process process, Throwable error) {
        if (touchRotationRunning) Log.e("UserService", "touch rotation failed; releasing external input", error);
        process.destroyForcibly();
        synchronized (touchEventLock) {
            if (touchRelayProcess == process) {
                touchRotationRunning = false;
                cancelTouchLocked();
                lastTouchDownRaw = null;
            }
        }
    }

    private void startTouchRotationInternal(String devicePath, int rotation,
            int targetWidth, int targetHeight, int referencePhoneRotation, int displayId,
            final double[] affine) throws RemoteException {
        // Native relay verifies exact name, USB bus, MT axes and direct-input property.
        if (!("auto".equals(devicePath) || (devicePath != null
                && devicePath.matches("/dev/input/event[0-9]+") && !devicePath.equals("/dev/input/event2")))
                || rotation < 0 || rotation > 3
                || referencePhoneRotation < 0 || referencePhoneRotation > 3
                || targetWidth <= 0 || targetHeight <= 0 || displayId != 0) {
            throw new RemoteException("touch rotation: invalid device/rotation/size/display");
        }
        stopTouchRotation();
        try {
            if (touchClientToken == null || !touchClientToken.isBinderAlive()
                    || touchShizukuToken == null || !touchShizukuToken.isBinderAlive()) {
                throw new IllegalStateException("APP/Shizuku lifecycle tokens are not connected");
            }
            touchInputManager = IInputManager.Stub.asInterface(
                    SystemServiceHelper.getSystemService(Context.INPUT_SERVICE));
            if (touchInputManager == null) throw new IllegalStateException("input service unavailable");
            synchronized (touchEventLock) {
                touchGeometry = readPhoneGeometry();
                touchReferenceRotation = referencePhoneRotation;
                touchBaseRotation = rotation;
            }
            Process process = new ProcessBuilder("/data/local/tmp/touch_relay", devicePath).start();
            touchRelayProcess = process;
            CountDownLatch ready = new CountDownLatch(1);
            AtomicReference<String> startupError = new AtomicReference<>("relay did not report READY");
            touchRelayErrorThread = new Thread(() -> {
                try (BufferedReader errors = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = errors.readLine()) != null) {
                        Log.i("UserService", "touch_relay: " + line);
                        if (line.startsWith("READY grabbed ")) {
                            startupError.set(null);
                            ready.countDown();
                        } else if (startupError.get() != null) startupError.set(line);
                    }
                } catch (Exception e) {
                    Log.e("UserService", "touch rotation stderr", e);
                } finally {
                    ready.countDown();
                }
            }, "touch-relay-stderr");
            touchRelayErrorThread.start();
            if (!ready.await(3, TimeUnit.SECONDS) || startupError.get() != null || !process.isAlive()) {
                throw new IllegalStateException(startupError.get() == null ? "relay exited" : startupError.get());
            }
            touchDisplayId = displayId;
            touchRotationRunning = true;
            // Runs in the shell UserService while the APP is in the background.
            // Only PHONE display 0 supplies runtime dimensions/rotation. External
            // viewport orientation never overrides the app's requested visual rotation.
            touchDisplayThread = new Thread(() -> {
                try {
                    while (touchRotationRunning && touchRelayProcess == process) {
                        int[] next = readPhoneGeometry();
                        synchronized (touchEventLock) {
                            if (!touchRotationRunning || touchRelayProcess != process) break;
                            if (!java.util.Arrays.equals(next, touchGeometry)) {
                                cancelTouchLocked();
                                lastTouchDownRaw = null;
                                touchGeometry = next;
                                Log.i("UserService", "phone touch geometry=" + next[0] + "x" + next[1]
                                        + " phoneRotation=" + next[2] + " effectiveTransform="
                                        + (rotation + next[2] - referencePhoneRotation + 4) % 4);
                            }
                        }
                        Thread.sleep(100);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    if (touchRotationRunning && touchRelayProcess == process) releaseFailedTouch(process, ignored);
                } catch (Throwable error) {
                    releaseFailedTouch(process, error);
                }
            }, "touch-phone-display");
            touchDisplayThread.start();
            touchRelayThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (touchRotationRunning && (line = reader.readLine()) != null) {
                        String[] fields = line.trim().split("\\s+");
                        if (fields.length != 3) throw new IllegalStateException("invalid relay line: " + line);
                        int rawX = Integer.parseInt(fields[1]);
                        int rawY = Integer.parseInt(fields[2]);
                        synchronized (touchEventLock) {
                            if (!touchRotationRunning || touchRelayProcess != process) break;
                            int[] geometry = touchGeometry;
                            float[] point = TouchRotationTransform.mapAdaptive(rawX, rawY, rotation, affine,
                                    targetWidth, targetHeight, referencePhoneRotation,
                                    geometry[0], geometry[1], geometry[2]);
                            int action;
                            if ("D".equals(fields[0])) {
                                cancelTouchLocked();
                                touchDownTime = SystemClock.uptimeMillis();
                                lastTouchDownRaw = new long[] {rawX, rawY, touchDownTime};
                                action = MotionEvent.ACTION_DOWN;
                            } else if ("M".equals(fields[0])) action = MotionEvent.ACTION_MOVE;
                            else if ("U".equals(fields[0])) action = MotionEvent.ACTION_UP;
                            else throw new IllegalStateException("invalid relay action: " + fields[0]);
                            if (touchDownTime == 0) continue;
                            touchX = point[0]; touchY = point[1];
                            injectTouchLocked(action);
                            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                                Log.i("UserService", "touch rotation " + fields[0] + " raw=" + rawX + "," + rawY
                                        + " mapped=" + touchX + "," + touchY + " display=" + touchDisplayId);
                            }
                            if (action == MotionEvent.ACTION_UP) touchDownTime = 0;
                        }
                    }
                    if (touchRotationRunning) throw new IllegalStateException("touch_relay ended unexpectedly");
                } catch (Throwable e) {
                    releaseFailedTouch(process, e);
                } finally {
                    process.destroyForcibly();
                    synchronized (touchEventLock) {
                        if (touchRelayProcess == process) {
                            touchRotationRunning = false;
                            cancelTouchLocked();
                            lastTouchDownRaw = null;
                        }
                    }
                }
            }, "touch-rotation");
            touchRelayThread.start();
            Log.i("UserService", "touch rotation started rotation=" + rotation + " display=" + displayId
                    + " uid=" + android.os.Process.myUid() + " phoneGeometry=" + java.util.Arrays.toString(touchGeometry));
        } catch (Throwable e) {
            stopTouchRotation();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.e("UserService", "touch rotation start failed", e);
            throw new RemoteException("touch rotation: " + e.getMessage());
        }
    }

    private void injectTouchLocked(int action) {
        MotionEvent event = MotionEvent.obtain(touchDownTime, SystemClock.uptimeMillis(),
                action, touchX, touchY, 0);
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            MotionEventHidden hidden = Refine.unsafeCast(event);
            hidden.setDisplayId(touchDisplayId);
            if (!touchInputManager.injectInputEvent(event, 0)) {
                throw new IllegalStateException("injectInputEvent returned false");
            }
        } finally {
            event.recycle();
        }
    }

    private void cancelTouchLocked() {
        try {
            if (touchDownTime != 0) injectTouchLocked(MotionEvent.ACTION_CANCEL);
        } catch (Throwable e) {
            Log.e("UserService", "touch rotation cancel failed", e);
        } finally {
            touchDownTime = 0;
        }
    }

    @Override
    public synchronized void stopTouchRotation() {
        touchRotationRunning = false;
        Process process = touchRelayProcess;
        try {
            if (process != null) {
                process.destroy();
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("touch_relay did not exit; external input release unconfirmed");
                    }
                }
            }
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
                long deadline = SystemClock.uptimeMillis() + 1500;
                while (process.isAlive() && SystemClock.uptimeMillis() < deadline) {
                    try { process.waitFor(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* Reap before restoring interruption. */ }
                }
                if (process.isAlive()) {
                    throw new IllegalStateException("touch_relay did not exit after interruption");
                }
            }
            Thread.currentThread().interrupt();
        } finally {
            synchronized (touchEventLock) {
                cancelTouchLocked();
                lastTouchDownRaw = null;
            }
            if (touchRelayThread != null) {
                touchRelayThread.interrupt();
                try { touchRelayThread.join(1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (touchRelayErrorThread != null) touchRelayErrorThread.interrupt();
            if (touchDisplayThread != null) {
                touchDisplayThread.interrupt();
                try { touchDisplayThread.join(1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            touchRelayProcess = null;
            touchRelayThread = null;
            touchRelayErrorThread = null;
            touchDisplayThread = null;
            synchronized (touchEventLock) { touchGeometry = null; }
            Log.i("UserService", "touch rotation stopped");
        }
    }

    @Override
    public boolean isTouchRotationActive() {
        Process process = touchRelayProcess;
        return touchRotationRunning && process != null && process.isAlive();
    }

    @Override
    public synchronized void attachTouchRotationClient(IBinder appToken, IBinder shizukuToken)
            throws RemoteException {
        stopTouchRotation();
        if (touchClientToken != null) touchClientToken.unlinkToDeath(touchOwnerDied, 0);
        if (touchShizukuToken != null) touchShizukuToken.unlinkToDeath(touchOwnerDied, 0);
        touchClientToken = touchShizukuToken = null;
        if (appToken == null || shizukuToken == null) throw new RemoteException("Missing lifecycle token");
        appToken.linkToDeath(touchOwnerDied, 0);
        try { shizukuToken.linkToDeath(touchOwnerDied, 0); }
        catch (RemoteException e) { appToken.unlinkToDeath(touchOwnerDied, 0); throw e; }
        touchClientToken = appToken;
        touchShizukuToken = shizukuToken;
        // Attaching/restarting never starts a grab; only a new explicit configuration does.
    }

    @Override
    public synchronized void recoverDefaultTouch() throws RemoteException {
        stopTouchRotation();
        try {
            Process recovery = new ProcessBuilder("/system/bin/pkill", "-x", "touch_relay").start();
            int code = recovery.waitFor();
            if (code != 0 && code != 1) throw new IllegalStateException("pkill exit=" + code);
            Log.i("UserService", "touch rotation recovery: relay processes terminated");
        } catch (Exception e) {
            throw new RemoteException("触控恢复失败：" + e.getMessage());
        }
    }

    @Override public long[] getLastTouchDownRaw() {
        synchronized (touchEventLock) {
            return lastTouchDownRaw == null ? null : lastTouchDownRaw.clone();
        }
    }
}
