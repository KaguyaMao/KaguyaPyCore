package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.body.CollisionGroups
import cn.solarmoon.spark_core.physics.body.name
import cn.solarmoon.spark_core.physics.terrain.support.StructuralBox
import cn.solarmoon.spark_core.physics.terrain.support.StructuralObstaclePlan
import cn.solarmoon.spark_core.physics.terrain.support.TerrainSupportField
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f

/**
 * One revision of height-field support plus structural sides. The two lists are
 * installed and removed together. The chunk manager may install a pair only from
 * [TerrainSupportInstallPolicy.LIVE] and only when the field has a valid
 * cell. Callers must own the target physics space.
 */
object GroundSupportPairs {
    fun create(snapshot: SectionSnapshot, field: TerrainSupportField): GroundSupportPair =
        create(field, StructuralObstaclePlan.build(snapshot, field))

    fun create(field: TerrainSupportField, structural: List<StructuralBox>): GroundSupportPair {
        val support = TerrainSupportBodies.create(field)
        val bodies = structural.mapIndexed { index, box ->
            PhysicsRigidBody(BoxCollisionShape(Vector3f(box.halfX, box.halfY, box.halfZ)).apply {
                margin = PhysicsChunkManager.TERRAIN_BOX_MARGIN
            }, 0f).apply {
                setPhysicsLocation(Vector3f(box.centerX, box.centerY, box.centerZ))
                name = "structural_${field.revision}_$index"
                restitution = 0f
                collisionGroup = CollisionGroups.STRUCTURAL
                setCollideWithGroups(CollisionGroups.NONE)
            }
        }
        return GroundSupportPair(field.revision, support, java.util.List.copyOf(bodies))
    }
}

class GroundSupportPair(
    val revision: Long,
    val support: TerrainSupportSurface,
    val structural: List<PhysicsRigidBody>,
) {
    fun install(space: PhysicsSpace) {
        support.install(space)
        structural.forEach { if (!it.isInWorld) space.addCollisionObject(it) }
    }

    fun uninstall(space: PhysicsSpace) {
        support.uninstall(space)
        structural.forEach { if (it.isInWorld) space.removeCollisionObject(it) }
    }
}

class GroundSupportSlot {
    var revision: Long = Long.MIN_VALUE
        private set
    var staleRejections: Int = 0
        private set
    private var current: GroundSupportPair? = null

    fun replace(space: PhysicsSpace, next: GroundSupportPair) {
        if (current != null && next.revision < revision) {
            staleRejections++
            return
        }
        current?.uninstall(space)
        next.install(space)
        current = next
        revision = next.revision
    }

    fun clear(space: PhysicsSpace) {
        current?.uninstall(space)
        current = null
    }
}
