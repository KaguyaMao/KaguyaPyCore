package cn.solarmoon.spark_core.physics.level

import cn.solarmoon.spark_core.util.PPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The admission gate is the only place where task submission and shutdown meet.
 * These tests drive it without a Minecraft level so the ordering is explicit.
 */
class PhysicsLifecycleGateTest {
    private class FakeHost(initial: PhysicsLevelState = PhysicsLevelState.RUNNING) : PhysicsLifecycleHost {
        @Volatile private var state = initial
        val queued = ConcurrentLinkedQueue<Pair<PPhase, () -> Unit>>()
        val transitions = ConcurrentLinkedQueue<PhysicsLevelState>()
        val deduplicated = ConcurrentHashMap<String, Pair<PPhase, () -> Unit>>()

        override val levelName: String get() = "fake"
        override val lifecycleState: PhysicsLevelState get() = state
        override fun transitionTo(state: PhysicsLevelState) {
            this.state = state
            transitions += state
        }

        override fun enqueue(phase: PPhase, task: () -> Unit) {
            queued += phase to task
        }

        override fun enqueueDeduplicated(phase: PPhase, key: String, task: () -> Unit) {
            deduplicated[key] = phase to task
        }

        fun runAll(): Int {
            var count = 0
            while (true) {
                val next = queued.poll() ?: return count
                next.second()
                count++
            }
        }
    }

