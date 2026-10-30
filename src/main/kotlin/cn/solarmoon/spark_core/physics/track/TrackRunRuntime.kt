package cn.solarmoon.spark_core.physics.track

import cn.solarmoon.spark_core.physics.PhysicsHost
import cn.solarmoon.spark_core.physics.body.owner
import cn.solarmoon.spark_core.physics.level.PhysicsLevel
import cn.solarmoon.spark_core.physics.level.PhysicsLevelNotRunningException
import cn.solarmoon.spark_core.physics.toBullet
import cn.solarmoon.spark_core.util.PPhase
import cn.solarmoon.spark_core.util.Subscription
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.joints.New6Dof
import com.jme3.bullet.objects.PhysicsRigidBody
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Generic runtime for one dynamic track run.
 *
 * All native work happens on the owning [PhysicsLevel]'s physics thread. The
 * class itself may be created anywhere; construction only stores the spec.
 *
 * Lifecycle:
 *
 * ```
 * TrackRunRuntime(level, spec, owner)
 *   .create()          // queues link bodies and joints, publishes the first frame
 *   .snapshot()        // last published immutable state
 *   .reset(poses)      // teleport the assembly to a known pose
 *   .closeAsync()      // completes after this run's native objects are gone
 * ```
 *
 * The runtime publishes a new snapshot after every completed physics substep
 * and invalidates itself when the level shuts down or fails.
 *
 * Nothing here knows about vehicles, wheel roles, visible links or terrain
 * policy. Those belong to the caller.
 */
