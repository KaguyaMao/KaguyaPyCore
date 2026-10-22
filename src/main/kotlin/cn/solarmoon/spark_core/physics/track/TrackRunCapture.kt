package cn.solarmoon.spark_core.physics.track

import cn.solarmoon.spark_core.physics.body.bodyId
import cn.solarmoon.spark_core.physics.body.isColliding
import com.jme3.bullet.joints.New6Dof
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Read a run into plain values. Must run on the physics thread; the caller
 * guarantees that the native bodies are alive.
 */
internal fun captureTrackRunSnapshot(
    spec: TrackRunSpec,
    runId: Long,
    physicsFrame: Long,
    revision: Long,
    links: List<PhysicsRigidBody>,
    joints: List<New6Dof>,
    poseEpoch: Long = 0L,
): TrackRunSnapshot {
    val frames = ArrayList<TrackRunLinkFrame>(links.size)
    links.forEach { body ->
        val location = body.getPhysicsLocation(null)
        val rotation = body.getPhysicsRotation(null)
        // Libbulletjme asserts when velocity is read from a static body, and a
        // caller may pin a link by giving it zero mass.
        val linear = if (body.isDynamic) body.getLinearVelocity(null) else null
        val angular = if (body.isDynamic) body.getAngularVelocity(null) else null
        frames += TrackRunLinkFrame(
            position = Vec3(location.x.toDouble(), location.y.toDouble(), location.z.toDouble()),
            rotationX = rotation.x,
            rotationY = rotation.y,
            rotationZ = rotation.z,
            rotationW = rotation.w,
            linearVelocity = linear?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) } ?: Vec3.ZERO,
            angularVelocity = angular?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) } ?: Vec3.ZERO,
            active = body.isActive,
            colliding = body.isColliding,
        )
    }
    val jointFrames = if (joints.isEmpty() || links.size != spec.links.size) {
        emptyList()
    } else {
        captureJoints(spec, links, joints)
    }
    val positionError = jointFrames.maxOfOrNull { it.positionError } ?: Float.NaN
    val angleError = jointFrames.maxOfOrNull { it.angleError } ?: Float.NaN
    return TrackRunSnapshot(
        name = spec.name,
        runId = runId,
        physicsFrame = physicsFrame,
        revision = revision,
        links = frames,
        jointPositionError = positionError,
        jointAngleError = angleError,
        joints = jointFrames,
        poseEpoch = poseEpoch,
        linkBodyIds = links.map { it.bodyId },
    )
}

/**
 * Read every hinge into plain values.
 *
 * [TrackRunJointFrame.angleX]/[angleY]/[angleZ] and the limit violation come
 * from the native constraint coordinates, which is exactly what Bullet limits
 * and solves. The pin distance is derived from body poses and is therefore a
 * separate geometric diagnostic.
 */
private fun captureJoints(
    spec: TrackRunSpec,
    links: List<PhysicsRigidBody>,
    joints: List<New6Dof>,
): List<TrackRunJointFrame> {
    if (joints.size != spec.joints.size) return emptyList()
    return spec.joints.mapIndexed { index, joint ->
        val previous = links[joint.previousIndex]
        val next = links[joint.nextIndex]
        val previousRotation = previous.getPhysicsRotation(null)
        val nextRotation = next.getPhysicsRotation(null)
        val expected = previous.getPhysicsLocation(null)
            .add(previousRotation.toRotationMatrix().mult(joint.pivotInPrevious.toBulletVector(), null))
        val actual = next.getPhysicsLocation(null)
            .add(nextRotation.toRotationMatrix().mult(joint.pivotInNext.toBulletVector(), null))
        val angles = joints[index].getAngles(null)
        TrackRunJointFrame(
            previousIndex = joint.previousIndex,
            nextIndex = joint.nextIndex,
            angleX = angles.x,
            angleY = angles.y,
            angleZ = angles.z,
            positionError = expected.distance(actual),
            angleError = constraintAngleViolation(angles, joint.axis, joint.halfAngleRad),
        )
    }
}

/**
 * Violation of the authored hinge range, measured in the native constraint
 * coordinates.
 *
 * The free coordinate may move inside `[-halfAngleRad, +halfAngleRad]`; every
 * locked coordinate must stay at zero. This is the same coordinate space in
 * which Bullet applies its limits, so it does not invent an Euler decomposition
 * that could disagree with the solver.
 */
