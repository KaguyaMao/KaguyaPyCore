package cn.solarmoon.spark_core.physics.level

import cn.solarmoon.spark_core.util.PPhase
import cn.solarmoon.spark_core.util.Subscription
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * The host a [PhysicsLifecycleGate] drives. Keeping it behind an interface lets
 * the admission/shutdown protocol be tested without a Minecraft level while the
 * real [PhysicsLevel] remains the only production implementation.
 */
internal interface PhysicsLifecycleHost {
    val levelName: String

    /** Current observable lifecycle state. */
    val lifecycleState: PhysicsLevelState

    /** Publish a new lifecycle state. Called under the gate's lock. */
    fun transitionTo(state: PhysicsLevelState)

    /** Append a task to the phase queue. Called under the gate's lock. */
    fun enqueue(phase: PPhase, task: () -> Unit)

    /** Replace the deduplicated task for [key]. Called under the gate's lock. */
    fun enqueueDeduplicated(phase: PPhase, key: String, task: () -> Unit)
}

/**
 * Single admission gate for a physics worker.
 *
 * Guarantees:
 *
 * - startup, task admission and the shutdown transition are atomic, so a task
 *   is either admitted (and will run or fail explicitly) or rejected with
 *   [PhysicsLevelNotRunningException];
 * - a rejected task never leaves its [CompletableFuture] pending forever;
 * - shutdown is one-way: once it starts, no later startup completion can put
 *   the worker back into an accepting state;
 * - close listeners registered after shutdown started run exactly once,
 *   immediately, instead of being stored;
 * - repeated shutdown calls return the same future and never repeat teardown.
 */
internal class PhysicsLifecycleGate(private val host: PhysicsLifecycleHost) {
    private val lock = Any()
    private var shutdownFuture: CompletableFuture<Void>? = null
    private var shutdownCause: Throwable? = null
    private var shutdownState: PhysicsLevelState = PhysicsLevelState.STOPPED
    private var listenersNotified = false
    @Volatile private var shutdownStarted = false
    private val closeListeners = ArrayList<() -> Unit>()
    private val admittedTasks = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    /**
     * True while native mutation tasks may still be admitted.
     *
     * STARTING is included on purpose: entities can join a level while its
     * physics worker is still initializing, and their bodies must be queued
     * rather than silently dropped. STOPPING/STOPPED/FAILED reject everything.
     *
     * The flag is authoritative instead of the observable state, because a
     * startup completion racing a shutdown must not re-open admission.
     */
    val isAccepting: Boolean
        get() = synchronized(lock) { acceptingLocked() }

    private fun acceptingLocked(): Boolean =
        !shutdownStarted && when (host.lifecycleState) {
            PhysicsLevelState.NEW, PhysicsLevelState.STARTING, PhysicsLevelState.RUNNING -> true
            PhysicsLevelState.STOPPING, PhysicsLevelState.STOPPED, PhysicsLevelState.FAILED -> false
        }

    /** Terminal state chosen by the last [beginShutdown], or STOPPED. */
    val terminalState: PhysicsLevelState get() = synchronized(lock) { shutdownState }

    val cause: Throwable? get() = synchronized(lock) { shutdownCause }

    val admittedCount: Int get() = admittedTasks.size

    /**
     * Atomically move NEW to STARTING. Returns false when startup already
     * happened or shutdown has begun.
     */
    fun beginStart(): Boolean = synchronized(lock) {
        if (shutdownStarted || host.lifecycleState != PhysicsLevelState.NEW) return false
        host.transitionTo(PhysicsLevelState.STARTING)
        true
    }

    /**
     * Atomically publish a completed startup. Returns false when shutdown won
     * the race; the caller must not publish a successful init event and must
     * let the unified teardown own the constructed world.
     */
    fun completeStart(): Boolean = synchronized(lock) {
        if (!acceptingLocked() || host.lifecycleState != PhysicsLevelState.STARTING) return false
        host.transitionTo(PhysicsLevelState.RUNNING)
        true
    }

    /**
     * Queue a fire-and-forget task. Returns false when the worker is not
     * accepting, so the caller can log or drop deliberately instead of racing
     * a teardown.
     */
    fun enqueue(phase: PPhase, task: () -> Unit): Boolean {
        synchronized(lock) {
            if (!acceptingLocked()) return false
            try {
                host.enqueue(phase, task)
            } catch (error: Throwable) {
                cn.solarmoon.spark_core.SparkCore.LOGGER.error(
                    "{} failed to enqueue an immediate task", host.levelName, error,
                )
                return false
            }
            return true
        }
    }

    /**
     * Queue a deduplicated fire-and-forget task. Admission uses the same lock
     * as shutdown so a task cannot slip into the map after the drain started.
     */
    fun enqueueDeduplicated(phase: PPhase, key: String, task: () -> Unit): Boolean {
        synchronized(lock) {
            if (!acceptingLocked()) return false
            try {
                host.enqueueDeduplicated(phase, key, task)
            } catch (error: Throwable) {
                cn.solarmoon.spark_core.SparkCore.LOGGER.error(
                    "{} failed to enqueue deduplicated task '{}'", host.levelName, key, error,
                )
                return false
            }
            return true
        }
    }

