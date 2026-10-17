package cn.solarmoon.spark_core.physics.body

import com.jme3.bullet.RotationOrder
import com.jme3.bullet.joints.New6Dof
import com.jme3.bullet.joints.motors.MotorParam
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Matrix3f
import com.jme3.math.Vector3f

object PhysicsJoints {
    const val LIN_X = 0
    const val LIN_Y = 1
    const val LIN_Z = 2
    const val ANG_X = 3
    const val ANG_Y = 4
    const val ANG_Z = 5

    @JvmStatic
    fun lock(dof: New6Dof, axis: Int) {
        dof.set(MotorParam.LowerLimit, axis, 0f)
        dof.set(MotorParam.UpperLimit, axis, 0f)
    }

    @JvmStatic
    fun free(dof: New6Dof, axis: Int) {
        dof.set(MotorParam.LowerLimit, axis, -1.0e9f)
        dof.set(MotorParam.UpperLimit, axis, 1.0e9f)
    }

    @JvmStatic
    fun limit(dof: New6Dof, axis: Int, lower: Float, upper: Float) {
        dof.set(MotorParam.LowerLimit, axis, lower)
        dof.set(MotorParam.UpperLimit, axis, upper)
    }

    /**
     * Compatible overload: identity frames, equilibrium at 0.
     */
    @JvmStatic
    fun wheelSuspension(
        chassis: PhysicsRigidBody,
        wheel: PhysicsRigidBody,
        pivotInChassis: Vector3f,
        pivotInWheel: Vector3f,
        minTravel: Float,
        maxTravel: Float,
        stiffness: Float,
        damping: Float,
    ): New6Dof = wheelSuspension(
        chassis, wheel, pivotInChassis, pivotInWheel,
        Matrix3f(), Matrix3f(),
        minTravel, maxTravel, stiffness, damping, 0f,
    )

    /**
     * Chassis (A) to wheel (B). Constraint frame Y is suspension travel, X is the roll axle.
     * Frames are copied; callers may reuse/mutate the source matrices afterwards.
     *
     * 历史兼容接口仍将 [dampingRatio] 钳制在 [0,1]，保持既有调用行为。
     * 注意：这不是底层 Spring2 的物理单位。Libbulletjme 22.0.3 所带
     * Bullet 用 `kd * relativeVelocity * dt` 生成线性阻尼冲量，kd 为 N·s/m，
     * 数值 1 不代表临界阻尼（JavaDoc 的 ratio 描述与原生实现不一致）。
     * 新物理应直接配置原生 SI 系数，或使用 [guidedWheelAxle] 显式计算力；
     * 不要用本兼容接口传递 SI 阻尼，否则会被历史钳制截断。
     */
    @JvmStatic
    fun wheelSuspension(
        chassis: PhysicsRigidBody,
        wheel: PhysicsRigidBody,
        pivotInChassis: Vector3f,
        pivotInWheel: Vector3f,
        frameInChassis: Matrix3f,
        frameInWheel: Matrix3f,
        minTravel: Float,
        maxTravel: Float,
        stiffness: Float,
        dampingRatio: Float,
        equilibrium: Float,
    ): New6Dof {
        val dof = New6Dof(
            chassis,
            wheel,
            Vector3f(pivotInChassis),
            Vector3f(pivotInWheel),
            Matrix3f().set(frameInChassis),
            Matrix3f().set(frameInWheel),
            RotationOrder.XYZ
        )
        lock(dof, LIN_X)
        lock(dof, LIN_Z)
        limit(dof, LIN_Y, minTravel, maxTravel)
        dof.enableSpring(LIN_Y, true)
        dof.setStiffness(LIN_Y, stiffness, true)
        dof.setDamping(LIN_Y, dampingRatio.coerceIn(0f, 1f), true)
        dof.setEquilibriumPoint(LIN_Y, equilibrium)
        free(dof, ANG_X)
        lock(dof, ANG_Y)
        lock(dof, ANG_Z)
        return dof
    }

