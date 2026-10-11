package cn.solarmoon.spark_core.physics

/**
 * Immutable vehicle data exchanged between the game thread and Spark's
 * physics worker.  The API deliberately contains values only; no Bullet or
 * Minecraft object is allowed to cross the thread boundary.
 */
enum class VehicleControlMode {
    GROUND_WHEEL,
    GROUND_TRACK,
    FLIGHT,
    PASSIVE,
}

data class VehicleControlCommand(
    val frame: Long = 0L,
    val mode: VehicleControlMode = VehicleControlMode.PASSIVE,
    val linearAccelerationX: Double = 0.0,
    val linearAccelerationY: Double = 0.0,
    val linearAccelerationZ: Double = 0.0,
    val angularVelocityX: Double = 0.0,
    val angularVelocityY: Double = 0.0,
    val angularVelocityZ: Double = 0.0,
    val drive: Boolean = false,
    val groundAssist: Boolean = false,
) {
    fun isFinite(): Boolean = listOf(
        linearAccelerationX, linearAccelerationY, linearAccelerationZ,
        angularVelocityX, angularVelocityY, angularVelocityZ,
    ).all(Double::isFinite)
}

data class VehiclePhysicsFrame(
    val frame: Long,
    val positionX: Double,
    val positionY: Double,
    val positionZ: Double,
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float,
    val rotationW: Float,
    val velocityX: Double,
    val velocityY: Double,
    val velocityZ: Double,
    val angularVelocityX: Double,
    val angularVelocityY: Double,
    val angularVelocityZ: Double,
    val grounded: Boolean,
)
