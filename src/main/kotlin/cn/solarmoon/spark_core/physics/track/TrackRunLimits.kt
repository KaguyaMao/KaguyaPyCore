package cn.solarmoon.spark_core.physics.track

import net.minecraft.world.phys.Vec3
import kotlin.math.min

/**
 * Input clamping shared by the runtime and its tests.
 *
 * The caller owns the vehicle-level budget; the core only guarantees that a
 * single call cannot exceed the finite cap declared in [TrackRunSpec].
 */
internal object TrackRunLimits {
    fun clampForce(force: Vec3, maxNewtons: Float): Vec3 {
        val length = force.length()
        if (!length.isFinite() || length < 1.0e-9) return Vec3.ZERO
        val scale = min(1.0, maxNewtons.toDouble() / length)
        return force.scale(scale)
    }

    fun clampTorqueImpulse(magnitude: Float, maxNewtonMetreSeconds: Float): Float {
        if (!magnitude.isFinite() || magnitude == 0f) return 0f
        return magnitude.coerceIn(-maxNewtonMetreSeconds, maxNewtonMetreSeconds)
    }
}
