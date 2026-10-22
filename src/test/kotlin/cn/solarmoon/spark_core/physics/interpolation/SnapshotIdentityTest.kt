package cn.solarmoon.spark_core.physics.interpolation

import cn.solarmoon.spark_core.physics.body.BodyFrame
import cn.solarmoon.spark_core.physics.level.PhysicsFrameSnapshot
import cn.solarmoon.spark_core.physics.track.TrackRunLinkFrame
import cn.solarmoon.spark_core.physics.track.TrackRunSnapshot
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Snapshots are immutable values with stable identity. Interpolation must refuse
 * to blend values that describe different runs or different bodies.
 */
class SnapshotIdentityTest {
    private fun frame(x: Double) = TrackRunLinkFrame.of(
        position = Vec3(x, 64.0, 0.0),
        rotation = Quaternionf(),
        linearVelocity = Vec3.ZERO,
        angularVelocity = Vec3.ZERO,
        active = true,
        colliding = false,
    )

    @Test
    fun snapshotsCarryTheProducingRunIdentity() {
        val snapshot = TrackRunSnapshot("run", 10L, 1L, listOf(frame(0.0)), runId = 7L)
        assertEquals(7L, snapshot.runId)
        assertTrue(snapshot.sameTopologyAs(TrackRunSnapshot("run", 11L, 2L, listOf(frame(1.0)), runId = 7L)))
    }

    @Test
    fun aDifferentLinkIdSetIsNotTheSameTopology() {
        val first = TrackRunSnapshot("run", 10L, 1L, listOf(frame(0.0)), runId = 7L, linkBodyIds = listOf(1L))
        val reused = TrackRunSnapshot("run", 11L, 2L, listOf(frame(1.0)), runId = 7L, linkBodyIds = listOf(2L))
        assertFalse(first.sameTopologyAs(reused), "same runId and link count must not hide replaced links")
    }

    @Test
    fun aReusedNameFromAnotherRunIsNotTheSameTopology() {
        val first = TrackRunSnapshot("run", 10L, 1L, listOf(frame(0.0)), runId = 1L)
        val second = TrackRunSnapshot("run", 11L, 2L, listOf(frame(2.0)), runId = 2L)
        assertFalse(first.sameTopologyAs(second))
        val blended = PhysicsStateInterpolator.trackRun(first, second, .5f)
        assertEquals(second, blended, "a different run must be shown as-is")
    }

    @Test
    fun interpolationKeepsTheTargetRunIdentity() {
        val from = TrackRunSnapshot("run", 10L, 1L, listOf(frame(0.0)), runId = 4L)
        val to = TrackRunSnapshot("run", 11L, 2L, listOf(frame(2.0)), runId = 4L)
        val blended = PhysicsStateInterpolator.trackRun(from, to, .5f)
        assertEquals(4L, blended.runId)
        assertEquals(1.0, blended.links[0].position.x, 1.0e-6)
    }

    @Test
    fun trackRunInterpolationKeepsNativeJointObservations() {
        val joint = cn.solarmoon.spark_core.physics.track.TrackRunJointFrame(
            previousIndex = 0,
            nextIndex = 1,
            angleX = .1f,
            angleY = .2f,
            angleZ = .3f,
            positionError = .01f,
            angleError = .02f,
        )
        val from = TrackRunSnapshot(
            "run", 10L, 1L, listOf(frame(0.0), frame(1.0)),
            runId = 4L, joints = listOf(joint), poseEpoch = 2L, linkBodyIds = listOf(10L, 11L),
        )
        val to = TrackRunSnapshot(
            "run", 11L, 2L, listOf(frame(2.0), frame(3.0)),
            runId = 4L, joints = listOf(joint), poseEpoch = 2L, linkBodyIds = listOf(10L, 11L),
        )
        val blended = PhysicsStateInterpolator.trackRun(from, to, .5f)
        assertEquals(listOf(joint), blended.joints, "joint samples must not disappear during interpolation")
        assertEquals(2L, blended.poseEpoch)
        assertEquals(listOf(10L, 11L), blended.linkBodyIds, "stable link ids must survive interpolation")
    }

    @Test
    fun aPoseEpochChangeIsNotInterpolated() {
        val from = TrackRunSnapshot("run", 10L, 1L, listOf(frame(0.0)), runId = 4L, poseEpoch = 1L)
        val to = TrackRunSnapshot("run", 11L, 2L, listOf(frame(2.0)), runId = 4L, poseEpoch = 2L)
        val result = PhysicsStateInterpolator.trackRun(from, to, .5f)
        assertEquals(to, result, "a teleport must not be blended across")
    }

