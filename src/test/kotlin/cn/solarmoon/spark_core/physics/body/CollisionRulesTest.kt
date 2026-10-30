package cn.solarmoon.spark_core.physics.body

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The owner filter is an allow list. A track link must still touch its own
 * vehicle's wheels and chassis while sibling links stay filtered.
 */
class CollisionRulesTest {
    private val terrain = CollisionGroups.TERRAIN
    private val chassis = CollisionGroups.CHASSIS
    private val wheel = CollisionGroups.WHEEL
    private val link = CollisionGroups.TRACK_LINK

    @Test
    fun terrainCollidesBecauseOneMaskListsIt() {
        assertTrue(
            CollisionRules.shouldCollide(
                groupA = terrain, collideWithA = CollisionGroups.NONE, ownerMaskA = CollisionGroups.NONE,
                groupB = link, collideWithB = terrain, ownerMaskB = CollisionGroups.NONE,
                sameOwner = false,
            ),
        )
    }

    @Test
    fun siblingLinksAreFilteredByDefault() {
        assertFalse(
            CollisionRules.shouldCollide(
                groupA = link, collideWithA = terrain or wheel or chassis, ownerMaskA = wheel or chassis,
                groupB = link, collideWithB = terrain or wheel or chassis, ownerMaskB = wheel or chassis,
                sameOwner = true,
            ),
        )
    }

    @Test
    fun aLinkStillTouchesItsOwnWheelAndChassis() {
        val linkAgainstWheel = CollisionRules.shouldCollide(
            groupA = link, collideWithA = terrain or wheel or chassis, ownerMaskA = wheel or chassis,
            groupB = wheel, collideWithB = terrain or link, ownerMaskB = CollisionGroups.NONE,
            sameOwner = true,
        )
        val linkAgainstChassis = CollisionRules.shouldCollide(
            groupA = link, collideWithA = terrain or wheel or chassis, ownerMaskA = wheel or chassis,
            groupB = chassis, collideWithB = terrain or link, ownerMaskB = CollisionGroups.NONE,
            sameOwner = true,
        )
        assertTrue(linkAgainstWheel, "link must collide with its own wheel")
        assertTrue(linkAgainstChassis, "link must collide with its own chassis")
    }

    @Test
    fun aChassisCanAlsoGrantThePairByListingTheLinkGroup() {
        assertTrue(
            CollisionRules.shouldCollide(
                groupA = link, collideWithA = terrain, ownerMaskA = CollisionGroups.NONE,
                groupB = chassis, collideWithB = link, ownerMaskB = link,
                sameOwner = true,
            ),
        )
    }

    @Test
    fun differentOwnersKeepNormalMaskBehaviour() {
        assertTrue(
            CollisionRules.shouldCollide(
                groupA = link, collideWithA = terrain, ownerMaskA = CollisionGroups.NONE,
                groupB = chassis, collideWithB = link, ownerMaskB = CollisionGroups.NONE,
                sameOwner = false,
            ),
        )
        assertFalse(
            CollisionRules.shouldCollide(
                groupA = link, collideWithA = terrain, ownerMaskA = CollisionGroups.NONE,
                groupB = wheel, collideWithB = chassis, ownerMaskB = CollisionGroups.NONE,
                sameOwner = false,
            ),
        )
    }

    @Test
    fun aMissingGroupMaskStillBlocksThePair() {
        assertFalse(
            CollisionRules.shouldCollide(
                groupA = link, collideWithA = CollisionGroups.NONE, ownerMaskA = wheel,
                groupB = wheel, collideWithB = CollisionGroups.NONE, ownerMaskB = link,
                sameOwner = true,
            ),
        )
    }

    @Test
    fun twoOwnerlessBodiesAreNotAutomaticallyTheSameOwner() {
        // sameOwner is decided by identity in PhysicsWorld.needsCollision. Two
        // unrelated bodies both having owner == null must not enter the owner
        // allow-list path; a private run token is what makes sibling links
        // share an owner.
        assertTrue(
            CollisionRules.shouldCollide(
                groupA = CollisionGroups.PHYSICS_BODY, collideWithA = CollisionGroups.PHYSICS_BODY,
                ownerMaskA = CollisionGroups.NONE,
                groupB = CollisionGroups.PHYSICS_BODY, collideWithB = CollisionGroups.PHYSICS_BODY,
                ownerMaskB = CollisionGroups.NONE,
                sameOwner = false,
            ),
        )
    }
}
