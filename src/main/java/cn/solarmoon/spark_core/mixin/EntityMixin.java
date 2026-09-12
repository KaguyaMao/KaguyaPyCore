package cn.solarmoon.spark_core.mixin;

import cn.solarmoon.spark_core.EntityPatch;
import cn.solarmoon.spark_core.physics.SparkPhysics;
import cn.solarmoon.spark_core.physics.body.CollisionObjectEntity;
import cn.solarmoon.spark_core.physics.level.PhysicsLevel;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(Entity.class)
public abstract class EntityMixin implements EntityPatch {
    @Shadow
    private Level level;

    @Unique
    private final Map<String, PhysicsCollisionObject> sparkCore$physicsBodies = new ConcurrentHashMap<>();

    @Override
    public PhysicsLevel getPhysicsLevel() {
        return SparkPhysics.get(level);
    }

    @Override
    public Map<String, PhysicsCollisionObject> getAllPhysicsBodies() {
        return sparkCore$physicsBodies;
    }

    @Inject(method = "setPosRaw", at = @At("TAIL"))
    private void sparkCore$syncPhysicsLocation(double x, double y, double z, CallbackInfo ci) {
        if ((Object) this instanceof CollisionObjectEntity entity) {
            entity.onRawPosUpdated(x, y, z);
        }
    }
}
