package com.gitee.connect_screen.shizuku;

/** Least-squares fit from measured [rawX, rawY, targetX, targetY] rows. */
public final class TouchAffineCalibration {
    private TouchAffineCalibration() {}
    public static double[] fit(double[][] points) {
        if (points.length < 3) throw new IllegalArgumentException("At least three calibration points required");
        double[][] normal = new double[3][3];
        double[] bx = new double[3], by = new double[3];
        for (double[] p : points) {
            if (p.length != 4) throw new IllegalArgumentException("Invalid sample");
            for (double value : p) if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite sample");
            double[] row = {p[0] / 16384d, p[1] / 16384d, 1};
            for (int i = 0; i < 3; i++) {
                bx[i] += row[i] * p[2]; by[i] += row[i] * p[3];
                for (int j = 0; j < 3; j++) normal[i][j] += row[i] * row[j];
            }
        }
        double[] x = solve(normal, bx), y = solve(normal, by);
        double[] result = {x[0]/16384, x[1]/16384, x[2], y[0]/16384, y[1]/16384, y[2]};
        if (Math.abs(result[0]*result[4] - result[1]*result[3]) < 1e-9) {
            throw new IllegalArgumentException("Degenerate calibration map");
        }
        return result;
    }
    private static double[] solve(double[][] matrix, double[] rhs) {
        double[][] rows = new double[3][4];
        for (int i = 0; i < 3; i++) { System.arraycopy(matrix[i], 0, rows[i], 0, 3); rows[i][3] = rhs[i]; }
        for (int column = 0; column < 3; column++) {
            int pivot = column;
            for (int i = column+1; i < 3; i++) if (Math.abs(rows[i][column]) > Math.abs(rows[pivot][column])) pivot = i;
            if (Math.abs(rows[pivot][column]) < 1e-10) throw new IllegalArgumentException("Calibration points are collinear");
            double[] swap = rows[column]; rows[column] = rows[pivot]; rows[pivot] = swap;
            double scale = rows[column][column];
            for (int j = column; j < 4; j++) rows[column][j] /= scale;
            for (int i = 0; i < 3; i++) if (i != column) {
                double factor = rows[i][column];
                for (int j = column; j < 4; j++) rows[i][j] -= factor * rows[column][j];
            }
        }
        return new double[] {rows[0][3], rows[1][3], rows[2][3]};
    }
    public static double rmse(double[][] points, double[] c) {
        double squared = 0;
        for (double[] p : points) {
            double dx = c[0]*p[0] + c[1]*p[1] + c[2] - p[2];
            double dy = c[3]*p[0] + c[4]*p[1] + c[5] - p[3];
            squared += dx*dx + dy*dy;
        }
        return Math.sqrt(squared / points.length);
    }
    public static int nearestQuarterTurn(double[][] points) {
        return nearestQuarterTurn(points, 1080, 1920);
    }
    public static int nearestQuarterTurn(double[][] points, int width, int height) {
        double bestError = Double.POSITIVE_INFINITY;
        int bestRotation = 1;
        for (int rotation = 0; rotation < 4; rotation++) {
            double error = 0;
            for (double[] p : points) {
                float[] mapped = TouchRotationTransform.map((int)p[0], (int)p[1], rotation, width, height);
                double dx = mapped[0]-p[2], dy = mapped[1]-p[3]; error += dx*dx + dy*dy;
            }
            if (error < bestError) { bestError = error; bestRotation = rotation; }
        }
        return bestRotation;
    }
}
