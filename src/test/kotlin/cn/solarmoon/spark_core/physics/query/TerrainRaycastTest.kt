package cn.solarmoon.spark_core.physics.query

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerrainRaycastTest {
    @Test
    fun queryRejectsNonFiniteCoordinates() {
        assertTrue(TerrainRayQuery(0f, 1f, 2f, 3f, 4f, 5f).isFinite())
        assertFalse(TerrainRayQuery(Float.NaN, 1f, 2f, 3f, 4f, 5f).isFinite())
    }

    @Test
    fun fractionsAreFiniteAndInsideTheSegment() {
        assertTrue(TerrainRayMath.validFraction(0f))
        assertTrue(TerrainRayMath.validFraction(1f))
        assertFalse(TerrainRayMath.validFraction(-0.01f))
        assertFalse(TerrainRayMath.validFraction(1.01f))
        assertFalse(TerrainRayMath.validFraction(Float.NaN))
    }

    @Test
    fun closestTerrainSelectionFiltersGroupsAndKeepsSmallestFraction() {
        val terrain = 1 shl 5
        var closest = Float.POSITIVE_INFINITY
        assertFalse(TerrainRayMath.shouldReplaceClosest(0.1f, 1 shl 2, terrain, closest))
        assertTrue(TerrainRayMath.shouldReplaceClosest(0.7f, terrain, terrain, closest))
        closest = 0.7f
        assertTrue(TerrainRayMath.shouldReplaceClosest(0.25f, terrain, terrain, closest))
        assertFalse(TerrainRayMath.shouldReplaceClosest(0.9f, terrain, terrain, 0.25f))
        assertFalse(TerrainRayMath.shouldReplaceClosest(Float.NaN, terrain, terrain, closest))
    }

    @Test
    fun hitPointUsesTheRayFraction() {
        assertEquals(12.5f, TerrainRayMath.interpolate(10f, 20f, 0.25f), 1.0e-6f)
        assertEquals(-2f, TerrainRayMath.interpolate(2f, -6f, 0.5f), 1.0e-6f)
    }

    @Test
    fun physicsThreadGuardRejectsAnyOtherThread() {
        PhysicsThreadGuard.assertCurrent(Thread.currentThread(), "test")
        assertThrows(IllegalStateException::class.java) {
            PhysicsThreadGuard.assertCurrent(Thread("not-current"), "test")
        }
        assertThrows(IllegalStateException::class.java) {
            PhysicsThreadGuard.assertCurrent(null, "test")
        }
    }
}
