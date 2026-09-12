package cn.solarmoon.spark_core.physics;

import cn.solarmoon.spark_core.physics.body.PhysicsJoints;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.joints.New6Dof;
import com.jme3.math.Matrix3f;
import com.jme3.math.Vector3f;
import com.jme3.bullet.objects.PhysicsRigidBody;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsJointsOleoTest {
    @Test
    void oleoForceIsEqualAndOppositeOnChassisAndWheel() {
        NativePhysicsLoader.load();
        PhysicsSpace space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        space.setGravity(new Vector3f());
        PhysicsRigidBody chassis = new PhysicsRigidBody(new BoxCollisionShape(1f), 1000f);
        PhysicsRigidBody wheel = new PhysicsRigidBody(new BoxCollisionShape(0.2f), 100f);
        try {
            chassis.setPhysicsLocation(new Vector3f(0f, 2f, 0f));
            wheel.setPhysicsLocation(new Vector3f(0f, 1f, 0f));
            space.addCollisionObject(chassis);
            space.addCollisionObject(wheel);
            New6Dof axle = PhysicsJoints.guidedWheelAxle(
                    chassis, wheel,
                    new Vector3f(0f, -1f, 0f), new Vector3f(),
                    new Matrix3f(), new Matrix3f(),
                    -0.3f, 0.22f);
            space.addJoint(axle);
            PhysicsJoints.applyOleoForce(
                    chassis, wheel,
                    new Vector3f(0f, 1f, 0f),
                    2000f,
                    new Vector3f(0f, 1f, 0f)
            );
            space.update(1f / 60f, 0);
            Vector3f chassisV = chassis.getLinearVelocity(null);
            Vector3f wheelV = wheel.getLinearVelocity(null);
            assertTrue(chassisV.y > 0f, "chassis must be pushed up: " + chassisV);
            assertTrue(wheelV.y < 0f, "wheel must be pushed down: " + wheelV);
        } finally {
            space.destroy();
        }
    }

    @Test
    void setTravelLimitsTightensLinY() {
        NativePhysicsLoader.load();
        PhysicsSpace space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        PhysicsRigidBody chassis = new PhysicsRigidBody(new BoxCollisionShape(1f), 10f);
        PhysicsRigidBody wheel = new PhysicsRigidBody(new BoxCollisionShape(0.2f), 1f);
        New6Dof axle = PhysicsJoints.guidedWheelAxle(
                chassis, wheel,
                new Vector3f(0f, -1f, 0f), new Vector3f(),
                new Matrix3f(), new Matrix3f(),
                -0.30f, 0.22f);
        PhysicsJoints.setTravelLimits(axle, -0.10f, 0.05f);
        assertEquals(-0.10f, axle.get(com.jme3.bullet.joints.motors.MotorParam.LowerLimit, PhysicsJoints.LIN_Y), 1.0e-5f);
        assertEquals(0.05f, axle.get(com.jme3.bullet.joints.motors.MotorParam.UpperLimit, PhysicsJoints.LIN_Y), 1.0e-5f);
        space.destroy();
    }
}
