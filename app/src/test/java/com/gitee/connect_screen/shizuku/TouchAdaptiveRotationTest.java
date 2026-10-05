package com.gitee.connect_screen.shizuku;

import org.junit.Test;
import static org.junit.Assert.*;

public class TouchAdaptiveRotationTest {
    @Test public void phone270LandscapeRemovesThePortraitQuarterTurn() {
        // Measured Mate 30 transition: phone 0,1080x1920 -> 3,1920x1080.
        assertPoint(0, 0, 3, 1920, 1080, 0, 0);
        assertPoint(16384, 0, 3, 1920, 1080, 1919, 0);
        assertPoint(0, 16384, 3, 1920, 1080, 0, 1079);
        assertPoint(16384, 16384, 3, 1920, 1080, 1919, 1079);
        assertPoint(8192, 8192, 3, 1920, 1080, 960, 540);
        // Back to portrait restores the original map without restarting/grabbing.
        assertPoint(4096, 12288, 0, 1080, 1920, 810, 1440);
    }
    @Test public void oppositeLandscapeAndSameRotationResizeAreSupported() {
        assertPoint(4096, 12288, 1, 1920, 1080, 1440, 270);
        assertPoint(4096, 12288, 0, 720, 1280, 540, 960);
    }
    @Test public void affineCalibrationRotatesAndScalesWithoutLosingOffsets() {
        double[] affine = {0,1080d/16384,12,-1920d/16384,0,1900};
        // Portrait (822,1420) -> phone270 landscape (1920-1420,822).
        assertArrayEquals(new float[] {500,822}, TouchRotationTransform.mapAdaptive(4096,12288,1,
                affine,1080,1920,0,1920,1080,3), .001f);
        assertArrayEquals(new float[] {822,1420}, TouchRotationTransform.mapAdaptive(4096,12288,1,
                affine,1080,1920,0,1080,1920,0), .001f);
    }
    @Test public void calibrationMadeInLandscapeCanReturnToPortrait() {
        double[] affine = {1920d/16384,0,0,0,1080d/16384,0};
        assertArrayEquals(new float[] {810,1440}, TouchRotationTransform.mapAdaptive(4096,12288,0,
                affine,1920,1080,3,1080,1920,0), .001f);
    }
    private static void assertPoint(int x, int y, int phoneRotation, int width, int height,
            float expectedX, float expectedY) {
        assertArrayEquals(new float[] {expectedX, expectedY}, TouchRotationTransform.mapAdaptive(
                x,y,1,null,1080,1920,0,width,height,phoneRotation), .001f);
    }
}
