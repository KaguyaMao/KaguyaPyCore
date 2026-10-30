package cn.solarmoon.spark_core.physics;

import cn.solarmoon.spark_core.physics.terrain.LocalHeightField;
import cn.solarmoon.spark_core.physics.terrain.LocalHeightSample;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalHeightFieldTest {
    @Test
    void bilinearSampleIsContinuousOnFlatGround() {
        float[] heights = new float[49];
        float[] friction = new float[49];
        boolean[] hard = new boolean[49];
        for (int i = 0; i < 49; i++) {
            heights[i] = 64f;
            friction[i] = 0.6f;
            hard[i] = true;
        }
        LocalHeightField field = LocalHeightField.fromColumns(0, 0, 7, heights, friction, hard, 0, 0.45f);
        LocalHeightSample sample = field.sample(3.4, 2.6);
        assertEquals(64.0, sample.getHeightY(), 1.0e-4);
        assertTrue(sample.getNormalY() > 0.99f);
    }

    @Test
    void downwardSmoothingTurnsAOneBlockCliffIntoARamp() {
        int size = 7;
        float[] heights = new float[size * size];
        float[] friction = new float[size * size];
        boolean[] hard = new boolean[size * size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                heights[i] = x < 3 ? 64f : 65f;
                friction[i] = 0.6f;
                hard[i] = true;
            }
        }
        LocalHeightField raw = LocalHeightField.fromColumns(0, 0, size, heights, friction, hard, 0, 0.45f);
        LocalHeightField smooth = LocalHeightField.fromColumns(0, 0, size, heights, friction, hard, 3, 0.45f);
        double cliff = raw.sample(3.0, 3.0).getHeightY() - raw.sample(2.0, 3.0).getHeightY();
        double ramp = smooth.sample(3.0, 3.0).getHeightY() - smooth.sample(2.0, 3.0).getHeightY();
        assertTrue(cliff > 0.9, "unsmoothed step should stay near 1 m: " + cliff);
        assertTrue(ramp < cliff, "smoothed step must be a ramp: " + ramp);
        assertTrue(smooth.sample(2.5, 3.0).getNormalZ() < 0.15f);
        assertTrue(smooth.sample(2.5, 3.0).getNormalX() < 0f, "ramp normal should tilt toward the low side");
    }
}
