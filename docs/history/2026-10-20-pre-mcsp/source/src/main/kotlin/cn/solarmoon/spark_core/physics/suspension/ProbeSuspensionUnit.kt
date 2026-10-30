package cn.solarmoon.spark_core.physics.suspension

import cn.solarmoon.spark_core.physics.body.CollisionGroups
import cn.solarmoon.spark_core.physics.query.sweepBody
import com.jme3.bullet.CollisionSpace
import com.jme3.bullet.collision.shapes.SphereCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import kotlin.math.PI

/**
 * Generic probe suspension unit for continuous supports: one downward point
 * probe per support point (each road-wheel station of a tracked side), every
 * probe owning an independent spring/damper and the shared travel window. The
 * unit owns no rigid body; it applies world-up forces to the chassis at the
 * wheel anchors, so pitch and roll emerge from the distributed contacts and a
 * long side can never jack-knife on a ramp kink. Parameters come from vehicle
 * data (JSON), so a new vehicle registers a layout instead of adding physics
 * code. Wheeled vehicles keep jointed wheel bodies and share only this
 * registration surface.
 */
class ProbeSuspensionUnit(
    private val chassis: PhysicsRigidBody,
    val probes: List<Probe>,
    val travelUp: Float,
    val travelDown: Float,
    val frequencyHz: Float,
    val gravity: Float,
    val compressionRatio: Float,
    val reboundRatio: Float,
    val compressionVelocityLimit: Float,
    /** Whole-unit elastic/viscous row cap in N; split across the probes. */
    val maxForce: Float,
) {
    /**
     * One support probe. [supportedMass] is the sprung mass share this probe
     * carries; [tag] groups probes into sides for reporting.
     */
    class Probe(
        val offset: Vector3f,
        val contactRadius: Float,
        val supportedMass: Float,
        val tag: Int = 0,
    ) {
        /** Point-sized pad sample: a small tip cannot wedge on a hole's walls. */
        val shape = SphereCollisionShape(TIP_RADIUS).apply { margin = .001f }
        var grounded = false
        var travel = 0f
        var force = 0f
        /** Relaxed tangential force used by the traction model (N). */
        var appliedLongitudinal = 0f
        var appliedLateral = 0f
        val point = Vector3f()
        val normal = Vector3f(0f, 1f, 0f)

        fun resetTraction() {
            appliedLongitudinal = 0f
            appliedLateral = 0f
        }
    }

    /**
     * Extra ground source used only when the raw sweep misses, e.g. the
     * smoothed local height field over a climbable one-block step. Returning
     * null keeps the probe airborne.
     */
    fun interface GroundFallback {
        fun sample(anchor: Vector3f, up: Vector3f, contactRadius: Float, tag: Int): GroundSample?
    }

    class GroundSample(val x: Float, val y: Float, val z: Float, val normal: Vector3f)

    /**
     * Solve one substep: sweep every probe, apply its spring/damper and leave
     * per-probe state readable. [worldUp] is the force direction; probes are
     * swept along the chassis up axis so a tilted hull keeps sampling its own
     * suspension direction.
     */
    fun step(
        space: CollisionSpace,
        mask: Int,
        rotation: Quaternion,
        up: Vector3f,
        chassisPosition: Vector3f,
        velocity: Vector3f,
        angular: Vector3f,
        dt: Float,
        fallback: GroundFallback? = null,
    ) {
        val matrix = rotation.toRotationMatrix()
        val omega = 2f * PI.toFloat() * frequencyHz.coerceAtLeast(.1f)
        val count = probes.size.coerceAtLeast(1)
        val perProbeCap = (maxForce / count).coerceAtLeast(0f)
        probes.forEach { probe ->
            val anchor = chassisPosition.add(matrix.mult(probe.offset, null))
            val radius = probe.contactRadius.coerceIn(.05f, 2f)
            // Sweep the probe *centre* through the window whose tip covers the
            // full mechanical travel: tip = centre - radius along the up axis.
            // `travel` is measured for the tip, so both ends are expressed in
            // tip space and the fraction maps linearly onto the travel window.
            val from = anchor.add(up.mult(travelUp - radius))
            val to = anchor.subtract(up.mult(travelDown + radius))
            val hit = sweepBody(space, probe.shape, from, to, rotation, chassis, mask)
            var contact = false
            var travel = -travelDown
            if (hit != null) {
                val candidate = hit.getHitNormalLocal(null).normalize()
                if (candidate.dot(up) >= .2f && candidate.y >= .5f) {
                    val fraction = hit.hitFraction.coerceIn(0f, 1f)
                    // fraction 0 => tip at full bump (travelUp), 1 => full droop.
                    travel = travelUp - fraction * (travelUp + travelDown)
                    probe.normal.set(candidate)
                    contact = true
                }
            }
            // A generated climb wedge is a real support surface: the belt rides
            // it exactly like a block top, and its incline normal is what gives
            // the traction a forward-and-up direction.
            //
            // It is deliberately *not* used as a support height here. A wedge
            // rises over three blocks, so a long tracked hull whose front probe
            // is already on the incline while its rear probes stand on the
            // floor gets its nose lifted by up to half a block. That overloaded
            // the front row (3.5x its weight share), raised every other anchor
            // out of reach and left the middle rows dead. The hull collides
            // with the wedge instead, so the vehicle rides the ramp the same
            // way a real one does, and the belt probes keep following the real
            // ground under the tracks.

            // A raw hit that only found a vertical face (a one-block step's
            // wall) must still consult the smoothed fallback; otherwise the
            // probe reports no ground at exactly the edge it should climb.
            if (fallback != null) {
                val sample = fallback.sample(anchor, up, radius, probe.tag)
                if (sample != null) {
                    val offset = (sample.x - anchor.x) * up.x + (sample.y - anchor.y) * up.y +
                        (sample.z - anchor.z) * up.z
                    val candidate = offset + radius
                    // A climbable one-block step may sit up to a full block above
                    // the current pad plane; the fallback itself owns the gate.
                    if (candidate >= -travelDown - .05f && candidate <= travelUp + 1.05f) {
                        val candidateTravel = candidate.coerceIn(-travelDown, travelUp)
                        // The fallback describes the surface under this probe, so
                        // it is authoritative whenever it reports more
                        // compression than a sweep that may have started inside
                        // a climb wedge.
                        if (!contact || candidateTravel > travel + 1.0e-3f) {
                            travel = candidateTravel
                            probe.normal.set(sample.normal)
                            contact = true
                        }
                    }
                }
            }
            probe.grounded = contact
            probe.travel = travel
            probe.force = 0f
            if (!contact) return@forEach
            val relative = anchor.subtract(chassisPosition)
            val anchorVelocity = velocity.add(angular.cross(relative, null))
            val compressionSpeed = (-anchorVelocity.dot(up)).coerceIn(
                -compressionVelocityLimit, compressionVelocityLimit)
            val ratio = if (compressionSpeed >= 0f) compressionRatio else reboundRatio
            val damping = (2f * ratio.coerceAtLeast(0f) * probe.supportedMass * omega)
                .coerceIn(0f, probe.supportedMass / dt)
            val spring = probe.supportedMass * omega * omega
            // The elastic term must not cancel the static preload on a drooped
            // probe. `travelUp` (0.18) and `travelDown` (0.24) are asymmetric,
            // so a probe resting on flat ground sits slightly below its anchor
            // and `travel` is negative there; `m*g + k*travel` then went
            // negative and was clamped to zero, which made every station on
            // flat ground report no load and let a hull bridge on two points.
            // Compression is therefore measured from the static equilibrium
            // point, and a drooped probe keeps its preload instead of dropping
            // to zero force.
            val staticCompression = (probe.supportedMass * gravity / spring.coerceAtLeast(1.0e-3f))
                .coerceAtLeast(0f)
            // A support row can push but never pull. The elastic term is a
            // linear spring around the static equilibrium point, floored at
            // zero: a probe that droops past equilibrium keeps its preload
            // instead of going dead, and a compressing probe still gains force
            // linearly. `travelUp` (0.18) and `travelDown` (0.24) are
            // asymmetric, so a probe resting on flat ground sits at a slightly
            // negative travel; the old formula turned that into a zero row.
            val elastic = (spring * (travel + staticCompression)).coerceAtLeast(0f)
            val dampingTerm = if (compressionSpeed >= 0f) damping * compressionSpeed
            else (damping * compressionSpeed).coerceAtLeast(-probe.supportedMass * gravity)
            val force = (elastic + dampingTerm).coerceIn(0f, perProbeCap)
            probe.force = force
            probe.point.set(anchor).addLocal(up.mult(travel - radius))
            if (force > 0f) chassis.applyForce(WORLD_UP.mult(force), relative)
        }
    }

    companion object {
        const val TIP_RADIUS = .06f
        private val WORLD_UP = Vector3f(0f, 1f, 0f)
    }
}
