package cn.solarmoon.spark_core.physics.body

import com.jme3.bullet.collision.ManifoldPoints
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import net.minecraft.world.phys.Vec3

/** Detached values only: a native manifold point is valid only inside its callback. */
data class ContactSnapshot(
    val point: Vec3,
    val normal: Vec3,
    val impulse: Vec3,
    val closingSpeed: Double,
    val normalImpulse: Float,
)

internal fun snapshotContact(id: Long, index: Int, self: PhysicsCollisionObject, other: PhysicsCollisionObject): ContactSnapshot {
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
        return body.getLinearVelocity(null).addLocal(body.getAngularVelocity(null).cross(p.subtract(body.getPhysicsLocation(null))))
    }
    fun Vector3f.vec() = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
    return ContactSnapshot(p.vec(), n.vec(), impulse.vec(), (-velocity(self).subtract(velocity(other)).dot(n)).toDouble().coerceAtLeast(0.0), j)
}
