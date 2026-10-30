package cn.solarmoon.spark_core.physics.track

import cn.solarmoon.spark_core.physics.body.CollisionGroups
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Geometry of one physical track link.
 *
 * This is a plain description: it contains no Bullet object, so it can be
 * produced on the game thread or by a builder thread and handed to the physics
 * thread.
 */
sealed interface TrackRunLinkShape {
    data class Box(val halfExtents: Vec3) : TrackRunLinkShape

    /**
     * [axis] is the local cylinder axis: 0 = X, 1 = Y, 2 = Z. [halfHeight] is
     * the half length along that axis; [radius] is the cross-section radius.
     */
    data class Cylinder(val radius: Float, val halfHeight: Float, val axis: Int = 1) : TrackRunLinkShape
}

/**
 * One dynamic link of a generic track run.
 *
 * The rotation is stored as four plain floats rather than a JOML
 * [Quaternionf], so a caller cannot mutate a spec after it has been handed to
 * the physics thread. Use the secondary constructor when a quaternion is more
 * convenient.
 */
data class TrackRunLinkSpec(
    val massKg: Float,
    val shape: TrackRunLinkShape,
    val position: Vec3,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val rotationW: Float = 1f,
) {
    constructor(
        massKg: Float,
        shape: TrackRunLinkShape,
        position: Vec3,
        rotation: Quaternionf,
    ) : this(massKg, shape, position, rotation.x, rotation.y, rotation.z, rotation.w)

    /** A defensive copy; mutating the result never changes this spec. */
    val rotation: Quaternionf
        get() = Quaternionf(rotationX, rotationY, rotationZ, rotationW)

    internal fun validate(prefix: String): List<String> = buildList {
        if (!massKg.isFinite() || massKg <= 0f) add("$prefix.massKg must be finite and > 0: $massKg")
        if (!position.isFiniteVec() || !position.isFiniteFloatVec()) {
            add("$prefix.position is not finite or not representable as float: $position")
        }
        if (!rotationX.isFinite() || !rotationY.isFinite() || !rotationZ.isFinite() || !rotationW.isFinite()) {
            add("$prefix.rotation is not finite")
        } else if (normalizedQuaternion(rotationX, rotationY, rotationZ, rotationW) == null) {
            add("$prefix.rotation is a zero quaternion")
        }
        when (val s = shape) {
            is TrackRunLinkShape.Box -> {
                val h = s.halfExtents
                if (!h.isFiniteVec() || !h.isFiniteFloatVec() || h.x <= 0.0 || h.y <= 0.0 || h.z <= 0.0) {
                    add("$prefix.box halfExtents must be finite, representable and > 0: $h")
                }
            }
            is TrackRunLinkShape.Cylinder -> {
                if (!s.radius.isFinite() || s.radius <= 0f) add("$prefix.cylinder radius: ${s.radius}")
                if (!s.halfHeight.isFinite() || s.halfHeight <= 0f) add("$prefix.cylinder halfHeight: ${s.halfHeight}")
                if (!(s.halfHeight * 2f).isFinite()) add("$prefix.cylinder full height overflows: ${s.halfHeight}")
                if (s.axis !in 0..2) add("$prefix.cylinder axis must be 0..2: ${s.axis}")
            }
        }
    }
}

/**
 * Immutable quaternion written as four scalars.
 *
 * Specs cross the game/physics thread boundary, so they must not carry a
 * mutable JOML [Quaternionf] that a caller could change after submission.
 */
data class TrackRunRotation(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float,
) {
    constructor(rotation: Quaternionf) : this(rotation.x, rotation.y, rotation.z, rotation.w)

    /** A defensive copy; mutating the result never changes this value. */
    val quaternion: Quaternionf
        get() = Quaternionf(x, y, z, w)

    internal fun validate(prefix: String): List<String> = buildList {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !w.isFinite()) {
            add("$prefix is not finite")
            return@buildList
        }
        if (normalizedQuaternion(x, y, z, w) == null) add("$prefix is a zero quaternion")
    }

    /** Normalized copy. The value has already passed [validate]; a degenerate
     * value cannot silently become identity on a validated spec. */
    internal fun normalized(): TrackRunRotation =
        normalizedQuaternion(x, y, z, w) ?: IDENTITY

    companion object {
        @JvmField
        val IDENTITY = TrackRunRotation(0f, 0f, 0f, 1f)
    }
}

