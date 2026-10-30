package cn.solarmoon.spark_core.physics.terrain.support

import cn.solarmoon.spark_core.physics.terrain.SectionSnapshot
import net.minecraft.world.phys.AABB
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class StructuralReason {
    PARTIAL_SHAPE,
    /**
     * Rejected representation. Full-cell volumes whose top is not a height-field
     * surface must not be copied into the support pair: that turns unknown and
     * other unowned tops into upward support. Tests assert this reason is absent.
     */
    UNOWNED_VOLUME,
    /** Full cubes strictly below an owned support surface, merged into prisms. */
    INTERNAL_FILL,
    STRUCTURAL_FACE,
}

data class StructuralBox(
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val halfX: Float,
    val halfY: Float,
    val halfZ: Float,
    val reason: StructuralReason,
) {
    val top: Float get() = centerY + halfY
}

/**
 * Shape-level arbitration. The support pair keeps height-field tops and
 * structural sides. It does not create Bullet objects or slope wedges.
 *
 * A full-cell top owned by the height field is not emitted again. Full cubes
 * buried under that surface are merged so they are not one rigid body per
 * block; their top stays below the owned surface. An unowned full-cell top,
 * including [SurfaceClass.UNKNOWN] and [SurfaceClass.NON_GROUND], is omitted
 * rather than turned into a substitute floor. Classified partial shapes keep
 * their real boxes and are not expanded to a full cell. Unclassified partial
 * shapes are omitted for the same reason as unowned tops.
 *
 * [FACE_THICKNESS] and [FACE_INSET] are collider geometry so a side is not a
 * second floor. They are not climb limits and are not acceptance thresholds.
 */
object StructuralObstaclePlan {
    const val FACE_THICKNESS = 0.05f
    const val FACE_INSET = 0.02f
    private const val EPS = 1.0e-3f

    fun build(snapshot: SectionSnapshot, field: TerrainSupportField): List<StructuralBox> {
        require(snapshot.revision == field.revision) { "structural plan must use the same revision as the support field" }
        val boxes = ArrayList<StructuralBox>()
        val buried = HashSet<Triple<Int, Int, Int>>()
        for (block in snapshot.blocks) {
            for (box in block.boxes) {
                if (!coversFullCell(box)) {
                    partialVolume(field, block, box)?.let(boxes::add)
                    continue
                }
                val worldTop = worldY(field, block.y, box.maxY)
                if (ownsTop(field, block.x, block.z, worldTop)) continue
                if (!buriedUnderOwnedTop(snapshot, field, block.x, block.z, worldTop)) continue
                if (isUnitCube(box)) buried += Triple(block.x, block.y, block.z)
                else boxes += volume(field, block, box, StructuralReason.INTERNAL_FILL)
            }
        }
        boxes += mergeUnitCubes(buried, field)
        boxes += faces(field)
        boxes += boundaryFaces(snapshot, field)
        return boxes
    }

    private fun partialVolume(
        field: TerrainSupportField,
        block: SectionSnapshot.BlockShapeSnapshot,
        box: AABB,
    ): StructuralBox? {
        // An unclassified or non-ground top must not become support. A box would
        // have an upward face, so these shapes stay out of the support pair.
        if (block.surfaceClass != SurfaceClass.SOLID_HARD && block.surfaceClass != SurfaceClass.SOLID_SOFT) {
            return null
        }
        return volume(field, block, box, StructuralReason.PARTIAL_SHAPE)
    }

    private fun buriedUnderOwnedTop(
        snapshot: SectionSnapshot,
        field: TerrainSupportField,
        x: Int,
        z: Int,
        worldTop: Float,
    ): Boolean {
        val cover = field.layersAt(x, z)
            .filter { it.valid && it.rawHeight > worldTop + EPS }
            .minByOrNull { it.rawHeight }
            ?: return false
        var cursor = worldTop
        while (cursor < cover.rawHeight - EPS) {
            val next = fullCellIntervals(snapshot, field, x, z)
                .firstOrNull { it.minY <= cursor + EPS && it.maxY > cursor + EPS }
                ?: return false
            val advanced = max(cursor, next.maxY)
            if (advanced <= cursor + EPS) return false
            cursor = advanced
        }
        return true
    }

