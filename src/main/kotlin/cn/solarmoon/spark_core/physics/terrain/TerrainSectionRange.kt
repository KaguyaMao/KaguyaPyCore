package cn.solarmoon.spark_core.physics.terrain

import kotlin.math.floor

/**
 * Section indices touched by a closed world interval.
 *
 * An endpoint that lands exactly on a section origin includes that section:
 * `16.0` includes section 1, while `15.999` stays in section 0. `-16.0` is
 * section -1. This is a readiness bound, not a collision shape.
 */
object TerrainSectionRange {
    fun indices(min: Double, max: Double): IntRange {
        if (!min.isFinite() || !max.isFinite() || max < min) return IntRange.EMPTY
        return (floor(min).toInt() shr 4)..(floor(max).toInt() shr 4)
    }
}
