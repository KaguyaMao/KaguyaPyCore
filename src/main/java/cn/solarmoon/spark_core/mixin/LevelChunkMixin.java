package cn.solarmoon.spark_core.mixin;

import cn.solarmoon.spark_core.physics.SparkPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Terrain invalidation is hooked on the chunk write funnel instead of
 * {@code ServerLevel.onBlockStateChange}. Forge's patched {@code Level.setBlock}
 * skips that notification entirely while {@code Level.captureBlockSnapshots} is
 * set (feature/structure placement, /place and other captured edits), so edits
 * made in such a window never invalidated cached collision even though the
 * blocks themselves changed. Every real write still passes through
 * {@code LevelChunk.setBlockState}, which reports the replaced state, so the
 * terrain cache is invalidated from there without any window that can swallow
 * an edit.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void sparkCore$markTerrainDirty(BlockPos pos, BlockState state, boolean isMoving,
                                            CallbackInfoReturnable<BlockState> cir) {
        BlockState oldState = cir.getReturnValue();
        if (oldState == null || oldState == state) return;
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        SparkPhysics.get(serverLevel).getTerrainManager().onBlockChanged(pos, oldState, state);
    }
}
