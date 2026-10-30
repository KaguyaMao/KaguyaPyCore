package cn.solarmoon.spark_core.physics.level

import cn.solarmoon.spark_core.physics.body.BodyFrame
import com.jme3.bullet.collision.PhysicsCollisionObject
import net.minecraft.world.phys.AABB
import java.util.Collections

/**
 * Immutable view of one physics step.
 *
 * [bodies] is keyed by the native object so existing callers on the physics
 * thread can look a body up directly. That view retains native references and
 * is only valid while the bodies are alive; [bodiesById] is the stable,
 * cross-thread view keyed by [BodyFrame.bodyId]. New code that publishes state
 * to another thread should use [bodiesById].
 *
 * Both maps and the activation list are defensively copied on construction, so
 * a caller that keeps its source collection cannot mutate a published frame.
 *
 * [epoch] changes when the physics world is recreated. Two frames from different
 * epochs must not be interpolated, because the body state has a discontinuity
 * even though the stable ids may be unchanged.
 */
class PhysicsFrameSnapshot(
    val tick: Long,
    bodies: Map<PhysicsCollisionObject, BodyFrame>,
    terrainActivationBoxes: List<AABB>,
    val stepNanos: Long,
    val completedAtNanos: Long,
    bodiesById: Map<Long, BodyFrame> = emptyMap(),
    val epoch: Long = 0L,
    val metrics: PhysicsStepMetrics = PhysicsStepMetrics.EMPTY,
) {
    /** Preserve the pre-metrics JVM constructor for already compiled consumers. */
    constructor(
        tick: Long,
        bodies: Map<PhysicsCollisionObject, BodyFrame>,
        terrainActivationBoxes: List<AABB>,
        stepNanos: Long,
        completedAtNanos: Long,
        bodiesById: Map<Long, BodyFrame>,
        epoch: Long,
    ) : this(
        tick, bodies, terrainActivationBoxes, stepNanos, completedAtNanos,
        bodiesById, epoch, PhysicsStepMetrics.EMPTY,
    )

    val bodies: Map<PhysicsCollisionObject, BodyFrame> =
        Collections.unmodifiableMap(LinkedHashMap(bodies))

    val terrainActivationBoxes: List<AABB> =
        Collections.unmodifiableList(ArrayList(terrainActivationBoxes))

    val bodiesById: Map<Long, BodyFrame> =
        Collections.unmodifiableMap(LinkedHashMap(bodiesById))

    /** Frame of a body addressed by its stable id, or null when it was not sampled. */
    fun frameOf(bodyId: Long): BodyFrame? = bodiesById[bodyId]

    /** Copy with a replaced stable-id view, preserving every other field. */
    fun withBodiesById(replaced: Map<Long, BodyFrame>): PhysicsFrameSnapshot =
        PhysicsFrameSnapshot(tick, bodies, terrainActivationBoxes, stepNanos, completedAtNanos, replaced, epoch, metrics)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhysicsFrameSnapshot) return false
        return tick == other.tick &&
            epoch == other.epoch &&
            stepNanos == other.stepNanos &&
            completedAtNanos == other.completedAtNanos &&
            metrics == other.metrics &&
            bodies == other.bodies &&
            terrainActivationBoxes == other.terrainActivationBoxes &&
            bodiesById == other.bodiesById
    }

    override fun hashCode(): Int {
        var result = tick.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + stepNanos.hashCode()
        result = 31 * result + completedAtNanos.hashCode()
        result = 31 * result + metrics.hashCode()
        result = 31 * result + bodies.hashCode()
        result = 31 * result + terrainActivationBoxes.hashCode()
        result = 31 * result + bodiesById.hashCode()
        return result
    }

    override fun toString(): String =
        "PhysicsFrameSnapshot(tick=$tick, epoch=$epoch, bodies=${bodies.size}, " +
            "terrainActivationBoxes=${terrainActivationBoxes.size}, stepNanos=$stepNanos)"

    companion object {
        val EMPTY = PhysicsFrameSnapshot(0, emptyMap(), emptyList(), 0, System.nanoTime())
    }
}
