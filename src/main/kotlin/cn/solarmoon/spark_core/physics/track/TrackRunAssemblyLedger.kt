package cn.solarmoon.spark_core.physics.track

import com.jme3.bullet.joints.New6Dof
import com.jme3.bullet.objects.PhysicsRigidBody

/**
 * Rollback ledger for a partially built track run.
 *
 * Native objects are recorded as soon as they exist, so a failure at any later
 * step can undo exactly what was created. Teardown order matters: joints are
 * removed before the bodies they reference, and every removal is attempted even
 * when an earlier one throws. Cleanup failures are attached to the original
 * error as suppressed exceptions so the caller still sees the real cause.
 *
 * Objects whose removal failed stay in the ledger. The runtime retains that
 * ledger so a later close can retry instead of reporting a success that leaves
 * native objects registered.
 */
internal class TrackRunAssemblyLedger(
    private val removeJoint: (New6Dof) -> Unit,
    private val removeBody: (PhysicsRigidBody) -> Unit,
) {
    private val bodies = ArrayList<PhysicsRigidBody>()
    private val joints = ArrayList<New6Dof>()

    /** Bodies in creation order, including any whose removal failed. */
    fun bodies(): List<PhysicsRigidBody> = bodies

    /** Joints in creation order, including any whose removal failed. */
    fun joints(): List<New6Dof> = joints

    fun recordBody(body: PhysicsRigidBody) {
        bodies += body
    }

    fun recordJoint(joint: New6Dof) {
        joints += joint
    }

    /** True when a previous rollback could not remove every recorded object. */
    fun hasRemaining(): Boolean = bodies.isNotEmpty() || joints.isNotEmpty()

    /**
     * Undo every recorded object, newest first, joint before body.
     *
     * Never throws: the primary failure is what the caller must report, and a
     * teardown error must not replace it. Successfully removed objects leave the
     * ledger; failures stay so [hasRemaining] can drive a retry.
     */
    fun rollback(primary: Throwable): List<Throwable> {
        val failures = ArrayList<Throwable>()
        val jointIterator = joints.listIterator(joints.size)
        while (jointIterator.hasPrevious()) {
            val joint = jointIterator.previous()
            try {
                removeJoint(joint)
                jointIterator.remove()
            } catch (error: Throwable) {
                primary.addSuppressed(error)
                failures += error
            }
        }
        val bodyIterator = bodies.listIterator(bodies.size)
        while (bodyIterator.hasPrevious()) {
            val body = bodyIterator.previous()
            try {
                removeBody(body)
                bodyIterator.remove()
            } catch (error: Throwable) {
                primary.addSuppressed(error)
                failures += error
            }
        }
        return failures
    }
}
