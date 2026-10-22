package cn.solarmoon.spark_core.physics.track

import cn.solarmoon.spark_core.physics.PhysicsHost
import cn.solarmoon.spark_core.physics.body.PhysicsJoints
import cn.solarmoon.spark_core.physics.body.collideWithOwnerGroups
import cn.solarmoon.spark_core.physics.body.name
import cn.solarmoon.spark_core.physics.body.owner
import cn.solarmoon.spark_core.physics.toBullet
import com.jme3.bullet.RotationOrder
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.collision.shapes.CollisionShape
import com.jme3.bullet.collision.shapes.CylinderCollisionShape
import com.jme3.bullet.joints.New6Dof
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Matrix3f
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f

/**
 * Pure Bullet assembly for a track run.
 *
 * This object never registers anything with a [cn.solarmoon.spark_core.physics.level.PhysicsLevel].
 * Production code calls it on the physics thread and then registers the result;
 * native tests call it against a bare `PhysicsSpace`, so both paths exercise
 * the same geometry, masks, damping, gravity and hinge construction.
 *
 * Every helper is per-item on purpose: a caller that builds a run can record
 * each object as soon as it exists and roll the partial assembly back when a
 * later step throws.
 */
internal object TrackRunAssembly {
    /**
     * Create one link body. [bodyName] must be unique within the owning host,
     * otherwise the host's name map would silently replace an earlier body.
     */
    fun createBody(
        spec: TrackRunSpec,
        index: Int,
        host: PhysicsHost?,
        bodyName: String,
        onCreated: (PhysicsRigidBody) -> Unit = {},
    ): PhysicsRigidBody {
        val link = spec.links[index]
        val shape = shapeOf(link.shape)
        val body = PhysicsRigidBody(shape, link.massKg)
        // Register ownership before any property can fail, so a partial body is
        // never left outside the rollback ledger.
        onCreated(body)
        return body.apply {
            name = bodyName
            owner = host
            collisionGroup = spec.collisionGroup
            setCollideWithGroups(spec.collideWithGroups)
            collideWithOwnerGroups = spec.collideWithOwnerGroups
            friction = spec.friction
            restitution = spec.restitution
            setDamping(spec.linearDamping, spec.angularDamping)
            setPhysicsLocation(link.position.toBullet())
            setPhysicsRotation(jmeQuaternion(link.rotation))
            setSleepingThresholds(.05f, .05f)
            isContactResponse = true
            spec.explicitGravity?.let { gravity ->
                setProtectGravity(true)
                setGravity(gravity.toBullet())
            }
            if (spec.continuousCollision) {
                val smallest = smallestExtent(link.shape).coerceAtLeast(.02f)
                ccdMotionThreshold = smallest * .5f
                ccdSweptSphereRadius = smallest * .4f
            }
        }
    }

    /** Convenience wrapper used by native tests; production code creates item by item. */
    fun createBodies(spec: TrackRunSpec, host: PhysicsHost?): List<PhysicsRigidBody> =
        spec.links.indices.map { index -> createBody(spec, index, host, bodyName(spec, index)) }

    /** Build one hinge between two already created links. */
    fun createJoint(
        spec: TrackRunSpec,
        joint: TrackRunJointSpec,
        links: List<PhysicsRigidBody>,
        onCreated: (New6Dof) -> Unit = {},
    ): New6Dof = hinge(joint, links, onCreated)

    /** Convenience wrapper used by native tests; production code creates item by item. */
    fun createJoints(spec: TrackRunSpec, links: List<PhysicsRigidBody>): List<New6Dof> =
        spec.joints.map { joint -> createJoint(spec, joint, links) }

    /** Stable per-run body name; [runId] keeps two runs under one owner distinct. */
    fun bodyName(spec: TrackRunSpec, index: Int): String = "${spec.name}_link_$index"

    fun bodyName(runId: Long, spec: TrackRunSpec, index: Int): String = "${spec.name}#$runId" + "_link_$index"

    private fun hinge(
        joint: TrackRunJointSpec,
        links: List<PhysicsRigidBody>,
        onCreated: (New6Dof) -> Unit,
    ): New6Dof {
        val previous = links[joint.previousIndex]
        val next = links[joint.nextIndex]
        val dof = New6Dof(
            previous,
            next,
            joint.pivotInPrevious.toBullet(),
            joint.pivotInNext.toBullet(),
            frameMatrix(joint.frameRotationInPrevious),
            frameMatrix(joint.frameRotationInNext),
            RotationOrder.XYZ,
        )
        // Record the constraint before configuring its rows so a later failure
        // still has an owner that can destroy it.
        onCreated(dof)
        PhysicsJoints.lock(dof, PhysicsJoints.LIN_X)
        PhysicsJoints.lock(dof, PhysicsJoints.LIN_Y)
        PhysicsJoints.lock(dof, PhysicsJoints.LIN_Z)
        for (axis in 0..2) {
            if (axis == joint.axis) {
                PhysicsJoints.limit(dof, PhysicsJoints.ANG_X + axis, -joint.halfAngleRad, joint.halfAngleRad)
            } else {
                PhysicsJoints.lock(dof, PhysicsJoints.ANG_X + axis)
            }
        }
        dof.enableSpring(PhysicsJoints.LIN_X, false)
        dof.enableSpring(PhysicsJoints.LIN_Y, false)
        dof.enableSpring(PhysicsJoints.LIN_Z, false)
        return dof
    }

    /**
     * Constraint-frame basis for one joint end. The rotation is normalized first
     * so a slightly denormalized spec cannot skew the basis.
     */
    internal fun frameMatrix(rotation: TrackRunRotation): Matrix3f =
        frameQuaternion(rotation).toRotationMatrix()

    /** Normalized constraint-frame rotation for one joint end. */
    internal fun frameQuaternion(rotation: TrackRunRotation): Quaternion {
        val unit = rotation.normalized()
        return Quaternion(unit.x, unit.y, unit.z, unit.w)
    }

    fun shapeOf(shape: TrackRunLinkShape): CollisionShape = when (shape) {
        is TrackRunLinkShape.Box -> BoxCollisionShape(shape.halfExtents.toBullet()).apply { margin = .001f }
        // The scalar constructor takes (radius, full height, axis), so the
        // cylinder axis is honoured for X, Y and Z instead of always being Y.
        is TrackRunLinkShape.Cylinder -> CylinderCollisionShape(
            shape.radius,
            shape.halfHeight * 2f,
            shape.axis,
        ).apply { margin = .001f }
    }

    private fun smallestExtent(shape: TrackRunLinkShape): Float = when (shape) {
        is TrackRunLinkShape.Box -> minOf(
            shape.halfExtents.x.toFloat(), shape.halfExtents.y.toFloat(), shape.halfExtents.z.toFloat(),
        ) * 2f
        is TrackRunLinkShape.Cylinder -> minOf(shape.radius, shape.halfHeight) * 2f
    }

    private fun jmeQuaternion(value: org.joml.Quaternionf): Quaternion {
        val unit = normalizedQuaternion(value.x, value.y, value.z, value.w) ?: return Quaternion()
        return Quaternion(unit.x, unit.y, unit.z, unit.w)
    }
}
