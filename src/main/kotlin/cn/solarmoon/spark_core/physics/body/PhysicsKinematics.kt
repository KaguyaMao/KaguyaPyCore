package cn.solarmoon.spark_core.physics.body

import cn.solarmoon.spark_core.physics.level.PhysicsLevel
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f

/**
 * Immutable kinematic target. Callers must copy JOML/JME values before crossing threads.
 */
data class KinematicBodyPose(
    val location: Vector3f,
    val rotation: Quaternion,
    val linearVelocity: Vector3f,
    val angularVelocity: Vector3f,
) {
    fun copy(): KinematicBodyPose = KinematicBodyPose(
        Vector3f(location),
        Quaternion(rotation),
        Vector3f(linearVelocity),
        Vector3f(angularVelocity),
    )
}

/**
 * Atomically writes location, rotation and velocities on the physics thread.
 */
fun PhysicsLevel.applyKinematicPose(body: PhysicsRigidBody, pose: KinematicBodyPose) {
    assertPhysicsThread()
    body.isKinematic = true
    body.setPhysicsLocation(pose.location)
    body.setPhysicsRotation(pose.rotation)
    body.setLinearVelocity(pose.linearVelocity)
    body.setAngularVelocity(pose.angularVelocity)
    body.activate(true)
}
