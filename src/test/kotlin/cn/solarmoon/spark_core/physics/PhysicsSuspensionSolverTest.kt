package cn.solarmoon.spark_core.physics

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhysicsSuspensionSolverTest {
    @Test
    fun missingContactProducesNoSupport() {
        val output = PhysicsSuspensionSolver.solve(
            SideSuspensionInput(false, .12f, 0f, 1f),
            massKg = 1000f,
            gravity = 9.81f,
            travelUpM = .18f,
            travelDownM = .24f,
            frequencyHz = 2.2f,
            compressionDamping = 1200f,
            reboundDamping = 700f,
            maxForceN = 250_000f,
        )
        assertTrue(output.forceN == 0f)
        assertTrue(!output.grounded)
    }

    @Test
    fun staticCompressionCarriesOneHalfVehicleWeight() {
        val output = PhysicsSuspensionSolver.solve(
            SideSuspensionInput(true, 0f, 0f, 1f),
            1000f, 9.81f, .18f, .24f, 2.2f, 1200f, 700f, 250_000f,
        )
        assertTrue(kotlin.math.abs(output.forceN - 1000f * 9.81f * .5f) < 20f)
    }

    @Test
    fun compressionProducesSupportAndDampingOpposesTravel() {
        val compressed = PhysicsSuspensionSolver.solve(
            SideSuspensionInput(true, .04f, 1f, 1f),
            massKg = 1000f,
            gravity = 9.81f,
            travelUpM = .18f,
            travelDownM = .24f,
            frequencyHz = 2.2f,
            compressionDamping = 1200f,
            reboundDamping = 700f,
            maxForceN = 250_000f,
        )
        val rebound = PhysicsSuspensionSolver.solve(
            SideSuspensionInput(true, .04f, -1f, 1f),
            1000f, 9.81f, .18f, .24f, 2.2f, 1200f, 700f, 250_000f
        )
        assertTrue(compressed.grounded)
        assertTrue(compressed.forceN > 1000f * 9.81f * .5f)
        assertTrue(compressed.forceN > rebound.forceN)
    }
}