    private fun fullCellIntervals(
        snapshot: SectionSnapshot,
        field: TerrainSupportField,
        x: Int,
        z: Int,
    ): List<Interval> {
        val out = ArrayList<Interval>()
        for (block in snapshot.blocks) {
            if (block.x != x || block.z != z) continue
            for (box in block.boxes) {
                if (!coversFullCell(box)) continue
                out += Interval(worldY(field, block.y, box.minY), worldY(field, block.y, box.maxY))
            }
        }
        return out
    }

    private fun mergeUnitCubes(cells: Set<Triple<Int, Int, Int>>, field: TerrainSupportField): List<StructuralBox> {
        if (cells.isEmpty()) return emptyList()
        val consumed = HashSet<Triple<Int, Int, Int>>()
        val out = ArrayList<StructuralBox>()
        for ((x, y, z) in cells.sortedWith(compareBy({ it.second }, { it.third }, { it.first }))) {
            val key = Triple(x, y, z)
            if (key in consumed) continue
            var endX = x + 1
            while (Triple(endX, y, z) in cells && Triple(endX, y, z) !in consumed) endX++
            var endZ = z + 1
            while ((x until endX).all { Triple(it, y, endZ) in cells && Triple(it, y, endZ) !in consumed }) endZ++
            var endY = y + 1
            while ((z until endZ).all { pz ->
                    (x until endX).all { px -> Triple(px, endY, pz) in cells && Triple(px, endY, pz) !in consumed }
                }) endY++
            for (py in y until endY) for (pz in z until endZ) for (px in x until endX) {
                consumed += Triple(px, py, pz)
            }
            val width = endX - x
            val height = endY - y
            val depth = endZ - z
            out += StructuralBox(
                centerX = field.originX + x + width / 2f,
                centerY = field.originY + y + height / 2f,
                centerZ = field.originZ + z + depth / 2f,
                halfX = width / 2f,
                halfY = height / 2f,
                halfZ = depth / 2f,
                reason = StructuralReason.INTERNAL_FILL,
            )
        }
        return out
    }

    /**
     * Faces that sit exactly on a section boundary. The in-section [faces]
     * loop cannot see the one-block margin, so a two-block wall whose step is
     * the section border would otherwise have no side at all for a
     * SUPPORT_PAIR body.
     *
     * Only the higher side emits the face. The lower side sees the same rise
     * but stays silent, so the boundary never gets two coincident colliders.
     */
    private fun boundaryFaces(snapshot: SectionSnapshot, field: TerrainSupportField): List<StructuralBox> {
        val out = ArrayList<StructuralBox>()
        val sides = listOf(
            BoundarySide(15, 0, 16, 0, true),
            BoundarySide(0, 0, -1, 0, true),
            BoundarySide(0, 15, 0, 16, false),
            BoundarySide(0, 0, 0, -1, false),
        )
        for (side in sides) {
            val length = if (side.alongX) field.sizeZ else field.sizeX
            for (i in 0 until length) {
                val lx = if (side.alongX) side.localX else i
                val lz = if (side.alongX) i else side.localZ
                val mx = if (side.alongX) side.marginX else lx
                val mz = if (side.alongX) lz else side.marginZ
                val top = snapshot.marginTop(mx, mz)
                if (top == SectionSnapshot.MARGIN_UNKNOWN || top == SectionSnapshot.MARGIN_TOO_HIGH) continue
                val neighborClass = snapshot.marginClass(mx, mz) ?: continue
                if (neighborClass != SurfaceClass.SOLID_HARD && neighborClass != SurfaceClass.SOLID_SOFT) continue
                val cell = field.layersAt(lx, lz).filter { it.valid }.maxByOrNull { it.rawHeight } ?: continue
                val neighborRaw = field.originY + top + 1f
                val rise = cell.rawHeight - neighborRaw
                if (rise < TerrainSupportFieldBuilder.STRUCTURAL_RISE_BLOCKS) continue
                val high = cell.rawHeight - FACE_INSET
                val low = neighborRaw + FACE_INSET
                if (high - low <= FACE_THICKNESS) continue
                val halfT = FACE_THICKNESS * 0.5f
                val planeX = field.originX + if (side.alongX) (if (side.marginX > 0) 16f else 0f) else lx + 0.5f
                val planeZ = field.originZ + if (side.alongX) lz + 0.5f else (if (side.marginZ > 0) 16f else 0f)
                out += StructuralBox(
                    centerX = planeX,
                    centerY = (low + high) * 0.5f,
                    centerZ = planeZ,
                    halfX = if (side.alongX) halfT else 0.5f,
                    halfY = (high - low) * 0.5f,
                    halfZ = if (side.alongX) 0.5f else halfT,
                    reason = StructuralReason.STRUCTURAL_FACE,
                )
            }
        }
        return out
    }

