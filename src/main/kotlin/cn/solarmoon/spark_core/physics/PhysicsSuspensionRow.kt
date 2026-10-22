package cn.solarmoon.spark_core.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One independent suspension station, in SI units.
 *
 * This is the vehicle-agnostic row primitive. It knows nothing about how many
 * wheels a vehicle has, how the load is shared, or which body is a hull: the
 * caller supplies one station's sprung mass and the row's spring, damper and
 * travel. That is deliberate. [PhysicsSuspensionSolver] splits a whole vehicle
 * into two sides, which cannot describe six independent road wheels per side.
 *
 * Sign convention: [SuspensionRowState.travelM] is positive while the wheel is
 * displaced toward the chassis (compression), and [SuspensionRowState.velocityMps]
 * is the rate of that compression. The returned force is **signed along the
 * separation axis**: positive pushes the two bodies apart (support), negative
 * pulls them back together (restoring force in droop). A passive spring-damper
 * row is bilateral; it must recover from both compression and extension.
 */
data class SuspensionRowSpec(
    /** Sprung mass carried by this station, kg. */
    val stationMassKg: Float,
    /** Compression travel available from the bind pose, metres. */
    val travelUpM: Float,
    /** Droop travel available from the bind pose, metres. */
    val travelDownM: Float,
    /** Spring rate, N/m. */
    val stiffnessNPerM: Float,
    /** Damper rate while compressing, N·s/m. */
    val compressionDampingNsPerM: Float,
    /** Damper rate while extending, N·s/m. */
    val reboundDampingNsPerM: Float,
    /**
     * Symmetric magnitude cap for the row, newtons.
     *
     * The force is clamped to `[-maxForceN, +maxForceN]`: the cap is an absolute
     * limit, not a "support only" ceiling. Clamping the negative side to zero
     * would delete the droop restoring force.
     */
    val maxForceN: Float,
    /** Fraction of [travelUpM] where the progressive bump stop starts. */
    val bumpStopStart: Float = 0.85f,
    /** Progressive bump stop exponent; 2 is the usual digressive knee. */
    val bumpStopExponent: Float = 2f,
) {
    val isUsable: Boolean
        get() = stationMassKg.isFinite() && stationMassKg > 0f &&
            travelUpM.isFinite() && travelUpM > 0f &&
            travelDownM.isFinite() && travelDownM > 0f &&
            stiffnessNPerM.isFinite() && stiffnessNPerM > 0f &&
            compressionDampingNsPerM.isFinite() && compressionDampingNsPerM >= 0f &&
            reboundDampingNsPerM.isFinite() && reboundDampingNsPerM >= 0f &&
            maxForceN.isFinite() && maxForceN > 0f &&
            bumpStopStart.isFinite() && bumpStopStart in 0f..1f &&
            bumpStopExponent.isFinite() && bumpStopExponent >= 1f

    /** Travel at which the spring alone balances the station's weight. */
    val staticSagM: Float
        get() = if (!isUsable) 0f else (stationMassKg * GRAVITY / stiffnessNPerM)

    /** Damping rate that would make the row critically damped, N·s/m. */
    val criticalDampingNsPerM: Float
        get() = if (!isUsable) 0f else 2f * sqrt(stiffnessNPerM * stationMassKg)

    /** Undamped natural frequency of the row, Hz. */
    val naturalFrequencyHz: Float
        get() = if (!isUsable) 0f else sqrt(stiffnessNPerM / stationMassKg) / (2f * Math.PI.toFloat())

    /** Where the bump stop begins, metres of compression. */
    val bumpStopTravelM: Float
        get() = travelUpM * bumpStopStart.coerceIn(0f, 0.99f)

    companion object {
        /** Matches the gravity the physics world is expected to use, m/s². */
        const val GRAVITY = 9.81f

        /**
         * Build a row from a target static sag and damping ratios.
         *
         * `k = m g / sag` and `f = sqrt(k / m) / 2π` are the same relation, so
         * this overload exists to make that explicit instead of letting a caller
         * pass a stiffness that disagrees with the sag it expects.
         */
        fun fromSag(
            stationMassKg: Float,
            staticSagM: Float,
            compressionRatio: Float,
            reboundRatio: Float,
            travelUpM: Float,
            travelDownM: Float,
            maxForceN: Float,
            bumpStopStart: Float = 0.85f,
            bumpStopExponent: Float = 2f,
        ): SuspensionRowSpec {
            require(stationMassKg > 0f && stationMassKg.isFinite()) { "station mass must be positive" }
            require(staticSagM > 0f && staticSagM.isFinite()) { "static sag must be positive" }
            require(travelUpM > 0f && travelUpM.isFinite()) { "compression travel must be positive" }
            require(travelDownM > 0f && travelDownM.isFinite()) { "droop travel must be positive" }
            require(maxForceN > 0f && maxForceN.isFinite()) { "force cap must be positive" }
            require(compressionRatio >= 0f && compressionRatio.isFinite()) {
                "compression damping ratio must be finite and non-negative"
            }
            require(reboundRatio >= 0f && reboundRatio.isFinite()) {
                "rebound damping ratio must be finite and non-negative"
            }
            require(bumpStopStart in 0f..1f) { "bump stop start must be a fraction within 0..1" }
            require(bumpStopExponent >= 1f && bumpStopExponent.isFinite()) {
                "bump stop exponent must be at least 1"
            }
            val stiffness = stationMassKg * GRAVITY / staticSagM
            val critical = 2f * sqrt(stiffness * stationMassKg)
            return SuspensionRowSpec(
                stationMassKg = stationMassKg,
                travelUpM = travelUpM,
                travelDownM = travelDownM,
                stiffnessNPerM = stiffness,
                compressionDampingNsPerM = critical * compressionRatio,
                reboundDampingNsPerM = critical * reboundRatio,
                maxForceN = maxForceN,
                bumpStopStart = bumpStopStart,
                bumpStopExponent = bumpStopExponent,
            )
        }
    }
}

