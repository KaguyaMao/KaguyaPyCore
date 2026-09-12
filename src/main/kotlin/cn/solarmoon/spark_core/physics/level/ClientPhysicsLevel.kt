package cn.solarmoon.spark_core.physics.level

import net.minecraft.world.level.Level

class ClientPhysicsLevel(override val mcLevel: Level) : PhysicsLevel("Client PhysicsThread", mcLevel) {
    val partialTicks: Float
        get() = ((System.nanoTime() - latestFrame.completedAtNanos) / 1_000_000_000f * 20f).coerceIn(0f, 1f)
}
