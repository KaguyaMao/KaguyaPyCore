package cn.solarmoon.spark_core.physics.track

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Matrix3f
import com.jme3.math.Vector3f
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Native checks against [TrackRunAssembly], which is the exact code path
 * [TrackRunRuntime] uses to build links and hinges. Testing the helper instead
 * would let the runtime diverge without a failing test.
 */
class TrackRunHingeSemanticsTest {
    private fun spaceWithGravity(gravity: Vector3f): PhysicsSpace {
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        space.setGravity(gravity)
        return space
    }

    private fun chainSpec(centres: List<Vec3>, halfAngleRad: Float = 1.2f, closed: Boolean = true): TrackRunSpec {
        val links = centres.map { centre ->
            TrackRunLinkSpec(
                massKg = 2f,
                shape = TrackRunLinkShape.Box(Vec3(.08, .08, .08)),
                position = centre,
            )
        }
        val jointCount = if (closed) centres.size else centres.size - 1
        val joints = (0 until jointCount).map { index ->
            val next = (index + 1) % centres.size
            val midpoint = Vec3(
                (centres[index].x + centres[next].x) * .5,
                (centres[index].y + centres[next].y) * .5,
                (centres[index].z + centres[next].z) * .5,
            )
            TrackRunJointSpec(
                previousIndex = index,
                nextIndex = next,
                pivotInPrevious = midpoint.subtract(centres[index]),
                pivotInNext = midpoint.subtract(centres[next]),
                axis = 2,
                halfAngleRad = halfAngleRad,
            )
        }
        return TrackRunSpec("native_chain", links, joints)
    }

    private fun ringCentres(count: Int, radius: Double, y: Double): List<Vec3> =
        (0 until count).map { index ->
            val angle = index * 2.0 * PI / count
            Vec3(radius * cos(angle), y + radius * sin(angle), 0.0)
        }

    @Test
    fun theAssemblyLocksTranslationAndKeepsTheAuthoredSpacing() {
        NativePhysicsLoader.load()
        val space = spaceWithGravity(Vector3f(0f, -9.81f, 0f))
        try {
            val spec = chainSpec(listOf(Vec3(0.0, 70.0, 0.0), Vec3(.3, 70.0, 0.0)), closed = false)
            val bodies = TrackRunAssembly.createBodies(spec, null)
            bodies.forEach(space::addCollisionObject)
            val joints = TrackRunAssembly.createJoints(spec, bodies)
            joints.forEach(space::addJoint)
            repeat(300) { space.update(1f / 60f, 0) }
            val distance = bodies[0].getPhysicsLocation(null).distance(bodies[1].getPhysicsLocation(null))
            assertEquals(.3f, distance, .01f, "hinge did not hold link spacing: $distance")
            assertTrue(bodies[0].getPhysicsLocation(null).y < 69.5f, "pair did not fall under gravity")
        } finally {
            space.destroy()
        }
    }

    @Test
    fun aClosedLoopStaysClosedUnderLoad() {
        NativePhysicsLoader.load()
        val space = spaceWithGravity(Vector3f(0f, -9.81f, 0f))
        try {
            val count = 8
            val centres = ringCentres(count, .5, 70.0)
            val spec = chainSpec(centres)
            val bodies = TrackRunAssembly.createBodies(spec, null)
            bodies.forEach(space::addCollisionObject)
            TrackRunAssembly.createJoints(spec, bodies).forEach(space::addJoint)
            repeat(600) { space.update(1f / 60f, 0) }

            var worst = 0f
            spec.joints.forEach { joint ->
                val previous = bodies[joint.previousIndex]
                val next = bodies[joint.nextIndex]
                val expected = previous.getPhysicsLocation(null)
                    .add(previous.getPhysicsRotation(null).toRotationMatrix()
                        .mult(joint.pivotInPrevious.let { Vector3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }, null))
                val actual = next.getPhysicsLocation(null)
                    .add(next.getPhysicsRotation(null).toRotationMatrix()
                        .mult(joint.pivotInNext.let { Vector3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }, null))
                worst = maxOf(worst, expected.distance(actual))
            }
            assertTrue(worst < .08f, "closed hinge loop opened: worst=$worst")
        } finally {
            space.destroy()
        }
    }