/** Instantaneous state of one suspension row. */
data class SuspensionRowState(
    /** Positive while the wheel is displaced toward the chassis, metres. */
    val travelM: Float,
    /** Rate of compression, m/s. */
    val velocityMps: Float,
)

object PhysicsSuspensionRow {

    /**
     * Signed support force for one station, newtons, along the separation axis.
     *
     * `F = k·travel + c·velocity + bump`, where the damper uses the compression
     * rate while compressing and the rebound rate while extending. The result is
     * clamped to `[-maxForceN, +maxForceN]`.
     *
     * The negative side is real: with `travel < 0` the wheel is in droop and the
     * spring must pull it back, and a fast rebound can also drive the total
     * negative. Clamping at zero would silently turn the row into a one-way
     * contact spring and remove the restoring force the axle limit relies on.
     */
    fun force(spec: SuspensionRowSpec, state: SuspensionRowState): Float {
        if (!spec.isUsable) return 0f
        if (!state.travelM.isFinite() || !state.velocityMps.isFinite()) return 0f

        val travel = state.travelM.coerceIn(-spec.travelDownM, spec.travelUpM)
        val spring = spec.stiffnessNPerM * travel
        val damping = if (state.velocityMps >= 0f) {
            spec.compressionDampingNsPerM * state.velocityMps
        } else {
            spec.reboundDampingNsPerM * state.velocityMps
        }
        val bump = bumpStop(spec, travel)
        return (spring + damping + bump).coerceIn(-spec.maxForceN, spec.maxForceN)
    }

    /** Progressive bump stop force; zero until [SuspensionRowSpec.bumpStopTravelM]. */
    fun bumpStop(spec: SuspensionRowSpec, travelM: Float): Float {
        if (!spec.isUsable) return 0f
        val start = spec.bumpStopTravelM
        if (travelM <= start) return 0f
        val span = (spec.travelUpM - start).coerceAtLeast(1.0e-4f)
        val t = ((travelM - start) / span).coerceIn(0f, 1f)
        // Sized so the spring plus the fully engaged bump stop lands on the cap.
        val headroom = max(0f, spec.maxForceN - spec.stiffnessNPerM * spec.travelUpM)
        return headroom * t.pow(max(spec.bumpStopExponent, 1f))
    }

    /**
     * Travel the row settles at under its own station weight, given no motion.
     *
     * Returns the same value as [SuspensionRowSpec.staticSagM]; it exists so a
     * test can assert the closed form against a simulated settle without
     * re-deriving the relation.
     */
    fun equilibriumTravelM(spec: SuspensionRowSpec): Float = spec.staticSagM

