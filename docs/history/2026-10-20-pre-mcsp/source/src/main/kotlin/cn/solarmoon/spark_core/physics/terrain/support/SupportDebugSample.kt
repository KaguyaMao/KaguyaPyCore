package cn.solarmoon.spark_core.physics.terrain.support

/**
 * Detached cell for the in-game height-field overlay. It is not a contact and
 * not an acceptance sample. Invalid and missing cells carry [markerY] only so a
 * cross can be drawn; that Y is not a support height.
 */
data class SupportDebugCell(
    val x: Int,
    val z: Int,
    val status: SupportDebugStatus,
    val y00: Float,
    val y10: Float,
    val y01: Float,
    val y11: Float,
)

enum class SupportDebugStatus {
    MISSING,
    INVALID,
    SOFT,
    HARD,
}

object SupportDebugSampler {
    const val MAX_RADIUS = 16

    fun sample(
        fields: List<TerrainSupportField>,
        centerX: Int,
        centerZ: Int,
        markerY: Float,
        minY: Float,
        maxY: Float,
        radius: Int,
    ): List<SupportDebugCell> {
        val clamped = radius.coerceIn(1, MAX_RADIUS)
        val out = ArrayList<SupportDebugCell>()
        for (z in centerZ - clamped..centerZ + clamped) {
            for (x in centerX - clamped..centerX + clamped) {
                val layers = fields
                    .filter { covers(it, x, z) }
                    .flatMap { it.layersAt(x - it.originX, z - it.originZ) }
                if (layers.isEmpty() && fields.none { covers(it, x, z) }) {
                    out += marker(x, z, SupportDebugStatus.MISSING, markerY)
                    continue
                }
                val inWindow = layers.filter { it.valid && overlaps(it, minY, maxY) }
                if (inWindow.isNotEmpty()) {
                    for (cell in inWindow) out += surface(x, z, cell)
                    continue
                }
                if (layers.any { it.valid }) continue
                if (fields.any { covers(it, x, z) }) out += marker(x, z, SupportDebugStatus.INVALID, markerY)
                else out += marker(x, z, SupportDebugStatus.MISSING, markerY)
            }
        }
        return out
    }

    private fun covers(field: TerrainSupportField, x: Int, z: Int): Boolean =
        x >= field.originX && x < field.originX + field.sizeX &&
            z >= field.originZ && z < field.originZ + field.sizeZ

    private fun overlaps(cell: SupportColumn, minY: Float, maxY: Float): Boolean {
        val low = minOf(cell.corner00, cell.corner10, cell.corner01, cell.corner11)
        val high = maxOf(cell.corner00, cell.corner10, cell.corner01, cell.corner11)
        return high >= minY && low <= maxY
    }

    private fun surface(x: Int, z: Int, cell: SupportColumn): SupportDebugCell {
        val status = if (cell.surfaceClass == SurfaceClass.SOLID_HARD) {
            SupportDebugStatus.HARD
        } else {
            SupportDebugStatus.SOFT
        }
        return SupportDebugCell(x, z, status, cell.corner00, cell.corner10, cell.corner01, cell.corner11)
    }

    private fun marker(x: Int, z: Int, status: SupportDebugStatus, y: Float) =
        SupportDebugCell(x, z, status, y, y, y, y)
}
