package cn.solarmoon.spark_core.physics.terrain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerrainSectionRangeTest {
    @Test
    fun exactSectionOriginIncludesThatSectionAndTheOpenSideDoesNot() {
        assertEquals(0..0, TerrainSectionRange.indices(0.0, 15.999))
        assertEquals(0..1, TerrainSectionRange.indices(0.0, 16.0))
        assertEquals(1..1, TerrainSectionRange.indices(16.0, 16.0))
        assertEquals(-1..-1, TerrainSectionRange.indices(-16.0, -16.0))
        assertEquals(-2..-1, TerrainSectionRange.indices(-16.001, -16.0))
        assertEquals(-1..0, TerrainSectionRange.indices(-0.1, 0.0))
        assertEquals(IntRange.EMPTY, TerrainSectionRange.indices(2.0, 1.0))
        assertEquals(IntRange.EMPTY, TerrainSectionRange.indices(Double.NaN, 1.0))
    }
}
