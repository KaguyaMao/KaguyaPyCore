package cn.solarmoon.spark_core.physics.track

import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The published `jointAngleError` must mean "outside the authored hinge
 * range", not "the pair rotated at all".
 */
class TrackRunHingeAngleErrorTest {
    private fun rotated(axisIndex: Int, radians: Float): Quaternion {
        val axis = when (axisIndex) {
            0 -> com.jme3.math.Vector3f(1f, 0f, 0f)
            1 -> com.jme3.math.Vector3f(0f, 1f, 0f)
            else -> com.jme3.math.Vector3f(0f, 0f, 1f)
        }
        return Quaternion().fromAngleNormalAxis(radians, axis)
    }

    @Test
    fun aRotationInsideTheLimitIsNotAnError() {
        val previous = rotated(2, .15f)
        val next = rotated(2, .3f)
        assertEquals(0f, hingeViolation(previous, next, 2, .5f), 1.0e-4f)
    }

    @Test
    fun aRotationBeyondTheLimitReportsTheExcess() {
        val previous = Quaternion()
        val next = rotated(2, .8f)
        assertEquals(.3f, hingeViolation(previous, next, 2, .5f), 1.0e-3f)
    }

    @Test
    fun rotationAboutALockedAxisCountsAsViolation() {
        val previous = Quaternion()
        val next = rotated(0, .4f)
        assertEquals(.4f, hingeViolation(previous, next, 2, .5f), 1.0e-3f)
    }

    @Test
    fun aMixedRotationSplitsTwistAndSwing() {
        val previous = Quaternion()
        // 0.6 rad about Z (the hinge axis) plus 0.2 rad about X (locked).
        val next = rotated(2, .6f).mult(rotated(0, .2f))
        val error = hingeViolation(previous, next, 2, .5f)
        assertTrue(error > .05f, "locked-axis rotation must be reported: $error")
        assertTrue(error < .6f, "error must not double count the whole rotation: $error")
    }

    @Test
    fun theNativeConstraintCoordinatesDriveThePublishedViolation() {
        // A free coordinate inside the limit and zero locked coordinates.
        assertEquals(0f, constraintAngleViolation(Vector3f(0f, 0f, .4f), 2, .5f), 0f)
        // The same coordinate outside the limit reports only the excess.
        assertEquals(.3f, constraintAngleViolation(Vector3f(0f, 0f, .8f), 2, .5f), 1.0e-6f)
        // A locked coordinate is an absolute violation, not part of the limit.
        assertEquals(.2f, constraintAngleViolation(Vector3f(.2f, 0f, .1f), 2, .5f), 1.0e-6f)
        // Non-finite native coordinates must not be reported as a clean hinge.
        assertTrue(constraintAngleViolation(Vector3f(Float.NaN, 0f, 0f), 2, .5f).isNaN())
    }

    @Test
    fun theGeometricCheckHandlesSignAndEquivalentQuaternions() {
        // -q is the same rotation as q and must not look like a full turn.
        val positive = rotated(2, .4f)
        val negated = Quaternion(-positive.x, -positive.y, -positive.z, -positive.w)
        assertEquals(
            hingeViolation(Quaternion(), positive, 2, .5f),
            hingeViolation(Quaternion(), negated, 2, .5f),
            1.0e-5f,
        )
        // Negative twist beyond the limit is still a violation of that size.
        val negative = rotated(2, -.8f)
        assertEquals(.3f, hingeViolation(Quaternion(), negative, 2, .5f), 1.0e-3f)
    }
}
