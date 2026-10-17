package cn.solarmoon.spark_core.physics

import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.ManifoldPoints
import com.jme3.bullet.collision.PersistentManifolds
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.collision.shapes.MeshCollisionShape
import com.jme3.bullet.collision.shapes.infos.IndexedMesh
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Isolate mesh contact generation from chassis/suspension/traction coupling.
 * Keep both the box-floor control and the two-triangle floor at identical geometry.
 */
class MeshFootprintContactTest {
    @Test fun boxFloorControlSupportsAnUnconstrainedFootprint() = checkFootprint(false)
    @Test fun triangleFloorSupportsAnUnconstrainedFootprint() = checkFootprint(true)

    private fun checkFootprint(mesh: Boolean) {
        NativePhysicsLoader.load()
        for (origin in listOf(Vector3f(), Vector3f(1600f, 65f, 1024f))) {
            val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
            try {
                space.setGravity(Vector3f(0f, -9.81f, 0f))
                val shape = if (mesh) MeshCollisionShape(true, IndexedMesh(arrayOf(
                    Vector3f(-10f, 0f, -10f), Vector3f(-10f, 0f, 10f),
                    Vector3f(10f, 0f, 10f), Vector3f(10f, 0f, -10f)), intArrayOf(0, 1, 2, 0, 2, 3)))
                else BoxCollisionShape(Vector3f(10f, .5f, 10f))
                shape.margin = .001f
                val floor = PhysicsRigidBody(shape, 0f)
                floor.setPhysicsLocation(if (mesh) origin else origin.add(0f, -.5f, 0f))
                val plate = PhysicsRigidBody(BoxCollisionShape(Vector3f(.2f, .04f, 3f)).apply { margin = .001f }, 5000f)
                plate.setPhysicsLocation(origin.add(0f, .8f, 0f))
                plate.restitution = 0f
                plate.ccdMotionThreshold = .02f
                plate.ccdSweptSphereRadius = .02f
                space.add(floor); space.add(plate)
                fun step() = space.update(1f / 60f, 0, false, true, false)
                repeat(600) { step() }
                val start = plate.getPhysicsLocation(null)
                repeat(600) { frame ->
                    step()
                    val delta = plate.getPhysicsLocation(null).subtract(start)
                    val velocity = plate.getLinearVelocity(null)
                    assertTrue(kotlin.math.hypot(delta.x, delta.z) <= .05f,
                        "mesh=$mesh origin=$origin step=$frame drift=$delta")
                    assertTrue(kotlin.math.hypot(velocity.x, velocity.z) <= .02f,
                        "mesh=$mesh origin=$origin step=$frame v=$velocity")
                    assertEquals(origin.y + .04f, plate.getPhysicsLocation(null).y, .005f,
                        "mesh=$mesh origin=$origin frame=$frame location=${plate.getPhysicsLocation(null)} rotation=${plate.getPhysicsRotation(null)}")
                }
                val xs = mutableListOf<Float>()
                for (direction in listOf(-1f, 1f)) {
                    // An actual 0.1 rad/s perturbation, not a large arbitrary
                    // torque that tips this narrow, unconstrained body over.
                    val iz = plate.getInverseInertiaLocal(null).z
                    plate.applyTorqueImpulse(Vector3f(0f, 0f, direction * .1f / iz))
                    plate.activate(true)
                    repeat(180) {
                        step()
                        for (m in space.listManifoldIds()) {
                            val isA = PersistentManifolds.getBodyAId(m) == plate.nativeId()
                            if (!isA && PersistentManifolds.getBodyBId(m) != plate.nativeId()) continue
                            for (id in PersistentManifolds.listPointIds(m)) {
                                if (ManifoldPoints.getDistance1(id) > .001f || ManifoldPoints.getAppliedImpulse(id) <= 0f) continue
                                val normal = Vector3f().also { ManifoldPoints.getNormalWorldOnB(id, it) }
                                if (normal.y * (if (isA) 1f else -1f) <= .25f) continue
                                val point = Vector3f().also {
                                    if (isA) ManifoldPoints.getPositionWorldOnA(id, it) else ManifoldPoints.getPositionWorldOnB(id, it)
                                }
                                val local = plate.getPhysicsRotation(null).inverse().toRotationMatrix()
                                    .mult(point.subtract(plate.getPhysicsLocation(null)), null)
                                xs += local.x
                            }
                        }
                    }
                }
                assertTrue(xs.any { it < -.15f } && xs.any { it > .15f },
                    "mesh=$mesh origin=$origin unloaded lateral edge min=${xs.minOrNull()} max=${xs.maxOrNull()}")
            } finally { space.destroy() }
        }
    }
}
