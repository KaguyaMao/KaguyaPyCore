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
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.collision.shapes.CompoundCollisionShape
import com.jme3.bullet.collision.shapes.MeshCollisionShape
import com.jme3.bullet.collision.shapes.infos.IndexedMesh
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraftforge.network.PacketDistributor
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.floor

class PhysicsChunkManager(private val physicsLevel: PhysicsLevel) {
    companion object {
        // Terrain physics is a local cache around dynamic bodies. One section
        // of horizontal padding covers the configured 10-block mesh radius.
        const val BUILD_RADIUS_CHUNKS = 1
        const val CACHE_RETENTION_PADDING_SECTIONS = 1
        const val VERTICAL_BUILD_PADDING_SECTIONS = 1
        const val ACTIVATION_PADDING_BLOCKS = 2.0
        const val MESH_RADIUS_BLOCKS = 10.0
        const val MESH_RETENTION_RADIUS_BLOCKS = 12.0
        const val MAX_SNAPSHOTS_PER_TICK = 8
        /** Keep Bullet's contact skin from lifting vehicles above the visible NoCubes surface. */
        const val TERRAIN_MESH_MARGIN = 0.001f
    }

    private val chunks = ConcurrentHashMap<ChunkPos, PhysicsChunk>()
    private val sections = ConcurrentHashMap<SectionPos, PhysicsChunkSection>()
    private val dirtySections = ConcurrentHashMap.newKeySet<SectionPos>()
    private val networkDirtySections = ConcurrentHashMap.newKeySet<SectionPos>()
    private val readyPlans = ConcurrentLinkedQueue<TerrainShapePlan>()
    private val desiredActive = AtomicReference<Set<SectionPos>>(emptySet())
    private val surfaceProvider = NoCubesSurfaceProvider(physicsLevel.mcLevel)
    private var meshSections: Set<SectionPos> = emptySet()
    @Volatile private var configurationVersion = surfaceProvider.configurationVersion
    private val stalePlans = java.util.concurrent.atomic.AtomicLong()
    private val rebuilds = java.util.concurrent.atomic.AtomicLong()
    private val buildNanos = java.util.concurrent.atomic.AtomicLong()
    private val cacheRequests = java.util.concurrent.atomic.AtomicLong()
    private val cacheHits = java.util.concurrent.atomic.AtomicLong()