    /**
     * Queue a task that reports its own outcome through a future.
     *
     * The task runs at most once. Its future completes after the task returns,
     * or exceptionally when the task throws. If the gate is not accepting, the
     * future fails immediately.
     */
    fun submitGuarded(description: String, phase: PPhase, task: () -> Unit): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        synchronized(lock) {
            if (!acceptingLocked()) {
                future.completeExceptionally(PhysicsLevelNotRunningException(host.levelName, host.lifecycleState))
                return future
            }
            admittedTasks += future
            try {
                host.enqueue(phase) {
                    if (shutdownStarted) {
                        // Shutdown won between admission and execution. A queued
                        // operation must fail explicitly instead of creating or
                        // mutating native objects during teardown.
                        future.completeExceptionally(
                            PhysicsLevelNotRunningException(host.levelName, host.lifecycleState),
                        )
                        admittedTasks -= future
                        return@enqueue
                    }
                    try {
                        task()
                        future.complete(null)
                    } catch (error: Throwable) {
                        future.completeExceptionally(error)
                    } finally {
                        admittedTasks -= future
                    }
                }
            } catch (error: Throwable) {
                admittedTasks -= future
                future.completeExceptionally(error)
            }
        }
        return future
    }

    /**
     * Generic guarded submission used by the public task API. The future carries
     * the action's value and is always completed, including on rejection and
     * shutdown.
     */
    fun <T> submitTask(description: String, phase: PPhase, task: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        synchronized(lock) {
            if (!acceptingLocked()) {
                future.completeExceptionally(PhysicsLevelNotRunningException(host.levelName, host.lifecycleState))
                return future
            }
            admittedTasks += future
            try {
                host.enqueue(phase) {
                    if (shutdownStarted) {
                        future.completeExceptionally(
                            PhysicsLevelNotRunningException(host.levelName, host.lifecycleState),
                        )
                        admittedTasks -= future
                        return@enqueue
                    }
                    try {
                        future.complete(task())
                    } catch (error: Throwable) {
                        future.completeExceptionally(error)
                    } finally {
                        admittedTasks -= future
                    }
                }
            } catch (error: Throwable) {
                admittedTasks -= future
                future.completeExceptionally(error)
            }
        }
        return future
    }

    /**
     * Move the worker into shutdown and return the single shutdown future.
     *
     * Idempotent: later calls (including from a failure path) return the first
     * future. Returns true through [startCleanup] only for the caller that must
     * run the teardown.
     */
    fun beginShutdown(state: PhysicsLevelState, cause: Throwable?): Pair<CompletableFuture<Void>, Boolean> {
        synchronized(lock) {
            shutdownFuture?.let { return it to false }
            val future = CompletableFuture<Void>()
            shutdownFuture = future
            shutdownCause = cause
            shutdownState = state
            // Publish the terminal state before returning so no submission can
            // slip in between the transition and the queue drain. shutdownStarted
            // is the authoritative admission flag.
            shutdownStarted = true
            host.transitionTo(state)
            return future to true
        }
    }

    /** Non-consuming view of the shutdown future, or null before shutdown. */
    fun shutdownFutureOrNull(): CompletableFuture<Void>? = synchronized(lock) { shutdownFuture }

    /**
     * Register one-shot shutdown work.
     *
     * A listener added after the transition runs immediately so a component
     * built during teardown still observes the close. Otherwise it is invoked
     * exactly once by [notifyCloseListeners].
     */
    fun addCloseListener(listener: () -> Unit): Subscription {
        val invokeNow: Boolean
        synchronized(lock) {
            invokeNow = listenersNotified || !acceptingLocked()
            if (!invokeNow) closeListeners.add(listener)
        }
        if (invokeNow) {
            runListener(listener)
            return Subscription {}
        }
        return Subscription { synchronized(lock) { closeListeners.remove(listener) } }
    }

    /** Invoke every outstanding listener exactly once. Safe to call repeatedly. */
    fun notifyCloseListeners() {
        val listeners: List<() -> Unit>
        synchronized(lock) {
            if (listenersNotified) return
            listenersNotified = true
            listeners = ArrayList(closeListeners)
            closeListeners.clear()
        }
        listeners.forEach(::runListener)
    }

    /**
     * Fail every admitted task that has not completed. Called after the queues
     * are drained so a task that already ran is never double-completed.
     */
    fun failRemainingTasks() {
        val remaining = admittedTasks.toList()
        if (remaining.isEmpty()) return
        val error = PhysicsLevelNotRunningException(host.levelName, host.lifecycleState)
        remaining.forEach { it.completeExceptionally(error) }
        admittedTasks.clear()
    }

    /**
     * Complete the shutdown future. [cleanupError] wins over a recorded cause
     * only when no cause exists; otherwise the cause is primary and the cleanup
     * error is attached as a suppressed exception.
     */
    fun completeShutdown(cleanupError: Throwable?) {
        val future: CompletableFuture<Void>?
        val recordedCause: Throwable?
        synchronized(lock) {
            future = shutdownFuture
            recordedCause = shutdownCause
        }
        if (future == null) return
        if (cleanupError == null && recordedCause == null) {
            future.complete(null)
            return
        }
        val primary = recordedCause ?: cleanupError!!
        if (recordedCause != null && cleanupError != null && cleanupError !== recordedCause) {
            recordedCause.addSuppressed(cleanupError)
        }
        future.completeExceptionally(primary)
    }

    private fun runListener(listener: () -> Unit) {
        try {
            listener()
        } catch (error: Throwable) {
            cn.solarmoon.spark_core.SparkCore.LOGGER.error("{} close listener failed", host.levelName, error)
        }
    }
}
