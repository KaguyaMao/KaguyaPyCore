package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import cn.solarmoon.spark_core.physics.body.CollisionGroups
import cn.solarmoon.spark_core.physics.terrain.support.SupportFieldPolicy
import cn.solarmoon.spark_core.physics.terrain.support.SurfaceClass
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerrainSectionLifecycleTest {
    private val full = listOf(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
    private val halfY = 0.4f

    @Test
    fun livePolicyShavesOnlyAOneBlockEdgeAndBuilderProductHasNoBullet() {
        val policy = TerrainSupportInstallPolicy.LIVE
        assertEquals(1f, policy.maxDownwardCorrection)
        assertEquals(1f, policy.maxRiseForSupportTransition)
        assertEquals(1, policy.smoothIterations)
        assertFalse(policy == TerrainSupportInstallPolicy.NO_SMOOTHING)
        val bytes = TerrainSectionBuild::class.java.getResourceAsStream("TerrainSectionBuild.class")!!.readBytes()
        val pool = String(bytes, Charsets.ISO_8859_1)
        assertFalse(pool.contains("com/jme3/bullet"))
        val unknown = SectionSnapshot.BlockShapeSnapshot(0, 0, 0, full, true)
        val unread = TerrainSectionBuild.from(SectionSnapshot(SectionPos.of(0, 0, 0), 1, listOf(unknown)))
        assertTrue(unread.supportField.columns.none { it.valid })
        assertEquals(1L, unread.plan.revision)

        val step = TerrainSectionBuild.from(columns { x, _ -> if (x >= 8) 2 else 1 })
        val high = step.supportField.layersAt(8, 0).single { it.valid }
        assertEquals(1f, high.corner00, 1.0e-4f)
        assertEquals(2f, high.corner10, 1.0e-4f)
        val wall = TerrainSectionBuild.from(columns { x, _ -> if (x >= 8) 3 else 1 })
        val face = wall.supportField.layersAt(8, 0).single { it.valid }
        assertEquals(face.rawHeight, face.corner00)
        assertEquals(face.rawHeight, face.corner10)
    }

    @Test
    fun bridgeAndFloorAreSeparateSupportContacts() {
        NativePhysicsLoader.load()
        val blocks = buildList {
            for (z in 0 until 16) for (x in 0 until 16) {
                add(ground(x, 0, z))
                add(ground(x, 10, z))
            }
        }
        val snapshot = SectionSnapshot(SectionPos.of(0, 0, 0), 8, blocks)
        val built = TerrainSectionBuild.from(snapshot)
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            val pair = GroundSupportPairs.create(built.supportField, built.structural)
            pair.install(space)
            val above = box(3.5f, 14f, 3.5f, CollisionGroups.SUPPORT_PAIR)
            val between = box(5.5f, 4f, 5.5f, CollisionGroups.SUPPORT_PAIR)
            space.addCollisionObject(above)
            space.addCollisionObject(between)
            repeat(240) { space.update(1f / 60f, 0) }
            assertEquals(11f + halfY, above.getPhysicsLocation(null).y, 0.04f)
            assertEquals(1f + halfY, between.getPhysicsLocation(null).y, 0.04f)
            assertTrue(upwardGroups(space, above).all { it == CollisionGroups.HEIGHT_FIELD })
            assertTrue(upwardGroups(space, between).all { it == CollisionGroups.HEIGHT_FIELD })
        } finally {
            space.destroy()
        }
    }

    @Test
    fun drivableMaskDoesNotRestOnTheSupportPair() {
        NativePhysicsLoader.load()
        val snapshot = platform()
        val built = TerrainSectionBuild.from(snapshot)
        assertTrue(built.supportField.columns.any { it.valid })
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            GroundSupportPairs.create(built.supportField, built.structural).install(space)
            val ignored = box(8.5f, 3f, 8.5f, CollisionGroups.DRIVABLE)
            space.addCollisionObject(ignored)
            repeat(90) { space.update(1f / 60f, 0) }
            assertTrue(ignored.getPhysicsLocation(null).y < 0f)
        } finally {
            space.destroy()
        }
    }

    @Test
    fun contactUsesTheFrictionOfTheSurfaceUnderTheBox() {
        NativePhysicsLoader.load()
        val blocks = buildList {
            for (z in 0 until 16) for (x in 0 until 16) {
                val soft = x < 8
                add(SectionSnapshot.BlockShapeSnapshot(
                    x, 0, z, full, true,
                    if (soft) SurfaceClass.SOLID_SOFT else SurfaceClass.SOLID_HARD,
                    if (soft) 0.2f else 0.9f,
                ))
            }
        }
        val built = TerrainSectionBuild.from(SectionSnapshot(SectionPos.of(0, 0, 0), 3, blocks))
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            GroundSupportPairs.create(built.supportField, built.structural).install(space)
            val soft = box(2.5f, 3f, 2.5f, CollisionGroups.SUPPORT_PAIR)
            space.addCollisionObject(soft)
            repeat(180) { space.update(1f / 60f, 0) }
            assertEquals(0.2f, contactingFriction(space, soft))
        } finally {
            space.destroy()
        }
    }

    @Test
    fun liftingOffTheSurfaceClearsTheUpwardImpulseWithoutRemovingThePair() {
        NativePhysicsLoader.load()
        val built = TerrainSectionBuild.from(platform())
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            val pair = GroundSupportPairs.create(built.supportField, built.structural)
            pair.install(space)
            val body = box(4.5f, 3f, 4.5f, CollisionGroups.SUPPORT_PAIR)
            space.addCollisionObject(body)
            repeat(180) { space.update(1f / 60f, 0) }
            assertTrue(upwardImpulse(space, body) > 0f)
            body.setPhysicsLocation(Vector3f(4.5f, 6f, 4.5f))
            body.setLinearVelocity(Vector3f())
            body.activate(true)
            space.update(1f / 60f, 0)
            assertEquals(0f, upwardImpulse(space, body))
            assertTrue(pair.support.bodies.single().isInWorld)
        } finally {
            space.destroy()
        }
    }

    @Test
    fun aOneBlockStepOnASectionBoundaryIsShavedFromTheMarginEvidence() {
        val snapshot = columns { _, _ -> 2 }
        val marginTops = IntArray(SectionSnapshot.MARGIN_CELLS) { SectionSnapshot.MARGIN_UNKNOWN }
        val marginClasses = IntArray(SectionSnapshot.MARGIN_CELLS) { -1 }
        for (z in 0 until 16) {
            marginTops[(16 + 1) * 18 + (z + 1)] = 0
            marginClasses[(16 + 1) * 18 + (z + 1)] = SurfaceClass.SOLID_HARD.ordinal
        }
        val stepped = snapshot.copy(marginTops = marginTops, marginSurfaceClasses = marginClasses)
        val built = TerrainSectionBuild.from(stepped)
        val edge = built.supportField.layersAt(15, 0).single { it.valid }
        assertEquals(1f, edge.corner10, 1.0e-4f)
        assertEquals(1f, edge.corner11, 1.0e-4f)
        assertEquals(2f, edge.corner00, 1.0e-4f)
        assertEquals(2f, edge.corner01, 1.0e-4f)

        // A two-block face is not a transition.
        val wallTops = IntArray(SectionSnapshot.MARGIN_CELLS) { SectionSnapshot.MARGIN_UNKNOWN }
        val wallClasses = IntArray(SectionSnapshot.MARGIN_CELLS) { -1 }
        for (z in 0 until 16) {
            wallTops[(16 + 1) * 18 + (z + 1)] = -1
            wallClasses[(16 + 1) * 18 + (z + 1)] = SurfaceClass.SOLID_HARD.ordinal
        }
        val wall = TerrainSectionBuild.from(
            snapshot.copy(marginTops = wallTops, marginSurfaceClasses = wallClasses)
        )
        val wallEdge = wall.supportField.layersAt(15, 0).single { it.valid }
        assertEquals(2f, wallEdge.corner10, 1.0e-4f)
        assertEquals(2f, wallEdge.corner11, 1.0e-4f)

        // Unknown margin evidence never invents a slope.
        val unknown = TerrainSectionBuild.from(snapshot)
        val unknownEdge = unknown.supportField.layersAt(15, 0).single { it.valid }
        assertEquals(2f, unknownEdge.corner10, 1.0e-4f)
        assertEquals(2f, unknownEdge.corner11, 1.0e-4f)
    }

    private fun upwardGroups(space: PhysicsSpace, body: PhysicsRigidBody): Set<Int> {
        val groups = HashSet<Int>()
        for (manifold in space.listManifoldIds()) {
            val onA = PersistentManifolds.getBodyAId(manifold) == body.nativeId()
            if (!onA && PersistentManifolds.getBodyBId(manifold) != body.nativeId()) continue
            val otherId = if (onA) PersistentManifolds.getBodyBId(manifold) else PersistentManifolds.getBodyAId(manifold)
            val other = PhysicsCollisionObject.findInstance(otherId) ?: continue
            for (point in PersistentManifolds.listPointIds(manifold)) {
                val normal = Vector3f()
                ManifoldPoints.getNormalWorldOnB(point, normal)
                val up = normal.y * if (onA) 1f else -1f
                if (up > 0.5f && ManifoldPoints.getAppliedImpulse(point) > 0f) groups += other.collisionGroup
            }
        }
        return groups
    }

    private fun upwardImpulse(space: PhysicsSpace, body: PhysicsRigidBody): Float {
        var impulse = 0f
        for (manifold in space.listManifoldIds()) {
            val onA = PersistentManifolds.getBodyAId(manifold) == body.nativeId()
            if (!onA && PersistentManifolds.getBodyBId(manifold) != body.nativeId()) continue
            for (point in PersistentManifolds.listPointIds(manifold)) {
                val normal = Vector3f()
                ManifoldPoints.getNormalWorldOnB(point, normal)
                val up = normal.y * if (onA) 1f else -1f
                if (up > 0.5f) impulse += ManifoldPoints.getAppliedImpulse(point)
            }
        }
        return impulse
    }

    private fun contactingFriction(space: PhysicsSpace, body: PhysicsRigidBody): Float {
        for (manifold in space.listManifoldIds()) {
            val onA = PersistentManifolds.getBodyAId(manifold) == body.nativeId()
            if (!onA && PersistentManifolds.getBodyBId(manifold) != body.nativeId()) continue
            val otherId = if (onA) PersistentManifolds.getBodyBId(manifold) else PersistentManifolds.getBodyAId(manifold)
            val other = PhysicsCollisionObject.findInstance(otherId) ?: continue
            for (point in PersistentManifolds.listPointIds(manifold)) {
                if (ManifoldPoints.getAppliedImpulse(point) > 0f) return other.friction
            }
        }
        return Float.NaN
    }

    private fun box(x: Float, y: Float, z: Float, mask: Int) =
        PhysicsRigidBody(BoxCollisionShape(Vector3f(0.3f, halfY, 0.3f)), 1f).apply {
            setPhysicsLocation(Vector3f(x, y, z))
            restitution = 0f
            friction = 0.8f
            collisionGroup = CollisionGroups.PHYSICS_BODY
            setCollideWithGroups(mask)
            ccdMotionThreshold = 0.02f
            ccdSweptSphereRadius = 0.2f
        }

    @Test
    fun supportPairCollisionUsesTheShavedEdgeRatherThanTheRawTop() {
        NativePhysicsLoader.load()
        val built = TerrainSectionBuild.from(columns { x, _ -> if (x >= 8) 2 else 1 })
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            GroundSupportPairs.create(built.supportField, built.structural).install(space)
            val body = box(8.5f, 4f, 0.5f, CollisionGroups.SUPPORT_PAIR)
            space.addCollisionObject(body)
            repeat(240) { space.update(1f / 60f, 0) }
            val rest = body.getPhysicsLocation(null)
            // A 45-degree shaved edge is the collision. An axis-aligned box slides
            // toward the low side instead of sticking at the raw high top.
            assertTrue(rest.y < 2f, "raw high top still carried the box: $rest")
            assertTrue(rest.y > 0.8f, "box fell through the shaved support: $rest")
            assertTrue(rest.x < 8.5f, "box did not follow the shaved slope: $rest")
            assertTrue(upwardGroups(space, body).all { it == CollisionGroups.HEIGHT_FIELD })
        } finally {
            space.destroy()
        }
    }

    private fun columns(height: (Int, Int) -> Int): SectionSnapshot {
        val blocks = buildList {
            for (z in 0 until 16) for (x in 0 until 16) {
                for (y in 0 until height(x, z)) add(ground(x, y, z))
            }
        }
        return SectionSnapshot(SectionPos.of(0, 0, 0), 4, blocks)
    }

    private fun platform(): SectionSnapshot {
        val blocks = buildList {
            for (z in 0 until 16) for (x in 0 until 16) add(ground(x, 0, z))
        }
        return SectionSnapshot(SectionPos.of(0, 0, 0), 1, blocks)
    }

    private fun ground(x: Int, y: Int, z: Int) =
        SectionSnapshot.BlockShapeSnapshot(x, y, z, full, true, SurfaceClass.SOLID_HARD, 0.6f)
}
