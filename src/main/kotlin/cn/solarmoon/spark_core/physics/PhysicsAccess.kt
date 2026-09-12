@file:JvmName("PhysicsAccess")

package cn.solarmoon.spark_core.physics

import cn.solarmoon.spark_core.physics.level.PhysicsLevel
import com.jme3.bullet.collision.PhysicsCollisionObject
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level

val Level.physicsLevel: PhysicsLevel
    get() = SparkPhysics.get(this)

val Entity.physicsLevel: PhysicsLevel
    get() = SparkPhysics.get(level())

val Entity.allPhysicsBodies: MutableMap<String, PhysicsCollisionObject>
    get() = SparkPhysics.host(this).allPhysicsBodies
