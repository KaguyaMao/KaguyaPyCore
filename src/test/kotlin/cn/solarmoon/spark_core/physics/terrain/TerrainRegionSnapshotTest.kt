package cn.solarmoon.spark_core.physics.terrain

import net.minecraft.core.SectionPos
import net.minecraft.world.phys.AABB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Contract of the detached terrain region handed to non-physics consumers.
 *
 * No `Level`, chunk or Bullet object is created: a region snapshot must be
 * usable, comparable and immutable with plain values only. The
 * `PhysicsChunkManager.snapshotRegion` readiness rules themselves need a live
 * cache and are covered by the tracked-vehicle GameTests instead.
 */
class TerrainRegionSnapshotTest {

    private fun section(x: Int, y: Int, z: Int, revision: Long): SectionSnapshot {
        val pos = SectionPos.of(x, y, z)
        val blocks = listOf(
            SectionSnapshot.BlockShapeSnapshot(
                0, 0, 0,
                listOf(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)),
                true,
            ),
        )
        return SectionSnapshot(pos, revision, blocks)
    }

    @Test
    fun theRevisionHashIgnoresMapIterationOrder() {
        val a = SectionPos.of(0, 0, 0)
        val b = SectionPos.of(4, 4, 4)
        val first = TerrainRevisionToken.of(linkedMapOf(a to 1L, b to 2L))
        val second = TerrainRevisionToken.of(linkedMapOf(b to 2L, a to 1L))
        assertEquals(first.hash, second.hash, "the token must not depend on insertion order")
        assertEquals(first.sectionRevisions.keys.toList(), second.sectionRevisions.keys.toList())
    }

    @Test
    fun anySectionRevisionChangeChangesTheHash() {
        val a = SectionPos.of(0, 0, 0)
        val b = SectionPos.of(0, 1, 0)
        val base = TerrainRevisionToken.of(linkedMapOf(a to 1L, b to 1L))
        val bumped = TerrainRevisionToken.of(linkedMapOf(a to 2L, b to 1L))
        assertNotEquals(base.hash, bumped.hash, "a rebuilt section must invalidate the token")
    }

    @Test
    fun theSnapshotCopiesAndSortsItsSections() {
        val sections = mutableListOf(
            section(2, 0, 0, 5),
            section(0, 0, 0, 5),
        )
        val region = TerrainRegionSnapshot(
            bounds = AABB(0.0, 0.0, 0.0, 48.0, 16.0, 16.0),
            sections = sections,
            revision = TerrainRevisionToken.of(mapOf(SectionPos.of(0, 0, 0) to 5L, SectionPos.of(2, 0, 0) to 5L)),
        )
        // Mutating the caller's list must not reach the snapshot.
        sections.clear()
        assertEquals(2, region.sections.size, "the snapshot must defend its own section list")
        assertEquals(
            listOf(0, 2),
            region.sections.map { it.sectionPos.x() },
            "sections must be walked in a stable order",
        )
        assertEquals(2, region.blockCount)
    }

    @Test
    fun theSnapshotCarriesTheRequestedBounds() {
        val bounds = AABB(-7.5, -4.0, 3.25, 19.5, 12.0, 30.25)
        val region = TerrainRegionSnapshot(bounds, emptyList(), TerrainRevisionToken.of(emptyMap()))
        assertEquals(bounds, region.bounds)
        assertTrue(region.sections.isEmpty(), "an empty but complete region is legal")
        assertEquals(0, region.blockCount)
    }

    @Test
    fun revisionTokenCopiesAndProtectsTheSectionMap() {
        val pos = SectionPos.of(1, 2, 3)
        val source = linkedMapOf(pos to 9L)
        val token = TerrainRevisionToken.of(source)
        source[pos] = 10L
        assertEquals(9L, token.sectionRevisions[pos])
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (token.sectionRevisions as MutableMap<SectionPos, Long>)[pos] = 11L
        }
    }

    @Test
    fun regionSnapshotDeepCopiesBlockLists() {
        val pos = SectionPos.of(0, 0, 0)
        val box = AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        val mutableBoxes = mutableListOf(box)
        val mutableBlocks = mutableListOf(SectionSnapshot.BlockShapeSnapshot(0, 0, 0, mutableBoxes, true))
        val snapshot = SectionSnapshot(pos, 3L, mutableBlocks)
        val region = TerrainRegionSnapshot(
            AABB(0.0, 0.0, 0.0, 16.0, 16.0, 16.0),
            listOf(snapshot),
            TerrainRevisionToken.of(mapOf(pos to 3L)),
        )
        mutableBoxes.clear()
        mutableBlocks.clear()
        assertEquals(1, region.sections.single().blocks.single().boxes.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (region.sections as MutableList<SectionSnapshot>).clear()
        }
    }

    @Test
    fun aMissingResultCarriesItsReason() {
        val result: TerrainRegionSnapshotResult = TerrainRegionSnapshotResult.Missing("dirty:SectionPos")
        assertTrue(result is TerrainRegionSnapshotResult.Missing)
        assertEquals("dirty:SectionPos", (result as TerrainRegionSnapshotResult.Missing).reason)
    }
}
