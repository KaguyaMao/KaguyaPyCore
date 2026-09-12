package cn.solarmoon.spark_core.physics

import cn.solarmoon.spark_core.physics.level.PhysicsLevel
import com.jme3.bullet.collision.PhysicsCollisionObject

interface PhysicsHost {
    val physicsLevel: PhysicsLevel
    val allPhysicsBodies: MutableMap<String, PhysicsCollisionObject>

    fun getPhysicsBody(name: String): PhysicsCollisionObject? = allPhysicsBodies[name]
}
