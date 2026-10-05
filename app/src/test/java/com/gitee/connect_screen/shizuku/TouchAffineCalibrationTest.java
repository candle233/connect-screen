package com.gitee.connect_screen.shizuku;
import org.junit.Test;
import static org.junit.Assert.*;

public class TouchAffineCalibrationTest {
    @Test public void measuredVisual270UsesQuarterTurn90AndAffineCorrection() {
        double[][] points = {{14119,1644,108,264.9000244140625},
                {14182,15391,972,264.9000244140625}, {1332,2272,108,1736.0999755859375},
                {1338,14735,972,1736.0999755859375}, {7784,8181,540,1000.5}};
        assertEquals(1, TouchAffineCalibration.nearestQuarterTurn(points));
        assertEquals(20.93269375403792, TouchAffineCalibration.rmse(points,
                TouchAffineCalibration.fit(points)), 1e-7);
    }
    @Test public void recoversMeasuredReflectionAndPredictsUnseenPoint() {
        double[][] points = {{1000,2000,200,100}, {15000,2000,200,1500},
                {1000,14000,1400,100}, {15000,14000,1400,1500}, {8000,8000,800,800}};
        double[] c = TouchAffineCalibration.fit(points);
        assertEquals(0, TouchAffineCalibration.rmse(points, c), 1e-7);
        // Hold-out point was not supplied to the fit.
        assertArrayEquals(new float[] {500, 600}, TouchRotationTransform.mapAffine(6000,5000,c,1920,1920), .001f);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsCollinearCalibration() {
        TouchAffineCalibration.fit(new double[][] {{0,0,0,0},{100,100,10,10},{200,200,20,20}});
    }
}
