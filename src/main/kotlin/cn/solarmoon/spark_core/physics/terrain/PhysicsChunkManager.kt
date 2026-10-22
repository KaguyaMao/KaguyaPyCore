package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.SparkCore
import cn.solarmoon.spark_core.network.SparkNetwork
import cn.solarmoon.spark_core.network.TerrainUpdateMessage
import cn.solarmoon.spark_core.physics.body.CollisionGroups
import cn.solarmoon.spark_core.physics.body.clearSparkRuntime
import cn.solarmoon.spark_core.physics.body.name
import cn.solarmoon.spark_core.physics.body.owner
import cn.solarmoon.spark_core.physics.level.PhysicsLevel
import cn.solarmoon.spark_core.physics.level.PhysicsWorld
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraftforge.network.PacketDistributor
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.floor

class PhysicsChunkManager(private val physicsLevel: PhysicsLevel) {
    companion object {
        // Terrain physics is a local cache around dynamic bodies. One section
        // of horizontal padding covers the configured activation radius.
        const val BUILD_RADIUS_CHUNKS = 1
        const val CACHE_RETENTION_PADDING_SECTIONS = 1
        const val VERTICAL_BUILD_PADDING_SECTIONS = 1
        const val ACTIVATION_PADDING_BLOCKS = 2.0
        const val MAX_SNAPSHOTS_PER_TICK = 8
        /** Match the mesh-free box skin; the default .04 m rounds section seams. */
        const val TERRAIN_BOX_MARGIN = 0.001f
    }

    private val chunks = ConcurrentHashMap<ChunkPos, PhysicsChunk>()
    private val sections = ConcurrentHashMap<SectionPos, PhysicsChunkSection>()
    private val dirtySections = ConcurrentHashMap.newKeySet<SectionPos>()
    private val networkDirtySections = ConcurrentHashMap.newKeySet<SectionPos>()
    private val readyPlans = ConcurrentLinkedQueue<TerrainShapePlan>()
    private val desiredActive = AtomicReference<Set<SectionPos>>(emptySet())
    private val stalePlans = java.util.concurrent.atomic.AtomicLong()
    private val rebuilds = java.util.concurrent.atomic.AtomicLong()
    private val buildNanos = java.util.concurrent.atomic.AtomicLong()
    private val cacheRequests = java.util.concurrent.atomic.AtomicLong()
    private val cacheHits = java.util.concurrent.atomic.AtomicLong()
    private val unchangedCollisions = java.util.concurrent.atomic.AtomicLong()
    private var peakBoxBodies = 0
    private var peakActiveBoxBodies = 0
    private var remainingUpdateTraces = Integer.getInteger("spark.physics.terrainTraceUpdates", 0).coerceIn(0, 2000)
    private var remainingRebuildTraces =
        Integer.getInteger("spark.physics.terrainTraceRebuilds", 0).coerceIn(0, 40000)
    private var remainingChangeTraces =
        Integer.getInteger("spark.physics.terrainTraceChanges", 0).coerceIn(0, 40000)

