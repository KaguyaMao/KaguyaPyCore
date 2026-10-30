package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import cn.solarmoon.spark_core.physics.body.CollisionGroups
import cn.solarmoon.spark_core.physics.terrain.support.SurfaceClass
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.PhysicsTickListener
import com.jme3.bullet.collision.ManifoldPoints
import com.jme3.bullet.collision.PersistentManifolds
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import net.minecraft.core.SectionPos
import net.minecraft.world.phys.AABB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Measures when the installed support mesh produces a solved upward impulse.
 * This does not rewrite manifolds and does not treat onContactProcessed as pre-solve.
 */
class SupportContactTimingTest {
    private val full = listOf(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
    private val halfY = 0.4f

    @Test
    fun firstSolvedSupportImpulseIsVisibleAfterTheStepNotBeforeIt() {
        NativePhysicsLoader.load()
        val blocks = buildList {
            for (z in 0 until 16) for (x in 0 until 16) {
                add(SectionSnapshot.BlockShapeSnapshot(x, 0, z, full, true, SurfaceClass.SOLID_SOFT, 0.6f))
            }
        }
        val built = TerrainSectionBuild.from(SectionSnapshot(SectionPos.of(0, 0, 0), 2, blocks))
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            GroundSupportPairs.create(built.supportField, built.structural).install(space)
            val body = PhysicsRigidBody(BoxCollisionShape(Vector3f(0.3f, halfY, 0.3f)), 1f).apply {
                setPhysicsLocation(Vector3f(4.5f, 1f + halfY + 0.2f, 4.5f))
                restitution = 0f
                friction = 0.8f
                collisionGroup = CollisionGroups.PHYSICS_BODY
                setCollideWithGroups(CollisionGroups.SUPPORT_PAIR)
            }
            space.addCollisionObject(body)
            var preImpulse = 0f
            var solvedAfterZeroPre = false
            var solved = false
            space.addTickListener(object : PhysicsTickListener {
                override fun prePhysicsTick(s: PhysicsSpace, dt: Float) {
                    preImpulse = upwardImpulse(s, body)
                }

                override fun physicsTick(s: PhysicsSpace, dt: Float) {
                    val post = upwardImpulse(s, body)
                    if (!solved && post > 0f) {
                        solved = true
                        solvedAfterZeroPre = preImpulse == 0f
                    }
                }
            })
            repeat(180) { space.update(1f / 60f, 0) }
            assertTrue(solved, "support mesh produced no solved upward impulse")
            assertTrue(solvedAfterZeroPre, "solved impulse was already present before that step")
            assertEquals(setOf(CollisionGroups.HEIGHT_FIELD), upwardGroups(space, body))
            assertTrue(body.getPhysicsLocation(null).y > 1f)
        } finally {
            space.destroy()
        }
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
}