class TrackRunRuntime @JvmOverloads constructor(
    private val level: PhysicsLevel,
    val spec: TrackRunSpec,
    host: PhysicsHost? = null,
) : AutoCloseable {
    /**
     * Owner used for same-owner collision filtering.
     *
     * When the caller supplies a vehicle host, links join that host so they can
     * be filtered against the vehicle's own wheels and chassis. Without one,
     * the run gets a private token: sibling links still filter against each
     * other, but unrelated ownerless bodies in the world keep colliding with
     * the run normally. A global "both owners are null" rule would wrongly stop
     * every unowned pair in the world from colliding.
     */
    private val host: PhysicsHost = host ?: object : PhysicsHost {
        override val physicsLevel: PhysicsLevel get() = level
        override val allPhysicsBodies: MutableMap<String, PhysicsCollisionObject> = ConcurrentHashMap()
    }
    private enum class State { NEW, CREATING, ACTIVE, CLOSING, CLOSED, FAILED }

    private val lock = Any()
    private val stateRef = AtomicReference(State.NEW)
    private val snapshotRef = AtomicReference(TrackRunSnapshot.EMPTY)
    private val revision = AtomicLong(0)
    /** Changes on every teleport/reset so interpolation never crosses a jump. */
    private val poseEpoch = AtomicLong(0)
    private val closeFuture = AtomicReference<CompletableFuture<Void>?>()
    private val createFuture = AtomicReference<CompletableFuture<Void>?>()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    /** Stable identity used by snapshots and by per-run body names. */
    val runId: Long = nextRunId.incrementAndGet()

    /** Replaced atomically on the physics thread; readers may hold an old list safely. */
    @Volatile private var linksView: List<PhysicsRigidBody> = emptyList()
    @Volatile private var jointsView: List<New6Dof> = emptyList()

    @Volatile private var lastError: String? = null
    @Volatile private var postStepSubscription: Subscription? = null
    @Volatile private var closeSubscription: Subscription? = null
    /** Non-null when a failed create could not remove every native object. */
    @Volatile private var residualLedger: TrackRunAssemblyLedger? = null

    val isCreated: Boolean get() = stateRef.get() == State.ACTIVE
    val isClosed: Boolean get() = stateRef.get() == State.CLOSED || stateRef.get() == State.FAILED
    /** True while the run exists and its level is still stepping. */
    val isAlive: Boolean get() = stateRef.get() == State.ACTIVE && level.isAcceptingTasks()
    /** Link count of the current assembly; zero after teardown. */
    val linkCount: Int get() = linksView.size
    val currentRevision: Long get() = revision.get()

    /** Last error reported by the physics thread, or null. */
    fun lastError(): String? = lastError

    /**
     * Create every link body and joint on the physics thread.
     *
     * Safe to call more than once; concurrent and later calls share the same
     * result. The returned future completes after the objects exist and the
     * first snapshot is captured and visible through [snapshot], and
     * exceptionally if validation or native creation failed, the run was closed
     * first, or the level stopped.
     */
    fun create(): CompletableFuture<Void> {
        val future: CompletableFuture<Void>
        synchronized(lock) {
            when (stateRef.get()) {
                State.ACTIVE -> return CompletableFuture.completedFuture(null)
                State.CREATING -> return createFuture.get()
                    ?: CompletableFuture.failedFuture(IllegalStateException("create is in progress without a future"))
                State.CLOSING, State.CLOSED, State.FAILED -> return CompletableFuture.failedFuture(
                    IllegalStateException("TrackRunRuntime '${spec.name}' is closed"),
                )
                State.NEW -> {
                    stateRef.set(State.CREATING)
                    future = CompletableFuture()
                    createFuture.set(future)
                }
            }
        }
        level.submitGuarded("create ${spec.name}#$runId", PPhase.ALL) { createNow() }
            .whenComplete { _, error ->
                // A null error means createNow committed the assembly and its
                // first frame. A later close does not retroactively fail that.
                synchronized(lock) {
                    if (error != null && stateRef.get() == State.CREATING) {
                        stateRef.set(State.FAILED)
                    }
                    createFuture.set(null)
                }
                if (error == null) {
                    future.complete(null)
                } else {
                    lastError = error.message ?: error::class.java.simpleName
                    future.completeExceptionally(error)
                }
            }
        return future
    }

    /**
     * Transactional assembly.
     *
     * Every native object is recorded in a rollback ledger as soon as it
     * exists, before its remaining properties are configured. The run becomes
     * ACTIVE only after all bodies and joints are registered, the listeners are
     * installed and the first snapshot has been captured. Any failure removes
     * joints first, then bodies, then reports the original error with cleanup
     * failures attached.
     */
    private fun createNow() {
        level.assertPhysicsThread()
        if (stateRef.get() != State.CREATING) {
            throw PhysicsLevelNotRunningException(
                level.name,
                level.state,
                "TrackRunRuntime '${spec.name}' is no longer creating",
            )
        }
        val ledger = TrackRunAssemblyLedger(
            removeJoint = { joint -> level.removeJoint(joint) },
            removeBody = { body -> level.removeBody(body) },
        )
        var postSub: Subscription? = null
        var closeSub: Subscription? = null
        try {
            spec.links.indices.forEach { index ->
                val body = TrackRunAssembly.createBody(
                    spec,
                    index,
                    host,
                    TrackRunAssembly.bodyName(runId, spec, index),
                ) { created -> ledger.recordBody(created) }
                level.addBody(body)
            }
            spec.joints.forEach { joint ->
                val dof = TrackRunAssembly.createJoint(spec, joint, ledger.bodies()) { created ->
                    ledger.recordJoint(created)
                }
                level.addJoint(dof)
            }
            val bodies = ledger.bodies()
            val joints = ledger.joints()
            // Capture the first frame before ACTIVE becomes visible. Calling the
            // public publish() here would be a no-op by design.
            val first = captureTrackRunSnapshot(
                spec,
                runId,
                level.tickCount,
                revision.incrementAndGet(),
                bodies,
                joints,
                poseEpoch.get(),
            )
            postSub = level.addPostStepListener { publishIfAlive() }
            closeSub = level.addCloseListener { onLevelClosed() }
            val committed = synchronized(lock) {
                if (stateRef.get() != State.CREATING) {
                    false
                } else {
                    linksView = java.util.List.copyOf(bodies)
                    jointsView = java.util.List.copyOf(joints)
                    snapshotRef.set(first)
                    postStepSubscription = postSub
                    closeSubscription = closeSub
                    stateRef.set(State.ACTIVE)
                    true
                }
            }
            if (!committed) {
                throw PhysicsLevelNotRunningException(
                    level.name,
                    level.state,
                    "TrackRunRuntime '${spec.name}' was closed before assembly committed",
                )
            }
            lastError = null
        } catch (error: Throwable) {
            lastError = error.message ?: error::class.java.simpleName
            ledger.rollback(error)
            if (ledger.hasRemaining()) {
                // Keep ownership of whatever could not be removed so close can
                // retry instead of reporting success over live native objects.
                residualLedger = ledger
                linksView = java.util.List.copyOf(ledger.bodies())
                jointsView = java.util.List.copyOf(ledger.joints())
            } else {
                linksView = emptyList()
                jointsView = emptyList()
            }
            snapshotRef.set(TrackRunSnapshot.EMPTY)
            postSub?.dispose()
            postSub = null
            closeSub?.dispose()
            closeSub = null
            throw error
        }
    }

    /**
     * Publish the current link state as an immutable snapshot.
     *
     * Runs automatically after every substep. Must be called on the physics
     * thread when invoked manually, and only while the run is ACTIVE.
     */
    fun publish() {
        if (stateRef.get() != State.ACTIVE) return
        level.assertPhysicsThread()
        val links = linksView
        if (links.isEmpty()) return
        snapshotRef.set(
            captureTrackRunSnapshot(
                spec,
                runId,
                level.tickCount,
                revision.incrementAndGet(),
                links,
                jointsView,
                poseEpoch.get(),
            ),
        )
    }

    private fun publishIfAlive() {
        if (stateRef.get() == State.ACTIVE) publish()
    }

    /** Latest immutable snapshot; safe from any thread. */
    fun snapshot(): TrackRunSnapshot = snapshotRef.get()

    /**
     * Teleport the whole run to new link poses. The caller supplies one pose per
     * link in spec order; mismatched sizes or non-finite poses are rejected
     * before any native call. The pose list is copied before it is handed to
     * the physics thread.
     */
    fun reset(poses: List<TrackRunLinkFrame>): CompletableFuture<Void> {
        if (poses.size != spec.links.size) {
            return CompletableFuture.failedFuture(
                IllegalArgumentException("reset needs ${spec.links.size} poses, got ${poses.size}"),
            )
        }
        if (poses.any { !it.finite() || !it.isRepresentableInBullet() }) {
            return CompletableFuture.failedFuture(IllegalArgumentException("reset pose is not finite or not representable"))
        }
        val posesCopy = java.util.List.copyOf(poses)
        return submitGuarded("reset") {
            val links = linksView
            if (links.size != posesCopy.size) {
                throw IllegalStateException("TrackRunRuntime '${spec.name}' has no complete assembly")
            }
            links.forEachIndexed { index, body ->
                val pose = posesCopy[index]
                body.clearForces()
                body.setPhysicsLocation(pose.position.toBullet())
                body.setPhysicsRotation(jmeQuaternion(pose.rotation))
                body.setLinearVelocity(pose.linearVelocity.toBullet())
                body.setAngularVelocity(pose.angularVelocity.toBullet())
                body.activate(true)
            }
            // A teleport is not a continuous motion sample. Bump the epoch so a
            // renderer never blends across the jump.
            poseEpoch.incrementAndGet()
            publish()
        }
    }

    /**
     * Same pose write as [reset], but only when the caller is already the
     * physics thread. A vehicle reset uses this so it does not queue another
     * task behind itself and deadlock on [CompletableFuture.join].
     */
    fun resetNow(poses: List<TrackRunLinkFrame>) {
        level.assertPhysicsThread()
        if (poses.size != spec.links.size) {
            throw IllegalArgumentException("reset needs ${spec.links.size} poses, got ${poses.size}")
        }
        if (poses.any { !it.finite() || !it.isRepresentableInBullet() }) {
            throw IllegalArgumentException("reset pose is not finite or not representable")
        }
        val links = linksView
        if (links.size != poses.size) {
            throw IllegalStateException("TrackRunRuntime '${spec.name}' has no complete assembly")
        }
        links.forEachIndexed { index, body ->
            val pose = poses[index]
            body.clearForces()
            body.setPhysicsLocation(pose.position.toBullet())
            body.setPhysicsRotation(jmeQuaternion(pose.rotation))
            body.setLinearVelocity(pose.linearVelocity.toBullet())
            body.setAngularVelocity(pose.angularVelocity.toBullet())
            body.activate(true)
        }
        poseEpoch.incrementAndGet()
        publish()
    }

    /**
     * Apply one torque impulse to a link. [axis] is a world-space direction and
     * [magnitude] is in N*m*s.
     *
     * The magnitude is clamped to [TrackRunSpec.maxLinkTorqueImpulseNms]. The
     * returned value is the **accepted** magnitude, not proof that the physics
     * thread has applied it; a run that closes first drops the request. Use
     * [applyLinkTorqueImpulseAsync] when completion must be observed.
     */
    fun applyLinkTorqueImpulse(index: Int, axis: Vec3, magnitude: Float): Float {
        return applyLinkTorqueImpulseAsync(index, axis, magnitude).acceptedMagnitude
    }

    /**
     * Submit a torque impulse and report when the physics thread has applied
     * it. The future fails when the run or level is already closed.
     */
    fun applyLinkTorqueImpulseAsync(
        index: Int,
        axis: Vec3,
        magnitude: Float,
    ): TrackRunInputReceipt.Future {
        if (stateRef.get() != State.ACTIVE) {
            return rejected("run is not active")
        }
        if (index !in linksView.indices || !axis.isFiniteVec() || axis.lengthSqr() < 1.0e-8) {
            return rejected("invalid link index or axis")
        }
        val applied = TrackRunLimits.clampTorqueImpulse(magnitude, spec.maxLinkTorqueImpulseNms)
        if (applied == 0f) {
            return rejected("torque impulse clamped to zero")
        }
        val unit = axis.normalize()
        val receipt = TrackRunInputReceipt(accepted = true, clampedMagnitude = applied, reason = null)
        val future = submitGuarded("input") {
            val body = linksView.getOrNull(index) ?: throw IllegalStateException(
                "TrackRunRuntime '${spec.name}' no longer has link $index",
            )
            body.applyTorqueImpulse(unit.toBullet().multLocal(applied))
            body.activate(true)
        }
        return TrackRunInputReceipt.Future(receipt, future)
    }

    /**
     * Apply one central force to a link, in newtons. The vector is clamped to
     * [TrackRunSpec.maxLinkForceN]. The returned value is the **accepted**
     * magnitude, not proof that the physics thread has applied it.
     */
    fun applyLinkForce(index: Int, force: Vec3): Float {
        return applyLinkForceAsync(index, force).acceptedMagnitude
    }

    /** Submit a central force and report when the physics thread applied it. */
    fun applyLinkForceAsync(index: Int, force: Vec3): TrackRunInputReceipt.Future {
        if (stateRef.get() != State.ACTIVE) {
            return rejected("run is not active")
        }
        if (index !in linksView.indices || !force.isFiniteVec()) {
            return rejected("invalid link index or force")
        }
        val applied = TrackRunLimits.clampForce(force, spec.maxLinkForceN)
        val magnitude = applied.length().toFloat()
        if (magnitude < 1.0e-6f) {
            return rejected("force clamped to zero")
        }
        val receipt = TrackRunInputReceipt(accepted = true, clampedMagnitude = magnitude, reason = null)
        val future = submitGuarded("input") {
            val body = linksView.getOrNull(index) ?: throw IllegalStateException(
                "TrackRunRuntime '${spec.name}' no longer has link $index",
            )
            body.applyCentralForce(applied.toBullet())
            body.activate(true)
        }
        return TrackRunInputReceipt.Future(receipt, future)
    }

    /**
     * Read the current linear velocity of one link on the physics thread.
     * Returns null when the link is unavailable or the caller is not the
     * physics worker.
     */
    fun linkVelocity(index: Int): Vec3? {
        if (!level.isPhysicsThread() || stateRef.get() != State.ACTIVE) return null
        val body = linksView.getOrNull(index) ?: return null
        // A pinned (zero-mass) link has no velocity, and reading one asserts.
        if (!body.isDynamic) return Vec3.ZERO
        val velocity = body.getLinearVelocity(null)
        return Vec3(velocity.x.toDouble(), velocity.y.toDouble(), velocity.z.toDouble())
    }

    /**
     * Switch one link from inherited world gravity to an explicit vector. The
     * world gravity itself is never changed.
     */
    fun setLinkGravity(index: Int, gravity: Vec3) {
        if (stateRef.get() != State.ACTIVE || index !in linksView.indices || !gravity.isFiniteVec()) return
        level.submitImmediateTask {
            if (stateRef.get() != State.ACTIVE) return@submitImmediateTask
            val body = linksView.getOrNull(index) ?: return@submitImmediateTask
            body.setProtectGravity(true)
            body.setGravity(gravity.toBullet())
            body.activate(true)
        }
    }

    /**
     * Run [action] on the physics thread with the current native link bodies in
     * spec order.
     *
     * This is the controlled composition point for external constraints (for
     * example a wheel-to-link contact joint owned by a vehicle runtime). The
     * callback must not retain the list or the bodies after it returns; the run
     * and level own their lifetimes. The future fails when the run is not
     * active, and completes only after the callback has returned on the physics
     * thread.
     *
     * Any joint created by the callback is owned by the caller and must be
     * removed before this run closes.
     */
    fun <T> withLinkBodies(action: (List<PhysicsRigidBody>) -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        synchronized(lock) {
            if (stateRef.get() != State.ACTIVE) {
                future.completeExceptionally(
                    IllegalStateException("TrackRunRuntime '${spec.name}' is not active"),
                )
                return future
            }
            pending += future
        }
        level.submitTask("withLinkBodies ${spec.name}#$runId", PPhase.ALL) {
            if (stateRef.get() != State.ACTIVE) {
                throw PhysicsLevelNotRunningException(
                    level.name,
                    level.state,
                    "TrackRunRuntime '${spec.name}' closed before the link callback ran",
                )
            }
            action(java.util.List.copyOf(linksView))
        }.whenComplete { value, error ->
            pending -= future
            if (error != null) {
                lastError = error.message ?: error::class.java.simpleName
                future.completeExceptionally(error)
            } else {
                future.complete(value)
            }
        }
        return future
    }

    /**
     * Idempotent teardown. Joints are removed before their bodies on the
     * physics thread; the returned future completes after this run's native
     * objects are gone, or after the level's unified teardown when the level
     * already owns them.
     *
     * A successful future therefore means the objects are actually gone, not
     * merely that this runtime dropped its references.
     */
    fun closeAsync(): CompletableFuture<Void> {
        val future: CompletableFuture<Void>
        var shouldSubmit = false
        var delegate = false
        var completeImmediately = false
        synchronized(lock) {
            val existing = closeFuture.get()
            if (existing != null) {
                val residual = linksView.isNotEmpty() || jointsView.isNotEmpty()
                // A failed close that still owns native objects may be retried.
                if (!existing.isCompletedExceptionally || !residual) return existing
            }
            future = CompletableFuture()
            closeFuture.set(future)
            when (stateRef.get()) {
                State.NEW, State.CREATING, State.ACTIVE -> {
                    stateRef.set(State.CLOSING)
                    shouldSubmit = true
                }
                State.CLOSING -> delegate = true
                State.CLOSED -> {
                    if (linksView.isNotEmpty() || jointsView.isNotEmpty()) {
                        stateRef.set(State.CLOSING)
                        shouldSubmit = true
                    } else {
                        delegate = true
                    }
                }
                State.FAILED -> {
                    // A failed create normally rolled everything back. If it
                    // could not, retry the residual removal before succeeding.
                    if (residualLedger != null || linksView.isNotEmpty() || jointsView.isNotEmpty()) {
                        stateRef.set(State.CLOSING)
                        shouldSubmit = true
                    } else {
                        completeImmediately = true
                    }
                }
            }
        }
        if (completeImmediately) {
            // create() failed and rolled back its own partial assembly.
            future.complete(null)
            return future
        }
        if (!shouldSubmit) {
            delegateToLevel(future)
            return future
        }
        if (!level.isAcceptingTasks()) {
            markClosedAndClear()
            delegateToLevel(future)
            return future
        }
        level.submitGuarded("close ${spec.name}#$runId", PPhase.ALL) {
            if (!removeNative()) throw LevelOwnsNative()
        }.whenComplete { _, error ->
            when {
                error == null -> {
                    residualLedger = null
                    markClosedAndClear()
                    future.complete(null)
                }
                error is LevelOwnsNative || error is PhysicsLevelNotRunningException -> {
                    markClosedAndClear(error)
                    delegateToLevel(future)
                }
                else -> {
                    // Keep ownership of whatever could not be removed. The level
                    // still has it registered, so teardown can finish the job,
                    // but this future must not report success.
                    markClosedAndClear(error, clearAssembly = false)
                    future.completeExceptionally(error)
                }
            }
        }
        return future
    }

    override fun close() {
        closeAsync()
    }

    /**
     * Remove this run on the physics thread without submitting another task.
     *
     * A shutdown participant calls this after admission has stopped. Success
     * means the native objects were removed here, not that the level might
     * remove them later.
     */
    fun closeNow() {
        level.assertPhysicsThread()
        synchronized(lock) {
            if (stateRef.get() == State.CLOSED && linksView.isEmpty() && jointsView.isEmpty() && residualLedger == null) {
                closeFuture.get()?.let { if (!it.isDone) it.complete(null) }
                return
            }
            stateRef.set(State.CLOSING)
        }
        try {
            removeNative(force = true)
            residualLedger = null
            markClosedAndClear()
            closeFuture.get()?.let { if (!it.isDone) it.complete(null) }
        } catch (error: Throwable) {
            markClosedAndClear(error, clearAssembly = false)
            closeFuture.get()?.let { if (!it.isDone) it.completeExceptionally(error) }
            throw error
        }
    }

    /**
     * Remove this run's native objects. Returns false when the level has already
     * started shutdown and therefore owns teardown; in that case the caller
     * must wait for [PhysicsLevel.closeAsync] instead of reporting success.
     *
     * [force] is only for [closeNow], which is already on the physics thread
     * during shutdown and must not pretend the objects are gone.
     */
    private fun removeNative(force: Boolean = false): Boolean {
        if (!force && !level.isAcceptingTasks()) return false
        val joints = jointsView
        val links = linksView
        val failures = ArrayList<Throwable>()
        val residualJoints = ArrayList<New6Dof>()
        val residualBodies = ArrayList<PhysicsRigidBody>()
        joints.asReversed().forEach { joint ->
            try {
                level.removeJoint(joint)
            } catch (error: Throwable) {
                failures += error
                residualJoints += joint
            }
        }
        links.asReversed().forEach { body ->
            var removed = true
            try {
                body.owner = null
            } catch (error: Throwable) {
                failures += error
                removed = false
            }
            try {
                level.removeBody(body)
            } catch (error: Throwable) {
                failures += error
                removed = false
            }
            if (!removed) residualBodies += body
        }
        synchronized(lock) {
            // Only drop objects whose removal succeeded; anything still owned
            // must stay visible so the level teardown and diagnostics can see it.
            jointsView = java.util.List.copyOf(residualJoints)
            linksView = java.util.List.copyOf(residualBodies)
        }
        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach { primary.addSuppressed(it) }
            throw primary
        }
        return true
    }

    /**
     * Level shutdown/failure: drop references without touching native objects.
     * The level's unified teardown owns them. A pending close future is chained
     * to the level shutdown so it cannot complete before native cleanup.
     */
    private fun onLevelClosed() {
        residualLedger = null
        markClosedAndClear()
        val future = closeFuture.get()
        if (future != null && !future.isDone) {
            delegateToLevel(future)
        }
    }

    private fun delegateToLevel(future: CompletableFuture<Void>) {
        level.closeAsync().whenComplete { _, error ->
            if (error != null) future.completeExceptionally(error) else future.complete(null)
        }
    }

    private fun markClosedAndClear(error: Throwable? = null, clearAssembly: Boolean = true) {
        val cause = error ?: PhysicsLevelNotRunningException(
            level.name,
            level.state,
            "TrackRunRuntime '${spec.name}' closed before the queued task completed",
        )
        synchronized(lock) {
            if (stateRef.get() != State.FAILED) stateRef.set(State.CLOSED)
            // Do not complete createFuture here. If create already committed, the
            // create task's own callback reports success; if it never ran, the
            // gate fails that task and the callback reports the failure. Completing
            // it here could turn a successful assembly into a false failure.
        }
        postStepSubscription?.dispose()
        postStepSubscription = null
        if (clearAssembly) {
            closeSubscription?.dispose()
            closeSubscription = null
            linksView = emptyList()
            jointsView = emptyList()
            snapshotRef.set(TrackRunSnapshot.EMPTY)
        }
        // When native removal failed, keep the level-close listener registered:
        // the level still owns the residual objects and must be able to clear
        // this runtime's references during its unified teardown.
        pending.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    private fun rejected(reason: String): TrackRunInputReceipt.Future =
        TrackRunInputReceipt.Future(
            TrackRunInputReceipt.rejected(reason),
            CompletableFuture.completedFuture(null),
        )

    /**
     * Queue work for the physics thread and fail the future when the level is
     * no longer accepting tasks. A pending future is completed exceptionally by
     * [markClosedAndClear], so shutdown cannot leave a caller waiting forever.
     */
    private fun submitGuarded(action: String, block: () -> Unit): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        synchronized(lock) {
            if (stateRef.get() != State.ACTIVE) {
                return CompletableFuture.failedFuture(
                    IllegalStateException("TrackRunRuntime '${spec.name}' is not active"),
                )
            }
            pending += future
        }
        level.submitGuarded("$action ${spec.name}#$runId", PPhase.ALL) {
            // Closing may have won between admission and execution.
            if (stateRef.get() != State.ACTIVE) {
                throw PhysicsLevelNotRunningException(
                    level.name,
                    level.state,
                    "TrackRunRuntime '${spec.name}' closed before the queued task ran",
                )
            }
            block()
        }.whenComplete { _, error ->
            pending -= future
            if (error != null) {
                lastError = error.message ?: error::class.java.simpleName
                future.completeExceptionally(error)
            } else {
                future.complete(null)
            }
        }
        return future
    }

    private fun jmeQuaternion(value: org.joml.Quaternionf): com.jme3.math.Quaternion {
        val lengthSquared = value.x.toDouble() * value.x + value.y.toDouble() * value.y +
            value.z.toDouble() * value.z + value.w.toDouble() * value.w
        if (!lengthSquared.isFinite() || lengthSquared < 1.0e-8) {
            return com.jme3.math.Quaternion()
        }
        val inv = (1.0 / sqrt(lengthSquared)).toFloat()
        return com.jme3.math.Quaternion(value.x * inv, value.y * inv, value.z * inv, value.w * inv)
    }

    private class LevelOwnsNative : RuntimeException("level shutdown owns native teardown")

    private companion object {
        private val nextRunId = AtomicLong(0)
    }
}

/** Reject poses whose values would become infinity when narrowed to Bullet floats. */
private fun TrackRunLinkFrame.isRepresentableInBullet(): Boolean =
    position.isFiniteFloatVec() &&
        linearVelocity.isFiniteFloatVec() &&
        angularVelocity.isFiniteFloatVec()
