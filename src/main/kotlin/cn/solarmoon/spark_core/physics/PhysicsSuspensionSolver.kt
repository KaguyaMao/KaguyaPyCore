package cn.solarmoon.spark_core.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** A value-only two-side suspension solver shared by vehicle integrations. */
data class SideSuspensionInput(
    val contact: Boolean,
    val travelM: Float,
    /** Positive while the suspension is moving into the chassis. */
    val compressionVelocityMps: Float,
    val supportUpDot: Float,
)

data class SideSuspensionOutput(
    val forceN: Float,
    val compressionM: Float,
    val grounded: Boolean,
)

object PhysicsSuspensionSolver {
    fun solve(
        input: SideSuspensionInput,
        massKg: Float,
        gravity: Float,
        travelUpM: Float,
        travelDownM: Float,
        frequencyHz: Float,
        compressionDamping: Float,
        reboundDamping: Float,
        maxForceN: Float,
    ): SideSuspensionOutput {
        if (!input.contact || !input.travelM.isFinite() || !input.compressionVelocityMps.isFinite() ||
            !massKg.isFinite() || massKg <= 0f || !gravity.isFinite() || gravity <= 0f
        ) return SideSuspensionOutput(0f, 0f, false)

        val up = travelUpM.coerceAtLeast(0.001f)
        val down = travelDownM.coerceAtLeast(0.001f)
        // The sweep travel is measured from the chassis anchor along its up
        // axis.  Positive travel means the contact is above the anchor and the
        // suspension is compressed; negative travel is droop.  The previous
        // implementation inverted this relation and continuously over-loaded
        // vehicles resting on a flat surface.
        val travel = input.travelM.coerceIn(-down, up)
        val compression = travel.coerceAtLeast(0f)
        val sideMass = massKg * .5f
        val omega = 2f * Math.PI.toFloat() * frequencyHz.coerceAtLeast(.1f)
        val stiffness = sideMass * omega * omega
        val criticalDamping = 2f * sqrt((stiffness * sideMass).coerceAtLeast(0f))
        val damping = if (input.compressionVelocityMps >= 0f) {
            max(compressionDamping.coerceAtLeast(0f), criticalDamping * .9f)
        } else {
            max(reboundDamping.coerceAtLeast(0f), criticalDamping * .6f)
        }
        val bumpStart = up * .85f
        val bump = if (compression > bumpStart) {
            val t = ((compression - bumpStart) / (up - bumpStart).coerceAtLeast(.001f)).coerceIn(0f, 1f)
            maxForceN.coerceAtLeast(sideMass * gravity) * t * t
        } else 0f
        val preload = sideMass * gravity
        val force = (preload + stiffness * travel + damping * input.compressionVelocityMps + bump)
            .coerceIn(0f, maxForceN.coerceAtLeast(sideMass * gravity * 2f))
        return SideSuspensionOutput(force, compression, input.supportUpDot >= .25f)
    }

    fun antiRoll(
        leftCompressionM: Float,
        rightCompressionM: Float,
        stiffnessNPerM: Float,
        fraction: Float,
        maxCorrectionN: Float,
    ): Pair<Float, Float> {
        val correction = ((leftCompressionM - rightCompressionM) * stiffnessNPerM * fraction)
            .coerceIn(-abs(maxCorrectionN), abs(maxCorrectionN))
        return correction to -correction
    }
}