    @Test
    fun theAssemblyNeverEnablesASpringRow() {
        NativePhysicsLoader.load()
        val space = spaceWithGravity(Vector3f())
        try {
            val spec = chainSpec(listOf(Vec3(0.0, 70.0, 0.0), Vec3(.3, 70.0, 0.0)), closed = false)
            val bodies = TrackRunAssembly.createBodies(spec, null)
            bodies.forEach(space::addCollisionObject)
            TrackRunAssembly.createJoints(spec, bodies).forEach(space::addJoint)
            bodies[0].setLinearVelocity(Vector3f(0f, 0f, 2f))
            repeat(120) { space.update(1f / 60f, 0) }
            assertTrue(bodies[0].getPhysicsLocation(null).z > .2f, "hinge acted like a spring")
            assertTrue(bodies[1].getPhysicsLocation(null).z > .2f, "link did not follow the hinge")
        } finally {
            space.destroy()
        }
    }

    @Test
    fun aLinkInheritsWorldGravityUnlessItOptsOut() {
        NativePhysicsLoader.load()
        val space = spaceWithGravity(Vector3f(0f, -9.81f, 0f))
        try {
            val inherits = chainSpec(listOf(Vec3(0.0, 70.0, 0.0), Vec3(.3, 70.0, 0.0)), closed = false)
            val bodies = TrackRunAssembly.createBodies(inherits, null)
            bodies.forEach(space::addCollisionObject)
            TrackRunAssembly.createJoints(inherits, bodies).forEach(space::addJoint)
            repeat(120) { space.update(1f / 60f, 0) }
            assertTrue(
                bodies[0].getPhysicsLocation(null).y < 69.9f,
                "default link did not fall: ${bodies[0].getPhysicsLocation(null)}",
            )
        } finally {
            space.destroy()
        }

        val explicitSpace = spaceWithGravity(Vector3f(0f, -9.81f, 0f))
        try {
            val pinned = TrackRunSpec(
                name = "pinned",
                links = listOf(
                    TrackRunLinkSpec(2f, TrackRunLinkShape.Box(Vec3(.08, .08, .08)), Vec3(0.0, 70.0, 0.0)),
                ),
                joints = emptyList(),
                explicitGravity = Vec3.ZERO,
            )
            val body = TrackRunAssembly.createBodies(pinned, null).single()
            explicitSpace.addCollisionObject(body)
            repeat(120) { explicitSpace.update(1f / 60f, 0) }
            assertEquals(70.0f, body.getPhysicsLocation(null).y, .01f, "explicit gravity was not honoured")
        } finally {
            explicitSpace.destroy()
        }
    }

    @Test
    fun cylinderShapesHonourTheirAxis() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            val expected = mapOf(
                0 to Vector3f(.6f, .2f, .2f),
                1 to Vector3f(.2f, .6f, .2f),
                2 to Vector3f(.2f, .2f, .6f),
            )
            expected.forEach { (axis, halfExtents) ->
                val spec = TrackRunSpec(
                    name = "cylinder_$axis",
                    links = listOf(
                        TrackRunLinkSpec(
                            1f,
                            TrackRunLinkShape.Cylinder(radius = .2f, halfHeight = .6f, axis = axis),
                            Vec3(0.0, 70.0, 0.0),
                        ),
                    ),
                    joints = emptyList(),
                )
                val body = TrackRunAssembly.createBodies(spec, null).single()
                space.addCollisionObject(body)
                val box = com.jme3.bounding.BoundingBox()
                body.collisionShape.boundingBox(Vector3f(), Matrix3f(), box)
                assertEquals(halfExtents.x, box.getXExtent(), .02f, "axis $axis x extent")
                assertEquals(halfExtents.y, box.getYExtent(), .02f, "axis $axis y extent")
                assertEquals(halfExtents.z, box.getZExtent(), .02f, "axis $axis z extent")
                space.removeCollisionObject(body)
            }
        } finally {
            space.destroy()
        }
    }

    @Test
    fun aLinkCarriesTheCollisionMasksFromItsSpec() {
        NativePhysicsLoader.load()
        val spec = TrackRunSpec(
            name = "masks",
            links = listOf(
                TrackRunLinkSpec(1f, TrackRunLinkShape.Box(Vec3(.1, .1, .1)), Vec3(0.0, 70.0, 0.0)),
            ),
            joints = emptyList(),
        )
        val body = TrackRunAssembly.createBodies(spec, null).single()
        assertNotNull(body.collisionShape)
        assertEquals(spec.collisionGroup, body.collisionGroup)
        assertEquals(spec.collideWithGroups, body.getCollideWithGroups())
        assertTrue(spec.collideWithOwnerGroups != 0, "links must allow same-owner wheels and chassis")
    }
}
