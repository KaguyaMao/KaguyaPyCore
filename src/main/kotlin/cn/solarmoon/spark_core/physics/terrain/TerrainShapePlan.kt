package cn.solarmoon.spark_core.physics.terrain

data class TerrainShapePlan(
    val sectionPos: net.minecraft.core.SectionPos,
    val revision: Long,
    val boxes: List<ShapeBox>,
    val mesh: TriangleMeshPlan? = null,
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
                    while (end < 16 && Triple(end, y, z) in full) end++
                    for (value in x until end) consumed.add(Triple(value, y, z))
                    val width = end - x
                    output.add(ShapeBox(x + width / 2f - 8f, y + .5f - 8f, z + .5f - 8f, width / 2f, .5f, .5f))
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
            return TerrainShapePlan(snapshot.sectionPos, snapshot.revision, java.util.List.copyOf(output), snapshot.mesh)
        }
    }
}
