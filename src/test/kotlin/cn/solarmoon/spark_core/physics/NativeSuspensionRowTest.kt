package cn.solarmoon.spark_core.physics

import cn.solarmoon.spark_core.physics.body.PhysicsJoints
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Matrix3f
import com.jme3.math.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * B1 native fixture: one independent road wheel on a guided axle.
 *
 * The joint owns the kinematic constraint (travel window, free roll, no other
 * degrees of freedom) and the row owns the force law. This is exactly the split
 * the vehicle runtime will use, so a wrong travel axis or a spring applied to
 * the wrong body fails here without a Minecraft world.
 *
 * Two layouts answer different questions:
 *
 * - **resting**: a chassis carrying exactly one station's sprung mass sits on the
 *   wheel, which rests on a static floor. This reproduces the load path and lets
 *   the row settle at its static sag.
 * - **held**: the chassis stands in for the rest of the vehicle (gravity
 *   protected, effectively immovable), so the wheel can be driven to a travel
 *   limit, or released from droop, without the chassis falling too.
 *
 * The bodies are separated by a real gap at the bind pose. If they touched, hull
 * contact would carry the load and the spring would look inert; in the real
 * vehicle the wheel sits inside the track envelope and never overlaps the hull.
 */
class NativeSuspensionRowTest {

    private class Fixture(
        val space: PhysicsSpace,
        val chassis: PhysicsRigidBody,
        val wheel: PhysicsRigidBody,
        val joint: com.jme3.bullet.joints.New6Dof,
        val spec: SuspensionRowSpec,
        /** Distance from chassis centre to wheel centre at the bind pose. */
        val bindOffsetY: Float,
    ) : AutoCloseable {
        override fun close() {
            space.destroy()
        }
    }

    /**
     * A row built the way the vehicle compiler builds it: from a target static
     * sag and the authored damping ratios, never from hand-picked stiffness.
     *
     * Defaults match one 99A station: 3313 kg share, 2.2 Hz, 0.65/0.45 ratios.
     */
    private fun row(
        stationMassKg: Float = 3313f,
        travelUp: Float = 0.18f,
        travelDown: Float = 0.24f,
        staticSag: Float = 0.051339f,
        compressionRatio: Float = 0.65f,
        reboundRatio: Float = 0.45f,
    ) = SuspensionRowSpec.fromSag(
        stationMassKg = stationMassKg,
        staticSagM = staticSag,
        compressionRatio = compressionRatio,
        reboundRatio = reboundRatio,
        travelUpM = travelUp,
        travelDownM = travelDown,
        maxForceN = 400_000f,
    )

    private fun fixture(
        spec: SuspensionRowSpec = row(),
        chassisMassKg: Float = spec.stationMassKg,
        ground: Boolean = false,
        holdChassis: Boolean = false,
        gravity: Float = 9.81f,
    ): Fixture {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        space.setGravity(Vector3f(0f, -gravity, 0f))

        if (ground) {
            val floor = PhysicsRigidBody(BoxCollisionShape(20f, 0.5f, 20f), 0f)
            floor.setPhysicsLocation(Vector3f(0f, -0.5f, 0f))
            floor.friction = 0.9f
            space.addCollisionObject(floor)
        }

        // The bind pose must already rest on the floor. Starting the wheel in
        // the air would let it free-fall into the joint's droop limit before the
        // spring ever carries load, which measures nothing.
        val wheelY = 0.3f
        val bindOffsetY = 1.2f
        val chassisY = wheelY + bindOffsetY
        val chassis = PhysicsRigidBody(BoxCollisionShape(1f, 0.5f, 2f), chassisMassKg)
        chassis.setPhysicsLocation(Vector3f(0f, chassisY, 0f))
        if (holdChassis) {
            // Stands in for "the rest of the vehicle": gravity-protected and heavy
            // enough that one row cannot visibly move it.
            chassis.setProtectGravity(true)
            chassis.setGravity(Vector3f())
            chassis.mass = 2_000_000f
        }

        // The 1.2 m anchor leaves a 0.4 m gap between the wheel top and the hull
        // underside, so hull contact can never carry the spring load.
        val wheel = PhysicsRigidBody(BoxCollisionShape(0.3f), 900f)
        wheel.setPhysicsLocation(Vector3f(0f, wheelY, 0f))
        wheel.friction = 0.9f
        space.addCollisionObject(chassis)
        space.addCollisionObject(wheel)

        // Anchor below the chassis centre; +Y is travel, X is the axle. The two
        // constraint frames coincide at the bind pose, so zero travel is the
        // authored wheel position rather than an offset the caller must remember.
        val joint = PhysicsJoints.guidedWheelAxle(
            chassis, wheel,
            Vector3f(0f, -bindOffsetY, 0f), Vector3f(0f, 0f, 0f),
            Matrix3f(), Matrix3f(),
            -spec.travelDownM, spec.travelUpM,
        )
        space.addJoint(joint)
        return Fixture(space, chassis, wheel, joint, spec, bindOffsetY)
    }

