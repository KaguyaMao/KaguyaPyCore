package cn.solarmoon.spark_core.physics.terrain

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB

data class SectionSnapshot(
    val sectionPos: SectionPos,
    val revision: Long,
    val blocks: List<BlockShapeSnapshot>,
    /**
     * Topmost full-cube local Y of the one-block horizontal margin, indexed
     * `[(x + 1) * 18 + (z + 1)]` for margin x/z in -1..16. Slope planning
     * reads it across section borders; [MARGIN_UNKNOWN] means the neighbour
     * chunk was not loaded, so no ramp is ever generated from unread terrain.
     */
    val marginTops: IntArray = IntArray(0),
) {
    /** Exact detached geometry equality, not block-state or revision equality. */
    fun hasSameBlockCollision(other: SectionSnapshot): Boolean =
        sectionPos == other.sectionPos && blocks == other.blocks &&
            marginTops.contentEquals(other.marginTops)

    /** Topmost full-cube local Y of a margin column (section-local x/z in -1..16). */
    fun marginTop(x: Int, z: Int): Int =
        if (marginTops.size == MARGIN_CELLS) marginTops[(x + 1) * 18 + (z + 1)] else MARGIN_UNKNOWN

    data class BlockShapeSnapshot(
        val x: Int,
        val y: Int,
        val z: Int,
        val boxes: List<AABB>,
        val fullCube: Boolean
    )

    companion object {
        /** 18-by-18 column grid: the section plus a one-block margin. */
        const val MARGIN_CELLS = 18 * 18
        const val MARGIN_UNKNOWN = -1

        /** A full cube on the section's top layer may continue into the
         * section above, so it must never read as a one-block step. */
        const val MARGIN_TOO_HIGH = 16

        private val FULL_CUBE = AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

        fun snapshotFromChunk(level: Level, chunk: LevelChunk, pos: SectionPos, revision: Long): SectionSnapshot {
            val sectionIndex = level.getSectionIndexFromSectionY(pos.y())
            if (sectionIndex !in chunk.sections.indices) {
                return SectionSnapshot(pos, revision, emptyList())
            }
            val section = chunk.sections[sectionIndex]
            if (section.hasOnlyAir()) {
                // No ground in this section can anchor a ramp; the margin is
                // irrelevant and stays unknown.
                return SectionSnapshot(pos, revision, emptyList())
            }
            val blocks = ArrayList<BlockShapeSnapshot>()
            for (y in 0 until 16) for (z in 0 until 16) for (x in 0 until 16) {
                val state = section.getBlockState(x, y, z)
                if (state.isAir) continue
                // Use the real level and absolute position: contextual collision
                // shapes depend on neighbouring blocks and the world position.
                val worldPos = pos.origin().offset(x, y, z)
                val voxel = state.getCollisionShape(level, worldPos)
                if (voxel.isEmpty) continue
                val boxes = voxel.toAabbs()
                val full = boxes.size == 1 && boxes[0] == FULL_CUBE
                blocks.add(BlockShapeSnapshot(x, y, z, java.util.List.copyOf(boxes), full))
            }
            return SectionSnapshot(pos, revision, java.util.List.copyOf(blocks), readMarginTops(level, pos))
        }

        private fun readMarginTops(level: Level, pos: SectionPos): IntArray {
            val margin = IntArray(MARGIN_CELLS) { MARGIN_UNKNOWN }
            val probe = BlockPos.MutableBlockPos()
            for (x in -1..16) for (z in -1..16) {
                if (x in 0..15 && z in 0..15) continue
                val worldX = pos.minBlockX() + x
                val worldZ = pos.minBlockZ() + z
                // Never load or guess a chunk: an unloaded neighbour keeps its
                // margin unknown and every ramp that would need it is skipped.
                val chunk = level.chunkSource.getChunkNow(worldX shr 4, worldZ shr 4) ?: continue
                var top = MARGIN_UNKNOWN
                for (y in 15 downTo 0) {
                    probe.set(worldX, pos.minBlockY() + y, worldZ)
                    val state = chunk.getBlockState(probe)
                    if (state.isAir) continue
                    val voxel = state.getCollisionShape(level, probe)
                    if (voxel.isEmpty) continue
                    val boxes = voxel.toAabbs()
                    if (boxes.size == 1 && boxes[0] == FULL_CUBE) {
                        top = if (y == 15) MARGIN_TOO_HIGH else y
                        break
                    }
                }
                margin[(x + 1) * 18 + (z + 1)] = top
            }
            return margin
        }
    }
}
