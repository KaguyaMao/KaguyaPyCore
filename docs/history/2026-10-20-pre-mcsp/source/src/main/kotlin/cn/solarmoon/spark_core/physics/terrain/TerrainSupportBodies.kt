package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.body.CollisionGroups
import cn.solarmoon.spark_core.physics.body.name
import cn.solarmoon.spark_core.physics.terrain.support.SupportColumn
import cn.solarmoon.spark_core.physics.terrain.support.TerrainSupportField
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.shapes.MeshCollisionShape
import com.jme3.bullet.collision.shapes.infos.IndexedMesh
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f

/**
 * Creates static support meshes from an already built [TerrainSupportField].
 * The pure-data builder does not call this. Callers must be the thread that
 * owns the target physics space; this type does not start a second scheduler.
 *
 * The mesh uses the baked corner patch, so the contact surface is the same
 * geometry as the downward sample. Invalid cells contribute no triangles.
 * Bodies use [CollisionGroups.HEIGHT_FIELD] and are not added to [CollisionGroups.DRIVABLE].
 */
object TerrainSupportBodies {
    fun create(field: TerrainSupportField): TerrainSupportSurface {
        val bodies = field.columns
            .filter { it.valid && cornersFinite(it) }
            .groupBy { it.friction.toBits() }
            .values
            .mapIndexedNotNull { index, cells -> meshBody(field, cells, index) }
        return TerrainSupportSurface(field.revision, java.util.List.copyOf(bodies))
    }

    private fun meshBody(field: TerrainSupportField, cells: List<SupportColumn>, index: Int): PhysicsRigidBody? {
        val vertices = ArrayList<Vector3f>(cells.size * 4)
        val indices = ArrayList<Int>(cells.size * 6)
        for (cell in cells) {
            val base = vertices.size
            vertices += vertex(field, cell, 0f, 0f, cell.corner00)
            vertices += vertex(field, cell, 0f, 1f, cell.corner01)
            vertices += vertex(field, cell, 1f, 0f, cell.corner10)
            vertices += vertex(field, cell, 1f, 1f, cell.corner11)
            // Winding gives a positive world-Y normal for an upward patch.
            indices += base
            indices += base + 1
            indices += base + 2
            indices += base + 2
            indices += base + 1
            indices += base + 3
        }
        if (indices.isEmpty()) return null
        val shape = MeshCollisionShape(true, IndexedMesh(vertices.toTypedArray(), indices.toIntArray())).apply {
            margin = PhysicsChunkManager.TERRAIN_BOX_MARGIN
        }
        return PhysicsRigidBody(shape, 0f).apply {
            name = "height_field_${field.revision}_$index"
            friction = cells.first().friction
            restitution = 0f
            collisionGroup = CollisionGroups.HEIGHT_FIELD
            setCollideWithGroups(CollisionGroups.NONE)
        }
    }

    private fun vertex(field: TerrainSupportField, cell: SupportColumn, fx: Float, fz: Float, height: Float) =
        Vector3f(field.originX + cell.localX + fx, height, field.originZ + cell.localZ + fz)

    private fun cornersFinite(cell: SupportColumn): Boolean =
        cell.corner00.isFinite() && cell.corner10.isFinite() && cell.corner01.isFinite() && cell.corner11.isFinite()
}

class TerrainSupportSurface(
    val revision: Long,
    val bodies: List<PhysicsRigidBody>,
) {
    fun install(space: PhysicsSpace) {
        bodies.forEach { body ->
            if (!body.isInWorld) space.addCollisionObject(body)
        }
    }

    fun uninstall(space: PhysicsSpace) {
        bodies.forEach { body ->
            if (body.isInWorld) space.removeCollisionObject(body)
        }
    }
}

/**
 * Replaces a support surface in one physics space. An older revision cannot
 * overwrite a newer one. This slot is not used by [PhysicsChunkManager].
 */
class TerrainSupportSurfaceSlot {
    var revision: Long = Long.MIN_VALUE
        private set
    var staleRejections: Int = 0
        private set
    private var current: TerrainSupportSurface? = null

    fun replace(space: PhysicsSpace, next: TerrainSupportSurface) {
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
