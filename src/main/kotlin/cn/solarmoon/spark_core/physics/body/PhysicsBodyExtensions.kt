package cn.solarmoon.spark_core.physics.body

import cn.solarmoon.spark_core.physics.physicsLevel
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.bullet.objects.PhysicsGhostObject
import com.jme3.bullet.objects.PhysicsCharacter
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level

fun Level.addPhysicsBody(body: PhysicsCollisionObject) = physicsLevel.addBody(body)
fun Level.removePhysicsBody(body: PhysicsCollisionObject) = physicsLevel.removeBody(body)

fun PhysicsRigidBody.attachToEntity(entity: Entity) {
    attachedEntity = entity
}

fun PhysicsRigidBody.detach() {
    attachedEntity = null
}

internal fun PhysicsCollisionObject.setSparkLocation(location: Vector3f) {
    when (this) {
        is PhysicsRigidBody -> setPhysicsLocation(location)
        is PhysicsGhostObject -> setPhysicsLocation(location)
        is PhysicsCharacter -> setPhysicsLocation(location)
    }
}

internal fun PhysicsCollisionObject.setSparkRotation(rotation: Quaternion) {
    when (this) {
        is PhysicsRigidBody -> setPhysicsRotation(rotation)
        is PhysicsGhostObject -> setPhysicsRotation(rotation)
    }
}

internal fun PhysicsCollisionObject.setSparkScale(scale: Vector3f) {
    if (this is PhysicsRigidBody) setPhysicsScale(scale)
}

internal fun PhysicsCollisionObject.setSparkContactResponse(enabled: Boolean) {
    if (this is PhysicsRigidBody) setContactResponse(enabled)
}

fun PhysicsCollisionObject.onTick(handler: (PhysicsBodyEvent.Tick) -> Unit) = onBodyEvent(handler)
fun PhysicsCollisionObject.onPrePhysicsTick(handler: (PhysicsBodyEvent.PhysicsTick.Pre) -> Unit) = onBodyEvent(handler)
fun PhysicsCollisionObject.onPostPhysicsTick(handler: (PhysicsBodyEvent.PhysicsTick.Post) -> Unit) = onBodyEvent(handler)
fun PhysicsCollisionObject.onCollideProcessed(handler: (PhysicsBodyEvent.Contact) -> Unit) = onBodyEvent(handler)
