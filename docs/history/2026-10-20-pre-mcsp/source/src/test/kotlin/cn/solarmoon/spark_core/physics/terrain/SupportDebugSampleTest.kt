package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.terrain.support.ClearanceKnowledge
import cn.solarmoon.spark_core.physics.terrain.support.SupportColumn
import cn.solarmoon.spark_core.physics.terrain.support.SupportDebugSampler
import cn.solarmoon.spark_core.physics.terrain.support.SupportDebugStatus
import cn.solarmoon.spark_core.physics.terrain.support.SupportInvalidReason
import cn.solarmoon.spark_core.physics.terrain.support.SurfaceClass
import cn.solarmoon.spark_core.physics.terrain.support.TerrainSupportField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupportDebugSampleTest {
    @Test
    fun installedSoftCellIsASurfaceAndUnknownIsNotAFloor() {
        val columns = buildList {
            for (z in 0 until 16) for (x in 0 until 16) {
                val soft = x == 0 && z == 0
                add(column(x, z, if (soft) 1f else Float.NaN, soft, if (soft) SurfaceClass.SOLID_SOFT else SurfaceClass.UNKNOWN))
                if (x == 2 && z == 0) add(column(x, z, 20f, true, SurfaceClass.SOLID_HARD, layer = 1))
            }
        }
        val field = TerrainSupportField(0, 0, 0, 1f, 3, 16, 16, columns)
        val cells = SupportDebugSampler.sample(listOf(field), 0, 0, 0.1f, -1f, 4f, 2)

        val soft = cells.single { it.x == 0 && it.z == 0 }
        assertEquals(SupportDebugStatus.SOFT, soft.status)
        assertEquals(1f, soft.y00)
        assertTrue(cells.none { it.x == 1 && it.z == 0 && it.status == SupportDebugStatus.SOFT })
        assertEquals(SupportDebugStatus.INVALID, cells.single { it.x == 1 && it.z == 0 }.status)
        assertEquals(0.1f, cells.single { it.x == 1 && it.z == 0 }.y00)
        assertEquals(SupportDebugStatus.MISSING, cells.single { it.x == -1 && it.z == 0 }.status)
        assertTrue(cells.none { it.x == 2 && it.z == 0 })
    }

    private fun column(
        x: Int,
        z: Int,
        height: Float,
        valid: Boolean,
        surface: SurfaceClass,
        layer: Int = 0,
    ) = SupportColumn(
        localX = x,
        localZ = z,
        layerId = if (valid) layer else -1,
        valid = valid,
        rawHeight = height,
        supportHeight = height,
        corner00 = height,
        corner10 = height,
        corner01 = height,
        corner11 = height,
        surfaceClass = surface,
        friction = 0.6f,
        clearanceAbove = 1f,
        clearanceKnowledge = ClearanceKnowledge.OPEN_ENDED,
        hasStructuralNeighbor = false,
        hasUnresolvedPartial = false,
        invalidReason = if (valid) null else SupportInvalidReason.UNKNOWN,
    )
}
