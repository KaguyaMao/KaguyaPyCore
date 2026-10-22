package cn.solarmoon.spark_core.physics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Per-station suspension contract.
 *
 * These are value tests: no world, no body. They pin the row's units, its
 * bilateral sign convention and the relations a vehicle audit depends on, so a
 * wrong spring, a damper wired to the rebound rate while compressing, or a
 * one-way clamp that deletes the droop force fails here rather than on a test
 * drive.
 */
class PhysicsSuspensionRowTest {

    private fun row(
        stationMassKg: Float = 3313f,
        travelUpM: Float = 0.18f,
        travelDownM: Float = 0.24f,
        stiffnessNPerM: Float = 712_000f,
        compression: Float = 83_000f,
        rebound: Float = 57_000f,
        maxForceN: Float = 400_000f,
    ) = SuspensionRowSpec(
        stationMassKg = stationMassKg,
        travelUpM = travelUpM,
        travelDownM = travelDownM,
        stiffnessNPerM = stiffnessNPerM,
        compressionDampingNsPerM = compression,
        reboundDampingNsPerM = rebound,
        maxForceN = maxForceN,
    )

    @Test
    fun anUnusableRowProducesNoSupport() {
        assertEquals(0f, PhysicsSuspensionRow.force(row(stationMassKg = 0f), SuspensionRowState(0.1f, 0f)))
        assertEquals(0f, PhysicsSuspensionRow.force(row(stiffnessNPerM = Float.NaN), SuspensionRowState(0.1f, 0f)))
        assertEquals(0f, PhysicsSuspensionRow.force(row(), SuspensionRowState(Float.NaN, 0f)))
        assertEquals(0f, PhysicsSuspensionRow.force(row(), SuspensionRowState(0.1f, Float.POSITIVE_INFINITY)))
        // A malformed bump stop must invalidate the row rather than silently use a default.
        assertEquals(0f, PhysicsSuspensionRow.force(row().copy(bumpStopStart = 1.5f), SuspensionRowState(0.1f, 0f)))
        assertEquals(0f, PhysicsSuspensionRow.force(row().copy(bumpStopExponent = 0.5f), SuspensionRowState(0.1f, 0f)))
    }

    @Test
    fun staticSagBalancesTheStationWeight() {
        val spec = row()
        val sag = spec.staticSagM
        val force = PhysicsSuspensionRow.force(spec, SuspensionRowState(sag, 0f))
        assertEquals(spec.stationMassKg * SuspensionRowSpec.GRAVITY, force, 1f)
    }

    @Test
    fun stiffnessFollowsTheSagRelation() {
        val spec = SuspensionRowSpec.fromSag(
            stationMassKg = 3313f,
            staticSagM = 0.0456f,
            compressionRatio = 0.65f,
            reboundRatio = 0.45f,
            travelUpM = 0.18f,
            travelDownM = 0.24f,
            maxForceN = 400_000f,
        )
        assertEquals(3313f * 9.81f / 0.0456f, spec.stiffnessNPerM, 1f)
        assertEquals(0.0456f, spec.staticSagM, 1e-5f)
        assertEquals(2f * kotlin.math.sqrt(spec.stiffnessNPerM * 3313f) * 0.65f,
            spec.compressionDampingNsPerM, 1f)
        assertEquals(2f * kotlin.math.sqrt(spec.stiffnessNPerM * 3313f) * 0.45f,
            spec.reboundDampingNsPerM, 1f)
        // The natural frequency implied by k and m is the same as the sag's.
        val omega = kotlin.math.sqrt(9.81f / 0.0456f)
        assertEquals(omega / (2f * Math.PI.toFloat()), spec.naturalFrequencyHz, 1e-4f)
    }

    @Test
    fun compressionAndReboundUseTheirOwnRates() {
        val spec = row()
        // Deep enough that the spring can still carry the rebound term, so the
        // clamp does not mask which rate was used.
        val travel = 0.10f
        val compressing = PhysicsSuspensionRow.force(spec, SuspensionRowState(travel, 1f))
        val extending = PhysicsSuspensionRow.force(spec, SuspensionRowState(travel, -1f))
        val springOnly = spec.stiffnessNPerM * travel

        assertEquals(springOnly + spec.compressionDampingNsPerM, compressing, 1f)
        assertEquals(springOnly - spec.reboundDampingNsPerM, extending, 1f)
        assertTrue(compressing > extending, "compression must resist harder than rebound for these rates")
    }

