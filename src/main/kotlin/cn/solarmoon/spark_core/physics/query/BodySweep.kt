package cn.solarmoon.spark_core.physics.query

import cn.solarmoon.spark_core.physics.level.PhysicsLevel
import com.jme3.bullet.CollisionSpace
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.collision.PhysicsSweepTestResult
import com.jme3.bullet.collision.shapes.ConvexShape
import com.jme3.math.Quaternion
import com.jme3.math.Transform
import com.jme3.math.Vector3f

/** Thread-confined query. Hit normals returned by Bullet's sweep callback are world-space. */
fun sweepBody(space: CollisionSpace, shape: ConvexShape, from: Vector3f, to: Vector3f,
              rotation: Quaternion, ignore: PhysicsCollisionObject?, groups: Int): PhysicsSweepTestResult? {
    val hits = ArrayList<PhysicsSweepTestResult>()
    space.sweepTest(shape, Transform(from, rotation), Transform(to, rotation), hits, 0f)
    return hits.asSequence().filter {
        it.collisionObject !== ignore && it.collisionObject.isContactResponse &&
            it.collisionObject.collisionGroup and groups != 0 && it.hitFraction.isFinite()
    }.minByOrNull { it.hitFraction }
}

fun PhysicsLevel.sweepBody(shape: ConvexShape, from: Vector3f, to: Vector3f,
                          rotation: Quaternion, ignore: PhysicsCollisionObject?, groups: Int): PhysicsSweepTestResult? {
    assertPhysicsThread()
    return sweepBody(world, shape, from, to, rotation, ignore, groups)
}
