package cn.solarmoon.spark_core.physics.track

import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** True when every component is finite. Local to the track package so it does
 * not depend on a downstream convenience extension. */
internal fun Vec3.isFiniteVec(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

/**
 * True when a double can be narrowed to a Bullet float without becoming
 * infinity. Bullet stores positions, shapes and forces as floats, so a finite
 * double is not sufficient input validation.
 */
internal fun Double.isFiniteFloat(): Boolean = isFinite() && abs(this) <= Float.MAX_VALUE.toDouble()

internal fun Vec3.isFiniteFloatVec(): Boolean = x.isFiniteFloat() && y.isFiniteFloat() && z.isFiniteFloat()

/** Normalize a quaternion using double arithmetic so large finite components do not overflow. */
internal fun normalizedQuaternion(x: Float, y: Float, z: Float, w: Float): TrackRunRotation? {
    if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !w.isFinite()) return null
    val lengthSquared = x.toDouble() * x + y.toDouble() * y + z.toDouble() * z + w.toDouble() * w
    if (!lengthSquared.isFinite() || lengthSquared < 1.0e-8) return null
    val inv = (1.0 / kotlin.math.sqrt(lengthSquared)).toFloat()
    return TrackRunRotation(x * inv, y * inv, z * inv, w * inv)
}
