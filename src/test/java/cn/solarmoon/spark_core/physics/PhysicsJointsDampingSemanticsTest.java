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
 * 锁定 New6Dof 悬挂 API 的阻尼语义，防止单位误用。
 *
 * wheelSuspension 的 damping 参数必须是无量纲求解器阻尼比（0=无阻尼、1=临界阻尼），
 * 而不是物理阻尼系数 c = 2ζ√(km)（单位 N·s/m，量级可达数万）。
 * 传入 N·s/m 会被 coerceIn(0,1) 钳制为 1.0（伪临界阻尼），这正说明它绝不是阻尼系数单元。
 * 显式物理阻尼必须使用 guidedWheelAxle（禁用内置弹簧）并在物理子步自行计算。
 */
class PhysicsJointsDampingSemanticsTest {

    @Test
    void wheelSuspensionOnlyAcceptsDimensionlessRatioAndIsaNspMValueIsClamped() {
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
            // 读回阻尼：API 仅支持 [0,1]，N·s/m 被钳制为 1.0（临界阻尼），绝不原样保留。
            float damping = joint.getTranslationMotor()
                    .get(MotorParam.Damping, new Vector3f()).y;
            assertEquals(1.0f, damping, 1.0e-3f,
                    "N·s/m damping must be clamped to 1.0 (dimensionless critical damping)");

            // 对比：合理的无量纲阻尼比（0.70）应原样保留而非钳制。
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
