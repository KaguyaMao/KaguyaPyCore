package cn.solarmoon.spark_core.physics;

import cn.solarmoon.spark_core.physics.level.PhysicsLevel;
import cn.solarmoon.spark_core.physics.level.PhysicsLevelState;
import cn.solarmoon.spark_core.util.PPhase;
import kotlin.Unit;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/** Java-friendly entry point for work that must own Libbulletjme objects. */
public final class PhysicsTasks {
    private PhysicsTasks() {
    }

    public static CompletableFuture<Void> run(PhysicsLevel level, Runnable action) {
        return call(level, () -> {
            action.run();
            return null;
        });
    }

    public static <T> CompletableFuture<T> call(PhysicsLevel level, Callable<T> action) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(action, "action");
        PhysicsLevelState state = level.getState();
        if (state == PhysicsLevelState.STOPPING || state == PhysicsLevelState.STOPPED
                || state == PhysicsLevelState.FAILED) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("PhysicsLevel is not accepting work: " + state));
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        level.submitImmediateTask(PPhase.ALL, () -> {
            PhysicsLevelState current = level.getState();
            if (current == PhysicsLevelState.STOPPING || current == PhysicsLevelState.STOPPED
                    || current == PhysicsLevelState.FAILED) {
                result.completeExceptionally(
                        new IllegalStateException("PhysicsLevel is not accepting work: " + current));
                return Unit.INSTANCE;
            }
            try {
                result.complete(action.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
            return Unit.INSTANCE;
        });
        return result;
    }
}
