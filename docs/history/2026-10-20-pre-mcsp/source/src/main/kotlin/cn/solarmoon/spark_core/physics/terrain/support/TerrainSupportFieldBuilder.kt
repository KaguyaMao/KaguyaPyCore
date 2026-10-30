package cn.solarmoon.spark_core.physics.terrain.support

import cn.solarmoon.spark_core.physics.terrain.SectionSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Caller-supplied smoothing policy. There is no hidden vehicle length and no
 * default that pretends to be an accepted climb limit.
 *
 * [maxDownwardCorrection] is a cumulative cap relative to each column's raw
 * height, not a fresh budget per iteration. The comparison is inclusive.
 */
data class SupportFieldPolicy(
    val maxDownwardCorrection: Float,
    val maxRiseForSupportTransition: Float?,
    val smoothIterations: Int,
) {
    init {
        require(maxDownwardCorrection >= 0f) { "downward correction cannot be negative" }
        require(smoothIterations >= 0) { "iterations cannot be negative" }
        maxRiseForSupportTransition?.let {
            require(it >= 0f) { "support transition cannot be negative" }
            require(it < TerrainSupportFieldBuilder.STRUCTURAL_RISE_BLOCKS) {
                "support transition must stay below the frozen 2-block structural face"
            }
        }
    }
}

/**
 * Builds a support tile from a detached [SectionSnapshot] only.
 * World, chunk, block state, and Bullet types are intentionally absent.
 *
 * A tile does not import the section above as its own ground. Above-section
 * evidence only proves whether a ceiling surface is exposed, occluded, or unknown.
 */
object TerrainSupportFieldBuilder {
    /** Frozen constraint: a continuous face of two blocks or more is never a support ramp. */
    const val STRUCTURAL_RISE_BLOCKS = 2f
    const val CELLS = 16
    private const val EPS = 1.0e-4f

    fun build(snapshot: SectionSnapshot, policy: SupportFieldPolicy): TerrainSupportField {
        val originX = snapshot.sectionPos.minBlockX()
        val originY = snapshot.sectionPos.minBlockY()
        val originZ = snapshot.sectionPos.minBlockZ()
        val columns = when (snapshot.coverage) {
            SectionCoverage.UNREAD -> unreadColumns()
            SectionCoverage.AIR -> airColumns()
            SectionCoverage.PRESENT -> presentColumns(snapshot, originY, policy)
        }
        return TerrainSupportField(
            originX = originX,
            originY = originY,
            originZ = originZ,
            cellSize = 1f,
            revision = snapshot.revision,
            sizeX = CELLS,
            sizeZ = CELLS,
            columns = columns,
        )
    }

    private fun unreadColumns(): List<SupportColumn> = filledColumns { x, z ->
        invalidColumn(x, z, SurfaceClass.UNKNOWN, SupportInvalidReason.UNKNOWN).freeze()
    }

    private fun airColumns(): List<SupportColumn> = filledColumns { x, z ->
        invalidColumn(x, z, SurfaceClass.AIR, SupportInvalidReason.AIR).freeze()
    }

    private fun presentColumns(
        snapshot: SectionSnapshot,
        originY: Int,
        policy: SupportFieldPolicy,
    ): List<SupportColumn> {
        val byColumn = snapshot.blocks.groupBy { it.x to it.z }
        val grid = Array(CELLS) { x ->
            Array(CELLS) { z ->
                surfacesAt(x, z, byColumn[x to z].orEmpty(), originY, aboveAt(snapshot, x, z, originY))
            }
        }
        assignLayers(grid)
        markStructuralNeighbors(grid)
        repeat(policy.smoothIterations) { smoothOnce(grid, policy) }
        smoothBoundary(grid, snapshot, policy, originY)
        return grid.flatMap { row -> row.flatMap { surfaces -> surfaces.map { it.freeze() } } }
    }

