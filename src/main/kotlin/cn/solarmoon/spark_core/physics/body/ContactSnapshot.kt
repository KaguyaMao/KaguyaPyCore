package cn.solarmoon.spark_core.physics.body

import com.jme3.bullet.collision.ManifoldPoints
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import net.minecraft.world.phys.Vec3

/**
 * Detached values only: a native manifold point is valid only inside its
 * callback.
 *
 * [selfId] and [otherId] are stable Spark body ids, so a consumer can associate
 * the sample after leaving the physics thread without retaining a native
 * pointer. [physicsFrame] is the substep counter the sample belongs to.
 * [impulse] is the total applied impulse (normal plus both friction
 * directions), in N*s, expressed as the reaction on [selfId].
 */
data class ContactSnapshot(
    val point: Vec3,
    val normal: Vec3,
    val impulse: Vec3,
    val closingSpeed: Double,
    val normalImpulse: Float,
    val selfId: Long = 0L,
    val otherId: Long = 0L,
    val physicsFrame: Long = 0L,
)

internal fun snapshotContact(
    id: Long,
    index: Int,
    self: PhysicsCollisionObject,
    other: PhysicsCollisionObject,
    physicsFrame: Long = 0L,
): ContactSnapshot {
    val p = Vector3f()
    if (index == 0) ManifoldPoints.getPositionWorldOnA(id, p) else ManifoldPoints.getPositionWorldOnB(id, p)
    // Bullet's normal points from B to A; this is the reaction on self.
    val sign = if (index == 0) 1f else -1f
    val n = Vector3f().also { ManifoldPoints.getNormalWorldOnB(id, it) }.multLocal(sign)
    val j = ManifoldPoints.getAppliedImpulse(id)
    val impulse = n.mult(j)
    val lateral = Vector3f()
    ManifoldPoints.getLateralFrictionDir1(id, lateral)
    impulse.addLocal(lateral.mult(sign * ManifoldPoints.getAppliedImpulseLateral1(id)))
    ManifoldPoints.getLateralFrictionDir2(id, lateral)
    impulse.addLocal(lateral.mult(sign * ManifoldPoints.getAppliedImpulseLateral2(id)))
    fun velocity(body: PhysicsCollisionObject): Vector3f {
        if (body !is PhysicsRigidBody) return Vector3f()
        // Static bodies (terrain) have no velocity, and Libbulletjme asserts if
        // the getter is called on them.
        if (!body.isDynamic) return Vector3f()
        return body.getLinearVelocity(null).addLocal(body.getAngularVelocity(null).cross(p.subtract(body.getPhysicsLocation(null))))
    }
    fun Vector3f.vec() = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
    // Closing speed is positive when the bodies approach along n:
    // n points from other to self, so (vOther - vSelf) . n > 0 means closing.
    val relative = velocity(other).subtractLocal(velocity(self))
    return ContactSnapshot(
        point = p.vec(),
        normal = n.vec(),
        impulse = impulse.vec(),
        closingSpeed = relative.dot(n).toDouble().coerceAtLeast(0.0),
        normalImpulse = j,
        selfId = self.bodyId,
        otherId = other.bodyId,
        physicsFrame = physicsFrame,
    )
}
