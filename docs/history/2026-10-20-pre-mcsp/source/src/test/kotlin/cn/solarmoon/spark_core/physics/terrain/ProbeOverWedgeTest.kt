package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
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
import net.minecraft.core.SectionPos
import net.minecraft.world.phys.AABB
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression for the tracked-staircase stall.
 *
 * A generated one-block climb wedge is a real support surface, but its buried
 * tail reaches one block behind the visible run. A multi-probe belt that is
 * still approaching the step therefore samples that tail with its middle
 * probes while its end probes stand on the flat floor. Every probe must keep a
 * non-negative spring load there; otherwise the hull bridges on two points,
 * settles onto its own collision box and the hull friction stalls the vehicle
 * before it ever reaches the step.
 */
class ProbeOverWedgeTest {

    private val full = listOf(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))

    /** Flat floor with top at local y=4 and one block of rise for z>=9. */
    private fun snapshot(): SectionSnapshot {
        val blocks = ArrayList<SectionSnapshot.BlockShapeSnapshot>()
        for (z in 0..15) for (x in 0..15) {
            val top = if (z >= 9) 5 else 4
            for (y in 0 until top) blocks.add(SectionSnapshot.BlockShapeSnapshot(x, y, z, full, true))
        }
        return SectionSnapshot(SectionPos.of(0, 0, 0), 1, blocks, IntArray(0))
    }

    /** The six ZTZ-99A support stations, in the chassis body frame. */
    private val supportOffsets = listOf(
        Vector3f(2.055664f, -.507862f, 2.952516f),
        Vector3f(2.055664f, -.507862f, 1.819703f),
        Vector3f(2.055664f, -.507862f, .686891f),
        Vector3f(2.055664f, -.507862f, -.426391f),
        Vector3f(2.055664f, -.507862f, -1.559203f),
        Vector3f(2.055664f, -.507862f, -2.692016f),
    )

    private fun space(): PhysicsSoftSpace = object : PhysicsSoftSpace(
        Vector3f(-512f, -512f, -512f), Vector3f(512f, 512f, 512f),
        BroadphaseType.DBVT, CollisionConfiguration(4096, 0)) {
        override fun needsCollision(a: PhysicsCollisionObject, b: PhysicsCollisionObject): Boolean =
            a.collisionGroup and b.collideWithGroups != 0 || b.collisionGroup and a.collideWithGroups != 0
    }

    @org.junit.jupiter.api.Disabled("Hand-written fallback is not the shipped support field; the vehicle GameTest owns this case.")
    @Test
    fun everyBeltProbeKeepsLoadWhileTheNoseRidesTheClimbWedge() {
        NativePhysicsLoader.load()
        val plan = TerrainShapePlan(SectionPos.of(0, 0, 0), 1, emptyList(), TerrainSlopePlanner.build(snapshot()))
        assertTrue(plan.slopes.isNotEmpty(), "the authored step must produce a climb wedge")

        val space = space()
        try {
            space.setGravity(Vector3f())
            val origin = Vector3f(1000f, 64f, 1000f)
            TerrainBoxBodies.create(plan.boxes, origin).forEach { space.addCollisionObject(it) }
            TerrainSlopeBodies.create(plan.slopes, origin).forEach { space.addCollisionObject(it) }
            // Wedge spans world x 998..1001, step face at 1001, floor top y=60.

            val chassisMass = 55000f
            val supportedMass = chassisMass / 2f / supportOffsets.size
            val chassis = PhysicsRigidBody(
                BoxCollisionShape(Vector3f(2.05f, .55f, 2.95f)).apply { margin = .001f }, chassisMass)
            chassis.collisionGroup = 16
            // Same rule as the shipped chassis: blocks only, never the wedge.
            chassis.setCollideWithGroups(1 or 2 or 16)
            val probes = supportOffsets.map {
                ProbeSuspensionUnit.Probe(Vector3f(it.x, it.y, it.z), .53125f, supportedMass, 0)
            }
            val unit = ProbeSuspensionUnit(
                chassis, probes, .18f, .24f, 2.2f, 9.81f, .65f, .45f, 8f, 1_000_000f)
            // Mirrors the shipped support field: the drivable surface (block top
            // or climb wedge) is the support height, while the block-only column
            // stays the gate. The wedge spans world z 998..1001 and rises from
            // y=60 to y=61 over three blocks.
            val fallback = ProbeSuspensionUnit.GroundFallback { anchor, _, _, _ ->
                val z = anchor.z
                val surface = when {
                    z >= 1001f -> 61f
                    z >= 998f -> 60f + (z - 998f) / 3f
                    else -> 60f
                }
                ProbeSuspensionUnit.GroundSample(anchor.x, surface, anchor.z, Vector3f(0f, 1f, 0f))
            }

            // Nose just short of the face: the front stations are over the
            // wedge while the rear stations still stand on the flat floor.
            chassis.setPhysicsLocation(Vector3f(1000f, 61.05f, 998.0f))
            space.addCollisionObject(chassis)
            val up = Vector3f(0f, 1f, 0f)
            space.addTickListener(object : PhysicsTickListener {
                override fun prePhysicsTick(s: PhysicsSpace, dt: Float) {
                    chassis.applyCentralForce(Vector3f(0f, -chassisMass * 9.81f, 0f))
                    unit.step(s, 1 or 2, Quaternion(), up, chassis.getPhysicsLocation(null),
                        chassis.getLinearVelocity(null), chassis.getAngularVelocity(null), dt, fallback)
                }

                override fun physicsTick(s: PhysicsSpace, dt: Float) {}
            })
            repeat(300) { space.update(1f / 60f, 0, false, true, false) }

            val loaded = probes.count { it.grounded && it.force > 0f }
            val total = probes.sumOf { it.force.toDouble() }
            val location = chassis.getPhysicsLocation(null)
            val detail = "loaded=$loaded/${probes.size} " +
                "travels=${probes.map { "%.3f".format(it.travel) }} " +
                "forces=${probes.map { "%.0f".format(it.force) }} " +
                "total=$total weight=$chassisMass*9.81 y=${location.y}"
            assertTrue(loaded == probes.size, "belt bridged instead of carrying every station: $detail")
            assertTrue(total > chassisMass * 9.81 * .85,
                "springs did not carry the sprung mass on the wedge: $detail")
        } finally {
            space.destroy()
        }
    }
}