    private fun surfacesAt(
        x: Int,
        z: Int,
        blocks: List<SectionSnapshot.BlockShapeSnapshot>,
        originY: Int,
        above: AboveColumn,
    ): List<WorkingSurface> {
        if (blocks.isEmpty()) {
            return listOf(invalidColumn(x, z, SurfaceClass.AIR, SupportInvalidReason.AIR))
        }
        var partial = false
        val intervals = ArrayList<Interval>()
        for (block in blocks) {
            if (block.boxes.isEmpty()) continue
            for (box in block.boxes) {
                if (!coversFullCell(box)) {
                    partial = true
                    continue
                }
                intervals += Interval(
                    bottom = originY + block.y + box.minY.toFloat(),
                    top = originY + block.y + box.maxY.toFloat(),
                    surfaceClass = block.surfaceClass,
                    friction = block.friction,
                )
            }
        }
        if (intervals.isEmpty()) {
            val reason = if (partial) SupportInvalidReason.PARTIAL_SHAPE else SupportInvalidReason.NON_GROUND
            return listOf(invalidColumn(x, z, SurfaceClass.NON_GROUND, reason, partial))
        }
        val merged = merge(intervals.sortedBy { it.bottom })
        val sectionTop = originY + CELLS.toFloat()
        return merged.mapIndexed { index, interval ->
            val next = merged.getOrNull(index + 1)
            val surface = WorkingSurface(
                localX = x,
                localZ = z,
                rawHeight = interval.top,
                surfaceClass = interval.surfaceClass,
                friction = interval.friction,
                hasUnresolvedPartial = partial,
            )
            if (next != null) {
                surface.clearanceAbove = next.bottom - interval.top
                surface.clearanceKnowledge = ClearanceKnowledge.BOUNDED
            } else {
                applyTopKnowledge(surface, interval.top >= sectionTop - EPS, above, sectionTop)
            }
            applyGroundClass(surface)
            surface
        }
    }

    private fun applyTopKnowledge(
        surface: WorkingSurface,
        reachesCeiling: Boolean,
        above: AboveColumn,
        sectionTop: Float,
    ) {
        val occluder = above.intervals.minByOrNull { it.bottom }
        if (reachesCeiling) {
            when (above.coverage) {
                SectionCoverage.UNREAD -> {
                    surface.valid = false
                    surface.invalidReason = SupportInvalidReason.UNPROVEN_TOP
                    surface.clearanceKnowledge = ClearanceKnowledge.UNPROVEN
                    surface.clearanceAbove = Float.NaN
                }
                SectionCoverage.AIR -> {
                    surface.clearanceKnowledge = ClearanceKnowledge.OPEN_ENDED
                    surface.clearanceAbove = CELLS.toFloat()
                }
                SectionCoverage.PRESENT -> {
                    if (occluder != null && occluder.bottom <= sectionTop + EPS) {
                        surface.valid = false
                        surface.invalidReason = SupportInvalidReason.CONTINUES_ABOVE
                        surface.clearanceKnowledge = ClearanceKnowledge.UNPROVEN
                        surface.clearanceAbove = Float.NaN
                    } else if (occluder != null) {
                        surface.clearanceKnowledge = ClearanceKnowledge.BOUNDED
                        surface.clearanceAbove = occluder.bottom - surface.rawHeight
                    } else {
                        surface.clearanceKnowledge = ClearanceKnowledge.OPEN_ENDED
                        surface.clearanceAbove = 1f
                    }
                }
            }
            return
        }
        val gapToCeiling = sectionTop - surface.rawHeight
        when (above.coverage) {
            SectionCoverage.UNREAD -> {
                surface.clearanceKnowledge = ClearanceKnowledge.OPEN_ENDED
                surface.clearanceAbove = gapToCeiling
            }
            SectionCoverage.AIR -> {
                surface.clearanceKnowledge = ClearanceKnowledge.OPEN_ENDED
                surface.clearanceAbove = gapToCeiling + CELLS
            }
            SectionCoverage.PRESENT -> {
                if (occluder != null) {
                    surface.clearanceKnowledge = ClearanceKnowledge.BOUNDED
                    surface.clearanceAbove = occluder.bottom - surface.rawHeight
                } else {
                    surface.clearanceKnowledge = ClearanceKnowledge.OPEN_ENDED
                    surface.clearanceAbove = gapToCeiling + 1f
                }
            }
        }
    }

    private fun applyGroundClass(surface: WorkingSurface) {
        val ground = surface.surfaceClass == SurfaceClass.SOLID_HARD || surface.surfaceClass == SurfaceClass.SOLID_SOFT
        if (!ground && surface.invalidReason == null) {
            surface.valid = false
            surface.invalidReason = if (surface.surfaceClass == SurfaceClass.UNKNOWN) {
                SupportInvalidReason.UNKNOWN
            } else {
                SupportInvalidReason.NON_GROUND
            }
        }
        if (!surface.valid) {
            surface.corner00 = Float.NaN
            surface.corner10 = Float.NaN
            surface.corner01 = Float.NaN
            surface.corner11 = Float.NaN
            surface.supportHeight = Float.NaN
        }
    }

