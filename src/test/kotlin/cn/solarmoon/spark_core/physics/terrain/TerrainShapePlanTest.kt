package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.PhysicsSweepTestResult
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.collision.shapes.CompoundCollisionShape
import com.jme3.bullet.collision.shapes.SphereCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Quaternion
import com.jme3.math.Transform
import com.jme3.math.Vector3f
import net.minecraft.core.SectionPos
import net.minecraft.world.phys.AABB
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerrainShapePlanTest {
    private fun slab(hole: Boolean = false): SectionSnapshot {
        val blocks = buildList {
            for (y in 0..4) for (z in 0..14) for (x in 0..14) {
                if (hole && x == 7 && z == 7) continue
                add(SectionSnapshot.BlockShapeSnapshot(x, y, z,
                    listOf(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)), true))
            }
        }
        return SectionSnapshot(SectionPos.of(0, 0, 0), 1, blocks)
    }

    @Test fun mergesSolidSlabWithoutOverlappingOrFillingHoles() {
        val solid = TerrainShapePlan.build(slab())
        assertEquals(1, solid.boxes.size)
        assertEquals(15f * 15f * 5f, solid.boxes.sumOf { (8f * it.halfX * it.halfY * it.halfZ).toDouble() }.toFloat())
        val perforated = TerrainShapePlan.build(slab(true))
        for (y in 0..4) for (z in 0..14) for (x in 0..14) {
            val px = x + .5f - 8f; val py = y + .5f - 8f; val pz = z + .5f - 8f
            val covering = perforated.boxes.count {
                kotlin.math.abs(px - it.centerX) < it.halfX &&
                    kotlin.math.abs(py - it.centerY) < it.halfY &&
                    kotlin.math.abs(pz - it.centerZ) < it.halfZ
            }
            assertEquals(if (x == 7 && z == 7) 0 else 1, covering, "cell $x,$y,$z")
        }
    }

    @Test fun rectangularMergingPreservesIrregularVoxelOccupancy() {
        val random = kotlin.random.Random(20261015)
        repeat(12) { trial ->
            val occupied = BooleanArray(16 * 16 * 16) { random.nextInt(4) != 0 }
            val blocks = buildList {
                for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                    if (occupied[y * 256 + z * 16 + x]) add(SectionSnapshot.BlockShapeSnapshot(x, y, z,
                        listOf(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)), true))
                }
            }
            val plan = TerrainShapePlan.build(SectionSnapshot(SectionPos.of(-1, 4, 0), 9, blocks))
            val actual = IntArray(occupied.size)
            plan.boxes.forEach { box ->
                val minX = (box.centerX - box.halfX + 8f).toInt()
                val minY = (box.centerY - box.halfY + 8f).toInt()
                val minZ = (box.centerZ - box.halfZ + 8f).toInt()
                val maxX = (box.centerX + box.halfX + 8f).toInt()
                val maxY = (box.centerY + box.halfY + 8f).toInt()
                val maxZ = (box.centerZ + box.halfZ + 8f).toInt()
                assertTrue(minX >= 0 && minY >= 0 && minZ >= 0 && maxX <= 16 && maxY <= 16 && maxZ <= 16)
                for (y in minY until maxY) for (z in minZ until maxZ) for (x in minX until maxX)
                    actual[y * 256 + z * 16 + x]++
            }
            occupied.forEachIndexed { cell, full ->
                assertEquals(if (full) 1 else 0, actual[cell], "trial $trial cell $cell")
            }
        }
    }

    @Test fun partialBlockKeepsItsAuthoredShapeBesideMergedFullBlocks() {
        val source = slab().copy(blocks = slab().blocks + SectionSnapshot.BlockShapeSnapshot(15, 0, 0,
            listOf(AABB(.25, 0.0, .125, .75, .5, .875)), false))
        val plan = TerrainShapePlan.build(source)
        assertEquals(2, plan.boxes.size)
        assertEquals(TerrainShapePlan.ShapeBox(7.5f, -7.75f, -7.5f, .25f, .25f, .375f), plan.boxes.last())
        assertEquals(source.sectionPos, plan.sectionPos)
        assertEquals(source.revision, plan.revision)
    }

    @Test fun topLevelBoxesKeepExactPlanGeometryAndActualHoles() {
        NativePhysicsLoader.load()
        val plan = TerrainShapePlan.build(slab(true))
        val origin = Vector3f(1032f, 72f, 1032f)
        val bodies = TerrainBoxBodies.create(plan.boxes, origin)
        assertEquals(plan.boxes.size, bodies.size)
        bodies.zip(plan.boxes).forEach { (body, box) ->
            assertTrue(body.isStatic)
            val shape = body.collisionShape as BoxCollisionShape
            assertEquals(Vector3f(box.halfX, box.halfY, box.halfZ), shape.getHalfExtents(null))
            assertEquals(.001f, shape.margin)
            assertEquals(origin.add(box.centerX, box.centerY, box.centerZ), body.getPhysicsLocation(null))
        }
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            bodies.forEach { space.add(it) }
            val hole = space.rayTest(Vector3f(1031.5f, 70f, 1031.5f), Vector3f(1031.5f, 63f, 1031.5f))
            assertTrue(hole.isEmpty(), "native top-level terrain filled the planned hole")
            assertTrue(space.rayTest(Vector3f(1032.5f, 70f, 1031.5f), Vector3f(1032.5f, 63f, 1031.5f)).isNotEmpty())
        } finally { space.destroy() }
    }

    @Test fun unchangedBlockCollisionIgnoresRevisionButNeverRealGeometryChanges() {
        val original = slab()
        assertTrue(original.hasSameBlockCollision(original.copy(revision = 20)))
        assertFalse(original.hasSameBlockCollision(slab(true)))
        assertFalse(original.hasSameBlockCollision(original.copy(sectionPos = SectionPos.of(1, 0, 0))))
        val partial = original.copy(blocks = original.blocks + SectionSnapshot.BlockShapeSnapshot(15, 0, 0,
            listOf(AABB(0.0, 0.0, 0.0, 1.0, .5, 1.0)), false))
        assertFalse(original.hasSameBlockCollision(partial))
        assertFalse(partial.hasSameBlockCollision(partial.copy(blocks = partial.blocks.dropLast(1) +
            partial.blocks.last().copy(boxes = listOf(AABB(0.0, 0.0, 0.0, 1.0, .6, 1.0))))))
    }

    @Test fun nativeSphereSweepHasNoArtificialRowSeams() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            TerrainBoxBodies.create(TerrainShapePlan.build(slab()).boxes,
                Vector3f(1032f, 68f, 1032f)).forEach(space::addCollisionObject)
            val sphere = SphereCollisionShape(.53125f)
            repeat(1000) { sample ->
                val z = 1027f + sample * .006f
                val hits = ArrayList<PhysicsSweepTestResult>()
                space.sweepTest(sphere,
                    Transform(Vector3f(1026.555f, 65.7f, z), Quaternion()),
                    Transform(Vector3f(1026.555f, 65.28f, z), Quaternion()), hits, 0f)
                val hit = hits.minByOrNull { it.hitFraction }
                assertNotNull(hit, "missing slab contact at $z")
                val normal = hit!!.getHitNormalLocal(null)
                assertTrue(normal.y > .999f, "artificial flat-floor normal at $z: $normal")
            }
        } finally {
            space.destroy()
        }
    }

    @Test fun nativeSphereSweepHasNoArtificialSectionSeam() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            repeat(2) { section ->
                TerrainBoxBodies.create(listOf(TerrainShapePlan.ShapeBox(0f, 0f, 0f, 8f, 2.5f, 8f)),
                    Vector3f(1024f, 62.5f, 1016f + section * 16f)).forEach(space::addCollisionObject)
            }
            val sphere = SphereCollisionShape(.53125f)
            repeat(1001) { sample ->
                val z = 1023.8f + sample * .0004f
                val hits = ArrayList<PhysicsSweepTestResult>()
                space.sweepTest(sphere,
                    Transform(Vector3f(1026.555f, 65.7f, z), Quaternion()),
                    Transform(Vector3f(1026.555f, 65.28f, z), Quaternion()), hits, 0f)
                val hit = hits.minByOrNull { it.hitFraction }
                assertNotNull(hit, "missing section seam contact at $z")
                val normal = hit!!.getHitNormalLocal(null)
                assertTrue(normal.y > .999f, "artificial section seam normal at $z: $normal")
                val surfaceY = 65.7f - .42f * hit.hitFraction - .53125f
                assertEquals(65f, surfaceY, .001f, "section seam changed the surface height")
            }
        } finally {
            space.destroy()
        }
    }
}