    /** Compression of the row, metres. Positive means the wheel rose toward the chassis. */
    private fun travel(fixture: Fixture): Float {
        val chassisY = fixture.chassis.getPhysicsLocation(null).y
        val wheelY = fixture.wheel.getPhysicsLocation(null).y
        return (wheelY - chassisY) + fixture.bindOffsetY
    }

    private fun applyRow(fixture: Fixture) {
        val compression = travel(fixture)
        val velocity = fixture.wheel.getLinearVelocity(null).y -
            fixture.chassis.getLinearVelocity(null).y
        val force = PhysicsSuspensionRow.force(fixture.spec, SuspensionRowState(compression, velocity))
        PhysicsJoints.applyPairedForce(
            fixture.chassis, fixture.wheel,
            Vector3f(0f, 1f, 0f), force,
            fixture.chassis.getPhysicsLocation(null),
        )
    }

    private fun step(fixture: Fixture, seconds: Float, applySuspension: Boolean = true): Float {
        val dt = 1f / 60f
        var peak = travel(fixture)
        repeat((seconds / dt).toInt()) {
            if (applySuspension) applyRow(fixture)
            fixture.space.update(dt, 0, false, true, false)
            peak = maxOf(peak, travel(fixture))
        }
        return peak
    }

    /**
     * The load path: hull weight on the spring, wheel weight on the ground.
     * The row must settle where `k · s = m_station · g`.
     */
    @Test
    fun theWheelSettlesAtItsStaticSag() {
        val spec = row()
        val fixture = fixture(spec = spec, ground = true)
        fixture.use {
            step(fixture, 4f)
            val sag = spec.staticSagM
            val settled = travel(fixture)
            assertEquals(sag, settled, 0.012f, "settled travel $settled vs static sag $sag")
            assertTrue(settled in 0f..spec.travelUpM, "the row must settle inside its travel: $settled")
        }
    }

    /** With no support force the joint, not the row, must stop the wheel. */
    @Test
    fun withoutSupportTheWheelFallsToTheDroopLimit() {
        val fixture = fixture(holdChassis = true)
        fixture.use {
            step(fixture, 2f, applySuspension = false)
            assertEquals(-fixture.spec.travelDownM, travel(fixture), 0.02f)
        }
    }

    /**
     * The travel window is the joint's job. Gravity is off here on purpose: the
     * question is whether the constraint stops the wheel, not whether gravity
     * later pulls it back, and mixing the two would measure the fall.
     */
    @Test
    fun theJointStopsTheWheelAtFullCompression() {
        val fixture = fixture(holdChassis = true, gravity = 0f)
        fixture.use {
            fixture.wheel.setLinearVelocity(Vector3f(0f, 4f, 0f))
            val peak = step(fixture, 1.5f, applySuspension = false)
            assertTrue(
                peak <= fixture.spec.travelUpM + 0.02f,
                "peak travel $peak must stay inside ${fixture.spec.travelUpM}",
            )
            assertTrue(peak > 0.10f, "the wheel must actually reach the limit, got $peak")
        }
    }

