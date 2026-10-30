package cn.solarmoon.spark_core.physics.track

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import com.jme3.bullet.PhysicsSpace
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

/**
 * A hinge whose two links start in different orientations must be authored with
 * per-end constraint frames. These tests exercise the production assembly path
 * so a runtime-only regression cannot pass.
 */
class TrackRunHingeFrameTest {
    /** 180 degrees about +X, written as plain scalars. */
    private fun halfTurnX(): TrackRunRotation = TrackRunRotation(1f, 0f, 0f, 0f)

    private fun jomlHalfTurnX(): org.joml.Quaternionf {
        val angle = PI.toFloat()
        return org.joml.Quaternionf().rotationAxis(angle, 1f, 0f, 0f)
    }

    private fun pairSpec(
        framePrevious: TrackRunRotation = TrackRunRotation.IDENTITY,
        frameNext: TrackRunRotation = TrackRunRotation.IDENTITY,
        nextRotation: org.joml.Quaternionf = org.joml.Quaternionf(),
    ): TrackRunSpec {
        val previous = TrackRunLinkSpec(
            massKg = 2f,
            shape = TrackRunLinkShape.Box(Vec3(.08, .08, .08)),
            position = Vec3(0.0, 70.0, 0.0),
        )
        val next = TrackRunLinkSpec(
            massKg = 2f,
            shape = TrackRunLinkShape.Box(Vec3(.08, .08, .08)),
            position = Vec3(.3, 70.0, 0.0),
            rotation = nextRotation,
        )
        val joint = TrackRunJointSpec(
            previousIndex = 0,
            nextIndex = 1,
            pivotInPrevious = Vec3(.15, 0.0, 0.0),
            pivotInNext = Vec3(-.15, 0.0, 0.0),
            axis = 2,
            halfAngleRad = 1.2f,
            frameRotationInPrevious = framePrevious,
            frameRotationInNext = frameNext,
        )
        return TrackRunSpec("frame_pair", listOf(previous, next), listOf(joint))
    }

    @Test
    fun identityFramesKeepTheHistoricalBehaviour() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT).apply { setGravity(Vector3f()) }
        try {
            val spec = pairSpec()
            val bodies = TrackRunAssembly.createBodies(spec, null)
            bodies.forEach(space::addCollisionObject)
            TrackRunAssembly.createJoints(spec, bodies).forEach(space::addJoint)
            repeat(120) { space.update(1f / 60f, 0) }
            assertEquals(.3f, bodies[0].getPhysicsLocation(null).distance(bodies[1].getPhysicsLocation(null)), .02f)
        } finally {
            space.destroy()
        }
    }

    @Test
    fun aMirroredFrameKeepsADifferentlyOrientedPairClosed() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT).apply { setGravity(Vector3f()) }
        try {
            // The next link is authored upside down relative to the previous one.
            val spec = pairSpec(
                framePrevious = TrackRunRotation.IDENTITY,
                frameNext = halfTurnX(),
                nextRotation = jomlHalfTurnX(),
            )
            val bodies = TrackRunAssembly.createBodies(spec, null)
            bodies.forEach(space::addCollisionObject)
            TrackRunAssembly.createJoints(spec, bodies).forEach(space::addJoint)
            repeat(240) { space.update(1f / 60f, 0) }
            val joint = spec.joints.single()
            val expected = bodies[0].getPhysicsLocation(null).add(
                bodies[0].getPhysicsRotation(null).toRotationMatrix()
                    .mult(joint.pivotInPrevious.toBulletVector(), null),
            )
            val actual = bodies[1].getPhysicsLocation(null).add(
                bodies[1].getPhysicsRotation(null).toRotationMatrix()
                    .mult(joint.pivotInNext.toBulletVector(), null),
            )
            assertTrue(expected.distance(actual) < .05f, "mirrored hinge opened: ${expected.distance(actual)}")
        } finally {
            space.destroy()
        }
    }

    @Test
    fun aFrameInsideTheLimitIsNotReportedAsAViolation() {
        val previous = Quaternion()
        val next = Quaternion().fromAngleNormalAxis(.2f, Vector3f(0f, 0f, 1f))
        assertEquals(
            0f,
            hingeViolation(previous, next, 2, .5f, TrackRunRotation.IDENTITY, TrackRunRotation.IDENTITY),
            1.0e-4f,
        )
    }

    @Test
    fun aMatchingNonIdentityFrameHidesTheAuthoredOffset() {
        // Both ends are rotated by the same frame, so the constraint sees no
        // relative rotation and must not report a violation.
        val frame = halfTurnX()
        val previous = Quaternion().fromAngleNormalAxis(PI.toFloat(), Vector3f(1f, 0f, 0f))
        val next = Quaternion().fromAngleNormalAxis(PI.toFloat(), Vector3f(1f, 0f, 0f))
        assertEquals(0f, hingeViolation(previous, next, 2, .5f, frame, frame), 1.0e-4f)
    }

    @Test
    fun aZeroFrameRotationIsRejectedBeforeAssembly() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            pairSpec(framePrevious = TrackRunRotation(0f, 0f, 0f, 0f))
        }
        assertTrue(error.message!!.contains("zero quaternion"), error.message!!)
    }

    @Test
    fun aNonFiniteFrameRotationIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            pairSpec(frameNext = TrackRunRotation(Float.NaN, 0f, 0f, 1f))
        }
        assertTrue(error.message!!.contains("not finite"), error.message!!)
    }

    @Test
    fun aDenormalizedFrameIsNormalizedBeforeItReachesBullet() {
        val matrix = TrackRunAssembly.frameMatrix(TrackRunRotation(0f, 0f, 0f, 3f))
        assertEquals(1f, matrix.determinant(), 1.0e-4f)
    }

    private fun Vec3.toBulletVector() = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
}