/**
 * Hinge between two links of a track run.
 *
 * All relative translation is locked. The pair may rotate only about [axis]
 * (0 = X, 1 = Y, 2 = Z, in the constraint frame) and only within
 * +/-[halfAngleRad]. No built-in spring is enabled: tension belongs to the
 * wheel envelope and the drive coupling.
 *
 * [frameRotationInPrevious] and [frameRotationInNext] orient the constraint
 * frame on each body. The identity default reproduces the historical behaviour
 * where both frames matched the body frames; non-identity values are required
 * whenever neighbouring links start with different orientations.
 */
data class TrackRunJointSpec @JvmOverloads constructor(
    val previousIndex: Int,
    val nextIndex: Int,
    val pivotInPrevious: Vec3,
    val pivotInNext: Vec3,
    val axis: Int = 0,
    val halfAngleRad: Float = 0.70f,
    val frameRotationInPrevious: TrackRunRotation = TrackRunRotation.IDENTITY,
    val frameRotationInNext: TrackRunRotation = TrackRunRotation.IDENTITY,
) {
    internal fun validate(prefix: String, linkCount: Int): List<String> = buildList {
        if (previousIndex !in 0 until linkCount) add("$prefix.previousIndex out of range: $previousIndex")
        if (nextIndex !in 0 until linkCount) add("$prefix.nextIndex out of range: $nextIndex")
        if (previousIndex == nextIndex) add("$prefix connects a link to itself")
        if (!pivotInPrevious.isFiniteVec() || !pivotInPrevious.isFiniteFloatVec()) {
            add("$prefix.pivotInPrevious is not finite or not representable as float")
        }
        if (!pivotInNext.isFiniteVec() || !pivotInNext.isFiniteFloatVec()) {
            add("$prefix.pivotInNext is not finite or not representable as float")
        }
        if (axis !in 0..2) add("$prefix.axis must be 0..2: $axis")
        if (!halfAngleRad.isFinite() || halfAngleRad <= 0f || halfAngleRad > Math.PI.toFloat()) {
            add("$prefix.halfAngleRad must be in (0, PI]: $halfAngleRad")
        }
        addAll(frameRotationInPrevious.validate("$prefix.frameRotationInPrevious"))
        addAll(frameRotationInNext.validate("$prefix.frameRotationInNext"))
    }
}

/**
 * Plain-data description of a generic dynamic track run.
 *
 * It describes only physics: link geometry, masses, initial poses, hinge
 * topology, collision filtering and input budgets. It must not contain vehicle
 * ids, wheel roles, model bones, visible link templates, terrain policy or
 * rendering data.
 *
 * The run inherits the physics world's gravity unless [explicitGravity] is set.
 * That field is an explicit opt-out, not a hidden default: leaving it null
 * never calls `setProtectGravity` and never zeroes a link.
 */