internal fun constraintAngleViolation(angles: Vector3f, axis: Int, halfAngleRad: Float): Float {
    if (!angles.x.isFinite() || !angles.y.isFinite() || !angles.z.isFinite()) return Float.NaN
    val free = when (axis) {
        0 -> angles.x
        1 -> angles.y
        else -> angles.z
    }
    val overFree = max(0f, abs(free) - halfAngleRad)
    var locked = 0f
    for (index in 0..2) {
        if (index == axis) continue
        locked += abs(
            when (index) {
                0 -> angles.x
                1 -> angles.y
                else -> angles.z
            },
        )
    }
    return overFree + locked
}

/**
 * Geometric pose check used by tests and diagnostics: the relative rotation of
 * [next] in [previous]'s constraint frame is decomposed into a twist about the
 * hinge axis and the remaining swing.
 *
 * ```
 * relative = inverse(framePrevious) * inverse(previous) * next * frameNext
 * ```
 *
 * This is a true swing-twist decomposition, not a projection of the total
 * axis-angle. It is intentionally separate from the published constraint
 * violation, which must use native coordinates.
 */
internal fun hingeViolation(
    previous: com.jme3.math.Quaternion,
    next: com.jme3.math.Quaternion,
    axis: Int,
    halfAngleRad: Float,
    framePrevious: TrackRunRotation = TrackRunRotation.IDENTITY,
    frameNext: TrackRunRotation = TrackRunRotation.IDENTITY,
): Float {
    val previousFrame = TrackRunAssembly.frameQuaternion(framePrevious)
    val nextFrame = TrackRunAssembly.frameQuaternion(frameNext)
    // inverse(framePrevious) * inverse(previous) * next * frameNext
    val relative = previousFrame
        .inverse()
        .mult(previous.inverse())
        .mult(next)
        .mult(nextFrame)
    val lengthSquared = relative.x.toDouble() * relative.x + relative.y.toDouble() * relative.y +
        relative.z.toDouble() * relative.z + relative.w.toDouble() * relative.w
    if (!lengthSquared.isFinite() || lengthSquared < 1.0e-12) return 0f
    val inv = (1.0 / sqrt(lengthSquared)).toFloat()
    var x = relative.x * inv
    var y = relative.y * inv
    var z = relative.z * inv
    var w = relative.w * inv
    // q and -q are the same rotation. Canonicalize to w >= 0 so the derived
    // angle stays in [-PI, PI] instead of reporting a spurious full turn.
    if (w < 0f) {
        x = -x
        y = -y
        z = -z
        w = -w
    }

    val axisX = if (axis == 0) 1f else 0f
    val axisY = if (axis == 1) 1f else 0f
    val axisZ = if (axis == 2) 1f else 0f
    val projected = x * axisX + y * axisY + z * axisZ

    // Signed twist angle about the hinge axis. atan2 keeps the sign and avoids
    // the acos branch ambiguity near identity and near a half turn.
    val twist = 2.0 * atan2(projected.toDouble(), w.toDouble())
    val twistLengthSquared = projected * projected + w * w
    if (twistLengthSquared < 1.0e-12f) {
        // The relative rotation has no component about the hinge axis; the
        // whole rotation is swing.
        return (2.0 * acos(abs(w).coerceIn(0f, 1f).toDouble())).toFloat()
    }
    val twistInvLength = 1f / sqrt(twistLengthSquared)
    val tx = projected * axisX * twistInvLength
    val ty = projected * axisY * twistInvLength
    val tz = projected * axisZ * twistInvLength
    val tw = w * twistInvLength
    // swing = relative * inverse(twist)
    val sx = -w * tx + x * tw - (y * tz - z * ty)
    val sy = -w * ty + y * tw - (z * tx - x * tz)
    val sz = -w * tz + z * tw - (x * ty - y * tx)
    val sw = w * tw + x * tx + y * ty + z * tz
    val swingLength = sqrt(sx.toDouble() * sx + sy.toDouble() * sy + sz.toDouble() * sz + sw.toDouble() * sw)
    if (!swingLength.isFinite() || swingLength < 1.0e-9) return 0f
    val swing = 2.0 * acos(abs((sw / swingLength).toDouble()).coerceIn(0.0, 1.0))
    val overTwist = max(0.0, abs(twist) - halfAngleRad)
    return (overTwist + swing).toFloat()
}

private fun Vec3.toBulletVector() = com.jme3.math.Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
