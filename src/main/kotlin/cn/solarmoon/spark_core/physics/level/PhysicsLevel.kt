package cn.solarmoon.spark_core.physics.level

import cn.solarmoon.spark_core.SparkCore
import cn.solarmoon.spark_core.event.PhysicsEntityTickEvent
import cn.solarmoon.spark_core.event.PhysicsLevelInitEvent
import cn.solarmoon.spark_core.event.PhysicsLevelTickEvent
import cn.solarmoon.spark_core.physics.SparkPhysics
import cn.solarmoon.spark_core.physics.body.*
import cn.solarmoon.spark_core.physics.terrain.PhysicsChunkManager
import cn.solarmoon.spark_core.physics.terrain.BlockShapeManager
import cn.solarmoon.spark_core.physics.query.TerrainRayHit
import cn.solarmoon.spark_core.physics.query.PhysicsThreadGuard
import cn.solarmoon.spark_core.physics.query.TerrainRayMath
import cn.solarmoon.spark_core.physics.query.TerrainRayQuery
import cn.solarmoon.spark_core.physics.toBullet
import cn.solarmoon.spark_core.util.PPhase
import cn.solarmoon.spark_core.util.Subscription
import cn.solarmoon.spark_core.util.TaskSubmitOffice
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.PhysicsTickListener
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.collision.PhysicsRayTestResult
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.joints.PhysicsJoint
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.MinecraftForge
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