    private data class BoundarySide(
        val localX: Int,
        val localZ: Int,
        val marginX: Int,
        val marginZ: Int,
        val alongX: Boolean,
    )

    private fun faces(field: TerrainSupportField): List<StructuralBox> {
        val out = ArrayList<StructuralBox>()
        for (cell in field.columns) {
            if (!cell.valid) continue
            if (cell.localX + 1 < field.sizeX) face(field, cell, 1, 0)?.let(out::add)
            if (cell.localZ + 1 < field.sizeZ) face(field, cell, 0, 1)?.let(out::add)
        }
        return out
    }

    private fun face(field: TerrainSupportField, cell: SupportColumn, stepX: Int, stepZ: Int): StructuralBox? {
        val other = field.layersAt(cell.localX + stepX, cell.localZ + stepZ)
            .filter { it.valid }
            .minByOrNull { abs(it.rawHeight - cell.rawHeight) }
            ?: return null
        val rise = abs(cell.rawHeight - other.rawHeight)
        if (rise < TerrainSupportFieldBuilder.STRUCTURAL_RISE_BLOCKS) return null
        val high = max(cell.rawHeight, other.rawHeight) - FACE_INSET
        val low = min(cell.rawHeight, other.rawHeight) + FACE_INSET
        if (high - low <= FACE_THICKNESS) return null
        val halfT = FACE_THICKNESS * 0.5f
        val alongX = stepX != 0
        return StructuralBox(
            centerX = field.originX + cell.localX + if (alongX) 1f else 0.5f,
            centerY = (low + high) * 0.5f,
            centerZ = field.originZ + cell.localZ + if (alongX) 0.5f else 1f,
            halfX = if (alongX) halfT else 0.5f,
            halfY = (high - low) * 0.5f,
            halfZ = if (alongX) 0.5f else halfT,
            reason = StructuralReason.STRUCTURAL_FACE,
        )
    }

    private fun volume(
        field: TerrainSupportField,
        block: SectionSnapshot.BlockShapeSnapshot,
        box: AABB,
        reason: StructuralReason,
    ): StructuralBox {
        val minX = field.originX + block.x + box.minX.toFloat()
        val minY = worldY(field, block.y, box.minY)
        val minZ = field.originZ + block.z + box.minZ.toFloat()
        val maxX = field.originX + block.x + box.maxX.toFloat()
        val maxY = worldY(field, block.y, box.maxY)
        val maxZ = field.originZ + block.z + box.maxZ.toFloat()
        return StructuralBox(
            (minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f,
            (maxX - minX) * 0.5f, (maxY - minY) * 0.5f, (maxZ - minZ) * 0.5f,
            reason,
        )
    }

    private fun worldY(field: TerrainSupportField, blockY: Int, local: Double): Float =
        field.originY + blockY + local.toFloat()

    private fun ownsTop(field: TerrainSupportField, x: Int, z: Int, worldTop: Float): Boolean =
        field.layersAt(x, z).any { it.valid && abs(it.rawHeight - worldTop) <= EPS }

    private fun coversFullCell(box: AABB): Boolean =
        box.minX <= EPS && box.maxX >= 1.0 - EPS && box.minZ <= EPS && box.maxZ >= 1.0 - EPS

    private fun isUnitCube(box: AABB): Boolean =
        coversFullCell(box) && box.minY <= EPS && box.maxY >= 1.0 - EPS

    private data class Interval(val minY: Float, val maxY: Float)
}