    /**
     * Bilateral recovery: released at full droop, the row must pull the wheel
     * back toward the bind pose. A one-way clamp would leave it sitting on the
     * joint limit with zero force.
     */
    @Test
    fun theRowRecoversTheWheelFromFullDroop() {
        val spec = row()
        val fixture = fixture(spec = spec, holdChassis = true, gravity = 0f)
        fixture.use {
            // Pin the wheel at the droop limit first, then let the row act.
            fixture.wheel.setPhysicsLocation(
                Vector3f(0f, fixture.chassis.getPhysicsLocation(null).y - fixture.bindOffsetY - spec.travelDownM, 0f),
            )
            assertEquals(-spec.travelDownM, travel(fixture), 0.01f)
            assertTrue(
                PhysicsSuspensionRow.droopRestoringForceN(spec) < 0f,
                "the row must pull at full droop",
            )

            step(fixture, 2.5f)
            val settled = travel(fixture)
            // Zero gravity, so the bilateral spring settles at the bind pose.
            assertTrue(
                abs(settled) < 0.02f,
                "the row must recover from droop toward the bind pose, got $settled",
            )
            assertTrue(settled > -spec.travelDownM + 0.10f, "the wheel must leave the droop limit")
        }
    }

    /** A compressed row must push back out, not just stop pulling. */
    @Test
    fun theRowRecoversTheWheelFromCompression() {
        val spec = row()
        val fixture = fixture(spec = spec, holdChassis = true, gravity = 0f)
        fixture.use {
            fixture.wheel.setPhysicsLocation(
                Vector3f(0f, fixture.chassis.getPhysicsLocation(null).y - fixture.bindOffsetY + 0.10f, 0f),
            )
            assertEquals(0.10f, travel(fixture), 0.01f)
            assertTrue(
                PhysicsSuspensionRow.force(spec, SuspensionRowState(0.10f, 0f)) > 0f,
                "a compressed row must push",
            )

            step(fixture, 2.5f)
            val settled = travel(fixture)
            assertTrue(
                abs(settled) < 0.02f,
                "the row must recover from compression toward the bind pose, got $settled",
            )
        }
    }

    @Test
    fun theWheelRollsFreelyAboutItsAxle() {
        val fixture = fixture(holdChassis = true)
        fixture.use {
            val spin = 20f
            fixture.wheel.setAngularVelocity(Vector3f(spin, 0f, 0f))
            step(fixture, 0.5f, applySuspension = false)
            val angular = fixture.wheel.getAngularVelocity(null)
            // No motor and no contact: the roll axis is free, the others locked.
            assertEquals(spin, angular.x, 0.5f, "roll must persist, got ${angular.x}")
            assertEquals(0f, angular.y, 1e-3f)
            assertEquals(0f, angular.z, 1e-3f)
        }
    }

    @Test
    fun suspensionTravelDoesNotInduceRoll() {
        val fixture = fixture(holdChassis = true)
        fixture.use {
            step(fixture, 1.5f)
            val angular = fixture.wheel.getAngularVelocity(null)
            assertEquals(0f, angular.x, 1e-2f, "vertical travel must not spin the wheel")
            assertEquals(0f, angular.y, 1e-3f)
            assertEquals(0f, angular.z, 1e-3f)
        }
    }

    @Test
    fun aDampedRowRemovesVerticalEnergy() {
        fun peakSpeed(dampingScale: Float): Float {
            val base = row()
            val spec = base.copy(
                compressionDampingNsPerM = base.compressionDampingNsPerM * dampingScale,
                reboundDampingNsPerM = base.reboundDampingNsPerM * dampingScale,
            )
            val fixture = fixture(spec = spec, holdChassis = true)
            fixture.use {
                // Start 10 cm compressed and let the spring release the wheel.
                fixture.wheel.setPhysicsLocation(
                    Vector3f(0f, fixture.chassis.getPhysicsLocation(null).y - fixture.bindOffsetY + 0.10f, 0f),
                )
                var peak = 0f
                val dt = 1f / 60f
                repeat(90) {
                    applyRow(fixture)
                    fixture.space.update(dt, 0, false, true, false)
                    peak = maxOf(peak, abs(fixture.wheel.getLinearVelocity(null).y))
                }
                return peak
            }
        }
        val damped = peakSpeed(1f)
        val barelyDamped = peakSpeed(0.02f)
        assertTrue(damped > 0f)
        assertTrue(
            damped < barelyDamped,
            "the declared damper must remove more energy than a near-free row: $damped vs $barelyDamped",
        )
    }
}
