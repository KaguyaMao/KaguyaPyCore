package cn.solarmoon.spark_core.physics

import cn.solarmoon.spark_core.physics.suspension.ProbeSuspensionUnit
import com.jme3.bullet.CollisionConfiguration
import com.jme3.bullet.PhysicsSoftSpace
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.PhysicsTickListener
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The generic probe suspension unit: distributed contacts must rest at the
 * authored travel, carry the weight share and stay finite, and losing the
 * ground must unload the probes instead of pinning anything.
 */
class ProbeSuspensionUnitTest {

    @Test
    fun probeSuspensionUnitRestsAtDesignTravelOnFlatGround() {
        NativePhysicsLoader.load()
        val chassisMass = 8000f
        val supportedMass = chassisMass / 4f
        val chassis = PhysicsRigidBody(BoxCollisionShape(Vector3f(1f, .35f, 2.4f)).apply { margin = .001f }, chassisMass)
        val probes = listOf(
            ProbeSuspensionUnit.Probe(Vector3f(1.6f, -.7f, 1.5f), .4f, supportedMass, tag = 0),
            ProbeSuspensionUnit.Probe(Vector3f(1.6f, -.7f, -1.5f), .4f, supportedMass, tag = 0),
            ProbeSuspensionUnit.Probe(Vector3f(-1.6f, -.7f, 1.5f), .4f, supportedMass, tag = 1),
            ProbeSuspensionUnit.Probe(Vector3f(-1.6f, -.7f, -1.5f), .4f, supportedMass, tag = 1),
        )
        val unit = ProbeSuspensionUnit(chassis, probes, .18f, .24f, 2.2f, 9.81f, .65f, .45f, 8f, 1_000_000f)
        val space = object : PhysicsSoftSpace(
            Vector3f(-512f, -512f, -512f), Vector3f(512f, 512f, 512f),
            BroadphaseType.DBVT, CollisionConfiguration(4096, 0)) {
            override fun needsCollision(a: PhysicsCollisionObject, b: PhysicsCollisionObject): Boolean =
                a.collisionGroup and b.collideWithGroups != 0 || b.collisionGroup and a.collideWithGroups != 0
        }
        space.setGravity(Vector3f())
        val floor = PhysicsRigidBody(BoxCollisionShape(Vector3f(8f, .5f, 8f)).apply { margin = .001f }, 0f)
        floor.setPhysicsLocation(Vector3f(0f, 65f - .5f, 0f))
        floor.collisionGroup = 1
        floor.setCollideWithGroups(0)
        space.add(floor)
        chassis.collisionGroup = 16
        chassis.setCollideWithGroups(1 or 2 or 16)
        chassis.setPhysicsLocation(Vector3f(0f, 66.3f, 0f))
        space.add(chassis)
        val up = Vector3f(0f, 1f, 0f)
        space.addTickListener(object : PhysicsTickListener {
            override fun prePhysicsTick(s: PhysicsSpace, dt: Float) {
                chassis.applyCentralForce(Vector3f(0f, -chassisMass * 9.81f, 0f))
                unit.step(s, 1 or 2, Quaternion(), up, chassis.getPhysicsLocation(null),
                    chassis.getLinearVelocity(null), chassis.getAngularVelocity(null), dt)
            }
            override fun physicsTick(s: PhysicsSpace, dt: Float) {}
        })
        repeat(600) { space.update(1f / 60f, 0, false, true, false) }
        val y = chassis.getPhysicsLocation(null).y
        // The pad plane (origin - 1.1) rests on the floor top with travel ~0,
        // because the spring preload equals each probe's weight share.
        assertTrue(abs(y - 66.1f) < .06f, "rest height drifted: y=$y")
        assertTrue(chassis.getLinearVelocity(null).length() < .02f, "unit did not settle")
        probes.forEach { p ->
            assertTrue(p.grounded, "probe lost ground: travel=${p.travel}")
            assertTrue(abs(p.travel) < .05f, "travel not at design: ${p.travel}")
            assertTrue(abs(p.force - supportedMass * 9.81f) < supportedMass * 9.81f * .25f,
                "force not near the weight share: ${p.force} vs ${supportedMass * 9.81f}")
        }
        // Removing the ground must unload every probe instead of pinning it.
        space.remove(floor)
        repeat(60) { space.update(1f / 60f, 0, false, true, false) }
        probes.forEach { p ->
            assertTrue(!p.grounded, "probe kept a phantom contact over a hole")
            assertTrue(p.force == 0f, "unloaded probe still produced force: ${p.force}")
        }
    }
}
