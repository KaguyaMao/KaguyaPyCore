package cn.solarmoon.spark_core.physics.body

import cn.solarmoon.spark_core.physics.PhysicsHost
import com.jme3.bullet.collision.PhysicsCollisionObject

sealed interface PhysicsBodyEvent {
    data class OwnerChanged(val oldOwner: PhysicsHost?, var newOwner: PhysicsHost?) : PhysicsBodyEvent
    data object AddedToWorld : PhysicsBodyEvent
    data object RemovedFromWorld : PhysicsBodyEvent
    data object Tick : PhysicsBodyEvent
    sealed interface PhysicsTick : PhysicsBodyEvent {
        data object Pre : PhysicsTick
        data object Post : PhysicsTick
    }
    data class Contact(
        val self: PhysicsCollisionObject,
        val other: PhysicsCollisionObject,
        val selfPoint: ManifoldPoint,
        val otherPoint: ManifoldPoint,
        val snapshot: ContactSnapshot
    ) : PhysicsBodyEvent
    /** Contact response sampled after Bullet has solved the current substep. */
    data class ResolvedContact(
        val self: PhysicsCollisionObject,
        val other: PhysicsCollisionObject,
        val snapshot: ContactSnapshot
    ) : PhysicsBodyEvent
}
