package com.gitee.connect_screen.shizuku;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

public class TouchRotationTransformTest {
    @Test public void cornersRotateBothDirectionsAndStayInsideDisplay() {
        assertPoint(1, 0, 0, 0, 1919);
        assertPoint(1, 16384, 0, 0, 0);
        assertPoint(1, 0, 16384, 1079, 1919);
        assertPoint(1, 16384, 16384, 1079, 0);
        assertPoint(3, 0, 0, 1079, 0);
        assertPoint(3, 16384, 0, 1079, 1919);
        assertPoint(3, 0, 16384, 0, 0);
        assertPoint(3, 16384, 16384, 0, 1919);
    }

    @Test public void centerIsInvariantAndOutOfRangeInputIsClamped() {
        for (int rotation = 0; rotation < 4; rotation++) assertPoint(rotation, 8192, 8192, 540, 960);
        assertPoint(0, -100, 20000, 0, 1919);
        assertPoint(2, 0, 0, 1079, 1919);
        assertPoint(2, 16384, 16384, 0, 0);
    }

    private static void assertPoint(int rotation, int x, int y, float expectedX, float expectedY) {
        assertArrayEquals(new float[] {expectedX, expectedY},
                TouchRotationTransform.map(x, y, rotation, 1080, 1920), .001f);
    }
}
