package com.gitee.connect_screen.shizuku;

/** Raw ILITEK coordinates to the target Android logical display. */
public final class TouchRotationTransform {
    private TouchRotationTransform() {}

    public static float[] map(int rawX, int rawY, int rotation, int width, int height) {
        return mapAdaptive(rawX, rawY, rotation, null, width, height, 0, width, height, 0);
    }

    /** Compose the measured panel map with changes of the PHONE's logical coordinate frame.
     * External InputReader viewport orientation is deliberately not involved.
     */
    public static float[] mapAdaptive(int rawX, int rawY, int rotation, double[] affine,
            int referenceWidth, int referenceHeight, int referencePhoneRotation,
            int width, int height, int phoneRotation) {
        if (rotation < 0 || rotation > 3 || width <= 0 || height <= 0
                || referenceWidth <= 0 || referenceHeight <= 0
                || referencePhoneRotation < 0 || referencePhoneRotation > 3
                || phoneRotation < 0 || phoneRotation > 3) {
            throw new IllegalArgumentException("Invalid rotation or target size");
        }
        double u = rawX / 16384d, v = rawY / 16384d;
        double x, y;
        if (affine != null) {
            if (affine.length != 6) throw new IllegalArgumentException("Invalid affine map");
            x = (affine[0]*rawX + affine[1]*rawY + affine[2]) / referenceWidth;
            y = (affine[3]*rawX + affine[4]*rawY + affine[5]) / referenceHeight;
        } else {
            switch (rotation) {
                case 1: x = v; y = 1-u; break;
                case 2: x = 1-u; y = 1-v; break;
                case 3: x = 1-v; y = u; break;
                default: x = u; y = v;
            }
        }
        double previousX = x;
        // Android RotationUtils.rotatePointF: delta 90 => (y, parentWidth-x).
        switch ((phoneRotation - referencePhoneRotation + 4) % 4) {
            case 1: x = y; y = 1-previousX; break;
            case 2: x = 1-x; y = 1-y; break;
            case 3: x = 1-y; y = previousX; break;
        }
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Nonfinite mapped point");
        return new float[] {(float)Math.max(0, Math.min(width - 1, x * width)),
                (float)Math.max(0, Math.min(height - 1, y * height))};
    }

    public static float[] mapAffine(int rawX, int rawY, double[] coefficients, int width, int height) {
        if (coefficients.length != 6 || width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid affine map");
        double x = coefficients[0] * rawX + coefficients[1] * rawY + coefficients[2];
        double y = coefficients[3] * rawX + coefficients[4] * rawY + coefficients[5];
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Nonfinite mapped point");
        return new float[] {(float)Math.max(0, Math.min(width - 1, x)),
                (float)Math.max(0, Math.min(height - 1, y))};
    }
}
