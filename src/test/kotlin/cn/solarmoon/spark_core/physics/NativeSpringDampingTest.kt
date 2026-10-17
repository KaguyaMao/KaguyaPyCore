package cn.solarmoon.spark_core.physics

import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.RotationOrder
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.joints.New6Dof
import com.jme3.bullet.joints.motors.MotorParam
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Matrix3f
import com.jme3.math.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Verify the bundled native solver's units, not merely a wrapper's clamp/readback. */
class NativeSpringDampingTest {
    @Test fun nativeLinearDampingIsForcePerSpeedNotADimensionlessRatio() {
        NativePhysicsLoader.load()
        for (coefficient in listOf(1f, 1200f)) {
            val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
            try {
                space.setGravity(Vector3f())
                val a = PhysicsRigidBody(BoxCollisionShape(1f), 1000f)
                val b = PhysicsRigidBody(BoxCollisionShape(1f), 1000f)
                // Collocated centres remove angular lever arms; disable contact
                // response so only the spring's viscous row changes momentum.
                a.isContactResponse = false
                b.isContactResponse = false
                space.add(a); space.add(b)
                val joint = New6Dof(a, b, Vector3f(), Vector3f(), Matrix3f(), Matrix3f(), RotationOrder.XYZ)
                for (axis in 0..5) {
                    joint.set(MotorParam.LowerLimit, axis, 0f)
                    joint.set(MotorParam.UpperLimit, axis, 0f)
                }
                joint.set(MotorParam.LowerLimit, 1, 1f)
                joint.set(MotorParam.UpperLimit, 1, -1f)
                joint.enableSpring(1, true)
                joint.setStiffness(1, 0f, false)
                joint.setDamping(1, coefficient, false)
                space.addJoint(joint)
                b.setLinearVelocity(Vector3f(0f, 1f, 0f))
                val dt = 1f / 60f
                space.update(dt, 0, false, true, false)
                val impulse = coefficient * dt // c * relative speed(1 m/s) * dt
                assertEquals(impulse / a.mass, a.getLinearVelocity(null).y, .000002f)
                assertEquals(1f - impulse / b.mass, b.getLinearVelocity(null).y, .000002f)
                assertEquals(1000f,
                    a.mass * a.getLinearVelocity(null).y + b.mass * b.getLinearVelocity(null).y, .001f)
            } finally { space.destroy() }
        }
    }
}
