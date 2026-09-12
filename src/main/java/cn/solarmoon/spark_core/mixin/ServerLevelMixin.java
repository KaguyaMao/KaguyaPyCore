package cn.solarmoon.spark_core.mixin;

import cn.solarmoon.spark_core.physics.SparkPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "onBlockStateChange", at = @At("HEAD"))
    private void sparkCore$markTerrainDirty(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        if (oldState != newState) {
            SparkPhysics.get((ServerLevel) (Object) this).getTerrainManager().markDirty(pos);
        }
    }
}
