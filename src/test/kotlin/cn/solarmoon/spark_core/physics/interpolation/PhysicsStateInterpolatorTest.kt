package cn.solarmoon.spark_core.physics.interpolation

import cn.solarmoon.spark_core.physics.track.TrackRunLinkFrame
import cn.solarmoon.spark_core.physics.track.TrackRunSnapshot
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PhysicsStateInterpolatorTest {
    private fun link(x: Double, spin: Float = 0f, active: Boolean = true) = TrackRunLinkFrame.of(
        position = Vec3(x, 64.0, 0.0),
        rotation = Quaternionf().rotateY(spin),
        linearVelocity = Vec3(1.0, 0.0, 0.0),
        angularVelocity = Vec3(0.0, 2.0, 0.0),
        active = active,
        colliding = true,
    )

    @Test
    fun positionAndVelocityAreLinear() {
        val mid = PhysicsStateInterpolator.vector(Vec3(0.0, 0.0, 0.0), Vec3(2.0, 4.0, 6.0), .5f)
        assertEquals(1.0, mid.x, 1.0e-6)
        assertEquals(2.0, mid.y, 1.0e-6)
        assertEquals(3.0, mid.z, 1.0e-6)
    }

    @Test
    fun ratioIsClampedAndDegeneratePairsShowTheNewestState() {
        assertEquals(0f, PhysicsStateInterpolator.ratio(100L, 200L, 50L))
        assertEquals(1f, PhysicsStateInterpolator.ratio(100L, 200L, 300L))
        assertEquals(.5f, PhysicsStateInterpolator.ratio(100L, 200L, 150L))
        assertEquals(1f, PhysicsStateInterpolator.ratio(200L, 100L, 150L))
        assertEquals(1f, PhysicsStateInterpolator.ratio(100L, 100L, 100L))
    }

    @Test
    fun rotationTakesTheShortestArcAcrossTheSeam() {
        val from = Quaternionf().rotateY(Math.toRadians(350.0).toFloat())
        val to = Quaternionf().rotateY(Math.toRadians(10.0).toFloat())
        val mid = PhysicsStateInterpolator.rotation(from, to, .5f)
        // Quaternions double-cover rotations, so the shortest angle uses |w|.
        fun degrees(value: Quaternionf): Float =
            Math.toDegrees(2.0 * Math.acos(abs(value.w.coerceIn(-1f, 1f).toDouble()))).toFloat()
        // 350 -> 10 is +20 degrees, so the midpoint is 0/360, i.e. identity.
        assertEquals(0f, degrees(mid), 1.5f)
        // A quarter of the way is 355 degrees, not 95: proves the short arc.
        val quarter = PhysicsStateInterpolator.rotation(from, to, .25f)
        assertEquals(5f, degrees(quarter), 1.5f)
        assertEquals(1f, mid.lengthSquared(), 1.0e-4f)
    }

    @Test
    fun phaseWrapsForwardWithoutSpinningBackwards() {
        // 9 -> 1 is +2 on a 10-cycle, so the midpoint lands exactly on 0 (== 10).
        assertEquals(0f, PhysicsStateInterpolator.phase(9f, 1f, .5f, 10f), .01f)
        assertEquals(5f, PhysicsStateInterpolator.phase(4f, 6f, .5f, 10f), .01f)
        assertEquals(9.5f, PhysicsStateInterpolator.phase(9f, 10f, .5f, 10f), .01f)
        // 1 -> 0 is -1 on the short arc, so the midpoint is 0.5, not 5.5.
        assertEquals(0.5f, PhysicsStateInterpolator.phase(1f, 0f, .5f, 10f), .01f)
    }

    @Test
    fun phaseDistanceNeverExceedsHalfACycle() {
        assertEquals(2f, PhysicsStateInterpolator.phaseDistance(9f, 1f, 10f), .01f)
        assertEquals(0f, PhysicsStateInterpolator.phaseDistance(3f, 3f, 10f), .01f)
    }

    @Test
    fun trackRunInterpolatesEveryLinkAndRejectsTopologyChanges() {
        val from = TrackRunSnapshot("run", 10L, 1L, listOf(link(0.0), link(1.0)))
        val to = TrackRunSnapshot("run", 11L, 2L, listOf(link(2.0), link(3.0), link(4.0)))
        val unchanged = PhysicsStateInterpolator.trackRun(from, to, .5f)
        assertEquals(3, unchanged.links.size)
        assertEquals(4.0, unchanged.links[2].position.x, 1.0e-6)

        val sameTopology = TrackRunSnapshot("run", 11L, 2L, listOf(link(2.0), link(3.0)))
        val blended = PhysicsStateInterpolator.trackRun(from, sameTopology, .5f)
        assertEquals(2, blended.links.size)
        assertEquals(1.0, blended.links[0].position.x, 1.0e-6)
        assertEquals(2.0, blended.links[1].position.x, 1.0e-6)
        assertTrue(blended.finite())
    }

    @Test
    fun interpolationNeverMutatesItsInputs() {
        val from = link(0.0, active = true)
        val to = link(2.0, active = false)
        val blended = PhysicsStateInterpolator.link(from, to, .5f)
        assertEquals(0.0, from.position.x, 1.0e-6)
        assertEquals(2.0, to.position.x, 1.0e-6)
        assertTrue(from.active)
        assertFalse(to.active)
        assertEquals(1.0, blended.position.x, 1.0e-6)
    }

    @Test
    fun aDegenerateQuaternionFallsBackToTheSourcePose() {
        val zero = Quaternionf(0f, 0f, 0f, 0f)
        val valid = Quaternionf().rotateY(1f)
        val result = PhysicsStateInterpolator.rotation(zero, valid, .5f)
        assertEquals(1f, result.lengthSquared(), 1.0e-4f)
        assertTrue(result.isFinite)
    }
}
