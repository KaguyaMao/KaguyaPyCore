package cn.solarmoon.spark_core.physics.terrain

/**
 * Immutable plain-data collision plan for one section.
 *
 * [boxes] are already merged rectangular prisms for full cubes plus the
 * authored voxel boxes of partial shapes. There are no vehicle-only wedges:
 * terrain collision is exactly the Minecraft block collision geometry.
 */
data class TerrainShapePlan(
    val sectionPos: net.minecraft.core.SectionPos,
    val revision: Long,
    val boxes: List<ShapeBox>,
) {
    data class ShapeBox(
        val centerX: Float,
        val centerY: Float,
        val centerZ: Float,
        val halfX: Float,
        val halfY: Float,
        val halfZ: Float
    )

    companion object {
        fun build(snapshot: SectionSnapshot): TerrainShapePlan {
            val output = ArrayList<ShapeBox>()
            val full = snapshot.blocks.filter { it.fullCube }.associateBy { Triple(it.x, it.y, it.z) }
            val consumed = HashSet<Triple<Int, Int, Int>>()
            for (y in 0 until 16) for (z in 0 until 16) {
                var x = 0
                while (x < 16) {
                    val key = Triple(x, y, z)
                    if (key !in full || key in consumed) { x++; continue }
                    var end = x + 1
                    fun available(px: Int, py: Int, pz: Int): Boolean {
                        val cell = Triple(px, py, pz)
                        return cell in full && cell !in consumed
                    }
                    while (end < 16 && available(end, y, z)) end++
                    // Merge complete rectangular prisms, not just X rows.
                    // Internal row seams give convex sweeps rounded edge normals
                    // even on a perfectly level floor. Never bridge a missing voxel.
                    var endZ = z + 1
                    while (endZ < 16 && (x until end).all { available(it, y, endZ) }) endZ++
                    var endY = y + 1
                    while (endY < 16 && (z until endZ).all { pz ->
                        (x until end).all { px -> available(px, endY, pz) }
                    }) endY++
                    for (py in y until endY) for (pz in z until endZ) for (px in x until end)
                        consumed.add(Triple(px, py, pz))
                    val width = end - x
                    val height = endY - y
                    val depth = endZ - z
                    output.add(ShapeBox(x + width / 2f - 8f, y + height / 2f - 8f, z + depth / 2f - 8f,
                        width / 2f, height / 2f, depth / 2f))
                    x = end
                }
            }
            snapshot.blocks.filterNot { it.fullCube }.forEach { block ->
                block.boxes.forEach { box ->
                    output.add(
                        ShapeBox(
                            (block.x + (box.minX + box.maxX) / 2.0 - 8.0).toFloat(),
                            (block.y + (box.minY + box.maxY) / 2.0 - 8.0).toFloat(),
                            (block.z + (box.minZ + box.maxZ) / 2.0 - 8.0).toFloat(),
                            ((box.maxX - box.minX) / 2.0).toFloat(),
                            ((box.maxY - box.minY) / 2.0).toFloat(),
                            ((box.maxZ - box.minZ) / 2.0).toFloat()
                        )
                    )
                }
            }
            return TerrainShapePlan(snapshot.sectionPos, snapshot.revision, java.util.List.copyOf(output))
        }
    }
}
