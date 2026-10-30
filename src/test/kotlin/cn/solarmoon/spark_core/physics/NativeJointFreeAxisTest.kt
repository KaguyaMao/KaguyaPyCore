package cn.solarmoon.spark_core.physics

import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.RotationOrder
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.joints.New6Dof
import com.jme3.bullet.joints.motors.MotorParam
import cn.solarmoon.spark_core.physics.body.PhysicsJoints
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Matrix3f
import com.jme3.math.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Native check of the six-axis limit semantics, before the free-axis helper is
 * allowed to change.
 *
 * `PhysicsJoints.free()` used to write +/-1e9, which is a *finite window*: an
 * axis that should be unconstrained would still stop at a billion units. Bullet
 * expresses "no limit" as lower > upper, but that is a native convention, not
 * something to assume from the Java signature. This test reads the limits back
 * and then measures actual motion, so the helper cannot be "fixed" into a state
 * the solver does not honour.
 */
class NativeJointFreeAxisTest {

    init {
        NativePhysicsLoader.load()
    }

    private fun space(gravity: Vector3f = Vector3f()): PhysicsSpace =
        PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT).apply { setGravity(gravity) }

    private fun body(space: PhysicsSpace, mass: Float = 1000f): PhysicsRigidBody {
        val body = PhysicsRigidBody(BoxCollisionShape(0.5f), mass)
        body.isContactResponse = false
        space.add(body)
        return body
    }

    private fun sixDof(
        space: PhysicsSpace,
        a: PhysicsRigidBody,
        b: PhysicsRigidBody,
        frameA: Matrix3f = Matrix3f(),
        frameB: Matrix3f = Matrix3f(),
    ): New6Dof {
        val joint = New6Dof(a, b, Vector3f(), Vector3f(), frameA, frameB, RotationOrder.XYZ)
        space.addJoint(joint)
        return joint
    }

    /** lower > upper is what Bullet documents as "free"; read it back per axis. */
    @Test
    fun freeLimitsReadBackAsAnInvertedWindow() {
        val space = space()
        try {
            val a = body(space)
            val b = body(space)
            val joint = sixDof(space, a, b)
            for (axis in 0..5) {
                PhysicsJoints.free(joint, axis)
            }
            for (axis in 0..5) {
                val lower = joint.get(MotorParam.LowerLimit, axis)
                val upper = joint.get(MotorParam.UpperLimit, axis)
                assertTrue(lower > upper, "axis $axis is not free: $lower..$upper")
            }
        } finally {
            space.destroy()
        }
    }

    /**
     * A free linear axis must actually move, and a locked one must not.
     *
     * Two separate joints, each configured before any motion: locking an axis
     * *after* the body has already fallen is a different experiment, because the
     * solver then pulls the accumulated offset back to zero instead of holding
     * the body still.
     */
    @Test
    fun aFreeLinearAxisMovesAndALockedOneDoesNot() {
        val dt = 1f / 60f

        fun drop(lockY: Boolean): Float {
            val space = space(Vector3f(0f, -9.81f, 0f))
            try {
                // Both anchors are at the local origin, so the bodies must start
                // co-located: an initial 10 m offset would make the solver spend
                // the window pulling the error back instead of measuring the axis.
                val fixed = body(space, 0f)
                val hanging = body(space)
                hanging.setPhysicsLocation(Vector3f(0f, 10f, 0f))
                fixed.setPhysicsLocation(Vector3f(0f, 10f, 0f))
                val joint = sixDof(space, fixed, hanging)
                if (lockY) {
                    PhysicsJoints.lock(joint, PhysicsJoints.LIN_Y)
                } else {
                    PhysicsJoints.free(joint, PhysicsJoints.LIN_Y)
                }
                PhysicsJoints.lock(joint, PhysicsJoints.LIN_X)
                PhysicsJoints.lock(joint, PhysicsJoints.LIN_Z)
                for (axis in 3..5) PhysicsJoints.lock(joint, axis)

                val start = hanging.getPhysicsLocation(null).y
                repeat(30) { space.update(dt, 0, false, true, false) }
                return kotlin.math.abs(hanging.getPhysicsLocation(null).y - start)
            } finally {
                space.destroy()
            }
        }

        val free = drop(lockY = false)
        assertTrue(free > 0.5f, "a free linear axis must fall under gravity, moved $free m")
        val locked = drop(lockY = true)
        assertTrue(locked < 0.01f, "a locked linear axis must hold, drifted $locked m")
    }

    /**
     * A free angular axis must rotate; a locked one must hold its angle.
     *
     * Same rule as the linear case: each joint is configured before the body is
     * given any angular velocity.
     */
    @Test
    fun aFreeAngularAxisRotatesAndALockedOneHolds() {
        val dt = 1f / 60f

        fun spin(lockX: Boolean): Float {
            val space = space()
            try {
                val fixed = body(space, 0f)
                val spinning = body(space)
                val joint = sixDof(space, fixed, spinning)
                for (axis in 0..2) PhysicsJoints.lock(joint, axis)
                if (lockX) {
                    PhysicsJoints.lock(joint, PhysicsJoints.ANG_X)
                } else {
                    PhysicsJoints.free(joint, PhysicsJoints.ANG_X)
                }
                PhysicsJoints.lock(joint, PhysicsJoints.ANG_Y)
                PhysicsJoints.lock(joint, PhysicsJoints.ANG_Z)

                spinning.setAngularVelocity(Vector3f(1f, 0f, 0f))
                repeat(60) { space.update(dt, 0, false, true, false) }
                return kotlin.math.abs(joint.getAngles(Vector3f()).x)
            } finally {
                space.destroy()
            }
        }

        val free = spin(lockX = false)
        assertTrue(free > 0.3f, "a free angular axis must rotate, angle $free rad")
        val locked = spin(lockX = true)
        assertTrue(locked < 0.01f, "a locked angular axis must hold, angle $locked rad")
    }

    /** A non-identity frame must not silently move the free axis to another world axis. */
    @Test
    fun aRotatedFrameKeepsTheFreeAxisLocal() {
        val space = space()
        try {
            val fixed = body(space, 0f)
            val spinning = body(space)
            // Rotate both frames by 90 degrees about Z, so local X is world Y.
            // fromAxes takes the basis columns: X=(0,1,0), Y=(-1,0,0), Z=(0,0,1).
            val frame = Matrix3f()
            frame.fromAxes(
                Vector3f(0f, 1f, 0f),
                Vector3f(-1f, 0f, 0f),
                Vector3f(0f, 0f, 1f),
            )
            val joint = sixDof(space, fixed, spinning, Matrix3f().set(frame), Matrix3f().set(frame))

            for (axis in 0..2) PhysicsJoints.lock(joint, axis)
            PhysicsJoints.free(joint, PhysicsJoints.ANG_X)
            PhysicsJoints.lock(joint, PhysicsJoints.ANG_Y)
            PhysicsJoints.lock(joint, PhysicsJoints.ANG_Z)

            spinning.setAngularVelocity(Vector3f(0f, 1f, 0f))
            val dt = 1f / 60f
            repeat(60) { space.update(dt, 0, false, true, false) }
            val angle = joint.getAngles(Vector3f()).x
            assertTrue(
                kotlin.math.abs(angle) > 0.3f,
                "the free axis is local, so a world-Y spin must show up on local X; angle $angle",
            )
        } finally {
            space.destroy()
        }
    }

    /** The free window must not be the old finite +/-1e9 clamp. */
    @Test
    fun freeIsNotAFiniteBillionWindow() {
        val space = space()
        try {
            val a = body(space)
            val b = body(space)
            val joint = sixDof(space, a, b)
            PhysicsJoints.free(joint, PhysicsJoints.ANG_X)
            val lower = joint.get(MotorParam.LowerLimit, PhysicsJoints.ANG_X)
            val upper = joint.get(MotorParam.UpperLimit, PhysicsJoints.ANG_X)
            assertTrue(
                lower > upper,
                "free must be an inverted window, not $lower..$upper",
            )
            assertEquals(1f, lower, 0f, "the free marker is a convention, not a magnitude")
        } finally {
            space.destroy()
        }
    }
}
