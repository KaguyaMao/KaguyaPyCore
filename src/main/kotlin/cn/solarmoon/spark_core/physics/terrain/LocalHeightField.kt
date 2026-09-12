package cn.solarmoon.spark_core.physics.terrain

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class LocalHeightSample(
    val heightY: Double,
    val normalX: Float,
    val normalY: Float,
    val normalZ: Float,
    val friction: Float,
    val hard: Boolean,
)

/**
 * Machine-Max style local height field: a small column grid around a vehicle,
 * downward-only cliff smoothing, bilinear lookup. Does not replace chunk AABB
 * terrain bodies; it only supplies contact height/normal for wheels and belts.
 */
class LocalHeightField(
    val minX: Int,
    val minZ: Int,
    val size: Int,
    val heights: FloatArray,
    val friction: FloatArray,
    val hard: BooleanArray,
) {
    init {
        require(size >= 2) { "height field needs at least 2 columns" }
        require(heights.size == size * size)
        require(friction.size == size * size)
        require(hard.size == size * size)
    }

    fun sample(worldX: Double, worldZ: Double): LocalHeightSample {
        val localX = (worldX - minX).toFloat().coerceIn(0f, (size - 1).toFloat())
        val localZ = (worldZ - minZ).toFloat().coerceIn(0f, (size - 1).toFloat())
        val x0 = floor(localX.toDouble()).toInt().coerceIn(0, size - 2)
        val z0 = floor(localZ.toDouble()).toInt().coerceIn(0, size - 2)
        val tx = (localX - x0).coerceIn(0f, 1f)
        val tz = (localZ - z0).coerceIn(0f, 1f)
        val h00 = height(x0, z0)
        val h10 = height(x0 + 1, z0)
        val h01 = height(x0, z0 + 1)
        val h11 = height(x0 + 1, z0 + 1)
        val height = h00 * (1 - tx) * (1 - tz) + h10 * tx * (1 - tz) + h01 * (1 - tx) * tz + h11 * tx * tz
        val dhdx = ((h10 - h00) * (1 - tz) + (h11 - h01) * tz)
        val dhdz = ((h01 - h00) * (1 - tx) + (h11 - h10) * tx)
        val nx = -dhdx
        val nz = -dhdz
        val ny = 1f
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1.0e-6f)
        val i00 = index(x0, z0)
        return LocalHeightSample(
            heightY = height.toDouble(),
            normalX = nx / len,
            normalY = ny / len,
            normalZ = nz / len,
            friction = friction[i00],
            hard = hard[i00],
        )
    }

    private fun height(x: Int, z: Int): Float = heights[index(x, z)]
    private fun index(x: Int, z: Int): Int = z * size + x

    companion object {
        const val DEFAULT_RADIUS = 3
        const val DEFAULT_SMOOTH_ITERATIONS = 4
        const val DEFAULT_MAX_NEIGHBOR_RISE = 0.35f

        @JvmStatic
        @JvmOverloads
        fun fromColumns(
            minX: Int,
            minZ: Int,
            size: Int,
            rawHeights: FloatArray,
            friction: FloatArray,
            hard: BooleanArray,
            smoothIterations: Int = DEFAULT_SMOOTH_ITERATIONS,
            maxNeighborRise: Float = DEFAULT_MAX_NEIGHBOR_RISE,
        ): LocalHeightField {
            val smoothed = rawHeights.copyOf()
            repeat(smoothIterations.coerceAtLeast(0)) {
                val next = smoothed.copyOf()
                for (z in 0 until size) {
                    for (x in 0 until size) {
                        var neighborMin = Float.POSITIVE_INFINITY
                        if (x > 0) neighborMin = min(neighborMin, smoothed[z * size + x - 1])
                        if (x + 1 < size) neighborMin = min(neighborMin, smoothed[z * size + x + 1])
                        if (z > 0) neighborMin = min(neighborMin, smoothed[(z - 1) * size + x])
                        if (z + 1 < size) neighborMin = min(neighborMin, smoothed[(z + 1) * size + x])
                        if (neighborMin.isFinite()) {
                            val i = z * size + x
                            // Downward-only: never raise a column, only shave cliffs
                            // that stand more than [maxNeighborRise] above a neighbor.
                            if (smoothed[i] > neighborMin + maxNeighborRise) {
                                next[i] = neighborMin + maxNeighborRise
                            }
                        }
                    }
                }
                for (i in smoothed.indices) smoothed[i] = next[i]
            }
            return LocalHeightField(minX, minZ, size, smoothed, friction.copyOf(), hard.copyOf())
        }

        @JvmStatic
        @JvmOverloads
        fun capture(
            level: Level,
            center: Vec3,
            radius: Int = DEFAULT_RADIUS,
            scanAbove: Int = 4,
            scanBelow: Int = 16,
        ): LocalHeightField {
            val size = radius * 2 + 1
            val minX = floor(center.x).toInt() - radius
            val minZ = floor(center.z).toInt() - radius
            val heights = FloatArray(size * size)
            val friction = FloatArray(size * size)
            val hard = BooleanArray(size * size)
            val yHint = floor(center.y).toInt()
            val cursor = BlockPos.MutableBlockPos()
            for (z in 0 until size) {
                for (x in 0 until size) {
                    val i = z * size + x
                    val column = sampleColumn(
                        level, cursor, minX + x, minZ + z, yHint, scanAbove, scanBelow,
                    )
                    heights[i] = column.height
                    friction[i] = column.friction
                    hard[i] = column.hard
                }
            }
            return fromColumns(minX, minZ, size, heights, friction, hard)
        }

        private data class Column(val height: Float, val friction: Float, val hard: Boolean)

        private fun sampleColumn(
            level: Level,
            cursor: BlockPos.MutableBlockPos,
            x: Int,
            z: Int,
            yHint: Int,
            scanAbove: Int,
            scanBelow: Int,
        ): Column {
            val top = min(level.maxBuildHeight - 1, yHint + scanAbove)
            val bottom = max(level.minBuildHeight, yHint - scanBelow)
            for (y in top downTo bottom) {
                cursor.set(x, y, z)
                val state = level.getBlockState(cursor)
                val shape = state.getCollisionShape(level, cursor)
                if (shape.isEmpty) continue
                val height = y + shape.max(Direction.Axis.Y).toFloat()
                return Column(height, state.block.friction, isHard(state))
            }
            return Column(bottom.toFloat(), 0.6f, true)
        }

        private fun isHard(state: net.minecraft.world.level.block.state.BlockState): Boolean {
            if (state.`is`(BlockTags.DIRT) || state.`is`(BlockTags.SAND) || state.`is`(BlockTags.SNOW) ||
                state.`is`(BlockTags.WOOL) || state.`is`(BlockTags.LEAVES) || state.`is`(BlockTags.WART_BLOCKS)
            ) return false
            return state.block.defaultDestroyTime() >= 0.5f
        }
    }
}
