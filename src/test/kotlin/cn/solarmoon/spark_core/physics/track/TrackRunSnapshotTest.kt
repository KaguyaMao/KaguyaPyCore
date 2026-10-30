package cn.solarmoon.spark_core.physics.track

import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Snapshots are the cross-thread boundary: a consumer must not be able to
 * mutate a published value, and the list must not alias the producer's buffer.
 */
class TrackRunSnapshotTest {
    private fun frame(x: Double, spin: Float = 0f) = TrackRunLinkFrame.of(
        position = Vec3(x, 64.0, 0.0),
        rotation = Quaternionf().rotateY(spin),
        linearVelocity = Vec3(1.0, 0.0, 0.0),
        angularVelocity = Vec3(0.0, 2.0, 0.0),
        active = true,
        colliding = false,
    )

    @Test
    fun theLinkListIsCopiedOnConstruction() {
        val source = mutableListOf(frame(0.0), frame(1.0))
        val snapshot = TrackRunSnapshot("run", 10L, 1L, source)
        source.clear()
        assertEquals(2, snapshot.linkCount)
    }

    @Test
    fun aRotationReadIsAlwaysAFreshCopy() {
        val snapshot = TrackRunSnapshot("run", 10L, 1L, listOf(frame(0.0, .4f)))
        val first = snapshot.links[0].rotation
        val before = Quaternionf(first)
        first.set(0f, 0f, 0f, 1f)
        val second = snapshot.links[0].rotation
        assertNotSame(first, second)
        assertEquals(before.x, second.x, 0f)
        assertEquals(before.w, second.w, 0f)
    }

    @Test
    fun equalityUsesPlainValues() {
        val a = TrackRunSnapshot("run", 10L, 1L, listOf(frame(0.0)))
        val b = TrackRunSnapshot("run", 10L, 1L, listOf(frame(0.0)))
        val c = TrackRunSnapshot("run", 11L, 2L, listOf(frame(0.0)))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }

    @Test
    fun anEmptySnapshotIsFiniteAndHasNoLinks() {
        assertEquals(0, TrackRunSnapshot.EMPTY.linkCount)
        assertTrue(TrackRunSnapshot.EMPTY.finite())
    }

    @Test
    fun aNonFiniteLinkIsReported() {
        val broken = TrackRunLinkFrame.of(
            position = Vec3(Double.NaN, 0.0, 0.0),
            rotation = Quaternionf(),
            linearVelocity = Vec3.ZERO,
            angularVelocity = Vec3.ZERO,
            active = true,
            colliding = false,
        )
        assertTrue(!TrackRunSnapshot("run", 1L, 1L, listOf(broken)).finite())
    }

    @Test
    fun linkBodyIdsAreCopiedAndPartOfIdentity() {
        val source = mutableListOf(4L, 5L)
        val snapshot = TrackRunSnapshot("run", 10L, 1L, listOf(frame(0.0), frame(1.0)), linkBodyIds = source)
        source[0] = 99L
        assertEquals(listOf(4L, 5L), snapshot.linkBodyIds)
        val other = TrackRunSnapshot("run", 10L, 1L, listOf(frame(0.0), frame(1.0)), linkBodyIds = listOf(4L, 6L))
        assertTrue(!snapshot.sameTopologyAs(other))
    }
}
