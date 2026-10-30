package cn.solarmoon.spark_core.physics.terrain.support

/**
 * Closed surface classes. Unknown mappings must stay [UNKNOWN], never a hard ground.
 * The acceptance map is not frozen; [SurfaceClassMap] is only a provisional filter.
 */
enum class SurfaceClass {
    SOLID_HARD,
    SOLID_SOFT,
    AIR,
    UNKNOWN,
    NON_GROUND,
}

/** Who owns an upward contact. A downward sample does not invent a second owner. */
enum class ContactAuthority {
    HEIGHT_FIELD,
    STRUCTURAL_OBSTACLE,
    NONE,
}

enum class SupportInvalidReason {
    AIR,
    UNKNOWN,
    NON_GROUND,
    PARTIAL_SHAPE,
    UNPROVEN_TOP,
    CONTINUES_ABOVE,
    OUT_OF_TILE,
    NO_SURFACE_IN_RANGE,
}

enum class SectionCoverage {
    /** The section was read and contains the listed collision boxes. */
    PRESENT,
    /** The section was read and has no collision. */
    AIR,
    /** The section was not read. Columns must not be filled with a fake height. */
    UNREAD,
}

/**
 * How much of the gap above a surface is known.
 * [clearanceAbove] is never positive infinity; unknown sky is not an infinite floor.
 */
enum class ClearanceKnowledge {
    /** [SupportColumn.clearanceAbove] is the exact gap to the next full-cell occluder. */
    BOUNDED,
    /** [SupportColumn.clearanceAbove] is only a proven lower bound. */
    OPEN_ENDED,
    /** The surface may continue into unread or adjoining solid terrain. */
    UNPROVEN,
}

/**
 * The pure-data query implemented today. It is not a shape-contact solver:
 * a probe below the surface does not become penetration, and
 * [ContactAuthority.STRUCTURAL_OBSTACLE] is not emitted here.
 */
enum class SupportQueryMode {
    DOWNWARD_SAMPLE,
}

/**
 * One block column surface in a support tile.
 *
 * [rawHeight] is the unshaved full-cell top, in world Y.
 * [supportHeight] is the lowest corner of the baked patch, also world Y.
 * The four corners are the actual surface geometry. A point sample must use
 * those corners, not a tilted normal paired with a flat column height.
 * A cell covers world X `[originX + localX, originX + localX + 1)` and the same in Z.
 */
data class SupportColumn(
    val localX: Int,
    val localZ: Int,
    val layerId: Int,
    val valid: Boolean,
    val rawHeight: Float,
    val supportHeight: Float,
    val corner00: Float,
    val corner10: Float,
    val corner01: Float,
    val corner11: Float,
    val surfaceClass: SurfaceClass,
    val friction: Float,
    val clearanceAbove: Float,
    val clearanceKnowledge: ClearanceKnowledge,
    val hasStructuralNeighbor: Boolean,
    val hasUnresolvedPartial: Boolean,
    val invalidReason: SupportInvalidReason?,
)

data class HeightFieldContact(
    val valid: Boolean,
    val worldX: Double,
    val worldY: Double,
    val worldZ: Double,
    /** Unshaved column top. Not the contact height. */
    val rawHeight: Float,
    /** Sampled patch height at [worldX]/[worldZ]. This is the contact height. */
    val supportHeight: Float,
    val normalX: Float,
    val normalY: Float,
    val normalZ: Float,
    /**
     * Probe Y minus the sampled surface. Positive when the probe is above that
     * surface. This is not a solved shape penetration.
     */
    val signedDistance: Float,
    val surfaceClass: SurfaceClass,
    val friction: Float,
    val terrainRevision: Long,
    val authority: ContactAuthority,
    val invalidReason: SupportInvalidReason?,
    val layerId: Int,
    val queryMode: SupportQueryMode,
)

/**
 * Pure numeric support tile. It does not own Bullet objects and does not clamp
 * out-of-tile queries onto the border. The published column list is copied and
 * unmodifiable.
 */
