package cn.solarmoon.spark_core.physics.body

/**
 * Collision decision rules, kept free of native objects so the owner filter can
 * be tested without a physics world.
 *
 * A pair collides when either body's group appears in the other body's
 * `collideWithGroups` mask. Two bodies with the same non-null owner are then
 * filtered unless at least one of them explicitly lists the other's group in
 * `collideWithOwnerGroups`.
 *
 * That second mask is an allow list, not a deny list: leaving it at
 * [CollisionGroups.NONE] filters every same-owner pair, including a track link
 * against its own vehicle's wheels.
 */
internal object CollisionRules {
    fun masksAllow(groupA: Int, collideWithA: Int, groupB: Int, collideWithB: Int): Boolean =
        (groupA and collideWithB) != 0 || (groupB and collideWithA) != 0

    fun ownerAllows(ownerMaskA: Int, groupB: Int, ownerMaskB: Int, groupA: Int): Boolean =
        (ownerMaskA and groupB) != 0 || (ownerMaskB and groupA) != 0

    fun shouldCollide(
        groupA: Int,
        collideWithA: Int,
        ownerMaskA: Int,
        groupB: Int,
        collideWithB: Int,
        ownerMaskB: Int,
        sameOwner: Boolean,
    ): Boolean {
        if (!masksAllow(groupA, collideWithA, groupB, collideWithB)) return false
        if (!sameOwner) return true
        return ownerAllows(ownerMaskA, groupB, ownerMaskB, groupA)
    }
}
