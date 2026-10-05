package com.gitee.connect_screen.job;

import android.view.IWindowManager;

import com.gitee.connect_screen.State;
import com.gitee.connect_screen.TouchRotationController;
import com.gitee.connect_screen.shizuku.ServiceUtils;

public class ChangeRotation implements Job {
    private final static int FIXED_TO_USER_ROTATION_DEFAULT = 0;
    private final static int FIXED_TO_USER_ROTATION_ENABLED = 2;
    private final AcquireShizuku acquireShizuku = new AcquireShizuku();
    public final int displayId;
    public final int rotation;

    public ChangeRotation(int displayId, int rotation) {
        this.displayId = displayId;
        this.rotation = rotation;
    }

    @Override
    public void start() throws YieldException {
        acquireShizuku.start();
        if (!acquireShizuku.acquired) {
            return;
        }
        IWindowManager windowManager = ServiceUtils.getWindowManager();
        boolean applied = false;
        if (rotation == -1) {
            try {
                windowManager.setIgnoreOrientationRequest(displayId, false);
            } catch(Throwable e) {
                State.log("failed to setIgnoreOrientationRequest" + e.getMessage());
            }
            try {
                windowManager.setFixedToUserRotation(displayId, FIXED_TO_USER_ROTATION_DEFAULT);
            } catch(Throwable e) {
                State.log("failed to setFixedToUserRotation" + e.getMessage());
            }
            try {
                windowManager.thawDisplayRotation(displayId, "WindowManagerShellCommand#free");
                applied = true;
            } catch (Error e) {
                try {
                    windowManager.thawDisplayRotation(displayId);
                    applied = true;
                } catch(Throwable e2) {
                    State.log("设置旋转失败：" + e2.getMessage());
                }
            } catch (Exception e) {
                State.log("设置旋转失败：" + e.getMessage());
            }
        } else {
            try {
                windowManager.setIgnoreOrientationRequest(displayId, true);
            } catch(Throwable e) {
                State.log("failed to setIgnoreOrientationRequest" + e.getMessage());
            }
            try {
                windowManager.setFixedToUserRotation(displayId, FIXED_TO_USER_ROTATION_ENABLED);
            } catch(Throwable e) {
                State.log("failed to setFixedToUserRotation" + e.getMessage());
            }
            try {
                windowManager.freezeDisplayRotation(displayId, rotation, "WindowManagerShellCommand#lock");
                applied = true;
            } catch (Error e) {
                try {
                    windowManager.freezeDisplayRotation(displayId, rotation);
                    applied = true;
                } catch(Throwable e2) {
                    State.log("设置旋转失败：" + e2.getMessage());
                }
            } catch (Exception e) {
                State.log("设置旋转失败：" + e.getMessage());
            }
        }
        android.app.Activity activity = State.currentActivity == null ? null : State.currentActivity.get();
        if (applied) {
            State.log("旋转设置已应用：" + rotation);
            if (activity != null) {
                try {
                    TouchRotationController.onVisualRotationApplied(activity.getApplicationContext(), displayId, rotation);
                } catch (Exception e) {
                    TouchRotationController.report(activity, "画面旋转已应用，触控同步失败：" + e.getMessage());
                }
            }
        } else if (rotation == -1 && activity != null) {
            // Freeing the picture failed, but the explicit stop request must still release touch.
            try { TouchRotationController.stop(activity); }
            catch (Exception e) { TouchRotationController.report(activity, "触控停止失败：" + e.getMessage()); }
        }
    }
}