    @Test
    fun aTaskSubmittedWhileRunningRunsAndCompletesItsFuture() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        val future = gate.submitGuarded("create", PPhase.ALL) { }
        assertEquals(1, host.runAll())
        assertTrue(future.isDone)
        assertFalse(future.isCompletedExceptionally)
    }

    @Test
    fun aTaskSubmittedAfterShutdownFailsInsteadOfHanging() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        gate.beginShutdown(PhysicsLevelState.STOPPING, null)
        val future = gate.submitGuarded("create", PPhase.ALL) { }
        assertTrue(future.isCompletedExceptionally)
        val error = runCatching { future.getNow(null) }.exceptionOrNull()
        assertTrue(error?.cause is PhysicsLevelNotRunningException, "unexpected: $error")
        assertEquals(0, host.runAll())
    }

    @Test
    fun aTaskThatThrowsFailsItsOwnFutureOnly() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        val failing = gate.submitGuarded("bad", PPhase.ALL) { error("boom") }
        val healthy = gate.submitGuarded("good", PPhase.ALL) { }
        host.runAll()
        assertTrue(failing.isCompletedExceptionally)
        assertFalse(healthy.isCompletedExceptionally)
    }

    @Test
    fun repeatedShutdownReturnsTheSameFuture() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        val (first, firstOwner) = gate.beginShutdown(PhysicsLevelState.STOPPING, null)
        val (second, secondOwner) = gate.beginShutdown(PhysicsLevelState.FAILED, IllegalStateException("late"))
        assertSame(first, second)
        assertTrue(firstOwner)
        assertFalse(secondOwner, "only one caller may own cleanup")
        assertSame(first, gate.shutdownFutureOrNull())
    }

    @Test
    fun aListenerRegisteredAfterShutdownRunsImmediatelyAndOnce() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        val calls = AtomicInteger()
        gate.beginShutdown(PhysicsLevelState.STOPPING, null)
        gate.addCloseListener { calls.incrementAndGet() }
        gate.notifyCloseListeners()
        assertEquals(1, calls.get())
    }

    @Test
    fun aListenerRegisteredWhileRunningRunsOnceOnShutdown() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        val calls = AtomicInteger()
        gate.addCloseListener { calls.incrementAndGet() }
        gate.beginShutdown(PhysicsLevelState.STOPPING, null)
        gate.notifyCloseListeners()
        gate.notifyCloseListeners()
        assertEquals(1, calls.get())
    }

    @Test
    fun aDisposedListenerIsNeverInvoked() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        val calls = AtomicInteger()
        val subscription = gate.addCloseListener { calls.incrementAndGet() }
        subscription.dispose()
        gate.beginShutdown(PhysicsLevelState.STOPPING, null)
        gate.notifyCloseListeners()
        assertEquals(0, calls.get())
    }

    @Test
    fun anAdmittedTaskThatNeverRanIsFailedExplicitly() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        val admitted = gate.submitGuarded("create", PPhase.ALL) { }
        gate.beginShutdown(PhysicsLevelState.STOPPING, null)
        gate.failRemainingTasks()
        assertTrue(admitted.isCompletedExceptionally)
        assertEquals(0, gate.admittedCount)
    }

    @Test
    fun aQueuedTaskDoesNotRunAfterShutdownStarted() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        var ran = false
        val queued = gate.submitGuarded("create", PPhase.ALL) { ran = true }
        gate.beginShutdown(PhysicsLevelState.STOPPING, null)
        host.runAll()
        assertFalse(ran, "shutdown must not execute queued creation work")
        assertTrue(queued.isCompletedExceptionally)
        assertEquals(0, gate.admittedCount)
    }

    @Test
    fun aQueuedGenericTaskDoesNotRunAfterShutdownStarted() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        var ran = false
        val queued = gate.submitTask("value", PPhase.ALL) { ran = true; 1 }
        gate.beginShutdown(PhysicsLevelState.STOPPING, null)
        host.runAll()
        assertFalse(ran)
        assertTrue(queued.isCompletedExceptionally)
    }

    @Test
    fun shutdownCompletesExceptionallyWithTheRecordedCause() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        val boom = IllegalStateException("physics died")
        val (future, _) = gate.beginShutdown(PhysicsLevelState.FAILED, boom)
        gate.completeShutdown(null)
        val error = runCatching { future.getNow(null) }.exceptionOrNull()
        assertSame(boom, error?.cause)
    }

    @Test
    fun aCleanupErrorIsSuppressedOnThePrimaryCause() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        val boom = IllegalStateException("physics died")
        val cleanup = IllegalStateException("teardown failed")
        val (future, _) = gate.beginShutdown(PhysicsLevelState.FAILED, boom)
        gate.completeShutdown(cleanup)
        val error = runCatching { future.getNow(null) }.exceptionOrNull()
        assertSame(boom, error?.cause)
        assertTrue(boom.suppressed.contains(cleanup))
    }

    @Test
    fun aNormalShutdownCompletesSuccessfully() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        val (future, _) = gate.beginShutdown(PhysicsLevelState.STOPPING, null)
        gate.completeShutdown(null)
        assertTrue(future.isDone)
        assertFalse(future.isCompletedExceptionally)
    }

    @Test
    fun admissionAndShutdownAreAtomicUnderContention() {
        // Many threads race to submit while one shuts the gate down. Every
        // returned future must reach a terminal state, and every admitted task
        // must either run or be failed by the drain.
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads + 1)
        val start = CountDownLatch(1)
        val futures = ConcurrentLinkedQueue<CompletableFuture<Void>>()
        try {
            repeat(threads) {
                pool.submit {
                    start.await()
                    repeat(perThread) { futures += gate.submitGuarded("task", PPhase.ALL) { } }
                }
            }
            pool.submit {
                start.await()
                val (_, first) = gate.beginShutdown(PhysicsLevelState.STOPPING, null)
                assertTrue(first)
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "submitters did not finish")

            host.runAll()
            gate.failRemainingTasks()
            gate.completeShutdown(null)

            val pending = futures.count { !it.isDone }
            assertEquals(0, pending, "$pending futures stayed pending across shutdown")
            assertTrue(futures.size >= 1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun tasksSubmittedWhileStartingAreQueuedRatherThanDropped() {
        // Entities can join a level before its physics worker finished starting;
        // their bodies must still be built.
        val host = FakeHost(PhysicsLevelState.STARTING)
        val gate = PhysicsLifecycleGate(host)
        val future = gate.submitGuarded("entity body", PPhase.ALL) { }
        assertFalse(future.isDone, "a queued task must not complete before it runs")
        host.runAll()
        assertTrue(future.isDone)
        assertFalse(future.isCompletedExceptionally)
    }

    @Test
    fun tasksSubmittedWhileStoppingAreRejected() {
        val host = FakeHost(PhysicsLevelState.STOPPING)
        val gate = PhysicsLifecycleGate(host)
        assertFalse(gate.enqueue(PPhase.ALL) { })
        assertTrue(gate.submitGuarded("late", PPhase.ALL) { }.isCompletedExceptionally)
    }

    @Test
    fun aGenericTaskCompletesWithItsValue() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        val future = gate.submitTask("value", PPhase.ALL) { 42 }
        host.runAll()
        assertEquals(42, future.getNow(null))
    }

    @Test
    fun aGenericTaskRejectedByShutdownFailsImmediately() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        gate.beginShutdown(PhysicsLevelState.STOPPING, null)
        val future = gate.submitTask("value", PPhase.ALL) { 42 }
        assertTrue(future.isCompletedExceptionally)
        assertEquals(0, host.queued.size)
    }

    @Test
    fun startupCompletionCannotReopenAdmissionAfterShutdown() {
        val host = FakeHost(PhysicsLevelState.NEW)
        val gate = PhysicsLifecycleGate(host)
        assertTrue(gate.beginStart())
        assertTrue(gate.completeStart())
        assertEquals(PhysicsLevelState.RUNNING, host.lifecycleState)

        val other = FakeHost(PhysicsLevelState.NEW)
        val racing = PhysicsLifecycleGate(other)
        assertTrue(racing.beginStart())
        racing.beginShutdown(PhysicsLevelState.STOPPING, null)
        assertFalse(racing.completeStart(), "shutdown must win the startup race")
        assertEquals(PhysicsLevelState.STOPPING, other.lifecycleState)
        assertFalse(racing.isAccepting)
    }

    @Test
    fun deduplicatedAdmissionUsesTheShutdownGate() {
        val host = FakeHost()
        val gate = PhysicsLifecycleGate(host)
        assertTrue(gate.enqueueDeduplicated(PPhase.ALL, "terrain") { })
        gate.beginShutdown(PhysicsLevelState.STOPPING, null)
        assertFalse(gate.enqueueDeduplicated(PPhase.ALL, "late") { })
        assertEquals(setOf("terrain"), host.deduplicated.keys)
    }
}
