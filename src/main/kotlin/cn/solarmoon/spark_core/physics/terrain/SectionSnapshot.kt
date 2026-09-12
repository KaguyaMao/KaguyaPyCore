package cn.solarmoon.spark_core.physics.terrain

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB

data class SectionSnapshot(
    val sectionPos: SectionPos,
    val revision: Long,
    val blocks: List<BlockShapeSnapshot>
) {
    data class BlockShapeSnapshot(
        val x: Int,
        val y: Int,
        val z: Int,
        val state: BlockState,
        val boxes: List<AABB>,
        val fullCube: Boolean
    )

    companion object {
        fun snapshotFromChunk(level: Level, chunk: LevelChunk, pos: SectionPos, revision: Long): SectionSnapshot {
            val sectionIndex = level.getSectionIndexFromSectionY(pos.y())
            if (sectionIndex !in chunk.sections.indices) return SectionSnapshot(pos, revision, emptyList())
            val section = chunk.sections[sectionIndex]
            if (section.hasOnlyAir()) return SectionSnapshot(pos, revision, emptyList())
            val blocks = ArrayList<BlockShapeSnapshot>()
            for (y in 0 until 16) for (z in 0 until 16) for (x in 0 until 16) {
                val state = section.getBlockState(x, y, z)
                if (state.isAir) continue
                // Use the real level and absolute position. NoCubes and other
                // terrain providers derive smooth collision geometry from
                // neighboring blocks; EmptyBlockGetter/BlockPos.ZERO loses
                // that context and turns a smooth surface back into a cube.
                val worldPos = pos.origin().offset(x, y, z)
                val voxel = state.getCollisionShape(level, worldPos)
                if (voxel.isEmpty) continue
                val boxes = voxel.toAabbs()
                val full = boxes.size == 1 && boxes[0] == AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
                blocks.add(BlockShapeSnapshot(x, y, z, state, boxes, full))
            }
            return SectionSnapshot(pos, revision, blocks)
        }
    }
}