    @Test
    fun aFrameSnapshotCopiesItsSourceCollections() {
        val source = HashMap<Long, BodyFrame>()
        source[1L] = bodyFrame(1L, 0.0)
        val boxes = arrayListOf(AABB(-.5, -.5, -.5, .5, .5, .5))
        val snapshot = PhysicsFrameSnapshot(1L, emptyMap(), boxes, 0L, 1L, source)
        source[2L] = bodyFrame(2L, 0.0)
        boxes.clear()
        assertEquals(setOf(1L), snapshot.bodiesById.keys)
        assertEquals(1, snapshot.terrainActivationBoxes.size)
    }

    @Test
    fun aFrameEpochChangeIsNotInterpolated() {
        val from = PhysicsFrameSnapshot(1L, emptyMap(), emptyList(), 0L, 1L, mapOf(1L to bodyFrame(1L, 0.0)), 1L)
        val to = PhysicsFrameSnapshot(2L, emptyMap(), emptyList(), 0L, 2L, mapOf(1L to bodyFrame(1L, 4.0)), 2L)
        assertEquals(to, PhysicsStateInterpolator.physicsFrame(from, to, .5f))
    }

    private fun bodyFrame(id: Long, x: Double, rotation: Quaternionf = Quaternionf()): BodyFrame = BodyFrame(
        bodyId = id,
        position = Vec3(x, 64.0, 0.0),
        rotation = rotation,
        boundingBox = AABB(x - .5, 63.5, -.5, x + .5, 64.5, .5),
        active = true,
        colliding = false,
        contactResponse = true,
        activationState = 2,
        linearVelocity = Vec3(1.0, 0.0, 0.0),
        angularVelocity = Vec3.ZERO,
    )

    @Test
    fun aBodyFrameReadNeverAliasesThePublishedRotation() {
        val frame = bodyFrame(1L, 0.0, Quaternionf().rotateY(.5f))
        val first = frame.rotation
        first.set(0f, 0f, 0f, 1f)
        val second = frame.rotation
        assertNotSame(first, second)
        val angle = (2.0 * kotlin.math.asin(second.y.toDouble())).toFloat()
        assertEquals(.5f, angle, 1.0e-3f)
    }

    @Test
    fun bodyFramesOfDifferentBodiesAreNeverBlended() {
        val from = bodyFrame(1L, 0.0)
        val to = bodyFrame(2L, 4.0)
        assertEquals(to, PhysicsStateInterpolator.body(from, to, .5f))
    }

    @Test
    fun bodyInterpolationBlendsContinuousFieldsAndPicksDiscreteOnes() {
        val from = bodyFrame(1L, 0.0).copy(active = true, colliding = false)
        val to = bodyFrame(1L, 4.0).copy(active = false, colliding = true)
        val early = PhysicsStateInterpolator.body(from, to, .25f)
        val late = PhysicsStateInterpolator.body(from, to, .75f)
        assertEquals(1.0, early.position.x, 1.0e-6)
        assertEquals(3.0, late.position.x, 1.0e-6)
        assertTrue(early.active)
        assertFalse(late.active)
        assertFalse(early.colliding)
        assertTrue(late.colliding)
        assertEquals(.5, early.boundingBox.minX, 1.0e-6)
        assertEquals(2.5, late.boundingBox.minX, 1.0e-6)
    }

    @Test
    fun aPhysicsFrameWithADifferentBodySetIsReturnedUnchanged() {
        val from = PhysicsFrameSnapshot(1L, emptyMap(), emptyList(), 0L, 1L, mapOf(1L to bodyFrame(1L, 0.0)))
        val to = PhysicsFrameSnapshot(
            2L,
            emptyMap(),
            emptyList(),
            0L,
            2L,
            mapOf(1L to bodyFrame(1L, 2.0), 2L to bodyFrame(2L, 2.0)),
        )
        assertEquals(to, PhysicsStateInterpolator.physicsFrame(from, to, .5f))
    }

    @Test
    fun aMatchingPhysicsFrameIsBlendedById() {
        val from = PhysicsFrameSnapshot(1L, emptyMap(), emptyList(), 0L, 1L, mapOf(1L to bodyFrame(1L, 0.0)))
        val to = PhysicsFrameSnapshot(2L, emptyMap(), emptyList(), 0L, 2L, mapOf(1L to bodyFrame(1L, 4.0)))
        val blended = PhysicsStateInterpolator.physicsFrame(from, to, .5f)
        assertEquals(2.0, blended.frameOf(1L)!!.position.x, 1.0e-6)
    }
}