class TrackRunSpec @JvmOverloads constructor(
    val name: String,
    links: List<TrackRunLinkSpec>,
    joints: List<TrackRunJointSpec>,
    val collisionGroup: Int = CollisionGroups.TRACK_LINK,
    val collideWithGroups: Int = CollisionGroups.TERRAIN or CollisionGroups.WHEEL or CollisionGroups.CHASSIS,
    /**
     * Same-owner allow list. A link may still collide with same-owner bodies
     * whose group appears here; everything else from the same owner is
     * filtered. The default keeps sibling links from colliding while letting
     * the run touch its own wheels and chassis.
     */
    val collideWithOwnerGroups: Int = CollisionGroups.WHEEL or CollisionGroups.CHASSIS,
    val linearDamping: Float = .04f,
    val angularDamping: Float = .02f,
    val friction: Float = .8f,
    val restitution: Float = 0f,
    val continuousCollision: Boolean = false,
    /** Null inherits world gravity; non-null protects the link with this vector. */
    val explicitGravity: Vec3? = null,
    /** Upper bound for one [TrackRunRuntime.applyLinkForce] call, in newtons. */
    val maxLinkForceN: Float = 20_000f,
    /** Upper bound for one [TrackRunRuntime.applyLinkTorqueImpulse] call, in N*m*s. */
    val maxLinkTorqueImpulseNms: Float = 100f,
) {
    /** Defensive copy; later mutation of the caller's list cannot change this spec. */
    val links: List<TrackRunLinkSpec> = java.util.List.copyOf(links)

    /** Defensive copy; later mutation of the caller's list cannot change this spec. */
    val joints: List<TrackRunJointSpec> = java.util.List.copyOf(joints)

    init {
        val problems = problems()
        require(problems.isEmpty()) { "invalid TrackRunSpec '$name': " + problems.joinToString("; ") }
    }

    /** Detached validation used by tests and by callers that build specs dynamically. */
    fun problems(): List<String> = buildList {
        if (name.isBlank()) add("name must not be blank")
        if (links.isEmpty()) add("links must not be empty")
        if (links.size > MAX_LINKS) add("links must not exceed $MAX_LINKS")
        links.forEachIndexed { index, link -> addAll(link.validate("links[$index]")) }
        if (links.size > 1 && joints.isEmpty()) add("joints must not be empty for a multi-link run")
        if (joints.size > MAX_JOINTS) add("joints must not exceed $MAX_JOINTS")
        joints.forEachIndexed { index, joint -> addAll(joint.validate("joints[$index]", links.size)) }
        addAll(topologyProblems())
        if (!linearDamping.isFinite() || linearDamping < 0f) add("linearDamping must be finite and >= 0")
        if (!angularDamping.isFinite() || angularDamping < 0f) add("angularDamping must be finite and >= 0")
        if (!friction.isFinite() || friction < 0f) add("friction must be finite and >= 0")
        if (!restitution.isFinite() || restitution < 0f || restitution > 1f) add("restitution must be in [0,1]")
        explicitGravity?.let { gravity ->
            if (!gravity.isFiniteVec() || !gravity.isFiniteFloatVec()) {
                add("explicitGravity must be finite and representable as float: $gravity")
            }
        }
        if (!maxLinkForceN.isFinite() || maxLinkForceN <= 0f) add("maxLinkForceN must be finite and > 0")
        if (!maxLinkTorqueImpulseNms.isFinite() || maxLinkTorqueImpulseNms <= 0f) {
            add("maxLinkTorqueImpulseNms must be finite and > 0")
        }
    }

    /**
     * A track run is a serial chain: no duplicate pair in either direction, no
     * self connection, at most two hinges per link and one connected component.
     */
    private fun topologyProblems(): List<String> {
        if (links.size <= 1 || joints.isEmpty()) return emptyList()
        val problems = ArrayList<String>()
        val degree = IntArray(links.size)
        val components = UnionFind(links.size)
        val pairs = HashSet<Long>()
        joints.forEachIndexed { index, joint ->
            if (joint.previousIndex !in links.indices || joint.nextIndex !in links.indices) return@forEachIndexed
            degree[joint.previousIndex]++
            degree[joint.nextIndex]++
            components.union(joint.previousIndex, joint.nextIndex)
            val low = minOf(joint.previousIndex, joint.nextIndex)
            val high = maxOf(joint.previousIndex, joint.nextIndex)
            val key = (low.toLong() shl 32) or (high.toLong() and 0xffffffffL)
            if (!pairs.add(key)) problems += "joints[$index] duplicates the pair $low-$high"
        }
        degree.forEachIndexed { index, count ->
            if (count == 0) problems += "links[$index] is not connected to any joint"
            if (count > 2) problems += "links[$index] has $count joints; a track link may have at most 2"
        }
        if (links.isNotEmpty()) {
            val root = components.find(0)
            if ((1 until links.size).any { components.find(it) != root }) {
                problems += "joints do not connect every link into one run"
            }
        }
        return problems
    }

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }

        fun find(start: Int): Int {
            var root = start
            while (parent[root] != root) root = parent[root]
            var node = start
            while (parent[node] != root) {
                val next = parent[node]
                parent[node] = root
                node = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[rootB] = rootA
        }
    }

    companion object {
        const val MAX_LINKS = 4096
        const val MAX_JOINTS = 8192
    }
}
