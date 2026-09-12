package cn.solarmoon.spark_core.physics.level

import cn.solarmoon.spark_core.physics.body.BodyFrame
import com.jme3.bullet.collision.PhysicsCollisionObject
import net.minecraft.world.phys.AABB

data class PhysicsFrameSnapshot(
    val tick: Long,
    val bodies: Map<PhysicsCollisionObject, BodyFrame>,
    val terrainActivationBoxes: List<AABB>,
    val stepNanos: Long,
    val completedAtNanos: Long
) {
    companion object {
        val EMPTY = PhysicsFrameSnapshot(0, emptyMap(), emptyList(), 0, System.nanoTime())
    }
}
