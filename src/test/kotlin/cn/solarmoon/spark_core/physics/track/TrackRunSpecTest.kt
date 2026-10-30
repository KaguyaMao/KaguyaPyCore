package cn.solarmoon.spark_core.physics.track

import cn.solarmoon.spark_core.physics.body.CollisionGroups
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackRunSpecTest {
    private fun link(x: Double, mass: Float = 10f) = TrackRunLinkSpec(
        massKg = mass,
        shape = TrackRunLinkShape.Box(Vec3(.1, .05, .15)),
        position = Vec3(x, 64.0, 0.0),
    )

    private fun loopSpec(linkCount: Int = 3): TrackRunSpec {
        val links = (0 until linkCount).map { link(it * .3) }
        val joints = (0 until linkCount).map { index ->
            TrackRunJointSpec(
                previousIndex = index,
                nextIndex = (index + 1) % linkCount,
                pivotInPrevious = Vec3(.15, 0.0, 0.0),
                pivotInNext = Vec3(-.15, 0.0, 0.0),
            )
        }
        return TrackRunSpec("loop", links, joints)
    }

    @Test
    fun aClosedLoopIsValidAndKeepsGenericDefaults() {
        val spec = loopSpec()
        assertTrue(spec.problems().isEmpty(), spec.problems().joinToString())
        assertEquals(3, spec.links.size)
        assertEquals(CollisionGroups.TRACK_LINK, spec.collisionGroup)
        assertTrue(spec.collideWithGroups and CollisionGroups.TERRAIN != 0)
        assertTrue(spec.collideWithOwnerGroups and CollisionGroups.WHEEL != 0)
        assertTrue(spec.collideWithOwnerGroups and CollisionGroups.CHASSIS != 0)
        assertTrue(spec.collideWithOwnerGroups and CollisionGroups.TRACK_LINK == 0)
        assertEquals(null, spec.explicitGravity)
    }

    @Test
    fun invalidGeometryIsRejectedWithASpecificMessage() {
        val missingJoints = assertThrows(IllegalArgumentException::class.java) {
            TrackRunSpec(name = "open", links = listOf(link(0.0), link(.3)), joints = emptyList())
        }
        assertTrue(missingJoints.message!!.contains("joints must not be empty"))

        val badMass = assertThrows(IllegalArgumentException::class.java) {
            TrackRunSpec(
                name = "bad-mass",
                links = listOf(link(0.0, 0f), link(.3)),
                joints = listOf(TrackRunJointSpec(0, 1, Vec3(.15, 0.0, 0.0), Vec3(-.15, 0.0, 0.0))),
            )
        }
        assertTrue(badMass.message!!.contains("massKg"))

        val selfJoint = assertThrows(IllegalArgumentException::class.java) {
            TrackRunSpec(
                name = "self",
                links = listOf(link(0.0), link(.3)),
                joints = listOf(TrackRunJointSpec(1, 1, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0))),
            )
        }
        assertTrue(selfJoint.message!!.contains("itself"))

        val outOfRange = assertThrows(IllegalArgumentException::class.java) {
            TrackRunSpec(
                name = "range",
                links = listOf(link(0.0), link(.3)),
                joints = listOf(TrackRunJointSpec(0, 5, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0))),
            )
        }
        assertTrue(outOfRange.message!!.contains("out of range"))
    }

    @Test
    fun duplicatePairsAreRejectedInEitherDirection() {
        val sameDirection = assertThrows(IllegalArgumentException::class.java) {
            TrackRunSpec(
                name = "duplicate",
                links = listOf(link(0.0), link(.3)),
                joints = listOf(
                    TrackRunJointSpec(0, 1, Vec3(.15, 0.0, 0.0), Vec3(-.15, 0.0, 0.0)),
                    TrackRunJointSpec(0, 1, Vec3(.15, 0.0, 0.0), Vec3(-.15, 0.0, 0.0)),
                ),
            )
        }
        assertTrue(sameDirection.message!!.contains("duplicates"))

        val reversed = assertThrows(IllegalArgumentException::class.java) {
            TrackRunSpec(
                name = "reversed",
                links = listOf(link(0.0), link(.3)),
                joints = listOf(
                    TrackRunJointSpec(0, 1, Vec3(.15, 0.0, 0.0), Vec3(-.15, 0.0, 0.0)),
                    TrackRunJointSpec(1, 0, Vec3(.15, 0.0, 0.0), Vec3(-.15, 0.0, 0.0)),
                ),
            )
        }
        assertTrue(reversed.message!!.contains("duplicates"), reversed.message!!)
    }

    @Test
    fun disconnectedOrOverConstrainedTopologyIsRejected() {
        val disconnected = assertThrows(IllegalArgumentException::class.java) {
            TrackRunSpec(
                name = "disconnected",
                links = listOf(link(0.0), link(.3), link(.6)),
                joints = listOf(TrackRunJointSpec(0, 1, Vec3(.15, 0.0, 0.0), Vec3(-.15, 0.0, 0.0))),
            )
        }
        assertTrue(disconnected.message!!.contains("not connected"), disconnected.message!!)

        val star = assertThrows(IllegalArgumentException::class.java) {
            TrackRunSpec(
                name = "star",
                links = listOf(link(0.0), link(.3), link(.6), link(.9)),
                joints = listOf(
                    TrackRunJointSpec(0, 1, Vec3(.15, 0.0, 0.0), Vec3(-.15, 0.0, 0.0)),
                    TrackRunJointSpec(0, 2, Vec3(.15, 0.0, 0.0), Vec3(-.15, 0.0, 0.0)),
                    TrackRunJointSpec(0, 3, Vec3(.15, 0.0, 0.0), Vec3(-.15, 0.0, 0.0)),
                ),
            )
        }
        assertTrue(star.message!!.contains("at most 2"), star.message!!)
    }

    @Test
    fun theSpecContainsNoVehicleOrRenderConcepts() {
        val names = TrackRunSpec::class.java.methods.map { it.name.lowercase() }
        listOf("vehicle", "bone", "render", "terrain", "height").forEach { forbidden ->
            assertFalse(names.any { forbidden in it }, "TrackRunSpec exposes '$forbidden'")
        }
    }

    @Test
    fun linksAndJointsAreDefensivelyCopied() {
        val links = mutableListOf(link(0.0), link(.3))
        val joints = mutableListOf(TrackRunJointSpec(0, 1, Vec3(.15, 0.0, 0.0), Vec3(-.15, 0.0, 0.0)))
        val spec = TrackRunSpec("copy", links, joints)
        links.clear()
        joints.clear()
        assertEquals(2, spec.links.size)
        assertEquals(1, spec.joints.size)
    }

    @Test
    fun aRotationIsNotSharedWithTheCaller() {
        val source = Quaternionf().rotateY(.5f)
        val link = TrackRunLinkSpec(1f, TrackRunLinkShape.Box(Vec3(.1, .1, .1)), Vec3(0.0, 0.0, 0.0), source)
        val before = link.rotation
        source.set(0f, 0f, 0f, 1f)
        assertEquals(before.x, link.rotation.x, 0f)
        val read = link.rotation
        read.set(0f, 0f, 0f, 1f)
        assertNotSame(read, link.rotation)
        assertEquals(before.x, link.rotation.x, 0f)
    }

    @Test
    fun aZeroQuaternionIsRejected() {
        val zero = TrackRunLinkSpec(
            1f, TrackRunLinkShape.Box(Vec3(.1, .1, .1)), Vec3(0.0, 0.0, 0.0), Quaternionf(0f, 0f, 0f, 0f),
        )
        assertTrue(zero.validate("zero").any { "zero quaternion" in it })
    }

    @Test
    fun explicitGravityMustBeFiniteAndBudgetsMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackRunSpec(
                name = "gravity",
                links = listOf(link(0.0)),
                joints = emptyList(),
                explicitGravity = Vec3(Double.NaN, 0.0, 0.0),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackRunSpec("budget", listOf(link(0.0)), emptyList(), maxLinkForceN = 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackRunSpec("torque", listOf(link(0.0)), emptyList(), maxLinkTorqueImpulseNms = -1f)
        }
    }

    @Test
    fun inputLimitsClampInsteadOfPassingTheBudgetThrough() {
        val force = TrackRunLimits.clampForce(Vec3(30_000.0, 0.0, 0.0), 20_000f)
        assertEquals(20_000.0, force.length(), 1.0e-3)
        assertEquals(0f, TrackRunLimits.clampForce(Vec3(Double.NaN, 0.0, 0.0), 20_000f).length().toFloat(), 0f)
        assertEquals(0f, TrackRunLimits.clampTorqueImpulse(0f, 100f), 0f)
        assertEquals(100f, TrackRunLimits.clampTorqueImpulse(250f, 100f), 0f)
        assertEquals(-100f, TrackRunLimits.clampTorqueImpulse(-250f, 100f), 0f)
        assertEquals(0f, TrackRunLimits.clampTorqueImpulse(Float.NaN, 100f), 0f)
    }

    @Test
    fun valuesThatOverflowWhenNarrowedToFloatAreRejected() {
        // A finite double can still become infinity in Bullet's float fields.
        val huge = TrackRunLinkSpec(
            1f,
            TrackRunLinkShape.Box(Vec3(1.0e300, .1, .1)),
            Vec3(0.0, 0.0, 0.0),
        )
        assertTrue(huge.validate("huge").any { "representable" in it }, huge.validate("huge").joinToString())

        val hugePosition = TrackRunLinkSpec(
            1f,
            TrackRunLinkShape.Box(Vec3(.1, .1, .1)),
            Vec3(1.0e300, 0.0, 0.0),
        )
        assertTrue(hugePosition.validate("huge-pos").any { "representable" in it })
    }

    @Test
    fun aHugeButFiniteQuaternionIsNormalizedWithoutOverflowing() {
        // Squaring these in Float would overflow to infinity and previously
        // degrade to identity. Double arithmetic keeps the direction.
        val rotation = TrackRunRotation(1.0e30f, 0f, 0f, 1.0e30f)
        assertTrue(rotation.validate("rotation").isEmpty(), rotation.validate("rotation").joinToString())
        val unit = rotation.normalized()
        assertEquals(1f, unit.x * unit.x + unit.w * unit.w, 1.0e-4f)
        assertTrue(unit.x > .7f && unit.w > .7f)
    }
}
