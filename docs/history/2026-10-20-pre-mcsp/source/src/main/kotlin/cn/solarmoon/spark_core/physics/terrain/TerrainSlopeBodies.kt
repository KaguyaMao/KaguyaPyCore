package cn.solarmoon.spark_core.physics.terrain

import com.jme3.bullet.collision.shapes.HullCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import kotlin.math.sqrt

/** Closed convex slope wedges, dispatched as top-level static bodies exactly
 * like the merged boxes. Group SLOPE is opt-in: only bodies whose mask asks
 * for it collide with a ramp, every other object keeps colliding with the
 * original block boxes only.
 */
internal object TerrainSlopeBodies {
    fun create(slopes: List<TerrainShapePlan.ShapeSlope>, origin: Vector3f): List<PhysicsRigidBody> =
        slopes.mapNotNull { slope ->
            val dx = slope.highX - slope.lowX
            val dz = slope.highZ - slope.lowZ
            val run = sqrt(dx * dx + dz * dz)
            if (run <= 1.0e-4f || slope.halfWidth <= 0f || slope.highY <= slope.lowY) return@mapNotNull null
            val perpX = -dz / run * slope.halfWidth
            val perpZ = dx / run * slope.halfWidth
            // Closed prism whose low edge is carried one block past the visible
            // run and sunk below the ground plane along the same incline. A
            // zero-height knife line pins a flat track plate on its ambiguous
            // edge (full-weight friction, flipping normals), while the
            // continued plane leaves only the clean inclined face. The buried
            // tail lives inside the validated run block.
            val tail = 1f.coerceAtMost(run)
            val slopeRatio = (slope.highY - slope.lowY) / run
            val tailX = dx / run * tail
            val tailZ = dz / run * tail
            val tailY = slope.lowY - slopeRatio * tail
            val points = floatArrayOf(
                slope.lowX - tailX - perpX, tailY, slope.lowZ - tailZ - perpZ,
                slope.lowX - tailX + perpX, tailY, slope.lowZ - tailZ + perpZ,
                slope.highX - perpX, slope.lowY, slope.highZ - perpZ,
                slope.highX + perpX, slope.lowY, slope.highZ + perpZ,
                slope.highX - perpX, slope.highY, slope.highZ - perpZ,
                slope.highX + perpX, slope.highY, slope.highZ + perpZ,
            )
            PhysicsRigidBody(HullCollisionShape(*points).apply {
                margin = PhysicsChunkManager.TERRAIN_BOX_MARGIN
            }, 0f).apply {
                setPhysicsLocation(origin)
            }
        }
}
