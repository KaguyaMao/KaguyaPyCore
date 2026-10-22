package cn.solarmoon.spark_core.physics.track

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import com.jme3.bullet.PhysicsSpace
import com.jme3.math.Vector3f
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The published joint frames must come from the native constraint, not from a
 * guess. These tests read a real hinge in a bare Bullet space.
 */
class TrackRunJointStateTest {
    private fun pair(
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
            position = Vec3(0.0, 70.0, 0.0),
            rotation = nextRotation,
        )
        // Coincident pins so an authored relative rotation is still legal.
        val joint = TrackRunJointSpec(
            previousIndex = 0,
            nextIndex = 1,
            pivotInPrevious = Vec3.ZERO,
            pivotInNext = Vec3.ZERO,
            axis = 2,
            halfAngleRad = 1.2f,
        )
        return TrackRunSpec("joint_state", listOf(previous, next), listOf(joint))
    }

    @Test
    fun theSnapshotReportsOneNativeFramePerHinge() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT).apply { setGravity(Vector3f()) }
        try {
            val spec = pair()
            val bodies = TrackRunAssembly.createBodies(spec, null)
            bodies.forEach(space::addCollisionObject)
            val joints = TrackRunAssembly.createJoints(spec, bodies)
            joints.forEach(space::addJoint)
            repeat(10) { space.update(1f / 60f, 0) }

            val snapshot = captureTrackRunSnapshot(spec, runId = 3L, physicsFrame = 10L, revision = 1L, links = bodies, joints = joints)
            assertEquals(1, snapshot.joints.size)
            val frame = snapshot.joints.single()
            assertEquals(0, frame.previousIndex)
            assertEquals(1, frame.nextIndex)
            assertTrue(frame.finite(), "joint frame is not finite: $frame")
            assertEquals(3L, snapshot.runId)
            assertEquals(snapshot.jointPositionError, frame.positionError, 0f)
            assertEquals(snapshot.jointAngleError, frame.angleError, 0f)
        } finally {
            space.destroy()
        }
    }

    @Test
    fun theNativeAngleFollowsTheAuthoredRelativeRotation() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT).apply { setGravity(Vector3f()) }
        try {
            val spec = pair(org.joml.Quaternionf().rotationAxis(.3f, 0f, 0f, 1f))
            val bodies = TrackRunAssembly.createBodies(spec, null)
            bodies.forEach(space::addCollisionObject)
            val joints = TrackRunAssembly.createJoints(spec, bodies)
            joints.forEach(space::addJoint)
            space.update(1f / 60f, 0)

            val frame = captureTrackRunSnapshot(spec, 1L, 1L, 1L, bodies, joints).joints.single()
            // The hinge axis is Z, so the reported Z coordinate carries the
            // authored 0.3 rad offset; the locked axes stay near zero.
            // getAngles reports the relative rotation from A to B, so the
            // authored +0.3 rad on B shows up as -0.3 here.
            assertEquals(.3f, kotlin.math.abs(frame.angleZ), .05f,
                "native angle did not follow the pose: $frame")
            assertEquals(0f, frame.angleX, .05f)
            assertEquals(0f, frame.angleY, .05f)
            assertTrue(frame.angleError < .05f, "a legal pose must not report a violation: $frame")
        } finally {
            space.destroy()
        }
    }

    @Test
    fun anUnsampledRunReportsNoJointFrames() {
        // One link with no hinge: the snapshot must be empty rather than
        // inventing a joint frame.
        val spec = TrackRunSpec(
            name = "single",
            links = listOf(TrackRunLinkSpec(1f, TrackRunLinkShape.Box(Vec3(.1, .1, .1)), Vec3.ZERO)),
            joints = emptyList(),
        )
        val snapshot = captureTrackRunSnapshot(spec, 1L, 1L, 1L, emptyList(), emptyList())
        assertTrue(snapshot.joints.isEmpty())
        assertTrue(snapshot.jointPositionError.isNaN())
        assertTrue(snapshot.jointAngleError.isNaN())
    }

    @Test
    fun aMismatchedJointListIsNotReportedAsZeroError() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT).apply { setGravity(Vector3f()) }
        try {
            val spec = pair()
            val bodies = TrackRunAssembly.createBodies(spec, null)
            bodies.forEach(space::addCollisionObject)
            val snapshot = captureTrackRunSnapshot(spec, 1L, 1L, 1L, bodies, emptyList())
            assertTrue(snapshot.joints.isEmpty(), "missing joints must not produce frames")
            assertTrue(snapshot.jointPositionError.isNaN(), "missing joints must not report zero error")
        } finally {
            space.destroy()
        }
    }
}
