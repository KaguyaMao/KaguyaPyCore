package cn.solarmoon.spark_core.physics.terrain

import net.minecraft.core.SectionPos
import net.minecraft.world.phys.AABB

/**
 * Identity of one immutable terrain region.
 *
 * A tracked vehicle builds its own local collision mesh from block geometry, so
 * it must be able to prove that the geometry it built is still the geometry the
 * level currently has. The per-section revision numbers are exactly the values
 * `PhysicsChunkManager` already tracks for its own rebuilds; [hash] is a stable
 * mixture of them so a caller can store one number.
 */
class TerrainRevisionToken(
    /** Revisions of the snapshots in the region, keyed by section. */
    sectionRevisions: Map<SectionPos, Long>,
    val hash: Long,
) {
    val sectionRevisions: Map<SectionPos, Long> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(sectionRevisions))

    override fun equals(other: Any?): Boolean =
        other is TerrainRevisionToken && hash == other.hash && sectionRevisions == other.sectionRevisions

    override fun hashCode(): Int = 31 * sectionRevisions.hashCode() + hash.hashCode()

    override fun toString(): String = "TerrainRevisionToken(sections=${sectionRevisions.size}, hash=$hash)"

    companion object {
        private const val SEED = 1125899906842597L

        fun of(revisions: Map<SectionPos, Long>): TerrainRevisionToken {
            val ordered = LinkedHashMap<SectionPos, Long>(revisions.size)
            var hash = SEED
            // SectionPos is immutable, and a sorted walk keeps the hash stable
            // regardless of the iteration order the caller built the map with.
            for (key in revisions.keys.sortedWith(SECTION_ORDER)) {
                val revision = revisions.getValue(key)
                ordered[key] = revision
                hash = hash * 31 + key.asLong()
                hash = hash * 31 + revision
            }
            return TerrainRevisionToken(ordered, hash)
        }

        private val SECTION_ORDER: Comparator<SectionPos> =
            compareBy<SectionPos>({ it.x() }, { it.y() }, { it.z() })
    }
}

/**
 * A detached, immutable slice of the level's cached block collision geometry.
 *
 * This is the only terrain input a consumer outside the physics thread and the
 * terrain cache should ever read. It holds plain values and [SectionSnapshot]s:
 * no `Level`, no `LevelChunk`, no `BlockState`, no Bullet object. A consumer can
 * therefore walk it on any thread, including a builder executor, without
 * touching mutable Minecraft state.
 *
 * It is a *snapshot*, not a live view: once handed out it never changes, so a
 * plan built from it either matches [revision] or is discarded.
 */
class TerrainRegionSnapshot(
    /** The exact box that was requested; every section that covers it is included. */
    val bounds: AABB,
    sections: List<SectionSnapshot>,
    /** Revision identity of the sections in this region. */
    val revision: TerrainRevisionToken,
) {
    val sections: List<SectionSnapshot> = java.util.Collections.unmodifiableList(
        sections.map { section ->
            section.copy(
                blocks = java.util.Collections.unmodifiableList(section.blocks.map { block ->
                    block.copy(boxes = java.util.List.copyOf(block.boxes))
                }),
            )
        }.sortedWith(SECTION_ORDER),
    )

    /** Total collision boxes in the region; useful for diagnostics and budgets. */
    val blockCount: Int get() = sections.sumOf { it.blocks.size }

    override fun toString(): String =
        "TerrainRegionSnapshot(bounds=$bounds, sections=${sections.size}, " +
            "blocks=$blockCount, hash=${revision.hash})"

    private companion object {
        val SECTION_ORDER: Comparator<SectionSnapshot> =
            compareBy<SectionSnapshot>({ it.sectionPos.x() }, { it.sectionPos.y() }, { it.sectionPos.z() })
    }
}

/**
 * Outcome of asking the terrain cache for a region.
 *
 * [Ready] means every covered section is cached, active, at its applied
 * revision and not dirty: the geometry is safe to build collision from.
 * [Missing] means the caller must wait; it must never fall back to reading the
 * level itself or to a partial region.
 */
sealed interface TerrainRegionSnapshotResult {
    data class Ready(val snapshot: TerrainRegionSnapshot) : TerrainRegionSnapshotResult
    data class Missing(val reason: String) : TerrainRegionSnapshotResult
}