    private fun assignLayers(grid: Array<Array<List<WorkingSurface>>>) {
        val parent = HashMap<Long, Long>()
        fun find(key: Long): Long {
            var root = key
            while (parent.getOrDefault(root, root) != root) root = parent.getValue(root)
            var cursor = key
            while (cursor != root) {
                val next = parent.getValue(cursor)
                parent[cursor] = root
                cursor = next
            }
            return root
        }
        fun union(left: Long, right: Long) {
            val a = find(left)
            val b = find(right)
            if (a != b) parent[a] = b
        }
        for (z in 0 until CELLS) for (x in 0 until CELLS) {
            grid[x][z].forEachIndexed { index, _ ->
                val key = key(x, z, index)
                parent[key] = key
            }
        }
        for (z in 0 until CELLS) for (x in 0 until CELLS) {
            grid[x][z].forEachIndexed { index, cell ->
                if (!cell.valid) return@forEachIndexed
                for ((nx, nz) in neighbors(x, z)) {
                    grid[nx][nz].forEachIndexed { otherIndex, other ->
                        if (sameLayer(cell, other)) union(key(x, z, index), key(nx, nz, otherIndex))
                    }
                }
            }
        }
        val ids = HashMap<Long, Int>()
        var nextId = 0
        for (z in 0 until CELLS) for (x in 0 until CELLS) {
            grid[x][z].forEachIndexed { index, cell ->
                if (!cell.valid) {
                    cell.layerId = -1
                } else {
                    val root = find(key(x, z, index))
                    cell.layerId = ids.getOrPut(root) { nextId++ }
                }
            }
        }
    }

    /**
     * The one-block margin carries the neighbour section's top and class. A
     * one-block step exactly on a section border must behave like an in-tile
     * step: shave the high side's shared corners. It must never raise the low
     * side, and it never crosses a two-block face or a class boundary.
     */
    private fun smoothBoundary(
        grid: Array<Array<List<WorkingSurface>>>,
        snapshot: SectionSnapshot,
        policy: SupportFieldPolicy,
        originY: Int,
    ) {
        val allowed = policy.maxRiseForSupportTransition ?: return
        if (policy.maxDownwardCorrection <= 0f) return
        for (side in BOUNDARY_SIDES) {
            for (i in 0 until CELLS) {
                val lx = if (side.alongX) side.localX else i
                val lz = if (side.alongX) i else side.localZ
                val mx = if (side.alongX) side.marginX else lx
                val mz = if (side.alongX) lz else side.marginZ
                val top = snapshot.marginTop(mx, mz)
                if (top == SectionSnapshot.MARGIN_UNKNOWN || top == SectionSnapshot.MARGIN_TOO_HIGH) continue
                val neighborClass = snapshot.marginClass(mx, mz) ?: continue
                val neighborRaw = originY + top + 1f
                for (cell in grid[lx][lz]) {
                    if (!cell.valid || cell.surfaceClass != neighborClass) continue
                    val rise = cell.rawHeight - neighborRaw
                    if (rise <= EPS || rise > allowed || rise >= STRUCTURAL_RISE_BLOCKS) continue
                    val floor = cell.rawHeight - policy.maxDownwardCorrection
                    if (neighborRaw < floor - EPS) continue
                    when {
                        side.alongX && side.marginX > 0 -> {
                            cell.corner10 = min(cell.corner10, neighborRaw)
                            cell.corner11 = min(cell.corner11, neighborRaw)
                        }
                        side.alongX -> {
                            cell.corner00 = min(cell.corner00, neighborRaw)
                            cell.corner01 = min(cell.corner01, neighborRaw)
                        }
                        side.marginZ > 0 -> {
                            cell.corner01 = min(cell.corner01, neighborRaw)
                            cell.corner11 = min(cell.corner11, neighborRaw)
                        }
                        else -> {
                            cell.corner00 = min(cell.corner00, neighborRaw)
                            cell.corner10 = min(cell.corner10, neighborRaw)
                        }
                    }
                    cell.supportHeight = min(min(cell.corner00, cell.corner10), min(cell.corner01, cell.corner11))
                }
            }
        }
    }

    private data class BoundarySide(
        val localX: Int,
        val localZ: Int,
        val marginX: Int,
        val marginZ: Int,
        val alongX: Boolean,
    )

    private val BOUNDARY_SIDES = listOf(
        BoundarySide(15, 0, 16, 0, true),
        BoundarySide(0, 0, -1, 0, true),
        BoundarySide(0, 15, 0, 16, false),
        BoundarySide(0, 0, 0, -1, false),
    )

