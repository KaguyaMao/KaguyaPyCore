package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import cn.solarmoon.spark_core.physics.body.CollisionGroups
import cn.solarmoon.spark_core.physics.terrain.support.ClearanceKnowledge
import cn.solarmoon.spark_core.physics.terrain.support.ContactAuthority
import cn.solarmoon.spark_core.physics.terrain.support.SectionCoverage
import cn.solarmoon.spark_core.physics.terrain.support.SupportColumn
import cn.solarmoon.spark_core.physics.terrain.support.SupportFieldPolicy
import cn.solarmoon.spark_core.physics.terrain.support.SupportContactArbiter
import cn.solarmoon.spark_core.physics.terrain.support.SurfaceClass
import cn.solarmoon.spark_core.physics.terrain.support.TerrainSupportField
import cn.solarmoon.spark_core.physics.terrain.support.TerrainSupportFieldBuilder
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.ManifoldPoints
import com.jme3.bullet.collision.PersistentManifolds
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import net.minecraft.core.SectionPos
import net.minecraft.world.phys.AABB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class TerrainSupportSurfaceTest {
    private val full = listOf(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
    private val flat = SupportFieldPolicy(0f, null, 0)
    private val halfY = 0.4f

    @Test
    fun supportMeshIsNotPartOfTheLiveDrivableMask() {
        assertEquals(0, CollisionGroups.DRIVABLE and CollisionGroups.HEIGHT_FIELD)
        val bytes = PhysicsChunkManager::class.java.getResourceAsStream("PhysicsChunkManager.class")!!.readBytes()
        val pool = String(bytes, Charsets.ISO_8859_1)
        assertFalse(pool.contains("height_field_"))
        assertTrue(pool.contains("GroundSupport"))
    }

    @Test
    fun supportFactoryCreatesBulletButNotFromThePureBuilder() {
        val bytes = TerrainSupportBodies::class.java.getResourceAsStream("TerrainSupportBodies.class")!!.readBytes()
        val pool = String(bytes, Charsets.ISO_8859_1)
        assertTrue(pool.contains("com/jme3/bullet"))
        assertFalse(pool.contains("net/minecraft/world/level/Level"))
        val builder = TerrainSupportFieldBuilder::class.java.getResourceAsStream("TerrainSupportFieldBuilder.class")!!.readBytes()
        assertFalse(String(builder, Charsets.ISO_8859_1).contains("com/jme3/bullet"))
    }

    @Test
    fun dynamicBoxRestsOnTheSupportMeshAndFallsWhenItIsRemoved() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            val surface = TerrainSupportBodies.create(field(platform(1)))
            assertEquals(1, surface.bodies.size)
            assertEquals(CollisionGroups.HEIGHT_FIELD, surface.bodies.single().collisionGroup)
            val slot = TerrainSupportSurfaceSlot()
            slot.replace(space, surface)
            val box = dynamicBox(8.5f, 2.5f, 8.5f)
            space.addCollisionObject(box)
            repeat(240) { space.update(1f / 60f, 0) }
            val rested = box.getPhysicsLocation(null).y
            assertEquals(1f + halfY, rested, 0.03f)
            assertTrue(upwardImpulse(space, box) > 0f)
            slot.clear(space)
            box.activate(true)
            val beforeFall = box.getPhysicsLocation(null).y
            repeat(40) { space.update(1f / 60f, 0) }
            assertTrue(box.getPhysicsLocation(null).y < beforeFall - 0.4f)
            assertEquals(0f, upwardImpulse(space, box))
        } finally {
            space.destroy()
        }
    }

    @Test
    fun holeAndUnreadTileDoNotCreateAFloor() {
        NativePhysicsLoader.load()
        val hole = TerrainSupportBodies.create(field(platform(1, hole = 8 to 8)))
        val unread = TerrainSupportBodies.create(field(SectionSnapshot(
            SectionPos.of(0, 0, 0), 3, emptyList(), coverage = SectionCoverage.UNREAD,
        )))
        assertTrue(unread.bodies.isEmpty())
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            hole.install(space)
            val box = dynamicBox(8.5f, 3f, 8.5f)
            space.addCollisionObject(box)
            repeat(120) { space.update(1f / 60f, 0) }
            assertTrue(box.getPhysicsLocation(null).y < 0f)
        } finally {
            space.destroy()
        }
    }

    @Test
    fun meshUsesTheCornerPatchRatherThanTheRawColumnTop() {
        NativePhysicsLoader.load()
        val shaved = SupportColumn(
            localX = 1, localZ = 1, layerId = 0, valid = true,
            rawHeight = 2f, supportHeight = 1.75f,
            corner00 = 1.75f, corner10 = 1.75f, corner01 = 1.75f, corner11 = 1.75f,
            surfaceClass = SurfaceClass.SOLID_HARD, friction = 0.9f,
            clearanceAbove = 8f, clearanceKnowledge = ClearanceKnowledge.OPEN_ENDED,
            hasStructuralNeighbor = false, hasUnresolvedPartial = false, invalidReason = null,
        )
        val field = TerrainSupportField(0, 0, 0, 1f, 2, 16, 16, listOf(shaved))
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            TerrainSupportBodies.create(field).install(space)
            val box = dynamicBox(1.5f, 4f, 1.5f)
            space.addCollisionObject(box)
            repeat(240) { space.update(1f / 60f, 0) }
            val y = box.getPhysicsLocation(null).y
            assertEquals(1.75f + halfY, y, 0.03f)
            assertTrue(abs(y - (2f + halfY)) > 0.08f)
        } finally {
            space.destroy()
        }
    }

    @Test
    fun heightFieldGroupDoesNotCollideWithAnUnrequestedStructuralBox() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            TerrainSupportBodies.create(field(platform(1))).install(space)
            val wall = PhysicsRigidBody(BoxCollisionShape(Vector3f(0.5f, 0.5f, 0.5f)), 0f).apply {
                setPhysicsLocation(Vector3f(8.5f, 2.5f, 8.5f))
                collisionGroup = CollisionGroups.TERRAIN
                setCollideWithGroups(CollisionGroups.NONE)
            }
            space.addCollisionObject(wall)
            val box = dynamicBox(8.5f, 4f, 8.5f)
            space.addCollisionObject(box)
            repeat(240) { space.update(1f / 60f, 0) }
            assertEquals(1f + halfY, box.getPhysicsLocation(null).y, 0.03f)
            assertEquals(8.5f, box.getPhysicsLocation(null).x, 0.05f)
        } finally {
            space.destroy()
        }
    }

    @Test
    fun olderRevisionCannotReplaceTheInstalledSurface() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            val slot = TerrainSupportSurfaceSlot()
            val newer = TerrainSupportBodies.create(field(platform(1).copy(revision = 9)))
            val older = TerrainSupportBodies.create(field(platform(1).copy(revision = 4)))
            slot.replace(space, newer)
            slot.replace(space, older)
            assertEquals(9L, slot.revision)
            assertEquals(1, slot.staleRejections)
            assertTrue(newer.bodies.single().isInWorld)
            assertFalse(older.bodies.single().isInWorld)
        } finally {
            space.destroy()
        }
    }

    @Test
    fun arbiterDropsASecondUpwardContactOnAHeightFieldPatch() {
        val fieldContact = SupportContactArbiter.UpwardContact(1f, 2f, 1f, ContactAuthority.HEIGHT_FIELD, 12f)
        val structuralTop = SupportContactArbiter.UpwardContact(1.02f, 2.01f, 0.9f, ContactAuthority.STRUCTURAL_OBSTACLE, 9f)
        val side = SupportContactArbiter.UpwardContact(1.02f, 2.01f, 0.1f, ContactAuthority.STRUCTURAL_OBSTACLE, 4f)
        val decisions = SupportContactArbiter.arbitrate(listOf(fieldContact, structuralTop, side), patchRadius = 0.05f)
        assertTrue(decisions.single { it.contact === fieldContact }.kept)
        assertFalse(decisions.single { it.contact === structuralTop }.kept)
        assertTrue(decisions.single { it.contact === side }.kept)
        val structuralOnly = SupportContactArbiter.arbitrate(listOf(structuralTop), patchRadius = 0.05f)
        assertTrue(structuralOnly.single().kept)
    }

    private fun upwardImpulse(space: PhysicsSpace, body: PhysicsRigidBody): Float {
        var impulse = 0f
        for (manifold in space.listManifoldIds()) {
            val bodyA = PersistentManifolds.getBodyAId(manifold)
            val bodyB = PersistentManifolds.getBodyBId(manifold)
            val onA = bodyA == body.nativeId()
            if (!onA && bodyB != body.nativeId()) continue
            for (point in PersistentManifolds.listPointIds(manifold)) {
                val normal = Vector3f()
                ManifoldPoints.getNormalWorldOnB(point, normal)
                val up = normal.y * if (onA) 1f else -1f
                if (up > 0.5f) impulse += ManifoldPoints.getAppliedImpulse(point)
            }
        }
        return impulse
    }

    private fun dynamicBox(x: Float, y: Float, z: Float) =
        PhysicsRigidBody(BoxCollisionShape(Vector3f(0.3f, halfY, 0.3f)), 1f).apply {
            setPhysicsLocation(Vector3f(x, y, z))
            restitution = 0f
            friction = 0.8f
            collisionGroup = CollisionGroups.PHYSICS_BODY
            setCollideWithGroups(CollisionGroups.HEIGHT_FIELD)
            ccdMotionThreshold = 0.02f
            ccdSweptSphereRadius = 0.2f
        }

    private fun field(snapshot: SectionSnapshot) = TerrainSupportFieldBuilder.build(snapshot, flat)

    private fun platform(layers: Int, hole: Pair<Int, Int>? = null): SectionSnapshot {
        val blocks = buildList {
            for (z in 0 until 16) for (x in 0 until 16) {
                if (hole != null && hole.first == x && hole.second == z) continue
                for (y in 0 until layers) add(ground(x, y, z))
            }
        }
        return SectionSnapshot(SectionPos.of(0, 0, 0), 1, blocks)
    }

    private fun ground(x: Int, y: Int, z: Int) =
        SectionSnapshot.BlockShapeSnapshot(x, y, z, full, true, SurfaceClass.SOLID_HARD, 0.6f)
}
