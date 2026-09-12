package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.level.PhysicsLevel
import net.minecraft.core.SectionPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk

class PhysicsChunk(
    val chunkPos: ChunkPos,
    val physicsLevel: PhysicsLevel,
    val chunk: LevelChunk
) {
    internal val sections = mutableMapOf<Int, PhysicsChunkSection>()

    internal fun getOrCreateSection(sectionY: Int): PhysicsChunkSection? {
        if (sectionY !in physicsLevel.mcLevel.minSection until physicsLevel.mcLevel.maxSection) return null
        return sections.getOrPut(sectionY) {
            PhysicsChunkSection(SectionPos.of(chunkPos, sectionY), physicsLevel, chunk)
        }
    }

    internal fun initialize(): Collection<PhysicsChunkSection> {
        if (sections.isNotEmpty()) return sections.values
        for (sectionY in physicsLevel.mcLevel.minSection until physicsLevel.mcLevel.maxSection) {
            getOrCreateSection(sectionY)
        }
        return sections.values
    }
}
