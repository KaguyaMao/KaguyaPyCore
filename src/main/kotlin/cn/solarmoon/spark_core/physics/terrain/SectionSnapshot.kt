package cn.solarmoon.spark_core.physics.terrain

import net.minecraft.core.SectionPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB

/**
 * Detached block collision geometry for one 16^3 section.
 *
 * It contains only plain values: no Level, Chunk, BlockState or Bullet object.
 * Builder threads consume it to produce [TerrainShapePlan].
 */
data class SectionSnapshot(
    val sectionPos: SectionPos,
    val revision: Long,
    val blocks: List<BlockShapeSnapshot>,
) {
    /** Exact detached geometry equality, not block-state or revision equality. */
    fun hasSameBlockCollision(other: SectionSnapshot): Boolean =
        sectionPos == other.sectionPos && blocks == other.blocks

    data class BlockShapeSnapshot(
        val x: Int,
        val y: Int,
        val z: Int,
        val boxes: List<AABB>,
        val fullCube: Boolean,
    )

    companion object {
        private val FULL_CUBE = AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

        fun snapshotFromChunk(level: Level, chunk: LevelChunk, pos: SectionPos, revision: Long): SectionSnapshot {
            val sectionIndex = level.getSectionIndexFromSectionY(pos.y())
            if (sectionIndex !in chunk.sections.indices) {
                return SectionSnapshot(pos, revision, emptyList())
            }
            val section = chunk.sections[sectionIndex]
            if (section.hasOnlyAir()) {
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
            return SectionSnapshot(pos, revision, java.util.List.copyOf(blocks))
        }
    }
}
