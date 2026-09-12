package cn.solarmoon.spark_core.physics;

import cn.solarmoon.spark_core.EntityPatch;
import cn.solarmoon.spark_core.LevelPatch;
import cn.solarmoon.spark_core.physics.level.PhysicsLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public final class SparkPhysics {
    private SparkPhysics() {
    }

    public static PhysicsLevel get(Level level) {
        if (!(level instanceof LevelPatch patch)) {
            throw new IllegalStateException("Spark Core Level mixin was not applied to " + level.getClass().getName());
        }
        return patch.getPhysicsLevel();
    }

    public static PhysicsHost host(Entity entity) {
        if (!(entity instanceof EntityPatch patch)) {
            throw new IllegalStateException("Spark Core Entity mixin was not applied to " + entity.getClass().getName());
        }
        return patch;
    }
}
