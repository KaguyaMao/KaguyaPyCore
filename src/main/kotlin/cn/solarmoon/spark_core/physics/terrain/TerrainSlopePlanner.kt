package cn.solarmoon.spark_core.physics.terrain

/**
 * Vehicle-only slope ramps for climbable one-block height differences.
 *
 * The planner consumes the detached [SectionSnapshot] only. A column carries a
 * surface for every story of full cubes it stacks (a tunnel floor under a solid
 * roof, ground under a deck); a story's surface is its topmost *full* cube, the
 * one-block horizontal margin extends it across section borders, and a ramp is
 * emitted only when the low side offers a flat, clear run behind the step.
 * Everything else - taller steps, diagonal staircases, blocked runs, unknown
 * neighbour chunks - keeps the original block collision and is handled exactly
 * as before.
 */
object TerrainSlopePlanner {
    /** Highest raw surface step converted into a ramp. */
    const val MAX_STEP_BLOCKS = 1

    /** Horizontal run of a ramp. One block of rise over six blocks of run is
     * about 9.5 degrees: gentle enough that a long tracked hull rides the whole
     * incline instead of being levered up on its nose, and still inside the
     * friction budget of the continuous track support. */
    const val RUN_BLOCKS = 6

    /** Defensive per-section cap; deterministic order keeps rebuilds stable. */
    const val MAX_SLOPES = 256

    /** `low + 2` must still be a readable cell of this section. */
    private const val MAX_LOW_LAYER = 13

    private const val TOO_HIGH = SectionSnapshot.MARGIN_TOO_HIGH

    fun build(snapshot: SectionSnapshot): List<TerrainShapePlan.ShapeSlope> {
        // Per-column bitmask of full-cube local Y values, then the bitmask of
        // that column's readable story surfaces. Every story top is kept, so an
        // unrelated layer overhead (a roof, a deck) can never hide a legitimate
        // one-block step on the ground below it.
        val solids = IntArray(256)
        val occupied = HashSet<Int>()
        for (block in snapshot.blocks) {
            occupied.add(block.x or (block.y shl 4) or (block.z shl 8))
            if (!block.fullCube) continue
            val column = block.x or (block.z shl 4)
            solids[column] = solids[column] or (1 shl block.y)
        }
        // The section's top layer may continue into the section above, so a
        // cube on it never reads as a story top here. Margin columns only
        // expose their single topmost cube; anything lower stays unread and
        // keeps the original block collision exactly as before.
        val surfaces = IntArray(256)
        for (column in 0 until 256) {
            val mask = solids[column]
            var tops = 0
            for (y in 0..14) {
                if ((mask shr y) and 1 == 1 && (mask shr (y + 1)) and 1 == 0) tops = tops or (1 shl y)
            }
            surfaces[column] = tops
        }
        fun hasSurface(x: Int, z: Int, level: Int): Boolean {
            if (x in 0..15 && z in 0..15) {
                return level <= 14 && (surfaces[x or (z shl 4)] shr level) and 1 == 1
            }
            if (level == TOO_HIGH) return false
            return snapshot.marginTop(x, z) == level
        }
        fun air(x: Int, y: Int, z: Int): Boolean =
            !occupied.contains(x or (y shl 4) or (z shl 8))

        // (direction index, run-axis coordinate, low top) -> lateral columns.
        val candidates = LinkedHashMap<Triple<Int, Int, Int>, ArrayList<Int>>()
        val directions = intArrayOf(1, 0, -1, 0, 0, 1, 0, -1)
        for (dir in 0 until 4) {
            val dx = directions[dir * 2]
            val dz = directions[dir * 2 + 1]
            for (x in 0..15) for (z in 0..15) for (low in 0..MAX_LOW_LAYER) {
                if ((surfaces[x or (z shl 4)] shr low) and 1 == 0) continue
                // The step is judged against the raw terrain: exactly one block
                // of rise, never split or chained to smuggle in taller cliffs.
                if (!hasSurface(x + dx, z + dz, low + MAX_STEP_BLOCKS)) continue
                val c1x = x - dx; val c1z = z - dz
                val c2x = x - dx - dx; val c2z = z - dz - dz
                if (c1x < 0 || c1x > 15 || c1z < 0 || c1z > 15) continue
                if (c2x < 0 || c2x > 15 || c2z < 0 || c2z > 15) continue
                if (!hasSurface(c1x, c1z, low)) continue
                if (!hasSurface(c2x, c2z, low)) continue
                // The wedge's leading edge is carried one block further back
                // and sunk below the ground plane (see TerrainSlopeBodies): a
                // wedge ending in a zero-height knife line lets a flat track
                // plate pin on that line, while the continued incline only
                // offers the clean face. The tail needs the same solid run and
                // headroom as the visible wedge.
                val c3x = x - dx - dx - dx; val c3z = z - dz - dz - dz
                if (c3x < 0 || c3x > 15 || c3z < 0 || c3z > 15) continue
                if (!hasSurface(c3x, c3z, low)) continue
                // The whole wedge volume plus one block of clearance must be
                // free air: no buried ramps, no filling low ceilings.
                if (!air(x, low + 1, z) || !air(x, low + 2, z)) continue
                if (!air(c1x, low + 1, c1z) || !air(c1x, low + 2, c1z)) continue
                if (!air(c2x, low + 1, c2z) || !air(c2x, low + 2, c2z)) continue
                if (!air(c3x, low + 1, c3z) || !air(c3x, low + 2, c3z)) continue
                val runAxis = if (dx != 0) x else z
                val lateral = if (dx != 0) z else x
                candidates.getOrPut(Triple(dir, runAxis, low)) { ArrayList(4) }.add(lateral)
            }
        }

        val slopes = ArrayList<TerrainShapePlan.ShapeSlope>()
        for ((key, lateral) in candidates) {
            if (slopes.size >= MAX_SLOPES) break
            val dir = key.first
            val runAxis = key.second
            val low = key.third
            lateral.sort()
            var start = 0
            while (start < lateral.size && slopes.size < MAX_SLOPES) {
                var end = start
                while (end + 1 < lateral.size && lateral[end + 1] == lateral[end] + 1) end++
                slopes.add(ramp(dir, runAxis, low, lateral[start], lateral[end]))
                start = end + 1
            }
        }
        return java.util.List.copyOf(slopes)
    }

