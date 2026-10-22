package cn.solarmoon.spark_core.physics.track

import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Immutable world pose of one track link, captured after a physics substep.
 *
 * Rotation is stored as four floats so a consumer cannot mutate a published
 * snapshot through a shared JOML object. [rotation] returns a fresh copy.
 */
data class TrackRunLinkFrame(
    val position: Vec3,
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float,
    val rotationW: Float,
    val linearVelocity: Vec3,
    val angularVelocity: Vec3,
    val active: Boolean,
    val colliding: Boolean,
) {
    /** A defensive copy; mutating the result never changes this frame. */
    val rotation: Quaternionf
        get() = Quaternionf(rotationX, rotationY, rotationZ, rotationW)

    fun finite(): Boolean {
        if (!position.isFiniteVec() || !linearVelocity.isFiniteVec() || !angularVelocity.isFiniteVec()) return false
        if (!rotationX.isFinite() || !rotationY.isFinite() || !rotationZ.isFinite() || !rotationW.isFinite()) return false
        val lengthSquared = rotationX * rotationX + rotationY * rotationY +
            rotationZ * rotationZ + rotationW * rotationW
        return lengthSquared > 1.0e-8f
    }

    companion object {
        @JvmStatic
        fun of(
            position: Vec3,
            rotation: Quaternionf,
            linearVelocity: Vec3,
            angularVelocity: Vec3,
            active: Boolean,
            colliding: Boolean,
        ): TrackRunLinkFrame = TrackRunLinkFrame(
            position,
            rotation.x,
            rotation.y,
            rotation.z,
            rotation.w,
            linearVelocity,
            angularVelocity,
            active,
            colliding,
        )
    }
}

/**
 * Plain-value observation of one hinge, sampled after a substep.
 *
 * [angleX]/[angleY]/[angleZ] are the constraint coordinates reported by the
 * native joint in its rotation order (radians), not a pose approximation, and
 * they are only valid for the step they were read from.
 *
 * No constraint impulse is published: this wrapper's 6-DoF constraint does not
 * maintain `appliedImpulse` (it reads back as 0 regardless of load), so a load
 * field would be fabricated. Loads must be derived by the caller from contact
 * impulses and applied forces, not from this frame.
 */
data class TrackRunJointFrame(
    val previousIndex: Int,
    val nextIndex: Int,
    val angleX: Float,
    val angleY: Float,
    val angleZ: Float,
    /** Distance between the two world-space pin points, in blocks. */
    val positionError: Float,
    /** Rotation outside the authored hinge range plus locked-axis rotation, in radians. */
    val angleError: Float,
) {
    fun finite(): Boolean =
        angleX.isFinite() && angleY.isFinite() && angleZ.isFinite() &&
            positionError.isFinite() && angleError.isFinite()
}

/**
 * Immutable snapshot of one track run.
 *
 * It holds plain values only. A snapshot stays valid after the run is closed or
 * the physics world is recreated; consumers must never use it to reach a native
 * body. The link list is copied on construction and cannot be modified.
 *
 * [runId] identifies the runtime that produced the snapshot. Two snapshots may
 * only be blended when their [runId] matches and their topology is compatible;
 * a reused name is not enough because a new run may have different links.
 *
 * [poseEpoch] changes when the run is teleported or rebuilt in place. Two
 * snapshots with different epochs describe a discontinuity and must not be
 * blended even though their [runId] and link count still match.
 */
class TrackRunSnapshot @JvmOverloads constructor(
    val name: String,
    val physicsFrame: Long,
    val revision: Long,
    links: List<TrackRunLinkFrame>,
    /** Largest measured hinge position error, or NaN when it was not sampled. */
    val jointPositionError: Float = Float.NaN,
    /** Largest measured hinge angle violation, or NaN when it was not sampled. */
    val jointAngleError: Float = Float.NaN,
    /** Stable identity of the producing runtime; 0 for an unattributed snapshot. */
    val runId: Long = 0L,
    joints: List<TrackRunJointFrame> = emptyList(),
    /** Pose continuity generation; changes on teleport/reset. */
    val poseEpoch: Long = 0L,
    /**
     * Stable Spark body id of each link, in spec order. Consumers that need to
     * associate a link with an external constraint or contact sample use these
     * ids instead of relying on the runtime's internal body names.
     */
    linkBodyIds: List<Long> = emptyList(),
) {
    val linkBodyIds: List<Long> = java.util.List.copyOf(linkBodyIds)
    val links: List<TrackRunLinkFrame> = java.util.List.copyOf(links)

    /** One entry per hinge, in spec order; empty when the run has no joints. */
    val joints: List<TrackRunJointFrame> = java.util.List.copyOf(joints)

    val linkCount: Int get() = links.size

    fun finite(): Boolean = links.all { it.finite() } && joints.all { it.finite() }

    /** True when [other] describes the same run with the same link/joint topology. */
    fun sameTopologyAs(other: TrackRunSnapshot): Boolean =
        runId == other.runId && name == other.name && linkCount == other.linkCount &&
            joints.size == other.joints.size && linkBodyIds == other.linkBodyIds

    /** True when both snapshots belong to the same continuous motion segment. */
    fun samePoseSegmentAs(other: TrackRunSnapshot): Boolean =
        sameTopologyAs(other) && poseEpoch == other.poseEpoch

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackRunSnapshot) return false
        return name == other.name &&
            runId == other.runId &&
            poseEpoch == other.poseEpoch &&
            physicsFrame == other.physicsFrame &&
            revision == other.revision &&
            links == other.links &&
            joints == other.joints &&
            linkBodyIds == other.linkBodyIds &&
            jointPositionError.toRawBits() == other.jointPositionError.toRawBits() &&
            jointAngleError.toRawBits() == other.jointAngleError.toRawBits()
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + runId.hashCode()
        result = 31 * result + poseEpoch.hashCode()
        result = 31 * result + physicsFrame.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + links.hashCode()
        result = 31 * result + joints.hashCode()
        result = 31 * result + linkBodyIds.hashCode()
        result = 31 * result + jointPositionError.hashCode()
        result = 31 * result + jointAngleError.hashCode()
        return result
    }

    override fun toString(): String =
        "TrackRunSnapshot(name=$name, runId=$runId, poseEpoch=$poseEpoch, physicsFrame=$physicsFrame, " +
            "revision=$revision, links=$linkCount, joints=${joints.size}, " +
            "jointPositionError=$jointPositionError, jointAngleError=$jointAngleError)"

    companion object {
        @JvmField
        val EMPTY = TrackRunSnapshot("", 0L, 0L, emptyList())
    }
}