    /** Physics-thread readiness test, including empty (but successfully built) sections. */
    fun isReady(box: AABB): Boolean {
        for (x in (floor(box.minX).toInt() shr 4)..(floor(box.maxX).toInt() shr 4))
            for (y in (floor(box.minY).toInt() shr 4)..(floor(box.maxY).toInt() shr 4))
                for (z in (floor(box.minZ).toInt() shr 4)..(floor(box.maxZ).toInt() shr 4)) {
                    if (y < physicsLevel.mcLevel.minSection || y >= physicsLevel.mcLevel.maxSection) continue
                    val key = SectionPos.of(x, y, z)
                    val section = sections[key] ?: return false
                    if (!section.active || section.appliedRevision != section.revision.get() || key in dirtySections) return false
                }
        return true
    }
    private val builderExecutor: ExecutorService = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 2)
    ) { runnable -> Thread(runnable, "TerrainShapeBuilder-${physicsLevel.name}").apply { isDaemon = true } }

    fun onChunkLoaded(chunk: LevelChunk) {
        chunks.computeIfAbsent(chunk.pos) { PhysicsChunk(chunk.pos, physicsLevel, chunk) }
    }

    fun onChunkUnloaded(pos: ChunkPos) {
        val chunk = chunks.remove(pos) ?: return
        unloadSections(chunk)
    }

    fun unloadPhysicsChunk(pos: ChunkPos) {
        val chunk = chunks[pos] ?: return
        unloadSections(chunk)
    }

    private fun unloadSections(chunk: PhysicsChunk) {
        chunk.sections.values.forEach { section ->
            sections.remove(section.sectionPos)
            section.revision.incrementAndGet()
            section.buildFuture?.cancel(false)
            physicsLevel.submitImmediateTask {
                discardBody(physicsLevel.worldOrNull(), section.body)
                discardBody(physicsLevel.worldOrNull(), section.meshBody)
                section.body = null
                section.meshBody = null
                section.active = false
            }
        }
    }

    fun markDirty(pos: BlockPos) {
        // SurfaceNets needs neighboring voxels, including across section boundaries.
        val affected = HashSet<SectionPos>()
        for (x in -2..2 step 2) for (y in -2..2 step 2) for (z in -2..2 step 2)
            affected.add(SectionPos.of(pos.offset(x, y, z)))
        markDirty(affected)
    }

    fun markDirty(positions: Set<SectionPos>) {
        dirtySections.addAll(positions)
        if (!physicsLevel.mcLevel.isClientSide) networkDirtySections.addAll(positions)
    }

    fun tickMainThread(activationBoxes: List<AABB>) {
        val sectionsToBuild = HashSet<SectionPos>()
        val nearby = HashSet<SectionPos>()
        val retainedMesh = HashSet<SectionPos>()
        activationBoxes.forEach { raw ->
            val box = raw.inflate(MESH_RADIUS_BLOCKS)
            for (x in (floor(box.minX).toInt() shr 4)..(floor(box.maxX).toInt() shr 4))
                for (y in (floor(box.minY).toInt() shr 4)..(floor(box.maxY).toInt() shr 4))
                    for (z in (floor(box.minZ).toInt() shr 4)..(floor(box.maxZ).toInt() shr 4))
                        nearby.add(SectionPos.of(x, y, z))
            val retentionBox = raw.inflate(MESH_RETENTION_RADIUS_BLOCKS)
            for (x in (floor(retentionBox.minX).toInt() shr 4)..(floor(retentionBox.maxX).toInt() shr 4))
                for (y in (floor(retentionBox.minY).toInt() shr 4)..(floor(retentionBox.maxY).toInt() shr 4))
                    for (z in (floor(retentionBox.minZ).toInt() shr 4)..(floor(retentionBox.maxZ).toInt() shr 4))
                        retainedMesh.add(SectionPos.of(x, y, z))
        }
        val nextMeshSections = (meshSections intersect retainedMesh) + nearby
        dirtySections.addAll((meshSections - nextMeshSections) + (nextMeshSections - meshSections))
        meshSections = nextMeshSections
        val config = surfaceProvider.configurationVersion
        if (config != configurationVersion) {
            configurationVersion = config
            dirtySections.addAll(sections.keys)
        }
        val sectionsToRetain = HashSet<SectionPos>()
        activationBoxes.forEach { box ->
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
            section.revision.incrementAndGet()
            section.buildFuture?.cancel(false)
            physicsLevel.submitImmediateTask {
                discardBody(physicsLevel.worldOrNull(), section.body)
                discardBody(physicsLevel.worldOrNull(), section.meshBody)
                section.body = null
                section.meshBody = null
                section.active = false
            }
        }
        // Block updates outside the local cache must not leave permanent queue
        // entries. A newly requested section is marked dirty when created.
        dirtySections.removeIf { !sections.containsKey(it) }

        val active = HashSet<SectionPos>()
        activationBoxes.forEach { raw ->
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
        fun distanceSquared(position: SectionPos): Double = activationBoxes.minOfOrNull { box ->
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
            SectionSnapshot.snapshotFromChunk(physicsLevel.mcLevel, section.chunk, section.sectionPos, revision,
                if (section.sectionPos in meshSections) surfaceProvider else null)
        } catch (error: Exception) {
            SparkCore.LOGGER.warn("Terrain surface unavailable in {}; using blocks", section.sectionPos, error)
            SectionSnapshot.snapshotFromChunk(physicsLevel.mcLevel, section.chunk, section.sectionPos, revision)
        }
        section.lastSnapshot = snapshot
        section.buildFuture = builderExecutor.submit {
            try {
                val plan = TerrainShapePlan.build(snapshot)
                if (section.revision.get() == revision) readyPlans.add(plan)
                else stalePlans.incrementAndGet()
                rebuilds.incrementAndGet()
                buildNanos.addAndGet(System.nanoTime() - started)
            } catch (error: Throwable) {
                SparkCore.LOGGER.error("Failed to build terrain section {}", section.sectionPos, error)
            }
        }
    }

    fun physicsTick(world: PhysicsWorld) {
        physicsLevel.assertPhysicsThread()
        while (true) {
            val plan = readyPlans.poll() ?: break
            val section = sections[plan.sectionPos] ?: continue
            if (section.revision.get() != plan.revision ||
                (plan.mesh != null && !plan.mesh.isCurrent(plan.revision, configurationVersion))) {
                stalePlans.incrementAndGet()
                continue
            }
            replaceBody(world, section, plan)
        }

        val desired = desiredActive.get()
        sections.values.forEach { section ->
            val shouldBeActive = section.sectionPos in desired
            if (shouldBeActive != section.active) {
                setActive(world, section.body, shouldBeActive)
                setActive(world, section.meshBody, shouldBeActive)
                section.active = shouldBeActive
            }
        }
    }

    private fun replaceBody(world: PhysicsWorld, section: PhysicsChunkSection, plan: TerrainShapePlan) {
        val compound = CompoundCollisionShape()
        plan.boxes.forEach { box ->
            if (box.halfX > 0f && box.halfY > 0f && box.halfZ > 0f) {
                compound.addChildShape(
                    BoxCollisionShape(Vector3f(box.halfX, box.halfY, box.halfZ)),
                    Vector3f(box.centerX, box.centerY, box.centerZ)
                )
            }
        }
        val body = if (plan.boxes.isEmpty()) null else PhysicsRigidBody(compound, 0f).apply {
            name = "section_${section.sectionPos.asLong()}"
            owner = section
            collisionGroup = CollisionGroups.TERRAIN
            setCollideWithGroups(CollisionGroups.NONE)
            setPhysicsLocation(
                Vector3f(
                    section.sectionPos.minBlockX() + 8f,
                    section.sectionPos.minBlockY() + 8f,
                    section.sectionPos.minBlockZ() + 8f
                )
            )
        }
        val meshBody = plan.mesh?.snapshot?.let { mesh ->
            val origin = section.sectionPos
            val positions = Array(mesh.vertexCount) { index ->
                Vector3f((mesh.coordinate(index, 0) - origin.minBlockX() - 8).toFloat(),
                    (mesh.coordinate(index, 1) - origin.minBlockY() - 8).toFloat(),
                    (mesh.coordinate(index, 2) - origin.minBlockZ() - 8).toFloat())
            }
            val shape = MeshCollisionShape(true, IndexedMesh(positions, mesh.indices)).apply {
                margin = TERRAIN_MESH_MARGIN
            }
            PhysicsRigidBody(shape, 0f).apply {
                name = "terrain_mesh_${origin.asLong()}"
                owner = section
                collisionGroup = CollisionGroups.TERRAIN
                setCollideWithGroups(CollisionGroups.NONE)
                setPhysicsLocation(Vector3f(origin.minBlockX() + 8f, origin.minBlockY() + 8f, origin.minBlockZ() + 8f))
            }
        }
        // Construct first, then swap both bodies at a physics-step boundary.
        // Native objects become reclaimable only after removal from the world.
        discardBody(world, section.body)
        discardBody(world, section.meshBody)
        section.body = body
        section.meshBody = meshBody
        section.appliedRevision = plan.revision
        section.active = section.sectionPos in desiredActive.get()
        setActive(world, body, section.active)
        setActive(world, meshBody, section.active)
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
            section.body?.let { body ->
                if (section.active && !body.isInWorld) world.addCollisionObject(body)
            }
            setActive(world, section.meshBody, section.active)
        }
    }

    fun destroy(world: PhysicsWorld?) {
        SparkCore.LOGGER.info("Terrain cache {}: {}", physicsLevel.name, getStats())
        sections.values.forEach { section ->
            section.revision.incrementAndGet()
            section.buildFuture?.cancel(false)
            discardBody(world, section.body)
            discardBody(world, section.meshBody)
            section.body = null
            section.meshBody = null
        }
        sections.clear()
        chunks.clear()
        readyPlans.clear()
        builderExecutor.shutdownNow()
    }

    private fun discardBody(world: PhysicsWorld?, body: PhysicsRigidBody?) {
        if (body == null) return
        if (world != null && body.isInWorld) world.removeCollisionObject(body)
        body.owner = null
        body.clearSparkRuntime()
    }

    val builtSectionCount: Int get() = sections.values.count { it.isBuilt() }
    val activeSectionCount: Int get() = sections.values.count { it.isActive() }

    fun getStats(): String {
        val requests = cacheRequests.get()
        val hitRate = if (requests == 0L) 0.0 else cacheHits.get().toDouble() * 100.0 / requests
        return "chunks=${chunks.size}, sections=${sections.size}, active=${sections.values.count { it.active }}, " +
        "triangles=${sections.values.sumOf { it.lastSnapshot?.mesh?.snapshot?.triangleCount ?: 0 }}, " +
        "cacheHit=${"%.2f".format(java.util.Locale.ROOT, hitRate)}%, rebuilds=${rebuilds.get()}, " +
        "buildMs=${buildNanos.get() / 1_000_000}, queue=${dirtySections.size + readyPlans.size}, stale=${stalePlans.get()}"
    }
}
