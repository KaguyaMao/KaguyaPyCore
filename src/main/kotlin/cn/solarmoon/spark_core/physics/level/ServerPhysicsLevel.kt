package cn.solarmoon.spark_core.physics.level

import net.minecraft.server.level.ServerLevel

class ServerPhysicsLevel(override val mcLevel: ServerLevel) : PhysicsLevel("Server PhysicsThread[${mcLevel.dimension().location()}]", mcLevel)
