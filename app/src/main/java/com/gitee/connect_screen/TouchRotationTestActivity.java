package com.gitee.connect_screen;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import com.gitee.connect_screen.shizuku.TouchAffineCalibration;

/** A consuming test canvas avoids activating navigation/settings during corner tests. */
public class TouchRotationTestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(new TouchCanvas());
    }

    private final class TouchCanvas extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path trail = new Path();
        private float x = -1, y = -1;
        private int moves;
        private boolean calibrating = getIntent().getBooleanExtra("calibrate", false);
        private boolean fitting;
        private int collected;
        private final double[][] samples = new double[5][4];
        private final float[][] targets = {{.1f,.1f},{.9f,.1f},{.1f,.9f},{.9f,.9f},{.5f,.5f}};
        private String result = "依次触摸 1、2、3、4、5，然后长按、滑动；用返回键退出";
        TouchCanvas() { super(TouchRotationTestActivity.this); }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(245, 247, 250));
            paint.setStrokeWidth(5); paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.rgb(30, 100, 220)); canvas.drawPath(trail, paint);
            paint.setTextSize(32); paint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < targets.length; i++) {
                float tx = targets[i][0] * getWidth(), ty = targets[i][1] * getHeight();
                paint.setColor(calibrating && i == collected ? Color.rgb(220, 130, 0) : Color.rgb(190, 40, 45));
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(tx, ty, 48, paint);
                canvas.drawLine(tx - 65, ty, tx + 65, ty, paint);
                canvas.drawLine(tx, ty - 65, tx, ty + 65, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawText("" + (i + 1), tx, ty + 11, paint);
            }
            paint.setColor(Color.rgb(30, 100, 220));
            if (x >= 0) canvas.drawCircle(x, y, 18, paint);
            paint.setColor(Color.rgb(30, 35, 45)); paint.setTextSize(30);
            canvas.drawText("触控验证 " + getWidth() + " × " + getHeight(), getWidth()/2f, getHeight()*.32f, paint);
            int visual = TouchRotationController.preferences(TouchRotationTestActivity.this)
                    .getInt(TouchRotationController.VISUAL, -1);
            int transform = TouchRotationController.preferences(TouchRotationTestActivity.this)
                    .getInt(TouchRotationController.TRANSFORM, -1);
            canvas.drawText("记录画面 " + visual + "° / 触控 " + transform + "°", getWidth()/2f, getHeight()*.35f, paint);
            paint.setTextSize(25);
            canvas.drawText(calibrating && !fitting ? "标定：请单指点击橙色目标 " + (collected + 1) : result,
                    getWidth()/2f, getHeight()*.39f, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            x = event.getX(); y = event.getY();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (fitting) return true;
                moves = 0; trail.reset(); trail.moveTo(x, y);
                Log.i("TouchRotationTest", "DOWN raw=" + event.getRawX() + "," + event.getRawY()
                        + " pointers=" + event.getPointerCount() + " source=" + event.getSource());
                if (calibrating) {
                    try {
                        long[] raw = State.userService.getLastTouchDownRaw();
                        if (raw == null || raw.length != 3 || raw[2] != event.getDownTime()) {
                            throw new IllegalStateException("请使用外接 USB 触屏，不能用手机内屏或 ADB 点击标定");
                        }
                        int[] location = new int[2]; getLocationOnScreen(location);
                        samples[collected] = new double[] {raw[0], raw[1],
                                location[0] + targets[collected][0] * getWidth(),
                                location[1] + targets[collected][1] * getHeight()};
                        Log.i("TouchRotationTest", "CALIBRATION index=" + collected + " raw=" + raw[0] + "," + raw[1]
                                + " target=" + samples[collected][2] + "," + samples[collected][3]);
                    } catch (Exception e) {
                        result = e.getMessage();
                        Log.e("TouchRotationTest", "Calibration capture failed", e);
                        android.widget.Toast.makeText(TouchRotationTestActivity.this, result,
                                android.widget.Toast.LENGTH_LONG).show();
                        samples[collected] = null;
                    }
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                ++moves; trail.lineTo(x, y);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                long duration = event.getEventTime() - event.getDownTime();
                result = "UP: " + Math.round(event.getRawX()) + "," + Math.round(event.getRawY())
                        + "   " + duration + "ms   MOVE=" + moves;
                Log.i("TouchRotationTest", result);
                if (calibrating && !fitting && samples[collected] != null) {
                    if (++collected == samples.length) fitCalibration();
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) result = "手势已取消";
            invalidate();
            return true;
        }

        private void fitCalibration() {
            fitting = true; result = "正在求解五点标定…";
            new Thread(() -> {
                try {
                    double[] coefficients = TouchAffineCalibration.fit(samples);
                    double rmse = TouchAffineCalibration.rmse(samples, coefficients);
                    if (rmse > 1920 * .02) throw new IllegalStateException("标定误差过大：" + Math.round(rmse) + "px，请重测");
                    int visual = TouchRotationController.savedVisualRotation(TouchRotationTestActivity.this);
                    int[] geometry = State.userService.getTouchRotationGeometry();
                    int transform = TouchAffineCalibration.nearestQuarterTurn(samples, geometry[0], geometry[1]);
                    TouchRotationController.saveCalibration(TouchRotationTestActivity.this, visual, transform, samples, coefficients);
                    runOnUiThread(() -> {
                        calibrating = fitting = false;
                        result = "标定已应用，RMSE=" + Math.round(rmse) + "px；请复测1～5和滑动";
                        invalidate();
                    });
                    Log.i("TouchRotationTest", "CALIBRATION FIT visual=" + visual + " transform=" + transform + " rmse=" + rmse
                            + " matrix=" + java.util.Arrays.toString(coefficients));
                } catch (Exception e) {
                    Log.e("TouchRotationTest", "Calibration fit failed", e);
                    runOnUiThread(() -> {
                        fitting = false; collected = 0;
                        result = "标定失败：" + e.getMessage(); invalidate();
                        android.widget.Toast.makeText(TouchRotationTestActivity.this, result,
                                android.widget.Toast.LENGTH_LONG).show();
                    });
                }
            }, "touch-calibration").start();
        }
    }
}
