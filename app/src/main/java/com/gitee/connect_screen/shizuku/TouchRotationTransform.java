package com.gitee.connect_screen.shizuku;

/** Raw ILITEK coordinates to the target Android logical display. */
public final class TouchRotationTransform {
    private TouchRotationTransform() {}

    public static float[] map(int rawX, int rawY, int rotation, int width, int height) {
        if (rotation < 0 || rotation > 3 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid rotation or target size");
        }
        float u = rawX / 16384f, v = rawY / 16384f;
        float x, y;
        switch (rotation) {
            case 1: x = v * width; y = (1 - u) * height; break;
            case 2: x = (1 - u) * width; y = (1 - v) * height; break;
            case 3: x = (1 - v) * width; y = u * height; break;
            default: x = u * width; y = v * height;
        }
        return new float[] {Math.max(0, Math.min(width - 1, x)),
                Math.max(0, Math.min(height - 1, y))};
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
