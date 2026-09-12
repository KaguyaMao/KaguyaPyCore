package cn.solarmoon.spark_core.physics.body

import com.jme3.bullet.collision.ManifoldPoints
import com.jme3.math.Vector3f

class ManifoldPoint(private val id: Long, val objectIndex: Int) {
    fun appliedImpulse(): Float = ManifoldPoints.getAppliedImpulse(id)
    fun distance(): Float = ManifoldPoints.getDistance1(id)
    fun positionWorld(store: Vector3f = Vector3f()): Vector3f {
        if (objectIndex == 0) ManifoldPoints.getPositionWorldOnA(id, store)
        else ManifoldPoints.getPositionWorldOnB(id, store)
        return store
    }

    fun normalWorld(store: Vector3f = Vector3f()): Vector3f {
        ManifoldPoints.getNormalWorldOnB(id, store)
        if (objectIndex == 0) store.negateLocal()
        return store
    }
    fun setCombinedFriction(value: Float) = ManifoldPoints.setCombinedFriction(id, value)
    fun setCombinedRestitution(value: Float) = ManifoldPoints.setCombinedRestitution(id, value)
    fun setCombinedRollingFriction(value: Float) = ManifoldPoints.setCombinedRollingFriction(id, value)
    fun setCombinedSpinningFriction(value: Float) = ManifoldPoints.setCombinedSpinningFriction(id, value)
    fun setAppliedImpulse(value: Float) = ManifoldPoints.setAppliedImpulse(id, value)
    fun setAppliedImpulseLateral1(value: Float) = ManifoldPoints.setAppliedImpulseLateral1(id, value)
    fun setAppliedImpulseLateral2(value: Float) = ManifoldPoints.setAppliedImpulseLateral2(id, value)
    fun setDistance(value: Float) = ManifoldPoints.setDistance1(id, value)
    fun setNormalWorldOnB(normal: Vector3f) = ManifoldPoints.setNormalWorldOnB(id, normal)
    fun setPositionWorldOnA(position: Vector3f) = ManifoldPoints.setPositionWorldOnA(id, position)
    fun setPositionWorldOnB(position: Vector3f) = ManifoldPoints.setPositionWorldOnB(id, position)
}
