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
    /**
     * Opt-in height-field support surface. Not part of [DRIVABLE]: the live chunk
     * path still emits block tops and slope wedges, so adding this bit there would
     * double-support the same patch.
     */
    const val HEIGHT_FIELD = 1 shl 8
    /**
     * Sides, partial shapes, and volumes whose upward top is not owned by the
     * height field. Not part of [DRIVABLE].
     */
    const val STRUCTURAL = 1 shl 9
    /**
     * Opt-in mask for the arbitrated support pair. It excludes [TERRAIN] and
     * [SLOPE], so a body using it cannot also rest on a raw block top or wedge.
     */
    const val SUPPORT_PAIR = HEIGHT_FIELD or STRUCTURAL
    /** Terrain group mask for drivable ground: block boxes plus slope ramps. */
    const val DRIVABLE = TERRAIN or SLOPE
    const val ALL = -1
}