    /**
     * The row is bilateral. In droop the spring pulls the wheel back toward the
     * bind pose; a one-way clamp would report zero and the wheel would simply
     * hang on the joint limit.
     */
    @Test
    fun negativeTravelProducesARestoringForce() {
        val spec = row()
        for (fraction in listOf(0.25f, 0.5f, 1.0f)) {
            val travel = -spec.travelDownM * fraction
            val force = PhysicsSuspensionRow.force(spec, SuspensionRowState(travel, 0f))
            assertEquals(spec.stiffnessNPerM * travel, force, 1f, "droop force at $travel")
            assertTrue(force < 0f, "droop must pull, got $force at $travel")
        }
        assertTrue(PhysicsSuspensionRow.hasBilateralRecovery(spec))
        assertTrue(PhysicsSuspensionRow.droopRestoringForceN(spec) < 0f)
    }

    /** Fast rebound must still apply the rebound rate, not collapse to zero. */
    @Test
    fun fastReboundKeepsTheReboundDamping() {
        val spec = row()
        // Shallow travel, fast extension: the damper term dominates the spring.
        val travel = 0.02f
        val velocity = -1f
        val force = PhysicsSuspensionRow.force(spec, SuspensionRowState(travel, velocity))
        val expected = spec.stiffnessNPerM * travel + spec.reboundDampingNsPerM * velocity
        assertEquals(expected, force, 1f)
        assertTrue(force < 0f, "a fast rebound must pull, got $force")
    }

    /** Both clamp directions use the same declared magnitude. */
    @Test
    fun theForceCapIsSymmetricAndAbsolute() {
        val spec = row(maxForceN = 50_000f)
        val compressed = PhysicsSuspensionRow.force(spec, SuspensionRowState(spec.travelUpM, 100f))
        val extended = PhysicsSuspensionRow.force(spec, SuspensionRowState(-spec.travelDownM, -100f))
        assertEquals(spec.maxForceN, compressed, 1e-3f)
        assertEquals(-spec.maxForceN, extended, 1e-3f)
        assertTrue(PhysicsSuspensionRow.hasSymmetricForceCap(spec))
        assertEquals(spec.maxForceN, PhysicsSuspensionRow.forceCapN(spec), 1e-3f)
    }

    @Test
    fun travelIsClampedToTheDeclaredWindow() {
        val spec = row()
        val window = PhysicsSuspensionRow.travelWindow(spec)
        assertEquals(-spec.travelDownM, window.start)
        assertEquals(spec.travelUpM, window.endInclusive)
        assertEquals(spec.travelUpM, PhysicsSuspensionRow.clampTravel(spec, 10f))
        assertEquals(-spec.travelDownM, PhysicsSuspensionRow.clampTravel(spec, -10f))

        // Beyond the window the force saturates instead of growing without bound.
        val atLimit = PhysicsSuspensionRow.force(spec, SuspensionRowState(spec.travelUpM, 0f))
        val beyond = PhysicsSuspensionRow.force(spec, SuspensionRowState(spec.travelUpM * 3f, 0f))
        assertEquals(atLimit, beyond, 1e-3f)
        val atDroop = PhysicsSuspensionRow.force(spec, SuspensionRowState(-spec.travelDownM, 0f))
        val beyondDroop = PhysicsSuspensionRow.force(spec, SuspensionRowState(-spec.travelDownM * 3f, 0f))
        assertEquals(atDroop, beyondDroop, 1e-3f)
    }

    @Test
    fun theBumpStopEngagesOnlyNearFullCompression() {
        val spec = row()
        assertEquals(0f, PhysicsSuspensionRow.bumpStop(spec, spec.bumpStopTravelM - 0.001f))
        assertTrue(PhysicsSuspensionRow.bumpStop(spec, spec.travelUpM) > 0f)
        // Compression only: a droop position must never engage it.
        assertEquals(0f, PhysicsSuspensionRow.bumpStop(spec, -spec.travelDownM))

        // Monotone from the knee to full travel.
        var previous = 0f
        for (step in 0..10) {
            val travel = spec.bumpStopTravelM + (spec.travelUpM - spec.bumpStopTravelM) * step / 10f
            val value = PhysicsSuspensionRow.bumpStop(spec, travel)
            assertTrue(value >= previous, "bump stop must not decrease with compression")
            previous = value
        }
    }

    @Test
    fun theBumpStopShapeComesFromTheSpec() {
        val soft = row().copy(bumpStopStart = 0.5f, bumpStopExponent = 1f)
        val late = row().copy(bumpStopStart = 0.95f, bumpStopExponent = 3f)
        assertEquals(soft.travelUpM * 0.5f, soft.bumpStopTravelM, 1e-6f)
        assertEquals(late.travelUpM * 0.95f, late.bumpStopTravelM, 1e-6f)
        // The same travel engages the early knee and not the late one.
        val probe = soft.travelUpM * 0.6f
        assertTrue(PhysicsSuspensionRow.bumpStop(soft, probe) > 0f)
        assertEquals(0f, PhysicsSuspensionRow.bumpStop(late, probe))
        // A higher exponent is gentler near the knee.
        val nearKnee = soft.bumpStopTravelM + 0.005f
        assertTrue(PhysicsSuspensionRow.bumpStop(soft, nearKnee) >
            PhysicsSuspensionRow.bumpStop(late.copy(bumpStopStart = 0.5f), nearKnee))
    }

