package cn.solarmoon.spark_core.mixin;

import cn.solarmoon.spark_core.LevelPatch;
import cn.solarmoon.spark_core.physics.level.ClientPhysicsLevel;
import cn.solarmoon.spark_core.physics.level.PhysicsLevel;
import cn.solarmoon.spark_core.physics.level.ServerPhysicsLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(Level.class)
public abstract class LevelMixin implements LevelPatch {
    @Unique
    private PhysicsLevel sparkCore$physicsLevel;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sparkCore$createPhysicsLevel(WritableLevelData data, ResourceKey<Level> dimension,
                                               RegistryAccess registryAccess, Holder<DimensionType> dimensionType,
                                               Supplier<ProfilerFiller> profiler, boolean clientSide,
                                               boolean debug, long biomeZoomSeed, int maxNeighborUpdates,
                                               CallbackInfo ci) {
        Level self = (Level) (Object) this;
        sparkCore$physicsLevel = clientSide
                ? new ClientPhysicsLevel(self)
                : new ServerPhysicsLevel((ServerLevel) self);
    }

    @Override
    public PhysicsLevel getPhysicsLevel() {
        return sparkCore$physicsLevel;
    }
}
