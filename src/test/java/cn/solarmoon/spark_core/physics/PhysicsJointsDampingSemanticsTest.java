package cn.solarmoon.spark_core.physics;

import cn.solarmoon.spark_core.physics.body.PhysicsJoints;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.joints.New6Dof;
import com.jme3.bullet.joints.motors.MotorParam;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Matrix3f;
import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定历史 wheelSuspension 包装接口的钳制行为，不推断底层物理单位。
 *
 * 包装器将 damping 钳制在 [0,1] 仅是兼容策略，读回 1.0 不能证明临界阻尼。
 * Libbulletjme 22.0.3 所带 Spring2 原生求解使用 N·s/m 系数；真正的动力学
 * 单位回归见 NativeSpringDampingTest。这里不改变旧调用的运行行为。
 */
class PhysicsJointsDampingSemanticsTest {

    @Test
    void legacyWheelSuspensionClampsDampingWithoutProvingNativeUnits() {
        NativePhysicsLoader.load();
        PhysicsSpace space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        PhysicsRigidBody chassis = null;
        PhysicsRigidBody wheelBody = null;
        New6Dof joint = null;
        New6Dof good = null;
        try {
            chassis = new PhysicsRigidBody(new BoxCollisionShape(1f), 55000f);
            chassis.setPhysicsLocation(new Vector3f(0f, 0f, 0f));
            wheelBody = new PhysicsRigidBody(new BoxCollisionShape(0.3f, 0.25f, 0.25f), 180f);
            wheelBody.setPhysicsLocation(new Vector3f(0f, -0.5f, 0f));
            space.addCollisionObject(chassis);
            space.addCollisionObject(wheelBody);

            // 现实量级：k≈712e3 N/m、cornerMass≈4583 kg → N·s/m 阻尼可达约 8.3e4。
            joint = PhysicsJoints.wheelSuspension(
                    chassis, wheelBody,
                    new Vector3f(0f, -0.5f, 0f), new Vector3f(0f, 0f, 0f),
                    -0.30f, 0.22f,
                    712_000f, 83_000f); // 83_000 是 N·s/m，不是阻尼比

            assertTrue(joint.isSpringEnabled(LIN_Y), "wheelSuspension enables the built-in spring");
            // 这是包装器的 coerceIn，不是原生 API 的单位或可接受范围限制。
            float damping = joint.getTranslationMotor()
                    .get(MotorParam.Damping, new Vector3f()).y;
            assertEquals(1.0f, damping, 1.0e-3f,
                    "Legacy wrapper still clamps damping to 1.0");

            // 历史 [0,1] 输入原样保留；不把 0.70 宣称为原生阻尼比。
            good = PhysicsJoints.wheelSuspension(
                    chassis, wheelBody,
                    new Vector3f(0f, -0.5f, 0f), new Vector3f(0f, 0f, 0f),
                    -0.30f, 0.22f,
                    712_000f, 0.70f);
            float ratio = good.getTranslationMotor()
                    .get(MotorParam.Damping, new Vector3f()).y;
            assertEquals(0.70f, ratio, 1.0e-2f);
        } finally {
            if (good != null) chassis.removeJoint(good);
            if (joint != null) chassis.removeJoint(joint);
            if (wheelBody != null) space.removeCollisionObject(wheelBody);
            if (chassis != null) space.removeCollisionObject(chassis);
            space.destroy();
        }
    }

    @Test
    void guidedWheelAxleDisablesBuiltinSpringSoExplicitDampingCannotDoubleApply() {
        NativePhysicsLoader.load();
        PhysicsSpace space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        PhysicsRigidBody chassis = new PhysicsRigidBody(new BoxCollisionShape(1f), 55000f);
        chassis.setPhysicsLocation(new Vector3f(0f, 0f, 0f));
        PhysicsRigidBody wheelBody = new PhysicsRigidBody(new BoxCollisionShape(0.3f, 0.25f, 0.25f), 180f);
        wheelBody.setPhysicsLocation(new Vector3f(0f, -0.5f, 0f));
        space.addCollisionObject(chassis);
        space.addCollisionObject(wheelBody);

        New6Dof joint = PhysicsJoints.guidedWheelAxle(
                chassis, wheelBody,
                new Vector3f(0f, -0.5f, 0f), new Vector3f(0f, 0f, 0f),
                new Matrix3f(), new Matrix3f(),
                -0.30f, 0.22f);

        assertFalse(joint.isSpringEnabled(LIN_Y),
                "guidedWheelAxle must disable the built-in spring to avoid double spring+damping");

        chassis.removeJoint(joint);
        space.removeCollisionObject(wheelBody);
        space.removeCollisionObject(chassis);
        space.destroy();
    }

    private static final int LIN_Y = PhysicsJoints.LIN_Y;
}
