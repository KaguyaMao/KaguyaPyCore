package cn.solarmoon.spark_core.physics

import com.jme3.bullet.RotationOrder
import com.jme3.bullet.collision.ContactPointFlag
import com.jme3.bullet.collision.ManifoldPoints
import com.jme3.bullet.joints.New6Dof
import com.jme3.bullet.joints.motors.MotorParam
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.math.Matrix3f
import com.jme3.math.Vector3f
import kotlin.math.*

/** One continuous skid footprint, not a collection of wheel/ray supports.
 * The only relative degree of freedom is chassis-local Y. Native terrain
 * manifolds supply the actual contact locations and equal/opposite reactions.
 * Neither the footprint nor the joint is a simulated articulated track chain.
 */
class PlanarTrackSupport(
    val chassis: PhysicsRigidBody,
    val anchor: Vector3f,
    halfWidth: Float,
    halfLength: Float,
    massKg: Float,
    val travelUp: Float,
    val travelDown: Float,
    val halfThickness: Float = .04f,
) {
    companion object {
        /** Convert an explicit sprung-side damping ratio to SI N*s/m.
         * Use the supported chassis mass, not the airborne plate reduced mass:
         * this is the suspension's grounded ride mode, not a solver-unit guess.
         */
        @JvmStatic fun dampingCoefficient(supportedMass: Float, frequency: Float, ratio: Float): Float =
            2f * supportedMass * (2f * PI.toFloat() * frequency.coerceAtLeast(.1f)) * ratio.coerceAtLeast(0f)
    }
    val body = PhysicsRigidBody(BoxCollisionShape(Vector3f(halfWidth, halfThickness, halfLength)).apply {
        margin = .001f
    }, massKg)
    val joint = New6Dof(chassis, body, anchor, Vector3f(), Matrix3f(), Matrix3f(), RotationOrder.XYZ).apply {
        for (axis in 0..5) {
            set(MotorParam.LowerLimit, axis, 0f)
            set(MotorParam.UpperLimit, axis, 0f)
        }
        set(MotorParam.LowerLimit, 1, -travelDown)
        set(MotorParam.UpperLimit, 1, travelUp)
        enableSpring(1, true)
    }

    fun travel(): Float {
        val matrix = chassis.getPhysicsRotation(null).toRotationMatrix()
        val up = matrix.mult(Vector3f.UNIT_Y, null)
        val origin = chassis.getPhysicsLocation(null).add(matrix.mult(anchor, null))
        return body.getPhysicsLocation(null).subtract(origin).dot(up)
    }

    fun compressionSpeed(): Float {
        val rotation = chassis.getPhysicsRotation(null).toRotationMatrix()
        val up = rotation.mult(Vector3f.UNIT_Y, null)
        val arm = rotation.mult(anchor, null)
        val anchorVelocity = chassis.getLinearVelocity(null)
            .add(chassis.getAngularVelocity(null).cross(arm, null))
        return body.getLinearVelocity(null).subtract(anchorVelocity).dot(up)
    }

    /** SI spring coefficients; the native joint applies equal/opposite forces. */
    fun spring(supportedMass: Float, gravity: Float, frequency: Float, damping: Float,
               maxForce: Float, dt: Float, loadTransfer: Float = 0f) {
        val k = supportedMass * (2f * PI.toFloat() * frequency.coerceAtLeast(.1f)).pow(2)
        // Honour the authored SI coefficient. A mass-dependent minimum damping
        // ratio silently replaces compression/rebound tuning on heavy vehicles.
        val c = damping.coerceAtLeast(0f)
        val compressionSpeed = compressionSpeed()
        val effectiveMass = chassis.mass * body.mass / (chassis.mass + body.mass)
        // Same translational reduced-mass bound used by bundled Spring2 when
        // limitIfNeeded=true. Apply here before estimating the row force budget.
        // It is a numerical ceiling, never a minimum damping or parking force.
        val nativeDamping = min(c, effectiveMass / dt)
        val preload = supportedMass * gravity + loadTransfer
        // Bound both the elastic and viscous row impulses, rather than silently
        // ignoring the authored force limit after switching to a native spring.
        val bound = abs(preload + k * travel()) + abs(nativeDamping * compressionSpeed)
        val scale = if (bound > maxForce) maxForce.coerceAtLeast(0f) / bound else 1f
        // Native spring equilibrium is below the authored neutral plane by
        // preload/k. At zero travel its upward chassis force balances weight.
        joint.setStiffness(1, k * scale, false)
        joint.setDamping(1, nativeDamping * scale, false)
        joint.setEquilibriumPoint(1, -preload / k)
    }

    /** Set one native friction budget, shared by drive, lateral grip and braking.
     * Must only be called inside the live contact-processed callback.
     */
    fun traction(pointId: Long, selfIsA: Boolean, normal: Vector3f, relativeSpeed: Float,
                 demand: Float, targetSpeed: Float, engine: Boolean, brake: Boolean,
                 friction: Float, rollingResistance: Float, driveScale: Float, dt: Float) {
        var forward = chassis.getPhysicsRotation(null).toRotationMatrix().mult(Vector3f.UNIT_Z, null)
        forward = forward.subtract(normal.mult(forward.dot(normal)))
        if (forward.lengthSquared() < 1e-8f) return
        forward = forward.normalize()
        val lateral = normal.cross(forward, null).normalize()
        val driving = engine && abs(demand) > .0001f && !brake
        val desired = when {
            driving -> relativeSpeed + (demand * targetSpeed - relativeSpeed) * (5f * driveScale * dt).coerceIn(0f, 1f)
            brake || abs(relativeSpeed) < .05f -> 0f
            else -> relativeSpeed - sign(relativeSpeed) * min(abs(relativeSpeed), rollingResistance * 9.81f * dt)
        }
        val sign = if (selfIsA) 1f else -1f
        // Bullet's two tangent rows are independently bounded. Inscribe their
        // square in the Coulomb disk so diagonal grip/drive cannot exceed mu*N.
        ManifoldPoints.setCombinedFriction(pointId, friction / sqrt(2f))
        ManifoldPoints.setLateralFrictionDir1(pointId, forward)
        ManifoldPoints.setLateralFrictionDir2(pointId, lateral)
        ManifoldPoints.setContactMotion1(pointId, desired * sign)
        ManifoldPoints.setContactMotion2(pointId, 0f)
        ManifoldPoints.setFlags(pointId, ManifoldPoints.getFlags(pointId) or ContactPointFlag.LATERAL_FRICTION)
    }

    fun reset(location: Vector3f, rotation: com.jme3.math.Quaternion, linear: Vector3f, angular: Vector3f) {
        val relative = rotation.toRotationMatrix().mult(anchor, null)
        body.clearForces()
        body.setPhysicsLocation(location.add(relative))
        body.setPhysicsRotation(rotation)
        body.setLinearVelocity(linear.add(angular.cross(relative)))
        body.setAngularVelocity(angular)
        body.activate(true)
    }
}
