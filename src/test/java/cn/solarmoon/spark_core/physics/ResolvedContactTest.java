package cn.solarmoon.spark_core.physics;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.PhysicsTickListener;
import com.jme3.bullet.collision.ManifoldPoints;
import com.jme3.bullet.collision.PersistentManifolds;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedContactTest {
    @Test
    void solverImpulseIsAvailableDuringPostPhysicsTick() {
        NativePhysicsLoader.load();
        PhysicsSpace space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        try {
            float[] largestImpulse = {0f};
            space.addTickListener(new PhysicsTickListener() {
                @Override public void prePhysicsTick(PhysicsSpace ignored, float timeStep) { }
                @Override public void physicsTick(PhysicsSpace current, float timeStep) {
                    for (long manifold : current.listManifoldIds()) {
                        for (long point : PersistentManifolds.listPointIds(manifold)) {
                            largestImpulse[0] = Math.max(largestImpulse[0], ManifoldPoints.getAppliedImpulse(point));
                        }
                    }
                }
            });
            space.setGravity(new Vector3f(0f, -9.81f, 0f));
            PhysicsRigidBody floor = new PhysicsRigidBody(new BoxCollisionShape(4f, .5f, 4f), 0f);
            floor.setPhysicsLocation(new Vector3f(0f, -.5f, 0f));
            PhysicsRigidBody falling = new PhysicsRigidBody(new BoxCollisionShape(.5f), 1f);
            falling.setPhysicsLocation(new Vector3f(0f, 3f, 0f));
            space.addCollisionObject(floor);
            space.addCollisionObject(falling);
            for (int i = 0; i < 120; i++) space.update(1f / 60f, 0, false, true, false);
            assertTrue(largestImpulse[0] > .1f, "post-solver manifold should expose collision impulse");
        } finally {
            space.destroy();
        }
    }
}
