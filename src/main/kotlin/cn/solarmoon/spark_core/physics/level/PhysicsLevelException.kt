package cn.solarmoon.spark_core.physics.level

/**
 * Thrown when work is submitted to a [PhysicsLevel] that is no longer accepting
 * native mutation tasks.
 *
 * A rejected task is never silently dropped when the caller holds a
 * [java.util.concurrent.CompletableFuture]: the future fails with this
 * exception so shutdown races surface as an explicit error instead of a hang.
 */
class PhysicsLevelNotRunningException(
    val levelName: String,
    val levelState: PhysicsLevelState,
    message: String = "PhysicsLevel '$levelName' is $levelState and is not accepting tasks",
) : IllegalStateException(message)
