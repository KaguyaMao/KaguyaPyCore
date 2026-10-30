package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.terrain.support.ClearanceKnowledge
import cn.solarmoon.spark_core.physics.terrain.support.ContactAuthority
import cn.solarmoon.spark_core.physics.terrain.support.SectionCoverage
import cn.solarmoon.spark_core.physics.terrain.support.SupportColumn
import cn.solarmoon.spark_core.physics.terrain.support.SupportFieldPolicy
import cn.solarmoon.spark_core.physics.terrain.support.SupportInvalidReason
import cn.solarmoon.spark_core.physics.terrain.support.SupportQueryMode
import cn.solarmoon.spark_core.physics.terrain.support.SurfaceClass
import cn.solarmoon.spark_core.physics.terrain.support.SurfaceClassMap
import cn.solarmoon.spark_core.physics.terrain.support.TerrainSupportField
import cn.solarmoon.spark_core.physics.terrain.support.TerrainSupportFieldBuilder
import net.minecraft.core.SectionPos
import net.minecraft.world.phys.AABB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class TerrainSupportFieldTest {
    private val full = listOf(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
    private val noRamp = SupportFieldPolicy(0f, null, 0)
    private val oneBlockLip = SupportFieldPolicy(
        maxDownwardCorrection = 0.25f,
        maxRiseForSupportTransition = 1f,
        smoothIterations = 1,
    )

    @Test
    fun cellCenterUsesWorldBlockCoordinatesNotSectionCenterOffsets() {
        val section = SectionPos.of(-1, 4, 0)
        assertEquals(-16, section.minBlockX())
        assertEquals(64, section.minBlockY())
        val field = TerrainSupportFieldBuilder.build(snapshot(section, columns = { x, z -> if (x == 0 && z == 0) 1 else 0 }), noRamp)
        val contact = field.query(-15.5, 0.5, 80.0, 20.0)
        assertTrue(contact.valid)
        assertEquals(65f, contact.rawHeight)
        assertEquals(65f, contact.supportHeight)
        assertEquals(field.revision, contact.terrainRevision)
        assertEquals(ContactAuthority.HEIGHT_FIELD, contact.authority)
        assertTrue(abs(contact.normalY - 1f) < 1.0e-4f)
    }

    @Test
    fun queryOutsideTheTileIsNoneAndDoesNotClampToTheEdge() {
        val field = TerrainSupportFieldBuilder.build(platform(1), noRamp)
        val outside = field.query(-0.01, 0.5, 10.0, 20.0)
        assertFalse(outside.valid)
        assertEquals(ContactAuthority.NONE, outside.authority)
        assertEquals(SupportInvalidReason.OUT_OF_TILE, outside.invalidReason)
        assertEquals(SurfaceClass.UNKNOWN, outside.surfaceClass)
        assertEquals(SupportQueryMode.DOWNWARD_SAMPLE, outside.queryMode)
        assertTrue(outside.supportHeight.isNaN())
        val far = field.query(1000.0, 1000.0, 10.0, 20.0)
        assertEquals(SupportInvalidReason.OUT_OF_TILE, far.invalidReason)
        assertTrue(far.supportHeight.isNaN())
    }

    @Test
    fun airHoleAndUnreadSectionDoNotInventALowFloor() {
        val hole = TerrainSupportFieldBuilder.build(platform(4, hole = 8 to 8), noRamp)
        val missing = hole.query(8.5, 8.5, 20.0, 30.0)
        assertFalse(missing.valid)
        assertEquals(SupportInvalidReason.AIR, missing.invalidReason)
        assertTrue(missing.supportHeight.isNaN())
        val beside = hole.column(7, 8, 0)!!
        assertEquals(4f, beside.supportHeight)
        val unread = TerrainSupportFieldBuilder.build(
            SectionSnapshot(SectionPos.of(0, 0, 0), 3, emptyList(), coverage = SectionCoverage.UNREAD),
            noRamp,
        )
        val unknown = unread.query(1.5, 1.5, 10.0, 40.0)
        assertEquals(SupportInvalidReason.UNKNOWN, unknown.invalidReason)
        assertEquals(SurfaceClass.UNKNOWN, unknown.surfaceClass)
        assertTrue(unknown.supportHeight.isNaN())
        assertFalse(platform(0).copy(coverage = SectionCoverage.AIR).hasSameBlockCollision(
            platform(0).copy(coverage = SectionCoverage.UNREAD),
        ))
    }

    @Test
    fun twoBlockFaceIsStructuralAndIsNotShavedIntoARamp() {
        val field = TerrainSupportFieldBuilder.build(columns { x, _ -> if (x >= 8) 3 else 1 }, oneBlockLip)
        val low = field.layersAt(7, 0).single { it.valid }
        val high = field.layersAt(8, 0).single { it.valid }
        assertTrue(low.layerId != high.layerId)
        assertEquals(1f, low.rawHeight)
        assertEquals(1f, low.supportHeight)
        assertEquals(3f, high.rawHeight)
        assertEquals(3f, high.supportHeight)
        assertTrue(high.hasStructuralNeighbor)
        val contact = field.query(8.5, 0.5, 6.0, 4.0)
        assertEquals(3f, contact.supportHeight)
        assertTrue(abs(contact.normalY - 1f) < 1.0e-3f)
    }

    @Test
    fun oneBlockStepIsNotImplicitlyARampUntilTheCallerSuppliesARiseLimit() {
        val closed = TerrainSupportFieldBuilder.build(columns { x, _ -> if (x >= 8) 2 else 1 }, noRamp)
        assertEquals(2f, closed.column(8, 0, 0)!!.supportHeight)
        val opened = TerrainSupportFieldBuilder.build(columns { x, _ -> if (x >= 8) 2 else 1 }, oneBlockLip)
        val high = opened.column(8, 0, 0)!!
        assertEquals(1.75f, high.supportHeight)
        assertTrue(high.rawHeight - high.supportHeight <= 0.25f)
        assertEquals(1f, opened.column(7, 0, 0)!!.supportHeight)
    }

    @Test
    fun smoothingDoesNotCrossHardSoftBoundaryOrRaiseTheLowColumn() {
        val blocks = buildList {
            for (z in 0 until 16) for (x in 0 until 16) {
                val soft = x < 8
                val layers = if (soft) 1 else 2
                for (y in 0 until layers) {
                    add(SectionSnapshot.BlockShapeSnapshot(
                        x, y, z, full, true,
                        if (soft) SurfaceClass.SOLID_SOFT else SurfaceClass.SOLID_HARD,
                        if (soft) 0.4f else 0.9f,
                    ))
                }
            }
        }
        val field = TerrainSupportFieldBuilder.build(
            SectionSnapshot(SectionPos.of(0, 0, 0), 5, blocks),
            SupportFieldPolicy(1f, 1f, 4),
        )
        val soft = field.layersAt(7, 0).single { it.valid }
        val hard = field.layersAt(8, 0).single { it.valid }
        assertTrue(soft.layerId != hard.layerId)
        assertEquals(1f, soft.supportHeight)
        assertEquals(2f, hard.supportHeight)
        assertEquals(0.4f, field.query(7.5, 0.5, 4.0, 4.0).friction)
        assertEquals(0.9f, field.query(8.5, 0.5, 4.0, 4.0).friction)
    }

    @Test
    fun bridgeAndFloorStayOnSeparateLayers() {
        val blocks = buildList {
            for (z in 0 until 16) for (x in 0 until 16) {
                add(ground(x, 0, z))
                add(ground(x, 10, z))
            }
        }
        val field = TerrainSupportFieldBuilder.build(SectionSnapshot(SectionPos.of(0, 0, 0), 8, blocks), noRamp)
        val floor = field.column(3, 3, 0)!!
        val bridge = field.column(3, 3, 1)!!
        assertEquals(1f, floor.rawHeight)
        assertEquals(11f, bridge.rawHeight)
        assertEquals(9f, floor.clearanceAbove)
        assertEquals(ClearanceKnowledge.BOUNDED, floor.clearanceKnowledge)
        assertEquals(ClearanceKnowledge.OPEN_ENDED, bridge.clearanceKnowledge)
        assertEquals(5f, bridge.clearanceAbove)
        assertFalse(bridge.clearanceAbove.isInfinite())
        assertEquals(1, field.query(3.5, 3.5, 12.0, 2.0).layerId)
        assertEquals(0, field.query(3.5, 3.5, 4.0, 4.0).layerId)
        val between = field.query(3.5, 3.5, 6.0, 2.0)
        assertFalse(between.valid)
        assertEquals(SupportInvalidReason.NO_SURFACE_IN_RANGE, between.invalidReason)
    }

    @Test
    fun slabTopIsTheBoxMaxNotAFullBlock() {
        val blocks = listOf(SectionSnapshot.BlockShapeSnapshot(
            1, 2, 3, listOf(AABB(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)), false, SurfaceClass.SOLID_HARD, 0.6f,
        ))
        val field = TerrainSupportFieldBuilder.build(SectionSnapshot(SectionPos.of(0, 0, 0), 2, blocks), noRamp)
        assertEquals(2.5f, field.column(1, 3, 0)!!.rawHeight)
    }

    @Test
    fun nonGroundCollisionDoesNotBecomeSupport() {
        val blocks = listOf(SectionSnapshot.BlockShapeSnapshot(
            2, 0, 2, full, true, SurfaceClass.NON_GROUND, 0.1f,
        ))
        val field = TerrainSupportFieldBuilder.build(SectionSnapshot(SectionPos.of(0, 0, 0), 1, blocks), noRamp)
        val contact = field.query(2.5, 2.5, 4.0, 4.0)
        assertFalse(contact.valid)
        assertEquals(SupportInvalidReason.NON_GROUND, contact.invalidReason)
        assertTrue(contact.supportHeight.isNaN())
    }

    @Test
    fun revisionComesFromTheSnapshotAndIsNotCached() {
        val first = TerrainSupportFieldBuilder.build(platform(1).copy(revision = 4), noRamp)
        val second = TerrainSupportFieldBuilder.build(platform(1).copy(revision = 9), noRamp)
        assertEquals(4L, first.query(0.5, 0.5, 3.0, 3.0).terrainRevision)
        assertEquals(9L, second.query(0.5, 0.5, 3.0, 3.0).terrainRevision)
    }

    @Test
    fun partialCollisionDoesNotExpandIntoFullCellSupport() {
        val fence = SectionSnapshot.BlockShapeSnapshot(4, 0, 4, listOf(
            AABB(0.0, 0.0, 0.375, 0.125, 1.0, 0.625),
            AABB(0.875, 0.0, 0.375, 1.0, 1.0, 0.625),
        ), false, SurfaceClass.SOLID_HARD, 0.6f)
        val floor = ground(3, 0, 4)
        val field = TerrainSupportFieldBuilder.build(SectionSnapshot(SectionPos.of(0, 0, 0), 1, listOf(fence, floor)), oneBlockLip)
        val missing = field.query(4.5, 4.5, 4.0, 4.0)
        assertFalse(missing.valid)
        assertEquals(SupportInvalidReason.PARTIAL_SHAPE, missing.invalidReason)
        assertTrue(missing.supportHeight.isNaN())
        assertEquals(ContactAuthority.NONE, missing.authority)
        assertEquals(1f, field.column(3, 4, 0)!!.supportHeight)
        val beside = field.query(3.99, 4.5, 4.0, 4.0)
        assertEquals(1f, beside.supportHeight)
        assertTrue(abs(beside.normalY - 1f) < 1.0e-3f)
    }

    @Test
    fun separatedBoxesInOneBlockStaySeparateIntervals() {
        val split = SectionSnapshot.BlockShapeSnapshot(2, 0, 2, listOf(
            AABB(0.0, 0.0, 0.0, 1.0, 0.2, 1.0),
            AABB(0.0, 0.8, 0.0, 1.0, 1.0, 1.0),
        ), false, SurfaceClass.SOLID_HARD, 0.6f)
        val field = TerrainSupportFieldBuilder.build(SectionSnapshot(SectionPos.of(0, 0, 0), 1, listOf(split)), noRamp)
        val lower = field.column(2, 2, 0)!!
        val upper = field.layersAt(2, 2).last { it.valid }
        assertEquals(0.2f, lower.rawHeight)
        assertEquals(0.6f, lower.clearanceAbove)
        assertEquals(ClearanceKnowledge.BOUNDED, lower.clearanceKnowledge)
        assertEquals(1f, upper.rawHeight)
        assertEquals(0, field.query(2.5, 2.5, 0.5, 0.4).layerId)
        val missed = field.query(2.5, 2.5, 0.5, 0.2)
        assertFalse(missed.valid)
        assertEquals(SupportInvalidReason.NO_SURFACE_IN_RANGE, missed.invalidReason)
        assertTrue(missed.supportHeight.isNaN())
    }

    @Test
    fun bridgeKeepsItsLayerWhenTheFloorUnderOneColumnIsRemoved() {
        val blocks = buildList {
            for (z in 0 until 16) for (x in 0 until 16) {
                if (x != 8) add(ground(x, 0, z))
                add(ground(x, 10, z))
            }
        }
        val field = TerrainSupportFieldBuilder.build(
            SectionSnapshot(SectionPos.of(0, 0, 0), 1, blocks),
            SupportFieldPolicy(5f, 1.9f, 4),
        )
        val bridgeOverHole = field.layersAt(8, 8).single { it.valid }
        val neighborBridge = field.layersAt(7, 8).single { it.rawHeight == 11f }
        val neighborFloor = field.layersAt(7, 8).single { it.rawHeight == 1f }
        assertEquals(neighborBridge.layerId, bridgeOverHole.layerId)
        assertTrue(bridgeOverHole.layerId != neighborFloor.layerId)
        assertEquals(11f, bridgeOverHole.supportHeight)
        assertEquals(11f, field.query(8.5, 8.5, 12.0, 2.0).supportHeight)
        assertEquals(1, field.query(8.5, 8.5, 12.0, 2.0).layerId)
    }

    @Test
    fun sectionCeilingIsNotGroundWithoutProofItIsExposed() {
        val ceiling = ground(0, 15, 0)
        val unread = TerrainSupportFieldBuilder.build(SectionSnapshot(SectionPos.of(0, 0, 0), 1, listOf(ceiling)), noRamp)
        val hidden = unread.query(0.5, 0.5, 20.0, 8.0)
        assertFalse(hidden.valid)
        assertEquals(SupportInvalidReason.UNPROVEN_TOP, hidden.invalidReason)
        assertTrue(hidden.supportHeight.isNaN())

        val open = TerrainSupportFieldBuilder.build(
            SectionSnapshot(SectionPos.of(0, 0, 0), 1, listOf(ceiling), aboveCoverage = SectionCoverage.AIR),
            noRamp,
        )
        val exposed = open.column(0, 0, 0)!!
        assertTrue(exposed.valid)
        assertEquals(16f, exposed.rawHeight)
        assertEquals(ClearanceKnowledge.OPEN_ENDED, exposed.clearanceKnowledge)
        assertEquals(16f, exposed.clearanceAbove)
        assertFalse(exposed.clearanceAbove.isInfinite())

        val continued = SectionSnapshot(
            SectionPos.of(0, 0, 0), 1, listOf(ceiling),
            aboveCoverage = SectionCoverage.PRESENT,
            aboveBottomBlocks = listOf(ground(0, 0, 0)),
        )
        val inside = TerrainSupportFieldBuilder.build(continued, noRamp).query(0.5, 0.5, 20.0, 8.0)
        assertEquals(SupportInvalidReason.CONTINUES_ABOVE, inside.invalidReason)
        assertTrue(inside.supportHeight.isNaN())
    }

    @Test
    fun aboveSectionFloorIsNotCopiedIntoAnEmptyColumn() {
        val snapshot = SectionSnapshot(
            SectionPos.of(0, 0, 0), 1, emptyList(),
            coverage = SectionCoverage.PRESENT,
            aboveCoverage = SectionCoverage.PRESENT,
            aboveBottomBlocks = listOf(ground(4, 0, 4)),
        )
        val contact = TerrainSupportFieldBuilder.build(snapshot, noRamp).query(4.5, 4.5, 20.0, 20.0)
        assertEquals(SupportInvalidReason.AIR, contact.invalidReason)
        assertTrue(contact.supportHeight.isNaN())
    }

    @Test
    fun forbiddenStepDoesNotTiltTheNormalOrSampleARamp() {
        val closed = TerrainSupportFieldBuilder.build(
            columns { x, _ -> if (x >= 8) 2 else 1 },
            SupportFieldPolicy(1f, null, 4),
        )
        val lip = closed.query(8.0, 0.5, 6.0, 6.0)
        assertEquals(2f, lip.supportHeight)
        assertTrue(abs(lip.normalY - 1f) < 1.0e-3f)
        assertEquals(6f - 2f, lip.signedDistance)
        assertEquals(2f, lip.worldY.toFloat())
    }

    @Test
    fun allowedLipUsesTheSamePatchForHeightNormalAndSignedDistance() {
        val field = TerrainSupportFieldBuilder.build(columns { x, _ -> if (x >= 8) 2 else 1 }, oneBlockLip)
        val high = field.column(8, 0, 0)!!
        assertEquals(1.75f, high.supportHeight)
        assertEquals(1.75f, high.corner00)
        assertEquals(2f, high.corner10)
        assertEquals(1f, field.column(7, 0, 0)!!.supportHeight)
        assertEquals(2f, field.column(9, 0, 0)!!.supportHeight)
        val west = field.query(8.0, 0.5, 6.0, 6.0)
        val east = field.query(8.999, 0.5, 6.0, 6.0)
        assertEquals(1.75f, west.supportHeight, 1.0e-3f)
        assertEquals(2f, east.supportHeight, 1.0e-3f)
        assertTrue(west.normalX < -0.1f)
        assertEquals(west.worldY.toFloat(), west.supportHeight)
        assertEquals(6f - west.supportHeight, west.signedDistance, 1.0e-3f)
        val lowEdge = field.query(7.999, 0.5, 6.0, 6.0)
        assertEquals(1f, lowEdge.supportHeight, 1.0e-3f)
        assertTrue(abs(lowEdge.normalY - 1f) < 1.0e-3f)
    }

    @Test
    fun downwardSampleDoesNotInventPenetrationInsideTheSurface() {
        val inside = TerrainSupportFieldBuilder.build(platform(1), noRamp).query(0.5, 0.5, 0.2, 2.0)
        assertFalse(inside.valid)
        assertEquals(SupportInvalidReason.NO_SURFACE_IN_RANGE, inside.invalidReason)
        assertEquals(SupportQueryMode.DOWNWARD_SAMPLE, inside.queryMode)
        assertEquals(ContactAuthority.NONE, inside.authority)
        assertTrue(inside.signedDistance.isNaN())
    }

    @Test
    fun cumulativeDownwardCapIsRelativeToEachRawHeight() {
        val field = TerrainSupportFieldBuilder.build(
            columns { x, _ -> (x % 4) + 1 },
            SupportFieldPolicy(0.25f, 1f, 8),
        )
        for (x in 0 until 16) {
            val cell = field.layersAt(x, 0).single { it.valid }
            assertTrue(cell.rawHeight - cell.corner00 <= 0.25f + 1.0e-4f)
            assertTrue(cell.rawHeight - cell.corner10 <= 0.25f + 1.0e-4f)
            assertTrue(cell.corner00 <= cell.rawHeight + 1.0e-4f)
            assertTrue(cell.corner10 <= cell.rawHeight + 1.0e-4f)
        }
        assertEquals(1f, field.layersAt(0, 0).single { it.valid }.supportHeight)
    }

    @Test
    fun publishedFieldDoesNotFollowLaterMutationOfItsInputs() {
        val blocks = mutableListOf(ground(0, 0, 0))
        val field = TerrainSupportFieldBuilder.build(SectionSnapshot(SectionPos.of(0, 0, 0), 4, blocks), noRamp)
        blocks.clear()
        assertTrue(field.query(0.5, 0.5, 3.0, 3.0).valid)
        assertEquals(1f, field.column(0, 0, 0)!!.rawHeight)
        val input = mutableListOf(field.columns.first())
        val published = TerrainSupportField(0, 0, 0, 1f, 4, 16, 16, input)
        input.clear()
        assertEquals(1, published.columns.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (published.columns as MutableList<SupportColumn>).add(published.columns.first())
        }
    }

    @Test
    fun unlistedBlocksStayUnknownAndOnlyTheWrittenHardIdsAreHard() {
        assertEquals(SurfaceClass.UNKNOWN, SurfaceClassMap.classify(emptySet()))
        assertEquals(SurfaceClass.UNKNOWN, SurfaceClassMap.classify(emptySet(), "minecraft:stone"))
        assertEquals(SurfaceClass.SOLID_SOFT, SurfaceClassMap.classify(setOf("minecraft:dirt")))
        assertEquals(SurfaceClass.UNKNOWN, SectionSnapshot.BlockShapeSnapshot(0, 0, 0, full, true).surfaceClass)
    }

    @Test
    fun compiledBuilderDoesNotReferenceWorldChunkBlockStateOrBullet() {
        val bytes = TerrainSupportFieldBuilder::class.java.getResourceAsStream("TerrainSupportFieldBuilder.class")!!.readBytes()
        val pool = String(bytes, Charsets.ISO_8859_1)
        assertFalse(pool.contains("net/minecraft/world/level/Level"))
        assertFalse(pool.contains("net/minecraft/world/level/chunk/LevelChunk"))
        assertFalse(pool.contains("net/minecraft/world/level/block/state/BlockState"))
        assertFalse(pool.contains("com/jme3/bullet"))
    }

    private fun platform(layers: Int, hole: Pair<Int, Int>? = null) =
        columns(hole) { _, _ -> layers }

    private fun columns(hole: Pair<Int, Int>? = null, height: (Int, Int) -> Int) =
        snapshot(SectionPos.of(0, 0, 0), hole, height)

    private fun snapshot(section: SectionPos, hole: Pair<Int, Int>? = null, columns: (Int, Int) -> Int): SectionSnapshot {
        val blocks = buildList {
            for (z in 0 until 16) for (x in 0 until 16) {
                if (hole != null && hole.first == x && hole.second == z) continue
                for (y in 0 until columns(x, z)) add(ground(x, y, z))
            }
        }
        return SectionSnapshot(section, 1, blocks)
    }

    private fun ground(x: Int, y: Int, z: Int) =
        SectionSnapshot.BlockShapeSnapshot(x, y, z, full, true, SurfaceClass.SOLID_HARD, 0.6f)

    private fun assertEquals(expected: Float, actual: Float, delta: Float) {
        assertTrue(abs(expected - actual) <= delta, "expected $expected but was $actual")
    }
}