    private fun sameLayer(cell: WorkingSurface, other: WorkingSurface): Boolean {
        if (!cell.valid || !other.valid || cell.surfaceClass != other.surfaceClass) return false
        val rise = abs(cell.rawHeight - other.rawHeight)
        if (rise >= STRUCTURAL_RISE_BLOCKS) return false
        val lower = if (cell.rawHeight <= other.rawHeight) cell else other
        val higher = if (cell.rawHeight <= other.rawHeight) other else cell
        if (lower.clearanceKnowledge != ClearanceKnowledge.UNPROVEN &&
            higher.rawHeight > lower.rawHeight + lower.clearanceAbove + EPS
        ) {
            return false
        }
        return true
    }

    private fun markStructuralNeighbors(grid: Array<Array<List<WorkingSurface>>>) {
        for (z in 0 until CELLS) for (x in 0 until CELLS) {
            for (cell in grid[x][z]) {
                if (!cell.valid) continue
                cell.hasStructuralNeighbor = neighbors(x, z).any { (nx, nz) ->
                    grid[nx][nz].any { other ->
                        other.valid && abs(cell.rawHeight - other.rawHeight) >= STRUCTURAL_RISE_BLOCKS
                    }
                }
            }
        }
    }

    private fun smoothOnce(grid: Array<Array<List<WorkingSurface>>>, policy: SupportFieldPolicy) {
        val next = Array(CELLS) { x ->
            Array(CELLS) { z ->
                grid[x][z].map { it.copyCorners() }.toTypedArray()
            }
        }
        for (z in 0 until CELLS) for (x in 0 until CELLS) {
            grid[x][z].forEachIndexed { index, cell ->
                if (!cell.valid) return@forEachIndexed
                val floor = cell.rawHeight - policy.maxDownwardCorrection
                var c00 = cell.corner00
                var c10 = cell.corner10
                var c01 = cell.corner01
                var c11 = cell.corner11
                for ((nx, nz) in neighbors(x, z)) {
                    val other = grid[nx][nz].firstOrNull { canSmoothToward(cell, it, policy) } ?: continue
                    val shared = sharedCorners(nx - x, nz - z, other)
                    c00 = min(c00, max(shared.c00, floor))
                    c10 = min(c10, max(shared.c10, floor))
                    c01 = min(c01, max(shared.c01, floor))
                    c11 = min(c11, max(shared.c11, floor))
                }
                next[x][z][index] = CornerPatch(c00, c10, c01, c11)
            }
        }
        for (z in 0 until CELLS) for (x in 0 until CELLS) {
            grid[x][z].forEachIndexed { index, cell ->
                if (!cell.valid) return@forEachIndexed
                val patch = next[x][z][index]
                cell.corner00 = patch.c00
                cell.corner10 = patch.c10
                cell.corner01 = patch.c01
                cell.corner11 = patch.c11
                cell.supportHeight = min(min(patch.c00, patch.c10), min(patch.c01, patch.c11))
            }
        }
    }

    private fun canSmoothToward(cell: WorkingSurface, other: WorkingSurface, policy: SupportFieldPolicy): Boolean {
        if (!cell.valid || !other.valid || cell.layerId < 0 || cell.layerId != other.layerId) return false
        val allowed = policy.maxRiseForSupportTransition ?: return false
        if (policy.maxDownwardCorrection <= 0f) return false
        val rise = cell.rawHeight - other.rawHeight
        return rise > 0f && rise <= allowed && rise < STRUCTURAL_RISE_BLOCKS
    }

