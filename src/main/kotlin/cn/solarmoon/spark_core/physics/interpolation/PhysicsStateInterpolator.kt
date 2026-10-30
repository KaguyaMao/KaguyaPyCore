package cn.solarmoon.spark_core.physics.interpolation

import cn.solarmoon.spark_core.physics.body.BodyFrame
import cn.solarmoon.spark_core.physics.level.PhysicsFrameSnapshot
import cn.solarmoon.spark_core.physics.track.TrackRunLinkFrame
import cn.solarmoon.spark_core.physics.track.TrackRunSnapshot
import com.jme3.bullet.collision.PhysicsCollisionObject
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.abs

/**
 * Generic numeric interpolation between two immutable physics states.
 *
 * This object knows nothing about Minecraft entities, vehicles, models or
 * rendering. It only turns two snapshots plus a ratio into interpolated plain
 * values, and never touches a native Bullet body.
 */
object PhysicsStateInterpolator {
    /** Shortest-arc quaternion interpolation. */
    fun rotation(from: Quaternionf, to: Quaternionf, alpha: Float): Quaternionf {
        val t = alpha.coerceIn(0f, 1f)
        val a = normalized(from)
        val b = normalized(to)
        val dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
        val sign = if (dot < 0f) -1f else 1f
        val bx = b.x * sign
        val by = b.y * sign
        val bz = b.z * sign
        val bw = b.w * sign
        // Linear blend then normalize is stable for small deltas and avoids
        // acos/asin edge cases at 180 degrees. Fall back to the source pose when
        // the blend degenerates.
        val x = a.x + (bx - a.x) * t
        val y = a.y + (by - a.y) * t
        val z = a.z + (bz - a.z) * t
        val w = a.w + (bw - a.w) * t
        val lengthSquared = x * x + y * y + z * z + w * w
        if (!lengthSquared.isFinite() || lengthSquared < 1.0e-10f) return Quaternionf(a)
        val inv = 1f / kotlin.math.sqrt(lengthSquared)
        return Quaternionf(x * inv, y * inv, z * inv, w * inv)
    }

    /** Linear position or velocity interpolation with a clamped ratio. */
    fun vector(from: Vec3, to: Vec3, alpha: Float): Vec3 {
        val t = alpha.coerceIn(0f, 1f).toDouble()
        return Vec3(
            from.x + (to.x - from.x) * t,
            from.y + (to.y - from.y) * t,
            from.z + (to.z - from.z) * t,
        )
    }

    /**
     * Interpolate a periodic phase in `[0, cycle)` along the shortest arc.
     *
     * A phase that wraps from near the cycle end to near zero must not spin the
     * whole way backwards.
     */
    fun phase(from: Float, to: Float, alpha: Float, cycle: Float): Float {
        if (!from.isFinite() || !to.isFinite() || !cycle.isFinite() || cycle <= 0f) return from
        val t = alpha.coerceIn(0f, 1f)
        var delta = (to - from) % cycle
        if (delta > cycle * .5f) delta -= cycle
        if (delta < -cycle * .5f) delta += cycle
        val value = (from + delta * t) % cycle
        return if (value < 0f) value + cycle else value
    }

    /** Interpolate one link pose. Velocities are blended linearly. */
    fun link(from: TrackRunLinkFrame, to: TrackRunLinkFrame, alpha: Float): TrackRunLinkFrame =
        TrackRunLinkFrame.of(
            position = vector(from.position, to.position, alpha),
            rotation = rotation(from.rotation, to.rotation, alpha),
            linearVelocity = vector(from.linearVelocity, to.linearVelocity, alpha),
            angularVelocity = vector(from.angularVelocity, to.angularVelocity, alpha),
            active = if (alpha < .5f) from.active else to.active,
            colliding = if (alpha < .5f) from.colliding else to.colliding,
        )

    /**
     * Interpolate a track run snapshot. Identity and topology must match; when
     * they do not, the newer snapshot wins because a different run cannot be
     * blended and a changed link count has no correspondence.
     */
    fun trackRun(from: TrackRunSnapshot, to: TrackRunSnapshot, alpha: Float): TrackRunSnapshot {
        if (!from.samePoseSegmentAs(to)) return to
        return TrackRunSnapshot(
            name = to.name,
            physicsFrame = to.physicsFrame,
            revision = to.revision,
            links = to.links.mapIndexed { index, link -> link(from.links[index], link, alpha) },
            // Native joint observations are samples of the target substep, not
            // continuous quantities. Carry them unchanged rather than inventing
            // an interpolated constraint state.
            jointPositionError = to.jointPositionError,
            jointAngleError = to.jointAngleError,
            runId = to.runId,
            joints = to.joints,
            poseEpoch = to.poseEpoch,
            linkBodyIds = to.linkBodyIds,
        )
    }

