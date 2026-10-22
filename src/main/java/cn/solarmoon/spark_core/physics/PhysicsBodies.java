package cn.solarmoon.spark_core.physics;

import cn.solarmoon.spark_core.physics.body.BodyFrame;
import cn.solarmoon.spark_core.physics.body.BodyRuntimeKt;
import cn.solarmoon.spark_core.physics.body.PhysicsBodyExtensionsKt;
import cn.solarmoon.spark_core.physics.level.PhysicsLevel;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Vector3f;
import net.minecraft.world.entity.Entity;

/** Java facade for Spark metadata associated with embedded Libbulletjme bodies. */
public final class PhysicsBodies {
    private PhysicsBodies() {
    }

    public static String getName(PhysicsCollisionObject body) {
        return BodyRuntimeKt.getName(body);
    }

    public static void setName(PhysicsCollisionObject body, String name) {
        BodyRuntimeKt.setName(body, name);
    }

    public static PhysicsHost getOwner(PhysicsCollisionObject body) {
        return BodyRuntimeKt.getOwner(body);
    }

    public static void setOwner(PhysicsCollisionObject body, PhysicsHost owner) {
        BodyRuntimeKt.setOwner(body, owner);
    }

    public static boolean isColliding(PhysicsCollisionObject body) {
        return BodyRuntimeKt.isColliding(body);
    }

    /** Stable cross-thread identity, matching {@code BodyFrame.bodyId}. */
    public static long getId(PhysicsCollisionObject body) {
        return BodyRuntimeKt.getBodyId(body);
    }

    public static void attachToEntity(PhysicsRigidBody body, Entity entity) {
        PhysicsBodyExtensionsKt.attachToEntity(body, entity);
    }

    public static void detach(PhysicsRigidBody body) {
        PhysicsBodyExtensionsKt.detach(body);
    }

    public static BodyFrame latestFrame(PhysicsLevel level, PhysicsCollisionObject body) {
        return level.getLatestFrame().getBodies().get(body);
    }

    /**
     * Bullet anisotropic friction. {@code mode} is the native flag (commonly 1 = linear).
     * Independent support wheels should stay isotropic with friction 0.
     */
    public static void setAnisotropicFriction(PhysicsCollisionObject body, Vector3f friction, int mode) {
        body.setAnisotropicFriction(new Vector3f(friction), mode);
    }
}
