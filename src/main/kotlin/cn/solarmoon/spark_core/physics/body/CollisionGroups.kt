package cn.solarmoon.spark_core.physics.body

object CollisionGroups {
    const val NONE = 0
    const val TERRAIN = 1 shl 0
    const val PHYSICS_BODY = 1 shl 1
    const val PAWN = 1 shl 2
    const val TRIGGER = 1 shl 3
    const val CHASSIS = 1 shl 4
    const val WHEEL = 1 shl 5
    /** Individual articulated track shoes.  Kept separate from WHEEL so
     * same-owner link-link pairs can be filtered while links still collide
     * with the chassis, wheels and terrain. */
    const val TRACK_LINK = 1 shl 6
    /** Vehicle-only slope ramps for climbable one-block height differences.
     * Only bodies that explicitly ask for SLOPE collide with them; every other
     * object keeps colliding with the original block boxes. */
    const val SLOPE = 1 shl 7
    /** Terrain group mask for drivable ground: block boxes plus slope ramps. */
    const val DRIVABLE = TERRAIN or SLOPE
    const val ALL = -1
}