    /**
     * 纯导向轮轴约束：只允许轮子绕局部 X 轴旋转，并沿局部 Y 轴在
     * [minTravel, maxTravel] 内移动。不启用 New6Dof 内置弹簧/阻尼；
     * 弹簧力由调用方在每个物理子步显式计算并施加到实际悬挂锚点。
     */
    @JvmStatic
    fun guidedWheelAxle(
        chassis: PhysicsRigidBody,
        wheel: PhysicsRigidBody,
        pivotInChassis: Vector3f,
        pivotInWheel: Vector3f,
        frameInChassis: Matrix3f,
        frameInWheel: Matrix3f,
        minTravel: Float,
        maxTravel: Float,
    ): New6Dof {
        val dof = New6Dof(
            chassis,
            wheel,
            Vector3f(pivotInChassis),
            Vector3f(pivotInWheel),
            Matrix3f().set(frameInChassis),
            Matrix3f().set(frameInWheel),
            RotationOrder.XYZ
        )
        lock(dof, LIN_X)
        lock(dof, LIN_Z)
        limit(dof, LIN_Y, minTravel, maxTravel)
        dof.enableSpring(LIN_Y, false)
        free(dof, ANG_X)
        lock(dof, ANG_Y)
        lock(dof, ANG_Z)
        return dof
    }

    /**
     * Drive a wheel axle about its joint X axis. [velocity] is the forward roll
     * of the chassis, in radians per second: positive values roll the vehicle
     * toward +Z, the nose the authored layouts mark as the front. The Bullet
     * rotational motor spins the opposite way under the identity-framed axles
     * used by every vehicle, so the target is negated at this boundary and
     * callers never see the raw constraint convention.
     */
    @JvmStatic
    fun setRollMotor(dof: New6Dof, velocity: Float, maxForce: Float) {
        val motor = dof.getRotationMotor(0)
        motor.setMotorEnabled(maxForce > 0f)
        motor.set(MotorParam.TargetVelocity, -velocity)
        motor.set(MotorParam.MaxMotorForce, maxForce)
    }

    /** Tightens the axle travel window; used to clamp a wheel inside a track envelope. */
    @JvmStatic
    fun setTravelLimits(dof: New6Dof, minTravel: Float, maxTravel: Float) {
        limit(dof, LIN_Y, minTravel, maxTravel)
    }

    /**
     * Machine-Max oleo pair: +forceN along [upWorld] on the chassis at [anchorWorld],
     * equal and opposite on the wheel. Independent suspension must use
     * [guidedWheelAxle] plus this force, never the built-in New6Dof spring.
     */
    @JvmStatic
    fun applyOleoForce(
        chassis: PhysicsRigidBody,
        wheel: PhysicsRigidBody,
        upWorld: Vector3f,
        forceN: Float,
        anchorWorld: Vector3f,
    ) {
        if (!forceN.isFinite() || kotlin.math.abs(forceN) < 1.0e-4f) return
        if (!upWorld.x.isFinite() || !upWorld.y.isFinite() || !upWorld.z.isFinite()) return
        if (!anchorWorld.x.isFinite() || !anchorWorld.y.isFinite() || !anchorWorld.z.isFinite()) return
        val up = Vector3f(upWorld)
        val len = up.length()
        if (len < 1.0e-6f) up.set(0f, 1f, 0f) else up.multLocal(1f / len)
        val force = up.mult(forceN, Vector3f())
        val chassisPos = chassis.getPhysicsLocation(null)
        val rel = Vector3f(anchorWorld).subtractLocal(chassisPos)
        chassis.applyForce(force, rel)
        wheel.applyCentralForce(force.mult(-1f, Vector3f()))
        chassis.activate(true)
        wheel.activate(true)
    }

