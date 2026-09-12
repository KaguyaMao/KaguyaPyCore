package cn.solarmoon.spark_core.physics.body

import cn.solarmoon.spark_core.physics.toMinecraftAabb
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.objects.PhysicsRigidBody
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

data class BodyFrame(
    val position: Vec3,
    val rotation: Quaternionf,
    val boundingBox: AABB,
    val active: Boolean,
    val colliding: Boolean,
    val contactResponse: Boolean,
    val activationState: Int,
    val linearVelocity: Vec3,
    val angularVelocity: Vec3
)

internal fun PhysicsCollisionObject.captureFrame(): BodyFrame {
    val location = getPhysicsLocation(null)
    val rotation = getPhysicsRotation(null)
    val rigid = this as? PhysicsRigidBody
    val linear = rigid?.getLinearVelocity(null)
    val angular = rigid?.getAngularVelocity(null)
    return BodyFrame(
        Vec3(location.x.toDouble(), location.y.toDouble(), location.z.toDouble()),
        Quaternionf(rotation.x, rotation.y, rotation.z, rotation.w),
        boundingBox(null).toMinecraftAabb(),
        isActive,
        isColliding,
        isContactResponse,
        activationState,
        linear?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) } ?: Vec3.ZERO,
        angular?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) } ?: Vec3.ZERO
    )
}
