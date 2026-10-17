package cn.solarmoon.spark_core.physics.terrain

import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f

/** Exact, non-overlapping merged boxes, each dispatched as a convex object.
 * Bullet's compound child AABB culling deletes a resting box manifold on a
 * micrometre separation, before its contact-breaking threshold is reached.
 * With a heavily preloaded suspension that skips a whole solver step. Using
 * top-level boxes preserves native near-contact generation without extending
 * geometry, retaining old contacts ourselves, or adding artificial support.
 */
internal object TerrainBoxBodies {
    fun create(boxes: List<TerrainShapePlan.ShapeBox>, origin: Vector3f): List<PhysicsRigidBody> =
        boxes.filter { it.halfX > 0f && it.halfY > 0f && it.halfZ > 0f }.map { box ->
            PhysicsRigidBody(BoxCollisionShape(Vector3f(box.halfX, box.halfY, box.halfZ)).apply {
                margin = PhysicsChunkManager.TERRAIN_BOX_MARGIN
            }, 0f).apply {
                setPhysicsLocation(origin.add(box.centerX, box.centerY, box.centerZ))
            }
        }
}
