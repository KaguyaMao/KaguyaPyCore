package cn.solarmoon.spark_core.physics;

import cn.solarmoon.spark_core.physics.level.PhysicsLevel;
import cn.solarmoon.spark_core.util.PPhase;
import kotlin.Unit;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Java-friendly entry point for work that must own Libbulletjme objects.
 *
 * <p>Both methods delegate to {@link PhysicsLevel#submitTask}, which is the
 * single admission gate. They must not perform a state check followed by a
 * fire-and-forget submission: that pattern can drop the task during a close
 * race and leave the returned future pending forever.
 */
public final class PhysicsTasks {
    private PhysicsTasks() {
    }

    public static CompletableFuture<Void> run(PhysicsLevel level, Runnable action) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(action, "action");
        return level.submitTask("PhysicsTasks.run", PPhase.ALL, () -> {
            action.run();
            return Unit.INSTANCE;
        }).<Void>thenApply(ignored -> null);
    }

    public static <T> CompletableFuture<T> call(PhysicsLevel level, Callable<T> action) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(action, "action");
        return level.submitTask("PhysicsTasks.call", PPhase.ALL, () -> {
            try {
                return action.call();
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
    }
}