    /**
     * Optional `minX,minZ,maxX,maxZ` trace filter. Only terrain activity that
     * touches this block box is logged, so a diagnostic run can watch one test
     * area without burning the whole budget on world-load and fixture churn.
     */
    private val traceArea: IntArray? = System.getProperty("spark.physics.terrainTraceArea")
        ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 4 }?.toIntArray()

    private fun traceAllows(pos: BlockPos): Boolean {
        val area = traceArea ?: return true
        return pos.x >= area[0] && pos.x <= area[2] && pos.z >= area[1] && pos.z <= area[3]
    }

    private fun traceAllows(section: SectionPos): Boolean {
        val area = traceArea ?: return true
        val minX = section.minBlockX()
        val minZ = section.minBlockZ()
        return minX + 15 >= area[0] && minX <= area[2] && minZ + 15 >= area[1] && minZ <= area[3]
    }

    /** Physics-thread readiness test, including empty (but successfully built) sections. */
    fun isReady(box: AABB): Boolean = readinessIssue(box) == null

    /**
     * Detached, diagnostic-only description. A finite box includes every section
     * from [TerrainSectionRange] , so an edge exactly on a section origin waits
     * for that next section too. Called on the physics worker or the server
     * thread; it only reads concurrent maps and volatile revision fields.
     */
    fun readinessIssue(box: AABB): String? {
        forEachCoveredSection(box) { key ->
            val section = sections[key] ?: return "missing:$key"
            if (!section.active) return "inactive:$key"
            if (section.appliedRevision != section.revision.get())
                return "revision:$key applied=${section.appliedRevision} requested=${section.revision.get()}"
            if (key in dirtySections) return "dirty:$key"
        }
        return null
    }

    private inline fun forEachCoveredSection(box: AABB, action: (SectionPos) -> Unit) {
        val minSection = physicsLevel.mcLevel.minSection
        val maxSection = physicsLevel.mcLevel.maxSection
        for (x in TerrainSectionRange.indices(box.minX, box.maxX)) {
            for (z in TerrainSectionRange.indices(box.minZ, box.maxZ)) {
                for (y in TerrainSectionRange.indices(box.minY, box.maxY)) {
                    if (y < minSection || y >= maxSection) continue
                    action(SectionPos.of(x, y, z))
                }
            }
        }
    }

    private val builderExecutor: ExecutorService = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 2)
    ) { runnable -> Thread(runnable, "TerrainShapeBuilder-${physicsLevel.name}").apply { isDaemon = true } }

    fun onChunkLoaded(chunk: LevelChunk) {
        chunks.computeIfAbsent(chunk.pos) { PhysicsChunk(chunk.pos, physicsLevel, chunk) }
        dirtyMarginNeighbours(chunk.pos)
    }

    fun onChunkUnloaded(pos: ChunkPos) {
        val chunk = chunks.remove(pos) ?: return
        unloadSections(chunk)
        dirtyMarginNeighbours(pos)
    }

    fun unloadPhysicsChunk(pos: ChunkPos) {
        val chunk = chunks[pos] ?: return
        unloadSections(chunk)
    }

    private fun unloadSections(chunk: PhysicsChunk) {
        chunk.sections.values.forEach { section ->
            sections.remove(section.sectionPos)
            retireLater(section)
        }
    }

    /**
     * A chunk load/unload can change contextual collision shapes on the section
     * border of its neighbours. Only cached sections can react immediately; the
     * rest are rebuilt from scratch when they enter the cache.
     */
    private fun dirtyMarginNeighbours(pos: ChunkPos) {
        val changed = sections.keys.filter {
            (it.x() != pos.x || it.z() != pos.z) &&
                abs(it.x() - pos.x) <= 1 && abs(it.z() - pos.z) <= 1
        }
        if (changed.isNotEmpty()) markDirty(changed.toSet())
    }

    fun onBlockChanged(pos: BlockPos, oldState: BlockState, newState: BlockState) {
        val traced = remainingUpdateTraces > 0 && sections.containsKey(SectionPos.of(pos)) && traceAllows(pos)
        if (traced) {
            remainingUpdateTraces--
            SparkCore.LOGGER.info("[terrain-update] {} pos={} old={} new={}", physicsLevel.name, pos, oldState, newState)
        }
        // A state change is not necessarily a collision change (leaf distance,
        // grass spread, etc.). Compare the detached geometry of just this block
        // before invalidating anything: identical boxes cannot change any section.
        val previous = collisionBoxes(physicsLevel.mcLevel, pos, oldState)
        val current = collisionBoxes(physicsLevel.mcLevel, pos, newState)
        if (previous == current) {
            unchangedCollisions.incrementAndGet()
            return
        }
        if (remainingChangeTraces > 0 && traceAllows(pos) && current.isEmpty() && previous.isNotEmpty()) {
            remainingChangeTraces--
            SparkCore.LOGGER.info(
                "[terrain-change] {} pos={} old={} new={} boxes={}->{}",
                physicsLevel.name, pos, oldState, newState, previous.size, current.size
            )
        }
        markDirty(affectedSections(pos))
    }

    private fun collisionBoxes(level: Level, pos: BlockPos, state: BlockState): List<AABB> {
        if (state.isAir) return emptyList()
        val voxel = state.getCollisionShape(level, pos)
        if (voxel.isEmpty) return emptyList()
        return voxel.toAabbs()
    }

    private fun affectedSections(pos: BlockPos): Set<SectionPos> {
        // Contextual collision shapes connect across section borders, so a change
        // on a border also invalidates the adjacent sections that can see it.
        // SectionPos.of(int, int, int) takes section coordinates, so block
        // coordinates must be shifted first.
        val affected = HashSet<SectionPos>()
        val sectionX = pos.x shr 4
        val sectionY = pos.y shr 4
        val sectionZ = pos.z shr 4
        val localX = pos.x and 15
        val localY = pos.y and 15
        val localZ = pos.z and 15
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            if (dx < 0 && localX != 0) continue
            if (dx > 0 && localX != 15) continue
            if (dy < 0 && localY != 0) continue
            if (dy > 0 && localY != 15) continue
            if (dz < 0 && localZ != 0) continue
            if (dz > 0 && localZ != 15) continue
            affected.add(SectionPos.of(sectionX + dx, sectionY + dy, sectionZ + dz))
        }
        return affected
    }

    fun markDirty(pos: BlockPos) {
        markDirty(affectedSections(pos))
    }

    fun markDirty(positions: Set<SectionPos>) {
        dirtySections.addAll(positions)
        if (!physicsLevel.mcLevel.isClientSide) networkDirtySections.addAll(positions)
    }

    fun tickMainThread(activationBoxes: List<AABB>) {
        val activation = activationBoxes
        val sectionsToBuild = HashSet<SectionPos>()
        val sectionsToRetain = HashSet<SectionPos>()
        activation.forEach { box ->
            val minX = SectionPos.blockToSectionCoord(floor(box.minX).toInt()) - BUILD_RADIUS_CHUNKS
            val maxX = SectionPos.blockToSectionCoord(floor(box.maxX).toInt()) + BUILD_RADIUS_CHUNKS
            val minY = SectionPos.blockToSectionCoord(floor(box.minY).toInt()) - VERTICAL_BUILD_PADDING_SECTIONS
            val maxY = SectionPos.blockToSectionCoord(floor(box.maxY).toInt()) + VERTICAL_BUILD_PADDING_SECTIONS
            val minZ = SectionPos.blockToSectionCoord(floor(box.minZ).toInt()) - BUILD_RADIUS_CHUNKS
            val maxZ = SectionPos.blockToSectionCoord(floor(box.maxZ).toInt()) + BUILD_RADIUS_CHUNKS
            for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
                sectionsToBuild.add(SectionPos.of(x, y, z))
            }
            for (x in minX - CACHE_RETENTION_PADDING_SECTIONS..maxX + CACHE_RETENTION_PADDING_SECTIONS)
                for (y in minY - CACHE_RETENTION_PADDING_SECTIONS..maxY + CACHE_RETENTION_PADDING_SECTIONS)
                    for (z in minZ - CACHE_RETENTION_PADDING_SECTIONS..maxZ + CACHE_RETENTION_PADDING_SECTIONS)
                        sectionsToRetain.add(SectionPos.of(x, y, z))
        }

        cacheRequests.addAndGet(sectionsToBuild.size.toLong())
        sectionsToBuild.forEach { position ->
            if (sections.containsKey(position)) cacheHits.incrementAndGet()
            val physicsChunk = chunks[ChunkPos(position.x(), position.z())] ?: return@forEach
            val section = physicsChunk.getOrCreateSection(position.y()) ?: return@forEach
            if (sections.putIfAbsent(position, section) == null) dirtySections.add(position)
        }

        // Do not retain native Bullet bodies for places that no dynamic body
        // can currently reach. They can be rebuilt from the loaded chunk when
        // a vehicle returns.
        sections.keys.filter { it !in sectionsToRetain }.forEach { position ->
            val section = sections.remove(position) ?: return@forEach
            chunks[ChunkPos(position.x(), position.z())]?.sections?.remove(position.y(), section)
            dirtySections.remove(position)
            retireLater(section)
        }
        // Block updates outside the local cache must not leave permanent queue
        // entries. A newly requested section is marked dirty when created.
        dirtySections.removeIf { !sections.containsKey(it) }

        val active = HashSet<SectionPos>()
        activation.forEach { raw ->
            val box = raw.inflate(ACTIVATION_PADDING_BLOCKS)
            val minX = SectionPos.blockToSectionCoord(floor(box.minX).toInt())
            val maxX = SectionPos.blockToSectionCoord(floor(box.maxX).toInt())
            val minY = SectionPos.blockToSectionCoord(floor(box.minY).toInt())
            val maxY = SectionPos.blockToSectionCoord(floor(box.maxY).toInt())
            val minZ = SectionPos.blockToSectionCoord(floor(box.minZ).toInt())
            val maxZ = SectionPos.blockToSectionCoord(floor(box.maxZ).toInt())
            for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
                val pos = SectionPos.of(x, y, z)
                // Include both existing sections and those just created in sectionsToBuild.
                // This ensures newly created sections are immediately marked as desired-active,
                // preventing the one-tick delay that caused vehicles to freeze while waiting
                // for terrain that was already queued for construction.
                if (sections.containsKey(pos)) active.add(pos)
            }
        }
        desiredActive.set(active)

        // Build collision directly under a newly registered body first. An
        // unordered two-section budget could spend the whole fall building
        // distant padding sections while the active floor remained queued.
        // Distance ordering also makes boundary crossings deterministic.
        fun distanceSquared(position: SectionPos): Double = activation.minOfOrNull { box ->
            val dx = position.minBlockX() + 8.0 - (box.minX + box.maxX) * .5
            val dy = position.minBlockY() + 8.0 - (box.minY + box.maxY) * .5
            val dz = position.minBlockZ() + 8.0 - (box.minZ + box.maxZ) * .5
            dx * dx + dy * dy + dz * dz
        } ?: Double.POSITIVE_INFINITY
        val dirty = dirtySections.asSequence()
            .filter { sections.containsKey(it) }
            .sortedWith(compareBy<SectionPos>({ it !in active }, { distanceSquared(it) }))
            .take(MAX_SNAPSHOTS_PER_TICK)
            .toList()
        dirtySections.removeAll(dirty.toSet())
        dirty.forEach { position -> sections[position]?.let(::scheduleBuild) }

        flushNetworkUpdates()
    }

    private fun scheduleBuild(section: PhysicsChunkSection) {
        val revision = section.revision.incrementAndGet()
        section.buildFuture?.cancel(false)
        val started = System.nanoTime()
        val snapshot = try {
            // Read the chunk currently owned by the level. The instance captured
            // at load can be stale after a chunk is replaced without a new load event.
            val live = physicsLevel.mcLevel.chunkSource.getChunkNow(section.sectionPos.x(), section.sectionPos.z())
                ?: section.chunk
            SectionSnapshot.snapshotFromChunk(physicsLevel.mcLevel, live, section.sectionPos, revision)
        } catch (error: Exception) {
            SparkCore.LOGGER.warn("Terrain snapshot failed in {}; retrying next tick", section.sectionPos, error)
            markDirty(setOf(section.sectionPos))
            return
        }
        if (remainingRebuildTraces > 0 && traceAllows(section.sectionPos)) {
            remainingRebuildTraces--
            SparkCore.LOGGER.info(
                "[terrain-rebuild] schedule {} revision={} blocks={}",
                section.sectionPos, revision, snapshot.blocks.size
            )
        }
        section.lastSnapshot = snapshot
        section.buildFuture = builderExecutor.submit {
            try {
                val built = TerrainShapePlan.build(snapshot)
                if (section.revision.get() == revision) readyPlans.add(built)
                else stalePlans.incrementAndGet()
                rebuilds.incrementAndGet()
                buildNanos.addAndGet(System.nanoTime() - started)
            } catch (error: Throwable) {
                SparkCore.LOGGER.error("Failed to build terrain section {}", section.sectionPos, error)
                if (section.revision.get() == revision) markDirty(setOf(section.sectionPos))
            }
        }
    }

    fun physicsTick(world: PhysicsWorld) {
        physicsLevel.assertPhysicsThread()
        var bodiesChanged = false
        while (true) {
            val built = readyPlans.poll() ?: break
            val section = sections[built.sectionPos] ?: continue
            if (section.revision.get() != built.revision) {
                stalePlans.incrementAndGet()
                continue
            }
            replaceBody(world, section, built)
            bodiesChanged = true
        }

        val desired = desiredActive.get()
        sections.values.forEach { section ->
            val shouldBeActive = section.sectionPos in desired
            if (shouldBeActive != section.active) {
                section.bodies.forEach { setActive(world, it, shouldBeActive) }
                section.active = shouldBeActive
                bodiesChanged = true
            }
        }
        // Sample only when the native cache changes, not every physics substep.
        // Peaks remain useful after test vehicles release their chunks.
        if (bodiesChanged) {
            peakBoxBodies = maxOf(peakBoxBodies, sections.values.sumOf { it.bodies.size })
            peakActiveBoxBodies = maxOf(peakActiveBoxBodies,
                sections.values.filter { it.active }.sumOf { it.bodies.size })
        }
    }

    private fun replaceBody(world: PhysicsWorld, section: PhysicsChunkSection, plan: TerrainShapePlan) {
        val origin = section.sectionPos
        val bodies = TerrainBoxBodies.create(plan.boxes, Vector3f(
            origin.minBlockX() + 8f, origin.minBlockY() + 8f, origin.minBlockZ() + 8f
        )).onEachIndexed { index, body ->
            body.apply {
                name = "section_${origin.asLong()}_box_$index"
                owner = section
                collisionGroup = CollisionGroups.TERRAIN
                setCollideWithGroups(CollisionGroups.NONE)
            }
        }
        // Construct first, then swap all convex bodies at a step boundary.
        // Native objects become reclaimable only after removal from the world.
        section.bodies.forEach { discardBody(world, it) }
        if (remainingRebuildTraces > 0 && traceAllows(origin)) {
            remainingRebuildTraces--
            SparkCore.LOGGER.info(
                "[terrain-rebuild] apply {} boxes={} revision={}",
                origin, bodies.size, plan.revision
            )
        }
        section.bodies = bodies
        section.appliedRevision = plan.revision
        section.active = section.sectionPos in desiredActive.get()
        bodies.forEach { setActive(world, it, section.active) }
    }

    private fun setActive(world: PhysicsWorld, body: PhysicsRigidBody?, active: Boolean) {
        if (body == null) return
        if (active && !body.isInWorld) world.addCollisionObject(body)
        if (!active && body.isInWorld) world.removeCollisionObject(body)
    }

    private fun flushNetworkUpdates() {
        if (physicsLevel.mcLevel.isClientSide || networkDirtySections.isEmpty()) return
        val update = networkDirtySections.take(4096).toSet()
        networkDirtySections.removeAll(update)
        val level = physicsLevel.mcLevel as ServerLevel
        SparkNetwork.CHANNEL.send(PacketDistributor.DIMENSION.with { level.dimension() }, TerrainUpdateMessage(update))
    }

    fun rebind(world: PhysicsWorld) {
        physicsLevel.assertPhysicsThread()
        sections.values.forEach { section ->
            section.bodies.forEach { body ->
                if (section.active && !body.isInWorld) world.addCollisionObject(body)
            }
        }
    }

    fun destroy(world: PhysicsWorld?) {
        SparkCore.LOGGER.info("Terrain cache {}: {}", physicsLevel.name, getStats())
        sections.values.forEach { section ->
            section.revision.incrementAndGet()
            section.buildFuture?.cancel(false)
            discardInstalled(section, world)
        }
        sections.clear()
        chunks.clear()
        readyPlans.clear()
        builderExecutor.shutdownNow()
    }

    private fun retireLater(section: PhysicsChunkSection) {
        section.revision.incrementAndGet()
        section.buildFuture?.cancel(false)
        physicsLevel.submitImmediateTask { discardInstalled(section, physicsLevel.worldOrNull()) }
    }

    /** Physics-thread cleanup shared by eviction, chunk unload and level destroy. */
    private fun discardInstalled(section: PhysicsChunkSection, world: PhysicsWorld?) {
        section.bodies.forEach { discardBody(world, it) }
        section.bodies = emptyList()
        section.active = false
        section.appliedRevision = -1
    }

    private fun discardBody(world: PhysicsWorld?, body: PhysicsRigidBody?) {
        if (body == null) return
        if (world != null && body.isInWorld) world.removeCollisionObject(body)
        body.owner = null
        body.clearSparkRuntime()
    }

    val builtSectionCount: Int get() = sections.values.count { it.isBuilt() }
    val activeSectionCount: Int get() = sections.values.count { it.isActive() }

    /** Applied revision of the section containing [pos], or -1 when that section is not cached. */
    fun appliedRevisionAt(pos: BlockPos): Long = sections[SectionPos.of(pos)]?.appliedRevision ?: -1L

    fun getStats(): String {
        val requests = cacheRequests.get()
        val hitRate = if (requests == 0L) 0.0 else cacheHits.get().toDouble() * 100.0 / requests
        return "chunks=${chunks.size}, sections=${sections.size}, active=${sections.values.count { it.active }}, " +
            "boxBodies=${sections.values.sumOf { it.bodies.size }}, " +
            "activeBoxBodies=${sections.values.filter { it.active }.sumOf { it.bodies.size }}, " +
            "peakBoxBodies=$peakBoxBodies, peakActiveBoxBodies=$peakActiveBoxBodies, " +
            "cacheHit=${"%.2f".format(java.util.Locale.ROOT, hitRate)}%, rebuilds=${rebuilds.get()}, " +
            "unchangedCollisions=${unchangedCollisions.get()}, " +
        "buildMs=${buildNanos.get() / 1_000_000}, queue=${dirtySections.size + readyPlans.size}, stale=${stalePlans.get()}"
    }
}
