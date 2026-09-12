package cn.solarmoon.spark_core.event

import cn.solarmoon.spark_core.physics.body.ManifoldPoint
import cn.solarmoon.spark_core.physics.level.PhysicsLevel
import com.jme3.bullet.collision.PhysicsCollisionObject
import net.minecraft.world.entity.Entity
import net.minecraftforge.event.entity.EntityEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.Event

class NeedsCollisionEvent(
    val bodyA: PhysicsCollisionObject,
    val bodyB: PhysicsCollisionObject,
    val originalResult: Boolean
) : Event() {
    var shouldCollide: Boolean = originalResult
}

class PhysicsContactEvent(
    val bodyA: PhysicsCollisionObject,
    val bodyB: PhysicsCollisionObject,
    val pointA: ManifoldPoint,
    val pointB: ManifoldPoint,
    val manifoldId: Long
) : Event()

class PhysicsEntityTickEvent(entity: Entity) : EntityEvent(entity)
class PhysicsLevelInitEvent(val physicsLevel: PhysicsLevel) : LevelEvent(physicsLevel.mcLevel)

sealed class PhysicsLevelTickEvent(val physicsLevel: PhysicsLevel) : LevelEvent(physicsLevel.mcLevel) {
    class Pre(level: PhysicsLevel) : PhysicsLevelTickEvent(level)
    class Post(level: PhysicsLevel) : PhysicsLevelTickEvent(level)
}
