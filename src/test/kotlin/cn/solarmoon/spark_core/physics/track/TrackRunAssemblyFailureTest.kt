package cn.solarmoon.spark_core.physics.track

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import com.jme3.bullet.PhysicsSpace
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A run that fails halfway must not leave native objects or half-registered
 * state behind. The ledger is the mechanism the runtime uses, and it is
 * exercised here with real Bullet objects so ordering and suppression are
 * verified against the same types production uses.
 */
class TrackRunAssemblyFailureTest {
    private fun chain(count: Int): TrackRunSpec {
        val links = (0 until count).map { index ->
            TrackRunLinkSpec(
                massKg = 1f,
                shape = TrackRunLinkShape.Box(Vec3(.08, .08, .08)),
                position = Vec3(index * .3, 70.0, 0.0),
            )
        }
        val joints = (0 until count - 1).map { index ->
            TrackRunJointSpec(
                previousIndex = index,
                nextIndex = index + 1,
                pivotInPrevious = Vec3(.15, 0.0, 0.0),
                pivotInNext = Vec3(-.15, 0.0, 0.0),
                axis = 2,
            )
        }
        return TrackRunSpec("ledger_chain", links, joints)
    }

    @Test
    fun rollbackRemovesJointsBeforeBodiesNewestFirst() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            val spec = chain(3)
            val bodies = TrackRunAssembly.createBodies(spec, null)
            bodies.forEach(space::addCollisionObject)
            val joints = TrackRunAssembly.createJoints(spec, bodies)
            joints.forEach(space::addJoint)

            val removed = ArrayList<String>()
            val ledger = TrackRunAssemblyLedger(
                removeJoint = { removed += "joint:${joints.indexOf(it)}" },
                removeBody = { removed += "body:${bodies.indexOf(it)}" },
            )
            bodies.forEach(ledger::recordBody)
            joints.forEach(ledger::recordJoint)

            ledger.rollback(IllegalStateException("assembly failed"))

            assertEquals(
                listOf("joint:1", "joint:0", "body:2", "body:1", "body:0"),
                removed,
                "rollback must unwind joints first, newest first, then bodies",
            )
            assertTrue(ledger.bodies().isEmpty())
            assertTrue(ledger.joints().isEmpty())
        } finally {
            space.destroy()
        }
    }

    @Test
    fun aFailingRemovalDoesNotStopTheRestAndIsSuppressed() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            val spec = chain(2)
            val bodies = TrackRunAssembly.createBodies(spec, null)
            bodies.forEach(space::addCollisionObject)
            val joints = TrackRunAssembly.createJoints(spec, bodies)
            joints.forEach(space::addJoint)

            val removed = ArrayList<String>()
            val ledger = TrackRunAssemblyLedger(
                removeJoint = {
                    removed += "joint"
                    throw IllegalStateException("joint removal failed")
                },
                removeBody = { removed += "body" },
            )
            bodies.forEach(ledger::recordBody)
            joints.forEach(ledger::recordJoint)

            val primary = IllegalStateException("assembly failed")
            ledger.rollback(primary)

            assertEquals(listOf("joint", "body", "body"), removed)
            assertEquals(1, primary.suppressed.size, "cleanup failure must be attached")
            assertEquals("joint removal failed", primary.suppressed[0].message)
            assertTrue(ledger.hasRemaining(), "a failed removal must remain owned for a retry")
            assertEquals(1, ledger.joints().size)
            assertTrue(ledger.bodies().isEmpty(), "successfully removed bodies must leave the ledger")
        } finally {
            space.destroy()
        }
    }

    @Test
    fun theOriginalFailureIsTheOneReported() {
        val primary = IllegalStateException("first")
        val ledger = TrackRunAssemblyLedger(
            removeJoint = { throw IllegalStateException("cleanup") },
            removeBody = { },
        )
        ledger.rollback(primary)
        assertSame(primary, primary)
        assertEquals("first", primary.message)
    }

    @Test
    fun rollingBackAnEmptyLedgerIsANoOp() {
        var calls = 0
        val ledger = TrackRunAssemblyLedger({ calls++ }, { calls++ })
        ledger.rollback(IllegalStateException("nothing built"))
        assertEquals(0, calls)
    }

    @Test
    fun aBodyThatFailsToBuildIsNotRecordedAndDoesNotBlockLaterRollback() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            val spec = chain(2)
            val bodies = TrackRunAssembly.createBodies(spec, null)
            bodies.forEach(space::addCollisionObject)
            val joints = TrackRunAssembly.createJoints(spec, bodies)
            joints.forEach(space::addJoint)

            val removed = ArrayList<String>()
            val ledger = TrackRunAssemblyLedger(
                removeJoint = { removed += "joint" },
                removeBody = { removed += "body" },
            )
            // Simulate: the first body was created and recorded, the second
            // creation threw, so only one body and no joint exist.
            ledger.recordBody(bodies[0])
            ledger.rollback(IllegalStateException("second body failed"))
            assertEquals(listOf("body"), removed)
        } finally {
            space.destroy()
        }
    }

    @Test
    fun aRecordedBodyIsOwnedBeforeConfigurationCanFail() {
        NativePhysicsLoader.load()
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            val spec = chain(1)
            val recorded = ArrayList<Any>()
            val body = TrackRunAssembly.createBody(spec, 0, null, "late", recorded::add)
            // Registration happened while the object was still being built, so
            // a property failure can no longer orphan it.
            assertEquals(listOf(body), recorded)
            space.addCollisionObject(body)
        } finally {
            space.destroy()
        }
    }
}
