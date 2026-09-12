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
import java.util.Collections
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
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }
    private val stateRef = AtomicReference(PhysicsLevelState.NEW)
    private val stepQueued = AtomicBoolean(false)
    private val restartCount = AtomicInteger(0)
    private val physicsTick = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val trackedEntities = ConcurrentHashMap.newKeySet<Entity>()
    private val registeredBodies = ConcurrentHashMap.newKeySet<PhysicsCollisionObject>()
    private val registeredJoints = ConcurrentHashMap.newKeySet<PhysicsJoint>()
    private val immediateTasks = ConcurrentHashMap<PPhase, ConcurrentLinkedQueue<() -> Unit>>()
    private val deduplicatedTasks = ConcurrentHashMap<PPhase, ConcurrentHashMap<String, () -> Unit>>()
    private val frameRef = AtomicReference(PhysicsFrameSnapshot.EMPTY)
    /** Scratch state is confined to [physicsThread]. */
    private val terrainRayResults = ArrayList<PhysicsRayTestResult>(8)
    private val terrainRayFrom = Vector3f()
    private val terrainRayTo = Vector3f()
    private val terrainRayNormal = Vector3f()
    @Volatile private var physicsThread: Thread? = null

    lateinit var world: PhysicsWorld
        private set
    val terrainManager = PhysicsChunkManager(this)
    val blockShapeManager = BlockShapeManager(this)
    val state: PhysicsLevelState get() = stateRef.get()
    val latestFrame: PhysicsFrameSnapshot get() = frameRef.get()
    val tickCount: Long get() = physicsTick.get()
    val droppedFrameCount: Long get() = droppedFrames.get()

    fun start() {
        if (!stateRef.compareAndSet(PhysicsLevelState.NEW, PhysicsLevelState.STARTING)) return
        executor.submit {
            physicsThread = Thread.currentThread()
            if (stateRef.get() != PhysicsLevelState.STARTING) return@submit
            try {
                world = PhysicsWorld(this)
                stateRef.set(PhysicsLevelState.RUNNING)
                MinecraftForge.EVENT_BUS.post(PhysicsLevelInitEvent(this))
                SparkCore.LOGGER.info("Started {}", name)
            } catch (error: Throwable) {
                fail(error)
            }
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
     */
    fun raycastClosestTerrain(query: TerrainRayQuery): TerrainRayHit? {
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
                    CollisionGroups.TERRAIN,
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
        if (state != PhysicsLevelState.RUNNING || !::world.isInitialized) return
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
        executor.submit {
            try {
                performStep(poses)
            } catch (error: Throwable) {
                recover(error)
            } finally {
                stepQueued.set(false)
            }
        }
    }

    private fun performStep(poses: Map<Entity, EntityPose>) {
        assertPhysicsThread()
        val started = System.nanoTime()
        processTasks(PPhase.FRAME_PRE)
        processTasks(PPhase.ALL)
        applyAttachedEntityPoses(poses)
        terrainManager.physicsTick(world)

        repeat(SUBSTEPS_PER_MC_TICK) {
            physicsTick.incrementAndGet()
            world.update(1f / TPS, 0, false, true, false)
        }

        val bodies = LinkedHashMap<PhysicsCollisionObject, BodyFrame>()
        val activation = ArrayList<net.minecraft.world.phys.AABB>()
        world.pcoList.forEach { body ->
            val frame = body.captureFrame()
            bodies[body] = frame
            if (!body.isStatic && body.collideWithGroups and CollisionGroups.TERRAIN != 0) {
                activation.add(frame.boundingBox)
            }
        }
        val completed = System.nanoTime()
        frameRef.set(
            PhysicsFrameSnapshot(
                physicsTick.get(),
                Collections.unmodifiableMap(LinkedHashMap(bodies)),
                Collections.unmodifiableList(ArrayList(activation)),
                completed - started,
                completed
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

    fun addBody(body: PhysicsCollisionObject) {
        if (Thread.currentThread() === physicsThread && ::world.isInitialized) addBodyNow(body)
        else submitImmediateTask { addBodyNow(body) }
    }

    fun removeBody(body: PhysicsCollisionObject) {
        if (Thread.currentThread() === physicsThread && ::world.isInitialized) removeBodyNow(body)
        else submitImmediateTask { removeBodyNow(body) }
    }

    fun addJoint(joint: PhysicsJoint) {
        if (Thread.currentThread() === physicsThread && ::world.isInitialized) addJointNow(joint)
        else submitImmediateTask { addJointNow(joint) }
    }

    fun removeJoint(joint: PhysicsJoint) {
        if (Thread.currentThread() === physicsThread && ::world.isInitialized) removeJointNow(joint)
        else submitImmediateTask { removeJointNow(joint) }
    }

    private fun addBodyNow(body: PhysicsCollisionObject) {
        assertPhysicsThread()
        if (registeredBodies.add(body) && !body.isInWorld) world.addCollisionObject(body)
    }

    private fun removeBodyNow(body: PhysicsCollisionObject) {
        assertPhysicsThread()
        registeredBodies.remove(body)
        if (body.isInWorld) world.removeCollisionObject(body)
        body.owner = null
        body.clearSparkRuntime()
    }

    private fun addJointNow(joint: PhysicsJoint) {
        assertPhysicsThread()
        if (registeredJoints.add(joint) && !world.contains(joint)) world.addJoint(joint)
    }

    private fun removeJointNow(joint: PhysicsJoint) {
        assertPhysicsThread()
        registeredJoints.remove(joint)
        if (world.contains(joint)) world.removeJoint(joint)
        joint.destroy()
    }

    fun submitImmediateTask(task: () -> Unit) = submitImmediateTask(PPhase.ALL, task)

    override fun submitImmediateTask(phase: PPhase, task: () -> Unit) {
        immediateTasks.computeIfAbsent(phase) { ConcurrentLinkedQueue() }.add(task)
    }

    fun submitDeduplicatedTask(key: String, task: () -> Unit) = submitDeduplicatedTask(key, PPhase.ALL, task)

    override fun submitDeduplicatedTask(key: String, phase: PPhase, task: () -> Unit) {
        deduplicatedTasks.computeIfAbsent(phase) { ConcurrentHashMap() }[key] = task
    }

    private fun processTasks(phase: PPhase) {
        deduplicatedTasks.remove(phase)?.values?.forEach { it() }
        val queue = immediateTasks[phase] ?: return
        while (true) (queue.poll() ?: break).invoke()
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
        world.pcoList.forEach { it.triggerBodyEvent(PhysicsBodyEvent.PhysicsTick.Post) }
        processTasks(PPhase.ALL)
        processTasks(PPhase.POST)
        MinecraftForge.EVENT_BUS.post(PhysicsLevelTickEvent.Post(this))
    }

    private fun recover(error: Throwable) {
        SparkCore.LOGGER.error("{} physics step failed", name, error)
        if (restartCount.incrementAndGet() >= 3) {
            fail(error)
            return
        }
        try {
            if (::world.isInitialized) world.destroy()
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
        stateRef.set(PhysicsLevelState.FAILED)
        SparkCore.LOGGER.error("Disabled {} after unrecoverable physics error", name, error)
    }

    fun assertPhysicsThread() {
        PhysicsThreadGuard.assertCurrent(physicsThread, name)
    }

    override fun close() {
        val previous = stateRef.getAndSet(PhysicsLevelState.STOPPING)
        if (previous == PhysicsLevelState.STOPPED || previous == PhysicsLevelState.STOPPING) return
        val cleanup = Runnable {
            processTasks(PPhase.FRAME_PRE)
            processTasks(PPhase.ALL)
            processTasks(PPhase.PRE)
            processTasks(PPhase.POST)
            immediateTasks.clear()
            deduplicatedTasks.clear()
            val currentWorld = if (::world.isInitialized) world else null
            terrainManager.destroy(currentWorld)
            registeredJoints.forEach { joint ->
                if (currentWorld != null && currentWorld.contains(joint)) currentWorld.removeJoint(joint)
                joint.destroy()
            }
            registeredJoints.clear()
            registeredBodies.forEach { body ->
                body.owner = null
                body.clearSparkRuntime()
            }
            registeredBodies.clear()
            if (::world.isInitialized) world.destroy()
            blockShapeManager.clear()
            stateRef.set(PhysicsLevelState.STOPPED)
        }
        if (Thread.currentThread() === physicsThread) {
            cleanup.run()
            executor.shutdown()
            return
        }
        try {
            executor.submit(cleanup).get(10, TimeUnit.SECONDS)
        } catch (error: Exception) {
            SparkCore.LOGGER.error("Timed out while closing {}", name, error)
            executor.shutdownNow()
            stateRef.set(PhysicsLevelState.STOPPED)
        } finally {
            executor.shutdown()
        }
    }

    private data class EntityPose(val center: Vec3, val halfExtents: Vector3f)
}