    /**
     * Clock-independent ratio between two physics frames.
     *
     * [renderNanos] is the client render time; frames carry their completion
     * time. A missing or non-monotonic pair yields 1 (show the newest state).
     */
    fun ratio(previousNanos: Long, currentNanos: Long, renderNanos: Long): Float {
        if (currentNanos <= previousNanos) return 1f
        val span = (currentNanos - previousNanos).toDouble()
        val elapsed = (renderNanos - previousNanos).toDouble()
        return (elapsed / span).toFloat().coerceIn(0f, 1f)
    }

    private fun normalized(value: Quaternionf): Quaternionf {
        val lengthSquared = value.x * value.x + value.y * value.y + value.z * value.z + value.w * value.w
        if (!lengthSquared.isFinite() || lengthSquared < 1.0e-10f) return Quaternionf()
        val inv = 1f / kotlin.math.sqrt(lengthSquared)
        return Quaternionf(value.x * inv, value.y * inv, value.z * inv, value.w * inv)
    }

    /**
     * Interpolate a generic body frame.
     *
     * Position, rotation, bounding box and velocities are continuous and are
     * blended. Discrete fields (active, colliding, contact response, activation
     * state) are taken from the nearer frame so a consumer never sees a
     * half-active body. Frames of different bodies are never mixed.
     */
    fun body(from: BodyFrame, to: BodyFrame, alpha: Float): BodyFrame {
        if (from.bodyId != to.bodyId) return to
        val t = alpha.coerceIn(0f, 1f)
        val rotation = rotation(from.rotation, to.rotation, t)
        return BodyFrame(
            bodyId = to.bodyId,
            position = vector(from.position, to.position, t),
            rotationX = rotation.x,
            rotationY = rotation.y,
            rotationZ = rotation.z,
            rotationW = rotation.w,
            boundingBox = aabb(from.boundingBox, to.boundingBox, t),
            active = if (t < .5f) from.active else to.active,
            colliding = if (t < .5f) from.colliding else to.colliding,
            contactResponse = if (t < .5f) from.contactResponse else to.contactResponse,
            activationState = if (t < .5f) from.activationState else to.activationState,
            linearVelocity = vector(from.linearVelocity, to.linearVelocity, t),
            angularVelocity = vector(from.angularVelocity, to.angularVelocity, t),
        )
    }

    /** Linear AABB interpolation; both boxes are value types. */
    fun aabb(from: AABB, to: AABB, alpha: Float): AABB {
        val t = alpha.coerceIn(0f, 1f).toDouble()
        return AABB(
            from.minX + (to.minX - from.minX) * t,
            from.minY + (to.minY - from.minY) * t,
            from.minZ + (to.minZ - from.minZ) * t,
            from.maxX + (to.maxX - from.maxX) * t,
            from.maxY + (to.maxY - from.maxY) * t,
            from.maxZ + (to.maxZ - from.maxZ) * t,
        )
    }

    /**
     * Interpolate a whole physics frame.
     *
     * Only frames of the same level tick sequence with the same body identity
     * set are blended; anything else returns [to] unchanged, because mixing a
     * different body set would silently invent or drop bodies.
     */
    fun physicsFrame(
        from: PhysicsFrameSnapshot,
        to: PhysicsFrameSnapshot,
        alpha: Float,
    ): PhysicsFrameSnapshot {
        if (from.epoch != to.epoch) return to
        if (from.bodiesById.keys != to.bodiesById.keys) return to
        val blended = LinkedHashMap<Long, BodyFrame>(to.bodiesById.size)
        to.bodiesById.forEach { (id, frame) ->
            val previous = from.bodiesById[id] ?: return@forEach
            blended[id] = body(previous, frame, alpha)
        }
        // Keep the compatibility view consistent with the blended stable-id
        // view instead of returning two contradictory states in one object.
        val blendedBodies = LinkedHashMap<PhysicsCollisionObject, BodyFrame>(to.bodies.size)
        to.bodies.forEach { (native, frame) ->
            blendedBodies[native] = blended[frame.bodyId] ?: frame
        }
        return PhysicsFrameSnapshot(
            tick = to.tick,
            bodies = blendedBodies,
            terrainActivationBoxes = to.terrainActivationBoxes,
            stepNanos = to.stepNanos,
            completedAtNanos = to.completedAtNanos,
            bodiesById = blended,
            epoch = to.epoch,
            metrics = to.metrics,
        )
    }

    /** Absolute phase distance in `[0, cycle/2]`, for diagnostics only. */
    fun phaseDistance(a: Float, b: Float, cycle: Float): Float {
        if (!cycle.isFinite() || cycle <= 0f) return 0f
        val delta = abs((b - a) % cycle)
        return minOf(delta, cycle - delta)
    }

    /** Full turn in radians, used by callers that keep spin angles. */
    const val TWO_PI: Float = (PI * 2.0).toFloat()
}