    /** Axis-aligned ramp whose low edge sits [RUN_BLOCKS] before the step face.
     * Coordinates are section-local metres relative to the section centre. */
    private fun ramp(dir: Int, runAxis: Int, low: Int, from: Int, to: Int): TerrainShapePlan.ShapeSlope {
        val halfWidth = (to - from + 1) / 2f
        val center = from + halfWidth - 8f
        val lowY = low + 1 - 8f
        val highY = low + 2 - 8f
        return when (dir) {
            0 -> TerrainShapePlan.ShapeSlope(
                lowX = runAxis + 1 - RUN_BLOCKS - 8f, lowZ = center, lowY = lowY,
                highX = runAxis + 1 - 8f, highZ = center, highY = highY, halfWidth = halfWidth)
            1 -> TerrainShapePlan.ShapeSlope(
                lowX = runAxis + RUN_BLOCKS - 8f, lowZ = center, lowY = lowY,
                highX = runAxis.toFloat() - 8f, highZ = center, highY = highY, halfWidth = halfWidth)
            2 -> TerrainShapePlan.ShapeSlope(
                lowX = center, lowZ = runAxis + 1 - RUN_BLOCKS - 8f, lowY = lowY,
                highX = center, highZ = runAxis + 1 - 8f, highY = highY, halfWidth = halfWidth)
            else -> TerrainShapePlan.ShapeSlope(
                lowX = center, lowZ = runAxis + RUN_BLOCKS - 8f, lowY = lowY,
                highX = center, highZ = runAxis.toFloat() - 8f, highY = highY, halfWidth = halfWidth)
        }
    }
}