    /**
     * Energy the damper absorbs over one full cycle of a sinusoidal input, joules.
     *
     * The spring term is conservative, so its net work over a closed cycle is
     * zero and the remaining `∫F·v dt` is the damper's dissipation. It is
     * computed through [force], so the wiring is what is measured, not a
     * restated formula. Callers must keep the amplitude inside the force cap;
     * clamping is a saturating nonlinearity and would stop the integral from
     * being the pure damper term.
     */
    fun dissipatedEnergyJ(
        spec: SuspensionRowSpec,
        amplitudeM: Float,
        frequencyHz: Float,
        samples: Int = 512,
    ): Float {
        if (!spec.isUsable || amplitudeM <= 0f || !amplitudeM.isFinite()) return 0f
        if (frequencyHz <= 0f || !frequencyHz.isFinite()) return 0f
        val omega = 2.0 * Math.PI * frequencyHz
        val dt = 1.0 / (frequencyHz * samples)
        var total = 0.0
        for (index in 0 until samples) {
            val t = index * dt
            val travel = (amplitudeM * kotlin.math.sin(omega * t)).toFloat()
            val velocity = (amplitudeM * omega * kotlin.math.cos(omega * t)).toFloat()
            val force = force(spec, SuspensionRowState(travel, velocity))
            // Work done *on the row*: a force opposing motion absorbs energy.
            total += force * velocity * dt
        }
        return total.toFloat()
    }

    /**
     * Smallest and largest travel reachable by a row under an external push.
     *
     * The physics joint enforces the same window; this is the value a data audit
     * checks before any body is created.
     */
    fun travelWindow(spec: SuspensionRowSpec): ClosedFloatingPointRange<Float> =
        -spec.travelDownM..spec.travelUpM

    /** True when the static sag leaves the row off its bump stop. */
    fun staticSagClearsBumpStop(spec: SuspensionRowSpec): Boolean =
        spec.isUsable && spec.staticSagM < spec.bumpStopTravelM

    /** True when the static sag fits inside the available compression travel. */
    fun staticSagFitsTravel(spec: SuspensionRowSpec): Boolean =
        spec.isUsable && spec.staticSagM <= spec.travelUpM

    /** Static support force, newtons. */
    fun staticLoadN(spec: SuspensionRowSpec): Float =
        if (!spec.isUsable) 0f else spec.stationMassKg * SuspensionRowSpec.GRAVITY

    /** Headroom between the static load and the declared force cap. */
    fun staticLoadMargin(spec: SuspensionRowSpec): Float {
        val load = staticLoadN(spec)
        if (load <= 0f) return 0f
        return spec.maxForceN / load
    }

    /** Clamp a raw travel reading into the row's window. */
    fun clampTravel(spec: SuspensionRowSpec, travelM: Float): Float =
        min(max(travelM, -spec.travelDownM), spec.travelUpM)

    /**
     * Force at full droop, newtons.
     *
     * Negative by construction: this is the pull that returns the wheel from its
     * droop limit. A row that reports zero here cannot recover.
     */
    fun droopRestoringForceN(spec: SuspensionRowSpec): Float =
        force(spec, SuspensionRowState(-spec.travelDownM, 0f))

    /** Absolute magnitude the row can produce in either direction, newtons. */
    fun forceCapN(spec: SuspensionRowSpec): Float = spec.maxForceN

    /** True when the row actually produces a restoring force at full droop. */
    fun hasBilateralRecovery(spec: SuspensionRowSpec): Boolean =
        spec.isUsable && droopRestoringForceN(spec) < 0f

    /** True when both clamp directions are the same declared magnitude. */
    fun hasSymmetricForceCap(spec: SuspensionRowSpec): Boolean {
        if (!spec.isUsable) return false
        val compressed = force(spec, SuspensionRowState(spec.travelUpM, spec.maxForceN))
        val extended = force(spec, SuspensionRowState(-spec.travelDownM, -spec.maxForceN))
        return abs(abs(compressed) - spec.maxForceN) < 1.0e-3f &&
            abs(abs(extended) - spec.maxForceN) < 1.0e-3f
    }
}