open class PhysicsLevel(
    val name: String,
    open val mcLevel: Level
) : AutoCloseable, TaskSubmitOffice, PhysicsTickListener {
    companion object {
        const val TPS = 60
        private const val SUBSTEPS_PER_MC_TICK = 3
        /** Safety bound so a task that keeps enqueueing work cannot hang shutdown. */
        private const val MAX_DRAIN_ROUNDS = 64
        private const val CLOSE_TIMEOUT_SECONDS = 10L
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }
    private val stateRef = AtomicReference(PhysicsLevelState.NEW)
    /**
     * Single admission/close protocol. Keeping it in its own class lets the
     * race-sensitive parts be unit tested without a Minecraft level.
     */
    private val lifecycle = PhysicsLifecycleGate(object : PhysicsLifecycleHost {
        override val levelName: String get() = name
        override val lifecycleState: PhysicsLevelState get() = stateRef.get()
        override fun transitionTo(state: PhysicsLevelState) {
            stateRef.set(state)
        }

        override fun enqueue(phase: PPhase, task: () -> Unit) {
            immediateTasks.computeIfAbsent(phase) { ConcurrentLinkedQueue() }.add(task)
        }

        override fun enqueueDeduplicated(phase: PPhase, key: String, task: () -> Unit) {
            deduplicatedTasks.computeIfAbsent(phase) { ConcurrentHashMap() }[key] = task
        }
    })
    private val cleanupScheduled = AtomicBoolean(false)
    private val stepQueued = AtomicBoolean(false)
    private val restartCount = AtomicInteger(0)
    private val physicsTick = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    /** Changes when the native world is recreated; interpolation must not cross it. */
    private val worldEpoch = AtomicLong(0)
    private val trackedEntities = ConcurrentHashMap.newKeySet<Entity>()
    private val registeredBodies = ConcurrentHashMap.newKeySet<PhysicsCollisionObject>()
    private val registeredJoints = ConcurrentHashMap.newKeySet<PhysicsJoint>()
    private val immediateTasks = ConcurrentHashMap<PPhase, ConcurrentLinkedQueue<() -> Unit>>()
    private val deduplicatedTasks = ConcurrentHashMap<PPhase, ConcurrentHashMap<String, () -> Unit>>()
    /**
     * Run on the physics thread after every completed substep. Used by generic
     * runtimes that must publish a fresh snapshot per substep; exceptions are
     * logged and never abort the step.
     */
    private val postStepListeners = CopyOnWriteArrayList<() -> Unit>()
    /** Run once when this level starts shutting down or fails, before native teardown. */
    private val closeListeners = CopyOnWriteArrayList<() -> Unit>()
    private val frameRef = AtomicReference(PhysicsFrameSnapshot.EMPTY)
    /** Scratch state is confined to [physicsThread]. */
    private val terrainRayResults = ArrayList<PhysicsRayTestResult>(8)
    private val terrainRayFrom = Vector3f()
    private val terrainRayTo = Vector3f()
    private val terrainRayNormal = Vector3f()
    @Volatile private var physicsThread: Thread? = null

    lateinit var world: PhysicsWorld
        private set
    // The concrete ServerPhysicsLevel/ClientPhysicsLevel overrides mcLevel.  Do
    // not construct managers while the base constructor is still running:
    // Kotlin dispatches the open property to the not-yet-initialized override,
    // which would hand the managers a null Level during world startup.
    val terrainManager: PhysicsChunkManager by lazy(LazyThreadSafetyMode.NONE) { PhysicsChunkManager(this) }
    val blockShapeManager: BlockShapeManager by lazy(LazyThreadSafetyMode.NONE) { BlockShapeManager(this) }
    val state: PhysicsLevelState get() = stateRef.get()
    val latestFrame: PhysicsFrameSnapshot get() = frameRef.get()
    val tickCount: Long get() = physicsTick.get()
    val droppedFrameCount: Long get() = droppedFrames.get()

    fun start() {
        if (!lifecycle.beginStart()) return
        try {
            executor.submit {
                physicsThread = Thread.currentThread()
                // Shutdown may have begun while this initializer was queued.
                // The unified cleanup owns teardown in that case.
                if (!lifecycle.isAccepting) return@submit
                try {
                    world = PhysicsWorld(this)
                    if (!lifecycle.completeStart()) {
                        // A close/failure won the race after the world was
                        // constructed. Do not publish a successful init event;
                        // the queued cleanup will destroy the world.
                        return@submit
                    }
                    MinecraftForge.EVENT_BUS.post(PhysicsLevelInitEvent(this))
                    SparkCore.LOGGER.info("Started {}", name)
                } catch (error: Throwable) {
                    fail(error)
                }
            }
        } catch (error: RejectedExecutionException) {
            fail(error)
        }
    }

    fun trackEntity(entity: Entity) {
        trackedEntities.add(entity)
    }

    fun untrackEntity(entity: Entity) {
        trackedEntities.remove(entity)
    }

    fun isTracked(entity: Entity): Boolean = trackedEntities.contains(entity)

    /**
     * Return the closest Spark terrain hit for [query]. This API may only be
     * used from PRE/POST callbacks on the physics thread.
     *
     * [groups] selects which terrain collision groups count as a hit. The
     * default is the drivable terrain mask, which is exactly the Minecraft
     * block collision geometry.
     */
    fun raycastClosestTerrain(query: TerrainRayQuery): TerrainRayHit? =
        raycastClosestTerrain(query, CollisionGroups.DRIVABLE)

    fun raycastClosestTerrain(query: TerrainRayQuery, groups: Int): TerrainRayHit? {
        assertPhysicsThread()
        if (!::world.isInitialized || !query.isFinite()) return null

        terrainRayFrom.set(query.fromX, query.fromY, query.fromZ)
        terrainRayTo.set(query.toX, query.toY, query.toZ)
        terrainRayResults.clear()
        world.rayTest(terrainRayFrom, terrainRayTo, terrainRayResults)

        var hit: PhysicsRayTestResult? = null
        var closestFraction = Float.POSITIVE_INFINITY
        for (candidate in terrainRayResults) {
            if (TerrainRayMath.shouldReplaceClosest(
                    candidate.hitFraction,
                    candidate.collisionObject.collisionGroup,
                    groups,
                    closestFraction,
                )
            ) {
                hit = candidate
                closestFraction = candidate.hitFraction
            }
        }
        val closest = hit ?: return null

        val normal = closest.getHitNormalLocal(terrainRayNormal)
        if (!normal.x.isFinite() || !normal.y.isFinite() || !normal.z.isFinite() || normal.lengthSquared() < 1.0e-10f) {
            return null
        }
        val unitNormal = normal.normalize()
        val fraction = closest.hitFraction
        return TerrainRayHit(
            hitFraction = fraction,
            pointX = TerrainRayMath.interpolate(query.fromX, query.toX, fraction),
            pointY = TerrainRayMath.interpolate(query.fromY, query.toY, fraction),
            pointZ = TerrainRayMath.interpolate(query.fromZ, query.toZ, fraction),
            normalX = unitNormal.x,
            normalY = unitNormal.y,
            normalZ = unitNormal.z,
        )
    }

    internal fun worldOrNull(): PhysicsWorld? = if (::world.isInitialized) world else null

    fun removeAttachedBodies(entity: Entity) {
        submitImmediateTask {
            registeredBodies.filter { it.attachedEntity === entity }.toList().forEach(::removeBodyNow)
        }
    }

    protected open fun requestEntities(): List<Entity> = trackedEntities.toList()

    fun requestStep() {
        if (!isAcceptingTasks() || !::world.isInitialized) return
        terrainManager.tickMainThread(latestFrame.terrainActivationBoxes)
        val poses = requestEntities().associateWith { entity ->
            val box = entity.boundingBox
            EntityPose(
                box.center,
                Vector3f(
                    (box.xsize / 2).toFloat().coerceAtLeast(.01f),
                    (box.ysize / 2).toFloat().coerceAtLeast(.01f),
                    (box.zsize / 2).toFloat().coerceAtLeast(.01f)
                )
            )
        }
        if (!stepQueued.compareAndSet(false, true)) {
            droppedFrames.incrementAndGet()
            return
        }
        try {
            executor.submit {
                try {
                    performStep(poses)
                } catch (error: Throwable) {
                    recover(error)
                } finally {
                    stepQueued.set(false)
                }
            }
        } catch (error: RejectedExecutionException) {
            stepQueued.set(false)
            droppedFrames.incrementAndGet()
        }
    }

    private fun performStep(poses: Map<Entity, EntityPose>) {
        assertPhysicsThread()
        if (!isAcceptingTasks()) return
        val started = System.nanoTime()
        processTasks(PPhase.FRAME_PRE)
        processTasks(PPhase.ALL)
        // A task may have requested shutdown; never touch a torn-down world.
        if (!isAcceptingTasks()) return
        applyAttachedEntityPoses(poses)
        terrainManager.physicsTick(world)

        repeat(SUBSTEPS_PER_MC_TICK) {
            if (!isAcceptingTasks()) return
            physicsTick.incrementAndGet()
            world.update(1f / TPS, 0, false, true, false)
        }

        val bodies = LinkedHashMap<PhysicsCollisionObject, BodyFrame>()
        val byId = LinkedHashMap<Long, BodyFrame>(bodies.size.coerceAtLeast(16))
        val activation = ArrayList<net.minecraft.world.phys.AABB>()
        world.pcoList.forEach { body ->
            val frame = body.captureFrame()
            bodies[body] = frame
            byId[frame.bodyId] = frame
            val requestsTerrain = body.collideWithGroups and CollisionGroups.DRIVABLE != 0 ||
                body.collisionGroup and CollisionGroups.CHASSIS != 0
            if ((!body.isStatic || (body is PhysicsRigidBody && body.isKinematic)) && requestsTerrain) {
                activation.add(frame.boundingBox.expandTowards(frame.linearVelocity.scale(0.5)))
            }
        }
        val completed = System.nanoTime()
        frameRef.set(
            PhysicsFrameSnapshot(
                physicsTick.get(),
                bodies,
                activation,
                completed - started,
                completed,
                byId,
                worldEpoch.get(),
            )
        )
    }

    private fun applyAttachedEntityPoses(poses: Map<Entity, EntityPose>) {
        registeredBodies.forEach { body ->
            val attached = body.attachedEntity ?: return@forEach
            val pose = poses[attached] ?: return@forEach
            body.setSparkLocation(pose.center.toBullet())
            val rigid = body as? PhysicsRigidBody ?: return@forEach
            val box = rigid.collisionShape as? BoxCollisionShape ?: return@forEach
            val current = box.getHalfExtents(null)
            if (abs(current.x - pose.halfExtents.x) > .01f ||
                abs(current.y - pose.halfExtents.y) > .01f ||
                abs(current.z - pose.halfExtents.z) > .01f
            ) {
                rigid.collisionShape = BoxCollisionShape(pose.halfExtents)
            }
        }
    }

    /**
     * Register a body with this level.
     *
     * On the physics thread the call is synchronous and a native failure is
     * thrown to the caller. Off the physics thread it is queued through the
     * admission gate; the returned future fails instead of silently dropping
     * the registration during shutdown. Callers that do not need the future may
     * ignore it.
     */
    fun addBody(body: PhysicsCollisionObject): CompletableFuture<Void> {
        if (Thread.currentThread() === physicsThread && ::world.isInitialized) {
            addBodyNow(body)
            return CompletableFuture.completedFuture(null)
        }
        return submitGuarded("add body", PPhase.ALL) { addBodyNow(body) }
    }

    /** See [addBody] for threading and completion semantics. */
    fun removeBody(body: PhysicsCollisionObject): CompletableFuture<Void> {
        if (Thread.currentThread() === physicsThread && ::world.isInitialized) {
            removeBodyNow(body)
            return CompletableFuture.completedFuture(null)
        }
        return submitGuarded("remove body", PPhase.ALL) { removeBodyNow(body) }
    }

    /** See [addBody] for threading and completion semantics. */
    fun addJoint(joint: PhysicsJoint): CompletableFuture<Void> {
        if (Thread.currentThread() === physicsThread && ::world.isInitialized) {
            addJointNow(joint)
            return CompletableFuture.completedFuture(null)
        }
        return submitGuarded("add joint", PPhase.ALL) { addJointNow(joint) }
    }

    /** See [addBody] for threading and completion semantics. */
    fun removeJoint(joint: PhysicsJoint): CompletableFuture<Void> {
        if (Thread.currentThread() === physicsThread && ::world.isInitialized) {
            removeJointNow(joint)
            return CompletableFuture.completedFuture(null)
        }
        return submitGuarded("remove joint", PPhase.ALL) { removeJointNow(joint) }
    }

    private fun addBodyNow(body: PhysicsCollisionObject) {
        assertPhysicsThread()
        if (registeredBodies.add(body) && !body.isInWorld) world.addCollisionObject(body)
    }

    private fun removeBodyNow(body: PhysicsCollisionObject) {
        assertPhysicsThread()
        // Remove from the native world first. If that throws, keep the
        // registration so teardown can retry instead of forgetting the object.
        if (body.isInWorld) world.removeCollisionObject(body)
        registeredBodies.remove(body)
        body.owner = null
        body.clearSparkRuntime()
    }

    private fun addJointNow(joint: PhysicsJoint) {
        assertPhysicsThread()
        if (registeredJoints.add(joint) && !world.contains(joint)) world.addJoint(joint)
    }

    private fun removeJointNow(joint: PhysicsJoint) {
        assertPhysicsThread()
        if (world.contains(joint)) world.removeJoint(joint)
        joint.destroy()
        registeredJoints.remove(joint)
    }

    fun submitImmediateTask(task: () -> Unit) = submitImmediateTask(PPhase.ALL, task)

    override fun submitImmediateTask(phase: PPhase, task: () -> Unit) {
        if (!lifecycle.enqueue(phase, task)) {
            SparkCore.LOGGER.debug("{} dropped a task submitted while {}", name, state)
        }
    }

    fun submitDeduplicatedTask(key: String, task: () -> Unit) = submitDeduplicatedTask(key, PPhase.ALL, task)

    override fun submitDeduplicatedTask(key: String, phase: PPhase, task: () -> Unit) {
        if (!lifecycle.enqueueDeduplicated(phase, key, task)) {
            SparkCore.LOGGER.debug("{} dropped deduplicated task '{}' while {}", name, key, state)
        }
    }

    /**
     * Public, Java-friendly guarded submission.
     *
     * This is the only entry point that both queues work and guarantees a
     * returned future reaches a terminal state. It must be used instead of a
     * state check followed by [submitImmediateTask], which can silently drop
     * the work during a close race.
     */
    fun <T> submitTask(description: String, phase: PPhase, task: () -> T): CompletableFuture<T> =
        lifecycle.submitTask(description, phase, task)

    fun <T> submitTask(description: String, task: () -> T): CompletableFuture<T> =
        submitTask(description, PPhase.ALL, task)

    /**
     * Submit a task that must run on the physics thread and report its own
     * outcome.
     *
     * Admission is atomic with shutdown: either the task is registered and will
     * run (or fail) during teardown, or the future fails immediately with
     * [PhysicsLevelNotRunningException]. A returned future never stays pending
     * because the level stopped.
     */
    internal fun submitGuarded(
        description: String,
        phase: PPhase = PPhase.ALL,
        task: () -> Unit,
    ): CompletableFuture<Void> = lifecycle.submitGuarded(description, phase) {
        try {
            task()
        } catch (error: PhysicsLevelNotRunningException) {
            // A queued operation rejected because shutdown won is an expected
            // race, not a failure of the physics step. The future still carries
            // the exception to its caller.
            SparkCore.LOGGER.debug("{} guarded task '{}' rejected during shutdown", name, description)
            throw error
        } catch (error: Throwable) {
            SparkCore.LOGGER.error("{} guarded task '{}' failed", name, description, error)
            throw error
        }
    }

    private fun processTasks(phase: PPhase) {
        // A failing legacy task must not take the rest of its phase with it.
        // Guarded tasks already complete their own future; this catch is for
        // fire-and-forget work.
        deduplicatedTasks.remove(phase)?.forEach { (key, task) ->
            try {
                task()
            } catch (error: Throwable) {
                SparkCore.LOGGER.error("{} deduplicated task '{}' failed", name, key, error)
            }
        }
        val queue = immediateTasks[phase] ?: return
        while (true) {
            val task = queue.poll() ?: break
            try {
                task()
            } catch (error: Throwable) {
                SparkCore.LOGGER.error("{} immediate task failed in phase {}", name, phase, error)
            }
        }
    }

    /**
     * Drain every queue, including tasks that enqueue more work while running.
     * New submissions are already rejected, so this terminates.
     */
    private fun drainTasks() {
        var rounds = 0
        while (rounds++ < MAX_DRAIN_ROUNDS) {
            val before = immediateTasks.values.sumOf { it.size } + deduplicatedTasks.values.sumOf { it.size }
            for (phase in PPhase.values()) {
                try {
                    processTasks(phase)
                } catch (error: Throwable) {
                    SparkCore.LOGGER.error("{} task failed during shutdown", name, error)
                }
            }
            val after = immediateTasks.values.sumOf { it.size } + deduplicatedTasks.values.sumOf { it.size }
            if (before == 0 && after == 0) return
            if (after == 0) return
        }
        SparkCore.LOGGER.warn("{} stopped draining tasks after {} rounds", name, MAX_DRAIN_ROUNDS)
    }

    override fun prePhysicsTick(space: PhysicsSpace, timeStep: Float) {
        world.pcoList.forEach { body ->
            body.isColliding = false
            body.triggerBodyEvent(PhysicsBodyEvent.Tick)
            body.triggerBodyEvent(PhysicsBodyEvent.PhysicsTick.Pre)
        }
        processTasks(PPhase.ALL)
        processTasks(PPhase.PRE)
        MinecraftForge.EVENT_BUS.post(PhysicsLevelTickEvent.Pre(this))
        requestEntities().forEach { MinecraftForge.EVENT_BUS.post(PhysicsEntityTickEvent(it)) }
    }

    override fun physicsTick(space: PhysicsSpace, timeStep: Float) {
        world.dispatchResolvedContacts()
        world.pcoList.forEach { it.triggerBodyEvent(PhysicsBodyEvent.PhysicsTick.Post) }
        processTasks(PPhase.ALL)
        processTasks(PPhase.POST)
        MinecraftForge.EVENT_BUS.post(PhysicsLevelTickEvent.Post(this))
        postStepListeners.forEach { listener ->
            try {
                listener()
            } catch (error: Throwable) {
                SparkCore.LOGGER.error("{} post-step listener failed", name, error)
            }
        }
    }

    private fun recover(error: Throwable) {
        SparkCore.LOGGER.error("{} physics step failed", name, error)
        if (restartCount.incrementAndGet() >= 3) {
            fail(error)
            return
        }
        if (!isAcceptingTasks()) {
            fail(error)
            return
        }
        try {
            if (::world.isInitialized) world.destroy()
            worldEpoch.incrementAndGet()
            world = PhysicsWorld(this)
            registeredBodies.forEach { if (!it.isInWorld) world.addCollisionObject(it) }
            registeredJoints.forEach { if (!world.contains(it)) world.addJoint(it) }
            terrainManager.rebind(world)
            SparkCore.LOGGER.warn("Recovered {} physics world, attempt {}/3", name, restartCount.get())
        } catch (recoveryError: Throwable) {
            error.addSuppressed(recoveryError)
            fail(error)
        }
    }

    private fun fail(error: Throwable) {
        SparkCore.LOGGER.error("Disabled {} after unrecoverable physics error", name, error)
        val (future, isFirst) = lifecycle.beginShutdown(PhysicsLevelState.FAILED, error)
        if (isFirst) scheduleCleanup(future)
    }

    /**
     * True while the worker accepts and steps native mutation tasks.
     *
     * NEW/STARTING are accepted and queued: entities can join before the world
     * exists. Shutdown is one-way, so this never becomes true again after a
     * close or failure transition.
     */
    private val acceptingTasks: Boolean
        get() = lifecycle.isAccepting

    /** Public mirror of [acceptingTasks] for callers that gate their own work. */
    fun isAcceptingTasks(): Boolean = lifecycle.isAccepting

    fun assertPhysicsThread() {
        PhysicsThreadGuard.assertCurrent(physicsThread, name)
    }

    /** True when the caller is the physics worker that owns this level. */
    fun isPhysicsThread(): Boolean = Thread.currentThread() === physicsThread

    /** True while the worker is accepting and stepping tasks. */
    val isRunning: Boolean get() = state == PhysicsLevelState.RUNNING

    /**
     * Register work that runs on the physics thread after each completed
     * substep. The listener must not block or touch mutable Minecraft state.
     */
    internal fun addPostStepListener(listener: () -> Unit): Subscription {
        postStepListeners.add(listener)
        return Subscription { postStepListeners.remove(listener) }
    }

    /**
     * Register one-shot work for level shutdown or unrecoverable failure. It
     * runs before native bodies and joints are destroyed, so listeners must
     * only release references and complete pending futures.
     *
     * Registration is atomic with the shutdown transition: a listener added
     * after shutdown started is invoked immediately instead of being stored, so
     * a runtime constructed during teardown still observes the close.
     */
    internal fun addCloseListener(listener: () -> Unit): Subscription = lifecycle.addCloseListener(listener)

    private fun notifyCloseListeners() = lifecycle.notifyCloseListeners()

    /**
     * Begin shutdown and return a future that completes after every admitted
     * task has finished and native objects are gone.
     *
     * Idempotent: repeated calls return the same future.
     */
    fun closeAsync(): CompletableFuture<Void> {
        val (future, isFirst) = lifecycle.beginShutdown(PhysicsLevelState.STOPPING, null)
        if (isFirst) scheduleCleanup(future)
        return future
    }

    private fun scheduleCleanup(future: CompletableFuture<Void>) {
        if (cleanupScheduled.compareAndSet(false, true)) {
            try {
                executor.submit { runShutdownCleanup(future) }
            } catch (error: RejectedExecutionException) {
                // The worker is already gone; teardown must still finish.
                runShutdownCleanup(future)
            }
        }
    }

    /**
     * Single teardown path for normal close, failure and rejected submissions.
     * Runs on the physics worker when possible because every native mutation
     * below must be thread-confined.
     */
    private fun runShutdownCleanup(future: CompletableFuture<Void>) {
        var cleanupError: Throwable? = null
        try {
            val worker = physicsThread
            // Before the worker exists there is no native state to protect, so
            // cleanup may run on the caller's thread.
            if (worker != null && Thread.currentThread() !== worker) {
                cleanupError = IllegalStateException(
                    "PhysicsLevel '$name' cleanup ran off the physics thread",
                )
            } else {
                notifyCloseListeners()
                if (::world.isInitialized) {
                    // Queued tasks may touch native objects, so they only run
                    // when the world actually exists. A level that failed
                    // before creating its world must fail them instead.
                    drainTasks()
                } else {
                    immediateTasks.clear()
                    deduplicatedTasks.clear()
                }
                lifecycle.failRemainingTasks()
                teardownNative()
            }
        } catch (error: Throwable) {
            cleanupError = error
            SparkCore.LOGGER.error("{} shutdown cleanup failed", name, error)
        } finally {
            postStepListeners.clear()
            immediateTasks.clear()
            deduplicatedTasks.clear()
            if (cleanupError == null && lifecycle.terminalState != PhysicsLevelState.FAILED) {
                // Preserve FAILED so callers can still observe the cause;
                // a normal close ends in STOPPED.
                stateRef.set(PhysicsLevelState.STOPPED)
            }
            lifecycle.completeShutdown(cleanupError)
            executor.shutdown()
        }
    }

    /**
     * Best-effort native teardown with aggregated failures.
     *
     * Every independent item is attempted even when an earlier one throws, and
     * the first failure is rethrown with the rest attached as suppressed
     * exceptions. A shutdown that could not destroy native state must not be
     * reported as a successful STOPPED close.
     */
    private fun teardownNative() {
        val failures = ArrayList<Throwable>()

        fun attempt(label: String, block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                SparkCore.LOGGER.error("{} {} teardown failed", name, label, error)
                failures += error
            }
        }

        val currentWorld = if (::world.isInitialized) world else null
        if (currentWorld != null) {
            attempt("terrain") { terrainManager.destroy(currentWorld) }
        }
        registeredJoints.toList().forEach { joint ->
            attempt("joint") {
                if (currentWorld != null && currentWorld.contains(joint)) currentWorld.removeJoint(joint)
                joint.destroy()
            }
        }
        registeredJoints.clear()
        registeredBodies.toList().forEach { body ->
            attempt("body") {
                if (currentWorld != null && body.isInWorld) currentWorld.removeCollisionObject(body)
            }
            attempt("body metadata") {
                body.owner = null
                body.clearSparkRuntime()
            }
        }
        registeredBodies.clear()
        if (currentWorld != null) {
            attempt("world") { currentWorld.destroy() }
        }
        attempt("block shape") { blockShapeManager.clear() }

        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach { primary.addSuppressed(it) }
            throw primary
        }
    }

    override fun close() {
        val future = closeAsync()
        if (Thread.currentThread() === physicsThread) {
            // Waiting here would deadlock the worker that must run the cleanup.
            return
        }
        try {
            future.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            // Do not cancel the only queued cleanup. Report the unfinished
            // close and leave the worker to finish; callers can still observe
            // the future.
            SparkCore.LOGGER.error("Timed out waiting for {} to close; cleanup is still queued", name, timeout)
        } catch (error: Exception) {
            SparkCore.LOGGER.error("Error while closing {}", name, error)
        } finally {
            // shutdown() stops new submissions but lets the queued cleanup run.
            // shutdownNow() could remove the one task that releases native state.
            executor.shutdown()
        }
    }

    private data class EntityPose(val center: Vec3, val halfExtents: Vector3f)
}