    /**
     * Neighbor corner values as seen from this cell. Corners that do not touch
     * the shared edge stay at this cell's own height so a lower neighbor cannot
     * raise, or indirectly lower, the far side.
     */
    private fun sharedCorners(stepX: Int, stepZ: Int, other: WorkingSurface): CornerPatch {
        return when {
            stepX == -1 && stepZ == 0 -> CornerPatch(other.corner10, Float.POSITIVE_INFINITY, other.corner11, Float.POSITIVE_INFINITY)
            stepX == 1 && stepZ == 0 -> CornerPatch(Float.POSITIVE_INFINITY, other.corner00, Float.POSITIVE_INFINITY, other.corner01)
            stepX == 0 && stepZ == -1 -> CornerPatch(other.corner01, other.corner11, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            stepX == 0 && stepZ == 1 -> CornerPatch(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, other.corner00, other.corner10)
            else -> CornerPatch(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        }
    }

    private fun aboveAt(snapshot: SectionSnapshot, x: Int, z: Int, originY: Int): AboveColumn {
        if (snapshot.aboveCoverage != SectionCoverage.PRESENT) {
            return AboveColumn(snapshot.aboveCoverage, emptyList())
        }
        val intervals = ArrayList<Interval>()
        for (block in snapshot.aboveBottomBlocks) {
            if (block.x != x || block.z != z) continue
            for (box in block.boxes) {
                if (!coversFullCell(box)) continue
                intervals += Interval(
                    bottom = originY + CELLS + box.minY.toFloat(),
                    top = originY + CELLS + box.maxY.toFloat(),
                    surfaceClass = block.surfaceClass,
                    friction = block.friction,
                )
            }
        }
        return AboveColumn(SectionCoverage.PRESENT, merge(intervals.sortedBy { it.bottom }))
    }

    private fun coversFullCell(box: net.minecraft.world.phys.AABB): Boolean =
        box.minX <= EPS && box.maxX >= 1.0 - EPS && box.minZ <= EPS && box.maxZ >= 1.0 - EPS

    private fun neighbors(x: Int, z: Int): List<Pair<Int, Int>> = buildList {
        if (x > 0) add(x - 1 to z)
        if (x + 1 < CELLS) add(x + 1 to z)
        if (z > 0) add(x to z - 1)
        if (z + 1 < CELLS) add(x to z + 1)
    }

    private fun merge(sorted: List<Interval>): List<Interval> {
        val out = ArrayList<Interval>()
        for (interval in sorted) {
            val last = out.lastOrNull()
            if (last != null && interval.bottom <= last.top + EPS) {
                out[out.lastIndex] = last.copy(
                    top = max(last.top, interval.top),
                    surfaceClass = if (interval.top >= last.top) interval.surfaceClass else last.surfaceClass,
                    friction = if (interval.top >= last.top) interval.friction else last.friction,
                )
            } else {
                out += interval
            }
        }
        return out
    }

    private fun filledColumns(factory: (Int, Int) -> SupportColumn): List<SupportColumn> =
        buildList {
            for (z in 0 until CELLS) for (x in 0 until CELLS) add(factory(x, z))
        }

    private fun invalidColumn(
        x: Int,
        z: Int,
        surfaceClass: SurfaceClass,
        reason: SupportInvalidReason,
        partial: Boolean = false,
    ) = WorkingSurface(
        localX = x,
        localZ = z,
        rawHeight = Float.NaN,
        surfaceClass = surfaceClass,
        friction = 0f,
        hasUnresolvedPartial = partial,
    ).also {
        it.valid = false
        it.invalidReason = reason
        it.clearanceKnowledge = ClearanceKnowledge.UNPROVEN
        it.clearanceAbove = Float.NaN
        it.corner00 = Float.NaN
        it.corner10 = Float.NaN
        it.corner01 = Float.NaN
        it.corner11 = Float.NaN
        it.supportHeight = Float.NaN
    }

    private fun key(x: Int, z: Int, index: Int): Long =
        (x.toLong() shl 20) or (z.toLong() shl 8) or index.toLong()

    private data class Interval(
        val bottom: Float,
        val top: Float,
        val surfaceClass: SurfaceClass,
        val friction: Float,
    )

    private data class AboveColumn(
        val coverage: SectionCoverage,
        val intervals: List<Interval>,
    )

    private data class CornerPatch(
        val c00: Float,
        val c10: Float,
        val c01: Float,
        val c11: Float,
    )

    private class WorkingSurface(
        val localX: Int,
        val localZ: Int,
        val rawHeight: Float,
        val surfaceClass: SurfaceClass,
        val friction: Float,
        val hasUnresolvedPartial: Boolean,
    ) {
        var valid: Boolean = true
        var supportHeight: Float = rawHeight
        var corner00: Float = rawHeight
        var corner10: Float = rawHeight
        var corner01: Float = rawHeight
        var corner11: Float = rawHeight
        var clearanceAbove: Float = Float.NaN
        var clearanceKnowledge: ClearanceKnowledge = ClearanceKnowledge.UNPROVEN
        var hasStructuralNeighbor: Boolean = false
        var invalidReason: SupportInvalidReason? = null
        var layerId: Int = -1

        fun copyCorners() = CornerPatch(corner00, corner10, corner01, corner11)

        fun freeze() = SupportColumn(
            localX = localX,
            localZ = localZ,
            layerId = layerId,
            valid = valid,
            rawHeight = rawHeight,
            supportHeight = supportHeight,
            corner00 = corner00,
            corner10 = corner10,
            corner01 = corner01,
            corner11 = corner11,
            surfaceClass = surfaceClass,
            friction = friction,
            clearanceAbove = clearanceAbove,
            clearanceKnowledge = clearanceKnowledge,
            hasStructuralNeighbor = hasStructuralNeighbor,
            hasUnresolvedPartial = hasUnresolvedPartial,
            invalidReason = invalidReason,
        )
    }
}
