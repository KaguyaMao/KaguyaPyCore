package cn.solarmoon.spark_core.physics.query

/**
 * Immutable physics-thread terrain query. Coordinates use Spark world metres.
 */
data class TerrainRayQuery(
    val fromX: Float,
    val fromY: Float,
    val fromZ: Float,
    val toX: Float,
    val toY: Float,
    val toZ: Float,
) {
    fun isFinite(): Boolean =
        fromX.isFinite() && fromY.isFinite() && fromZ.isFinite() &&
            toX.isFinite() && toY.isFinite() && toZ.isFinite()
}

/**
 * Detached ray result. It deliberately contains no Bullet or Minecraft object,
 * so callers cannot retain native state beyond the physics callback.
 */
data class TerrainRayHit(
    val hitFraction: Float,
    val pointX: Float,
    val pointY: Float,
    val pointZ: Float,
    val normalX: Float,
    val normalY: Float,
    val normalZ: Float,
    val surfaceVelocityX: Float = 0f,
    val surfaceVelocityY: Float = 0f,
    val surfaceVelocityZ: Float = 0f,
)

internal object TerrainRayMath {
    fun validFraction(fraction: Float): Boolean = fraction.isFinite() && fraction in 0f..1f

    fun shouldReplaceClosest(
        fraction: Float,
        collisionGroup: Int,
        terrainGroup: Int,
        currentClosest: Float,
    ): Boolean = validFraction(fraction) && collisionGroup and terrainGroup != 0 && fraction < currentClosest

    fun interpolate(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction
}

internal object PhysicsThreadGuard {
    fun assertCurrent(expected: Thread?, levelName: String) {
        check(expected != null && Thread.currentThread() === expected) { "Bullet access outside $levelName" }
    }
}
