package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import cn.solarmoon.spark_core.physics.body.CollisionGroups
import cn.solarmoon.spark_core.physics.terrain.support.SectionCoverage
import cn.solarmoon.spark_core.physics.terrain.support.StructuralObstaclePlan
import cn.solarmoon.spark_core.physics.terrain.support.StructuralReason
import cn.solarmoon.spark_core.physics.terrain.support.SupportFieldPolicy
import cn.solarmoon.spark_core.physics.terrain.support.SurfaceClass
import cn.solarmoon.spark_core.physics.terrain.support.TerrainSupportFieldBuilder
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.ManifoldPoints
import com.jme3.bullet.collision.PersistentManifolds
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import net.minecraft.core.SectionPos
import net.minecraft.world.phys.AABB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroundSupportArbitrationTest {
    private val full = listOf(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
    private val flat = SupportFieldPolicy(0f, null, 0)
    private val halfY = 0.4f

    @Test
    fun supportPairExcludesRawTerrainEvenAfterChunkInstallation() {
        assertEquals(0, CollisionGroups.SUPPORT_PAIR and CollisionGroups.DRIVABLE)
        assertEquals(0, CollisionGroups.SUPPORT_PAIR and CollisionGroups.TERRAIN)
        assertEquals(0, CollisionGroups.SUPPORT_PAIR and CollisionGroups.SLOPE)
        val bytes = PhysicsChunkManager::class.java.getResourceAsStream("PhysicsChunkManager.class")!!.readBytes()
        val pool = String(bytes, Charsets.ISO_8859_1)
        assertTrue(pool.contains("TerrainSectionBuild"))
        assertTrue(pool.contains("GroundSupport"))
    }

    @Test
    fun unknownFullCubesAreNotSubstituteFloorsAndBuriedFillIsMerged() {
        val mixed = SectionSnapshot(SectionPos.of(0, 0, 0), 4, buildList {
            for (z in 0 until 16) for (x in 0 until 16) for (y in 0 until 8) {
                val classified = x == 0 && z == 0
                add(SectionSnapshot.BlockShapeSnapshot(
                    x, y, z, full, true,
                    if (classified) SurfaceClass.SOLID_HARD else SurfaceClass.UNKNOWN,
                    0.6f,
                ))
            }
        }, coverage = SectionCoverage.PRESENT)
        val field = TerrainSupportFieldBuilder.build(mixed, flat)
        val plan = StructuralObstaclePlan.build(mixed, field)
        assertTrue(field.layersAt(0, 0).any { it.valid })
        assertTrue(field.layersAt(4, 4).none { it.valid })
        assertTrue(plan.none { it.reason == StructuralReason.UNOWNED_VOLUME })
        assertEquals(1, plan.count { it.reason == StructuralReason.INTERNAL_FILL })
        assertTrue(plan.none { covers(it, 4.5f, 8f, 4.5f) })
        assertTrue(plan.none { covers(it, 0.5f, 8f, 0.5f) })
        val fill = plan.single { it.reason == StructuralReason.INTERNAL_FILL }
        assertTrue(fill.top <= 7f + 1.0e-3f)

        val unknownPartial = SectionSnapshot(SectionPos.of(0, 0, 0), 1, listOf(
            SectionSnapshot.BlockShapeSnapshot(4, 0, 4, listOf(
                AABB(0.0, 0.0, 0.375, 0.125, 1.0, 0.625),
            ), false, SurfaceClass.UNKNOWN, 0.6f),
        ))
        val omitted = StructuralObstaclePlan.build(unknownPartial, TerrainSupportFieldBuilder.build(unknownPartial, flat))
        assertTrue(omitted.none { it.reason == StructuralReason.PARTIAL_SHAPE })
        assertTrue(omitted.none { covers(it, 4.06f, 1f, 4.5f) })
    }

    @Test
    fun optInBodyFallsThroughUnknownAndRestsOnlyOnTheClassifiedTop() {
        NativePhysicsLoader.load()
        val blocks = buildList {
            for (y in 0 until 4) {
                add(SectionSnapshot.BlockShapeSnapshot(1, y, 1, full, true, SurfaceClass.SOLID_HARD, 0.7f))
                add(SectionSnapshot.BlockShapeSnapshot(8, y, 8, full, true, SurfaceClass.UNKNOWN, 0.7f))
            }
        }
        val snapshot = SectionSnapshot(SectionPos.of(0, 0, 0), 3, blocks, coverage = SectionCoverage.PRESENT)
        val plan = StructuralObstaclePlan.build(snapshot, TerrainSupportFieldBuilder.build(snapshot, flat))
        assertTrue(plan.none { covers(it, 8.5f, 4f, 8.5f) })
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            GroundSupportPairs.create(snapshot, TerrainSupportFieldBuilder.build(snapshot, flat)).install(space)
            val supported = optInBox(1.5f, 6f, 1.5f)
            val unknown = optInBox(8.5f, 6f, 8.5f)
            space.addCollisionObject(supported)
            space.addCollisionObject(unknown)
            repeat(180) { space.update(1f / 60f, 0) }
            assertEquals(4f + halfY, supported.getPhysicsLocation(null).y, 0.04f)
            assertTrue(upwardOwners(space, supported).all { it == CollisionGroups.HEIGHT_FIELD })
            assertTrue(unknown.getPhysicsLocation(null).y < 0f)
            assertTrue(upwardOwners(space, unknown).isEmpty())
        } finally {
            space.destroy()
        }
    }

    @Test
    fun ownedTopsAreOmittedAndTwoBlockFacesRemainStructural() {
        val platform = platform(1)
        val field = TerrainSupportFieldBuilder.build(platform, flat)
        val plan = StructuralObstaclePlan.build(platform, field)
        assertTrue(plan.none { it.reason == StructuralReason.UNOWNED_VOLUME })
        assertTrue(plan.none { covers(it, 4.5f, 1f, 4.5f) })

        val step = columns { x, _ -> if (x >= 8) 3 else 1 }
        val stepped = StructuralObstaclePlan.build(step, TerrainSupportFieldBuilder.build(step, flat))
        assertTrue(stepped.any { it.reason == StructuralReason.STRUCTURAL_FACE && it.centerX == 8f })
        assertTrue(stepped.none { covers(it, 7.5f, 1f, 0.5f) })
        assertTrue(stepped.none { covers(it, 8.5f, 3f, 0.5f) })

        val fence = SectionSnapshot(SectionPos.of(0, 0, 0), 1, listOf(
            SectionSnapshot.BlockShapeSnapshot(4, 0, 4, listOf(
                AABB(0.0, 0.0, 0.375, 0.125, 1.0, 0.625),
            ), false, SurfaceClass.SOLID_HARD, 0.6f),
        ))
        val partial = StructuralObstaclePlan.build(fence, TerrainSupportFieldBuilder.build(fence, flat))
        assertTrue(partial.any { it.reason == StructuralReason.PARTIAL_SHAPE })
    }

    @Test
    fun optInBodyRestsOnHeightFieldOnlyAndIsBlockedByATwoBlockFace() {
        NativePhysicsLoader.load()
        val snapshot = columns { x, _ -> if (x >= 8) 3 else 1 }
        val field = TerrainSupportFieldBuilder.build(snapshot, flat)
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            val pair = GroundSupportPairs.create(snapshot, field)
            pair.install(space)
            val rawTop = PhysicsRigidBody(BoxCollisionShape(Vector3f(0.5f, 0.5f, 0.5f)), 0f).apply {
                setPhysicsLocation(Vector3f(1.5f, 0.5f, 1.5f))
                collisionGroup = CollisionGroups.TERRAIN
                setCollideWithGroups(CollisionGroups.NONE)
            }
            space.addCollisionObject(rawTop)
            val box = optInBox(1.5f, 3f, 1.5f)
            space.addCollisionObject(box)
            repeat(240) { space.update(1f / 60f, 0) }
            assertEquals(1f + halfY, box.getPhysicsLocation(null).y, 0.03f)
            val owners = upwardOwners(space, box)
            assertTrue(owners.isNotEmpty())
            assertTrue(owners.all { it == CollisionGroups.HEIGHT_FIELD })

            box.setPhysicsLocation(Vector3f(7.2f, 1f + halfY, 0.5f))
            box.setLinearVelocity(Vector3f(3f, 0f, 0f))
            box.activate(true)
            repeat(60) { space.update(1f / 60f, 0) }
            assertTrue(box.getPhysicsLocation(null).x < 7.9f)
            assertEquals(1f + halfY, box.getPhysicsLocation(null).y, 0.08f)
        } finally {
            space.destroy()
        }
    }

    @Test
    fun replacingThePairWithAHoleRemovesSupportAndAnOlderPairCannotReturn() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            val slot = GroundSupportSlot()
            val solid = platform(1).copy(revision = 5)
            slot.replace(space, GroundSupportPairs.create(solid, TerrainSupportFieldBuilder.build(solid, flat)))
            val box = optInBox(8.5f, 2.5f, 8.5f)
            space.addCollisionObject(box)
            repeat(180) { space.update(1f / 60f, 0) }
            assertEquals(1f + halfY, box.getPhysicsLocation(null).y, 0.03f)

            val older = platform(1).copy(revision = 4)
            slot.replace(space, GroundSupportPairs.create(older, TerrainSupportFieldBuilder.build(older, flat)))
            assertEquals(1, slot.staleRejections)
            assertEquals(5L, slot.revision)

            val opened = platform(1, hole = 8 to 8).copy(revision = 6)
            slot.replace(space, GroundSupportPairs.create(opened, TerrainSupportFieldBuilder.build(opened, flat)))
            box.activate(true)
            repeat(40) { space.update(1f / 60f, 0) }
            assertTrue(box.getPhysicsLocation(null).y < 1f)
        } finally {
            space.destroy()
        }
    }

    @Test
    fun missingNeighborTileDoesNotInventSupportAcrossTheSectionBoundary() {
        NativePhysicsLoader.load()
        val left = platform(1)
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            GroundSupportPairs.create(left, TerrainSupportFieldBuilder.build(left, flat)).install(space)
            val inside = optInBox(15.5f, 2.5f, 8.5f)
            val outside = optInBox(16.5f, 2.5f, 8.5f)
            space.addCollisionObject(inside)
            space.addCollisionObject(outside)
            repeat(120) { space.update(1f / 60f, 0) }
            assertEquals(1f + halfY, inside.getPhysicsLocation(null).y, 0.03f)
            assertTrue(outside.getPhysicsLocation(null).y < 0f)
        } finally {
            space.destroy()
        }
    }

    @Test
    fun frictionIsCopiedOntoTheSupportBody() {
        NativePhysicsLoader.load()
        val blocks = buildList {
            for (z in 0 until 16) for (x in 0 until 16) {
                val soft = x < 8
                add(SectionSnapshot.BlockShapeSnapshot(
                    x, 0, z, full, true,
                    if (soft) SurfaceClass.SOLID_SOFT else SurfaceClass.SOLID_HARD,
                    if (soft) 0.4f else 0.9f,
                ))
            }
        }
        val snapshot = SectionSnapshot(SectionPos.of(0, 0, 0), 2, blocks)
        val pair = GroundSupportPairs.create(snapshot, TerrainSupportFieldBuilder.build(snapshot, flat))
        assertEquals(setOf(0.4f, 0.9f), pair.support.bodies.map { it.friction }.toSet())
        assertTrue(pair.structural.isEmpty())
    }

    @Test
    fun sectionBoundaryWallEmitsOneFaceAndBlocksAnOptInBody() {
        NativePhysicsLoader.load()
        // Low ground in section 0, high ground in section 1. Each snapshot
        // carries the other side's one-block margin: low top = 1, high top = 3.
        val lowMarginTops = IntArray(SectionSnapshot.MARGIN_CELLS) { SectionSnapshot.MARGIN_UNKNOWN }
        val lowMarginClasses = IntArray(SectionSnapshot.MARGIN_CELLS) { -1 }
        val highMarginTops = IntArray(SectionSnapshot.MARGIN_CELLS) { SectionSnapshot.MARGIN_UNKNOWN }
        val highMarginClasses = IntArray(SectionSnapshot.MARGIN_CELLS) { -1 }
        for (z in 0 until 16) {
            lowMarginTops[(16 + 1) * 18 + (z + 1)] = 2
            lowMarginClasses[(16 + 1) * 18 + (z + 1)] = SurfaceClass.SOLID_HARD.ordinal
            highMarginTops[(-1 + 1) * 18 + (z + 1)] = 0
            highMarginClasses[(-1 + 1) * 18 + (z + 1)] = SurfaceClass.SOLID_HARD.ordinal
        }
        val low = platform(1).copy(revision = 1, marginTops = lowMarginTops, marginSurfaceClasses = lowMarginClasses)
        val high = platform(3).copy(
            sectionPos = SectionPos.of(1, 0, 0),
            revision = 2,
            marginTops = highMarginTops,
            marginSurfaceClasses = highMarginClasses,
        )
        val lowField = TerrainSupportFieldBuilder.build(low, flat)
        val lowPlan = StructuralObstaclePlan.build(low, lowField)
        val highField = TerrainSupportFieldBuilder.build(high, flat)
        val highPlan = StructuralObstaclePlan.build(high, highField)

        assertEquals(16, highPlan.count { it.reason == StructuralReason.STRUCTURAL_FACE && it.centerX == 16f },
            "the high side needs exactly one face per boundary column")
        assertTrue(lowPlan.none { it.reason == StructuralReason.STRUCTURAL_FACE && it.centerX == 16f },
            "the low side must not duplicate the boundary face")

        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            GroundSupportPairs.create(lowField, lowPlan).install(space)
            GroundSupportPairs.create(highField, highPlan).install(space)
            val box = optInBox(15.2f, 1f + halfY, 8.5f)
            space.addCollisionObject(box)
            box.setLinearVelocity(Vector3f(4f, 0f, 0f))
            box.activate(true)
            repeat(90) { space.update(1f / 60f, 0) }
            val rest = box.getPhysicsLocation(null)
            assertTrue(rest.x < 16f, "an opt-in body crossed the section boundary wall: $rest")
            assertEquals(1f + halfY, rest.y, 0.08f)
        } finally {
            space.destroy()
        }
    }

    private fun covers(box: cn.solarmoon.spark_core.physics.terrain.support.StructuralBox, x: Float, y: Float, z: Float): Boolean =
        kotlin.math.abs(x - box.centerX) <= box.halfX + 1.0e-3f &&
            kotlin.math.abs(y - box.centerY) <= box.halfY + 1.0e-3f &&
            kotlin.math.abs(z - box.centerZ) <= box.halfZ + 1.0e-3f

    private fun upwardOwners(space: PhysicsSpace, body: PhysicsRigidBody): Set<Int> {
        val owners = HashSet<Int>()
        for (manifold in space.listManifoldIds()) {
            val onA = PersistentManifolds.getBodyAId(manifold) == body.nativeId()
            val onB = PersistentManifolds.getBodyBId(manifold) == body.nativeId()
            if (!onA && !onB) continue
            val otherId = if (onA) PersistentManifolds.getBodyBId(manifold) else PersistentManifolds.getBodyAId(manifold)
            val other = PhysicsCollisionObject.findInstance(otherId) ?: continue
            for (point in PersistentManifolds.listPointIds(manifold)) {
                val normal = Vector3f()
                ManifoldPoints.getNormalWorldOnB(point, normal)
                val up = normal.y * if (onA) 1f else -1f
                if (up > 0.5f && ManifoldPoints.getAppliedImpulse(point) > 0f) owners += other.collisionGroup
            }
        }
        return owners
    }

    private fun optInBox(x: Float, y: Float, z: Float) =
        PhysicsRigidBody(BoxCollisionShape(Vector3f(0.3f, halfY, 0.3f)), 1f).apply {
            setPhysicsLocation(Vector3f(x, y, z))
            restitution = 0f
            friction = 0.8f
            collisionGroup = CollisionGroups.PHYSICS_BODY
            setCollideWithGroups(CollisionGroups.SUPPORT_PAIR)
            ccdMotionThreshold = 0.02f
            ccdSweptSphereRadius = 0.2f
        }

    private fun platform(layers: Int, hole: Pair<Int, Int>? = null) = columns(hole) { _, _ -> layers }

    private fun columns(hole: Pair<Int, Int>? = null, height: (Int, Int) -> Int): SectionSnapshot {
        val blocks = buildList {
            for (z in 0 until 16) for (x in 0 until 16) {
                if (hole != null && hole.first == x && hole.second == z) continue
                for (y in 0 until height(x, z)) {
                    add(SectionSnapshot.BlockShapeSnapshot(x, y, z, full, true, SurfaceClass.SOLID_HARD, 0.6f))
                }
            }
        }
        return SectionSnapshot(SectionPos.of(0, 0, 0), 1, blocks, coverage = SectionCoverage.PRESENT)
    }
}