class TerrainSupportField(
    val originX: Int,
    val originY: Int,
    val originZ: Int,
    val cellSize: Float,
    val revision: Long,
    val sizeX: Int,
    val sizeZ: Int,
    columns: List<SupportColumn>,
) {
    val columns: List<SupportColumn> = java.util.List.copyOf(columns)
    private val grid: Array<Array<List<SupportColumn>>>

    init {
        require(cellSize == 1f) { "support tiles use one-block cells" }
        require(sizeX == TerrainSupportFieldBuilder.CELLS && sizeZ == TerrainSupportFieldBuilder.CELLS) {
            "a section support tile is 16 by 16"
        }
        this.columns.forEach {
            require(it.localX in 0 until sizeX && it.localZ in 0 until sizeZ) {
                "column ${it.localX},${it.localZ} is outside the tile"
            }
        }
        grid = Array(sizeX) { x ->
            Array(sizeZ) { z ->
                this.columns.filter { it.localX == x && it.localZ == z }.sortedBy { it.layerId }
            }
        }
    }

    /** [layerId] is the connected-surface id, not the bottom-up index inside one column. */
    fun column(localX: Int, localZ: Int, layerId: Int): SupportColumn? =
        if (localX !in 0 until sizeX || localZ !in 0 until sizeZ) null
        else grid[localX][localZ].firstOrNull { it.layerId == layerId }

    fun layersAt(localX: Int, localZ: Int): List<SupportColumn> =
        if (localX !in 0 until sizeX || localZ !in 0 until sizeZ) emptyList()
        else grid[localX][localZ]

    /**
     * Downward surface sample. A surface above [probeY] is not selected, and a
     * probe that has already entered the surface is not reported as penetration.
     * Outside the tile the result is [ContactAuthority.NONE], never the edge column.
     */
    fun query(worldX: Double, worldZ: Double, probeY: Double, downwardRange: Double): HeightFieldContact {
        require(downwardRange >= 0.0) { "downward range cannot be negative" }
        val dx = worldX - originX
        val dz = worldZ - originZ
        val localX = kotlin.math.floor(dx).toInt()
        val localZ = kotlin.math.floor(dz).toInt()
        if (localX !in 0 until sizeX || localZ !in 0 until sizeZ) {
            return invalid(worldX, probeY, worldZ, SupportInvalidReason.OUT_OF_TILE, SurfaceClass.UNKNOWN)
        }
        val fx = (dx - localX).toFloat()
        val fz = (dz - localZ).toFloat()
        val layers = grid[localX][localZ]
        if (layers.isEmpty()) {
            return invalid(worldX, probeY, worldZ, SupportInvalidReason.UNKNOWN, SurfaceClass.UNKNOWN)
        }
        val floor = (probeY - downwardRange).toFloat()
        val selected = layers
            .filter { it.valid }
            .map { it to heightAt(it, fx, fz) }
            .filter { (_, height) -> height <= probeY.toFloat() + 1.0e-4f && height >= floor }
            .maxByOrNull { it.second }
        if (selected == null) {
            val reason = if (layers.none { it.valid }) {
                layers.first().invalidReason ?: SupportInvalidReason.NO_SURFACE_IN_RANGE
            } else {
                SupportInvalidReason.NO_SURFACE_IN_RANGE
            }
            val surface = layers.firstOrNull { it.invalidReason == reason }?.surfaceClass
                ?: if (reason == SupportInvalidReason.UNKNOWN) SurfaceClass.UNKNOWN else SurfaceClass.AIR
            return invalid(worldX, probeY, worldZ, reason, surface)
        }
        val cell = selected.first
        val height = selected.second
        val normal = normalAt(cell, fx, fz)
        return HeightFieldContact(
            valid = true,
            worldX = worldX,
            worldY = height.toDouble(),
            worldZ = worldZ,
            rawHeight = cell.rawHeight,
            supportHeight = height,
            normalX = normal[0],
            normalY = normal[1],
            normalZ = normal[2],
            signedDistance = (probeY - height).toFloat(),
            surfaceClass = cell.surfaceClass,
            friction = cell.friction,
            terrainRevision = revision,
            authority = ContactAuthority.HEIGHT_FIELD,
            invalidReason = null,
            layerId = cell.layerId,
            queryMode = SupportQueryMode.DOWNWARD_SAMPLE,
        )
    }

    private fun invalid(
        worldX: Double,
        probeY: Double,
        worldZ: Double,
        reason: SupportInvalidReason,
        surfaceClass: SurfaceClass,
    ) = HeightFieldContact(
        valid = false,
        worldX = worldX,
        worldY = Double.NaN,
        worldZ = worldZ,
        rawHeight = Float.NaN,
        supportHeight = Float.NaN,
        normalX = 0f,
        normalY = 0f,
        normalZ = 0f,
        signedDistance = Float.NaN,
        surfaceClass = surfaceClass,
        friction = 0f,
        terrainRevision = revision,
        authority = ContactAuthority.NONE,
        invalidReason = reason,
        layerId = -1,
        queryMode = SupportQueryMode.DOWNWARD_SAMPLE,
    )

    private fun heightAt(cell: SupportColumn, fx: Float, fz: Float): Float {
        val x0 = cell.corner00 * (1f - fx) + cell.corner10 * fx
        val x1 = cell.corner01 * (1f - fx) + cell.corner11 * fx
        return x0 * (1f - fz) + x1 * fz
    }

    private fun normalAt(cell: SupportColumn, fx: Float, fz: Float): FloatArray {
        val dhdx = (cell.corner10 - cell.corner00) * (1f - fz) + (cell.corner11 - cell.corner01) * fz
        val dhdz = (cell.corner01 - cell.corner00) * (1f - fx) + (cell.corner11 - cell.corner10) * fx
        val nx = -dhdx
        val ny = 1f
        val nz = -dhdz
        val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1.0e-6f)
        return floatArrayOf(nx / length, ny / length, nz / length)
    }
}
