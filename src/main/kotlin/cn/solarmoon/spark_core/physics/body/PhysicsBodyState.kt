package cn.solarmoon.spark_core.physics.body

import cn.solarmoon.spark_core.physics.toMinecraftAabb
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.objects.PhysicsRigidBody
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Immutable state of one physics body after a step.
 *
 * Rotation is stored as four scalars rather than a shared JOML
 * [Quaternionf]: a published frame must not be mutable through a reference the
 * producer also holds. [rotation] returns a fresh copy on every read.
 *
 * [bodyId] is the stable identity of the body. Consumers that need to look a
 * body up across threads should use this value, not the native object.
 */
data class BodyFrame(
    val bodyId: Long,
    val position: Vec3,
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float,
    val rotationW: Float,
    val boundingBox: AABB,
    val active: Boolean,
    val colliding: Boolean,
    val contactResponse: Boolean,
    val activationState: Int,
    val linearVelocity: Vec3,
    val angularVelocity: Vec3
) {
    /** A defensive copy; mutating the result never changes this frame. */
    val rotation: Quaternionf
        get() = Quaternionf(rotationX, rotationY, rotationZ, rotationW)

    constructor(
        bodyId: Long,
        position: Vec3,
        rotation: Quaternionf,
        boundingBox: AABB,
        active: Boolean,
        colliding: Boolean,
        contactResponse: Boolean,
        activationState: Int,
        linearVelocity: Vec3,
        angularVelocity: Vec3,
    ) : this(
        bodyId,
        position,
        rotation.x,
        rotation.y,
        rotation.z,
        rotation.w,
        boundingBox,
        active,
        colliding,
        contactResponse,
        activationState,
        linearVelocity,
        angularVelocity,
    )
}

internal fun PhysicsCollisionObject.captureFrame(bodyId: Long = this.bodyId): BodyFrame {
    val location = getPhysicsLocation(null)
    val rotation = getPhysicsRotation(null)
    val rigid = this as? PhysicsRigidBody
    // Libbulletjme asserts when velocity is read from a static body; terrain and
    // pinned bodies are static, so only dynamic bodies report a velocity.
    val dynamic = rigid != null && rigid.isDynamic
    val linear = if (dynamic) rigid.getLinearVelocity(null) else null
    val angular = if (dynamic) rigid.getAngularVelocity(null) else null
    return BodyFrame(
        bodyId = bodyId,
        position = Vec3(location.x.toDouble(), location.y.toDouble(), location.z.toDouble()),
        rotationX = rotation.x,
        rotationY = rotation.y,
        rotationZ = rotation.z,
        rotationW = rotation.w,
        boundingBox = boundingBox(null).toMinecraftAabb(),
        active = isActive,
        colliding = isColliding,
        contactResponse = isContactResponse,
        activationState = activationState,
        linearVelocity = linear?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) } ?: Vec3.ZERO,
        angularVelocity = angular?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) } ?: Vec3.ZERO
    )
}