    /**
     * Closed track-shoe hinge used by articulated belts.  Each shoe is a
     * dynamic body; the hinge removes all relative translation, allows the
     * shoe pair to wrap around a sprocket, and keeps lateral/yaw twist locked.
     * The angular limit is deliberately finite so a bad contact cannot open a
     * visible gap in the belt.  Built-in springs are never enabled here: belt
     * tension is supplied by the wheel envelope and the drive sprocket.
     */
    @JvmStatic
    fun trackLinkHinge(
        previous: PhysicsRigidBody,
        next: PhysicsRigidBody,
        pivotInPrevious: Vector3f,
        pivotInNext: Vector3f,
        halfAngleRad: Float = 0.70f,
    ): New6Dof {
        val identity = Matrix3f()
        val dof = New6Dof(
            previous,
            next,
            Vector3f(pivotInPrevious),
            Vector3f(pivotInNext),
            identity,
            identity,
            RotationOrder.XYZ,
        )
        lock(dof, LIN_X)
        lock(dof, LIN_Y)
        lock(dof, LIN_Z)
        limit(dof, ANG_X, -halfAngleRad.coerceAtLeast(0.05f), halfAngleRad.coerceAtLeast(0.05f))
        lock(dof, ANG_Y)
        lock(dof, ANG_Z)
        dof.enableSpring(LIN_X, false)
        dof.enableSpring(LIN_Y, false)
        dof.enableSpring(LIN_Z, false)
        return dof
    }

    /**
     * Carries one reference shoe with the sprung chassis while retaining the
     * belt's physical hinge axis.  A closed chain needs one such anchor per
     * side; without it the links are a free-falling loop and stay at the spawn
     * position after the vehicle drives away.
     */
    @JvmStatic
    fun trackLinkCarrier(
        chassis: PhysicsRigidBody,
        link: PhysicsRigidBody,
        pivotInChassis: Vector3f,
    ): New6Dof {
        val dof = New6Dof(
            chassis,
            link,
            Vector3f(pivotInChassis),
            Vector3f(0f, 0f, 0f),
            Matrix3f(),
            Matrix3f(),
            RotationOrder.XYZ,
        )
        lock(dof, LIN_X)
        lock(dof, LIN_Y)
        lock(dof, LIN_Z)
        free(dof, ANG_X)
        lock(dof, ANG_Y)
        lock(dof, ANG_Z)
        dof.enableSpring(LIN_X, false)
        dof.enableSpring(LIN_Y, false)
        dof.enableSpring(LIN_Z, false)
        return dof
    }

    /**
     * Kinematic controller (A) to sprung chassis (B). Locks local X/Z translation and yaw,
     * frees vertical travel, and limits pitch (ANG_X) and roll (ANG_Z) in radians.
     */
    @JvmStatic
    fun suspensionCarrier(
        controller: PhysicsRigidBody,
        sprungBody: PhysicsRigidBody,
        pivotInController: Vector3f,
        pivotInSprungBody: Vector3f,
        frameInController: Matrix3f,
        frameInSprungBody: Matrix3f,
        pitchMinRad: Float,
        pitchMaxRad: Float,
        rollMinRad: Float,
        rollMaxRad: Float,
    ): New6Dof {
        val dof = New6Dof(
            controller,
            sprungBody,
            Vector3f(pivotInController),
            Vector3f(pivotInSprungBody),
            Matrix3f().set(frameInController),
            Matrix3f().set(frameInSprungBody),
            RotationOrder.XYZ
        )
        lock(dof, LIN_X)
        free(dof, LIN_Y)
        lock(dof, LIN_Z)
        limit(dof, ANG_X, pitchMinRad, pitchMaxRad)
        lock(dof, ANG_Y)
        limit(dof, ANG_Z, rollMinRad, rollMaxRad)
        return dof
    }

    @JvmStatic
    fun springBetween(
        bodyA: PhysicsRigidBody,
        bodyB: PhysicsRigidBody,
        pivotInA: Vector3f,
        pivotInB: Vector3f,
        minTravel: Float,
        maxTravel: Float,
        stiffness: Float,
        damping: Float,
    ): New6Dof {
        val identity = Matrix3f()
        val dof = New6Dof(bodyA, bodyB, pivotInA, pivotInB, identity, identity, RotationOrder.XYZ)
        lock(dof, LIN_X)
        lock(dof, LIN_Z)
        limit(dof, LIN_Y, minTravel, maxTravel)
        dof.enableSpring(LIN_Y, true)
        dof.setStiffness(LIN_Y, stiffness, true)
        dof.setDamping(LIN_Y, damping, true)
        dof.setEquilibriumPoint(LIN_Y, 0f)
        lock(dof, ANG_X)
        lock(dof, ANG_Y)
        lock(dof, ANG_Z)
        return dof
    }
}
