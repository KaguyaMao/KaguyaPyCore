package cn.solarmoon.spark_core.physics

import com.jme3.bullet.CollisionConfiguration
import com.jme3.bullet.PhysicsSoftSpace
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.PhysicsTickListener
import com.jme3.bullet.RotationOrder
import com.jme3.bullet.SolverMode
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.collision.shapes.CylinderCollisionShape
import com.jme3.bullet.joints.New6Dof
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Matrix3f

import com.jme3.math.Vector3f
import cn.solarmoon.spark_core.physics.body.PhysicsJoints
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wheel roll motor is the only drive source of WHEEL vehicles. Its sign
 * must agree with the chassis forward axis: positive motor velocity around the
 * joint X axis has to push the vehicle toward +Z, the axis the layout marks as
 * the front (largest wheel z, steerable axle).
 */
class WheelRollMotorDirectionTest {

    private class Wheel(val offset: Vector3f, val body: PhysicsRigidBody) {
        lateinit var joint: New6Dof
    }

    private class Rig : AutoCloseable {
        val wheels: List<Wheel> = listOf(1.367f, -1.367f).flatMap { x ->
            listOf(2.21f, .843f, -.85f, -2.27f).map { z -> Vector3f(x, -.594f, z) }
        }.map { offset ->
            Wheel(offset, PhysicsRigidBody(
                CylinderCollisionShape(Vector3f(.273f, .625f, .625f), 0).apply { margin = .001f }, 200f))
        }
        val ground = PhysicsRigidBody(
            BoxCollisionShape(Vector3f(40f, .5f, 40f)).apply { margin = .001f }, 0f)
        val chassis = PhysicsRigidBody(
            BoxCollisionShape(Vector3f(.8f, .6f, 2f)).apply { margin = .001f }, 16000f)
        var demand = 0f
        val space = object : PhysicsSoftSpace(
            Vector3f(-100000f, -1000f, -100000f),
            Vector3f(100000f, 15000f, 100000f),
            BroadphaseType.DBVT, CollisionConfiguration(2048, 0)) {
            override fun needsCollision(a: PhysicsCollisionObject, b: PhysicsCollisionObject): Boolean {
                if (a === ground || b === ground) {
                    val other = if (a === ground) b else a
                    return wheels.any { it.body === other }
                }
                return false
            }
        }
        init {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            space.solverInfo.setMode(SolverMode.WarmStart or SolverMode.CacheDirection or SolverMode.Use2Directions)
            ground.setPhysicsLocation(Vector3f(0f, -.5f, 0f))
            ground.collisionGroup = 1
            ground.setCollideWithGroups(32)
            space.add(ground)
            chassis.setPhysicsLocation(Vector3f(0f, 1.219f, 0f))
            chassis.setDamping(.05f, .2f)
            chassis.collisionGroup = 16
            chassis.setCollideWithGroups(1)
            space.add(chassis)
            wheels.forEach { wheel ->
                wheel.body.friction = .7f
                wheel.body.restitution = 0f
                wheel.body.setProtectGravity(true)
                wheel.body.setGravity(Vector3f())
                wheel.body.collisionGroup = 32
                wheel.body.setCollideWithGroups(1)
                val joint = New6Dof(chassis, wheel.body, wheel.offset, Vector3f(),
                    Matrix3f(), Matrix3f(), RotationOrder.XYZ)
                PhysicsJoints.lock(joint, PhysicsJoints.LIN_X)
                PhysicsJoints.lock(joint, PhysicsJoints.LIN_Z)
                PhysicsJoints.limit(joint, PhysicsJoints.LIN_Y, -.2f, .2f)
                PhysicsJoints.free(joint, PhysicsJoints.ANG_X)
                PhysicsJoints.lock(joint, PhysicsJoints.ANG_Y)
                PhysicsJoints.lock(joint, PhysicsJoints.ANG_Z)
                joint.enableSpring(PhysicsJoints.LIN_Y, false)
                wheel.joint = joint
                space.add(wheel.body)
                space.addJoint(joint)
            }
            space.addTickListener(object : PhysicsTickListener {
                override fun prePhysicsTick(s: PhysicsSpace, dt: Float) {
                    wheels.forEach { wheel ->
                        PhysicsJoints.setRollMotor(wheel.joint, demand, 20000f)
                        wheel.body.activate(true)
                        val chassisY = chassis.getPhysicsLocation(Vector3f()).y
                        val wheelPos = wheel.body.getPhysicsLocation(Vector3f())
                        val travel = wheelPos.y - (chassisY + wheel.offset.y)
                        val relative = wheel.body.getLinearVelocity(Vector3f()).y
                            - chassis.getLinearVelocity(Vector3f()).y
                        val force = 400000f * (-travel) - 52000f * relative
                        wheel.body.applyCentralForce(Vector3f(0f, force, 0f))
                    }
                }
                override fun physicsTick(s: PhysicsSpace, dt: Float) { }
            })
        }
        fun run(steps: Int) = repeat(steps) { space.update(1f / 60f, 0, false, true, false) }
        override fun close() { }
    }

    @Test
    fun positiveRollMotorVelocityDrivesTheChassisTowardTheFrontAxis() {
        NativePhysicsLoader.load()
        val forward = Rig().use { rig ->
            rig.demand = 2.5f
            rig.run(120)
            rig.chassis.getLinearVelocity(Vector3f()).z
        }
        val backward = Rig().use { rig ->
            rig.demand = -2.5f
            rig.run(120)
            rig.chassis.getLinearVelocity(Vector3f()).z
        }
        println("wheel-roll-motor +omega vz=$forward -omega vz=$backward")
        assertTrue(forward > .5f,
            "positive roll motor velocity must drive the chassis toward +Z, vz=$forward")
        assertTrue(backward < -.5f,
            "negative roll motor velocity must drive the chassis toward -Z, vz=$backward")
    }

    @Test
    fun spinSignFollowsTheMotorSign() {
        NativePhysicsLoader.load()
        val spin = Rig().use { rig ->
            rig.demand = 2.5f
            rig.run(120)
            rig.wheels.first().body.getAngularVelocity(Vector3f()).x
        }
        println("wheel-roll-motor wheel omega_x=$spin")
        assertTrue(spin > 2f, "positive demand must spin the wheel forward (+X), omega_x=$spin")
    }
}