    @Test
    fun aWellFormedStationSitsOffItsBumpStopAtRest() {
        val spec = SuspensionRowSpec.fromSag(
            stationMassKg = 3313f,
            staticSagM = 0.0456f,
            compressionRatio = 0.65f,
            reboundRatio = 0.45f,
            travelUpM = 0.18f,
            travelDownM = 0.24f,
            maxForceN = 400_000f,
        )
        assertTrue(PhysicsSuspensionRow.staticSagFitsTravel(spec))
        assertTrue(PhysicsSuspensionRow.staticSagClearsBumpStop(spec))
        assertTrue(PhysicsSuspensionRow.staticLoadMargin(spec) > 1f)
        assertEquals(3313f * 9.81f, PhysicsSuspensionRow.staticLoadN(spec), 1f)
    }

    @Test
    fun fromSagRejectsImpossibleRows() {
        val invalid = listOf(
            runCatching { SuspensionRowSpec.fromSag(0f, 0.05f, 0.6f, 0.4f, 0.18f, 0.24f, 400_000f) },
            runCatching { SuspensionRowSpec.fromSag(1000f, 0f, 0.6f, 0.4f, 0.18f, 0.24f, 400_000f) },
            runCatching { SuspensionRowSpec.fromSag(1000f, 0.05f, 0.6f, 0.4f, 0f, 0.24f, 400_000f) },
            runCatching { SuspensionRowSpec.fromSag(1000f, 0.05f, 0.6f, 0.4f, 0.18f, 0f, 400_000f) },
            runCatching { SuspensionRowSpec.fromSag(1000f, 0.05f, 0.6f, 0.4f, 0.18f, 0.24f, 0f) },
            runCatching { SuspensionRowSpec.fromSag(1000f, 0.05f, -0.1f, 0.4f, 0.18f, 0.24f, 400_000f) },
            runCatching { SuspensionRowSpec.fromSag(1000f, 0.05f, 0.6f, 0.4f, 0.18f, 0.24f, 400_000f, 2f) },
        )
        invalid.forEach { assertTrue(it.isFailure, "must reject the invalid row") }
    }

    /**
     * The spring is conservative over a closed cycle, so the residual work is the
     * damper's. A bilateral row must still absorb energy; a softer rebound rate
     * must absorb less.
     */
    @Test
    fun theDamperDissipatesEnergyAndReboundRateChangesIt() {
        val spec = row()
        val fast = PhysicsSuspensionRow.dissipatedEnergyJ(spec, amplitudeM = 0.03f, frequencyHz = 1.5f)
        assertTrue(fast > 0f, "a damper must dissipate energy")

        val softer = spec.copy(reboundDampingNsPerM = spec.reboundDampingNsPerM * 0.25f)
        val soft = PhysicsSuspensionRow.dissipatedEnergyJ(softer, amplitudeM = 0.03f, frequencyHz = 1.5f)
        assertTrue(soft < fast, "a softer rebound rate must dissipate less")
        assertTrue(abs(soft - fast) > 1f)

        // The spring alone must contribute nothing over a closed cycle.
        val springOnly = spec.copy(
            compressionDampingNsPerM = 0f,
            reboundDampingNsPerM = 0f,
        )
        assertEquals(0f, PhysicsSuspensionRow.dissipatedEnergyJ(springOnly, 0.03f, 1.5f), 1f)
    }

    @Test
    fun travelAtRestIsIndependentOfTheVehicleLayout() {
        // Two stations of a six-wheel side: different shares, same relation.
        val light = SuspensionRowSpec.fromSag(1000f, 0.04f, 0.6f, 0.4f, 0.18f, 0.24f, 200_000f)
        val heavy = SuspensionRowSpec.fromSag(4000f, 0.04f, 0.6f, 0.4f, 0.18f, 0.24f, 800_000f)
        assertEquals(0.04f, PhysicsSuspensionRow.equilibriumTravelM(light), 1e-6f)
        assertEquals(0.04f, PhysicsSuspensionRow.equilibriumTravelM(heavy), 1e-6f)
        assertFalse(light.stiffnessNPerM == heavy.stiffnessNPerM)
    }
}
