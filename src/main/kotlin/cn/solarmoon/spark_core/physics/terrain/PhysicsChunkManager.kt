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
        const val BUILD_RADIUS_CHUNKS = 2
        const val VERTICAL_BUILD_PADDING_SECTIONS = 1
        const val ACTIVATION_PADDING_BLOCKS = 2.0
    }

    private val chunks = ConcurrentHashMap<ChunkPos, PhysicsChunk>()
    private val sections = ConcurrentHashMap<SectionPos, PhysicsChunkSection>()
    private val dirtySections = ConcurrentHashMap.newKeySet<SectionPos>()
    private val networkDirtySections = ConcurrentHashMap.newKeySet<SectionPos>()
    private val readyPlans = ConcurrentLinkedQueue<TerrainShapePlan>()
    private val desiredActive = AtomicReference<Set<SectionPos>>(emptySet())
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
                section.body = null
                section.active = false
            }
        }
    }

    fun markDirty(pos: BlockPos) = markDirty(setOf(SectionPos.of(pos)))

    fun markDirty(positions: Set<SectionPos>) {
        dirtySections.addAll(positions)
        if (!physicsLevel.mcLevel.isClientSide) networkDirtySections.addAll(positions)
    }

    fun tickMainThread(activationBoxes: List<AABB>) {
        val sectionsToBuild = HashSet<SectionPos>()
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
        }

        sectionsToBuild.forEach { position ->
            val physicsChunk = chunks[ChunkPos(position.x(), position.z())] ?: return@forEach
            val section = physicsChunk.getOrCreateSection(position.y()) ?: return@forEach
            if (sections.putIfAbsent(position, section) == null) scheduleBuild(section)
        }

        val dirty = dirtySections.toList()
        dirtySections.removeAll(dirty.toSet())
        dirty.forEach { position -> sections[position]?.let(::scheduleBuild) }

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
                if (sections.containsKey(pos)) active.add(pos)
            }
        }
        desiredActive.set(active)
        flushNetworkUpdates()
    }

    private fun scheduleBuild(section: PhysicsChunkSection) {
        val revision = section.revision.incrementAndGet()
        section.buildFuture?.cancel(false)
        val snapshot = SectionSnapshot.snapshotFromChunk(physicsLevel.mcLevel, section.chunk, section.sectionPos, revision)
        section.lastSnapshot = snapshot
        section.buildFuture = builderExecutor.submit {
            try {
                val plan = TerrainShapePlan.build(snapshot)
                if (section.revision.get() == revision) readyPlans.add(plan)
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
            if (section.revision.get() != plan.revision) continue
            replaceBody(world, section, plan)
        }

        val desired = desiredActive.get()
        sections.values.forEach { section ->
            val body = section.body ?: return@forEach
            val shouldBeActive = section.sectionPos in desired
            if (shouldBeActive && !section.active) {
                if (!body.isInWorld) world.addCollisionObject(body)
                section.active = true
            } else if (!shouldBeActive && section.active) {
                if (body.isInWorld) world.removeCollisionObject(body)
                section.active = false
            }
        }
    }

    private fun replaceBody(world: PhysicsWorld, section: PhysicsChunkSection, plan: TerrainShapePlan) {
        discardBody(world, section.body)
        section.body = null
        section.active = false
        if (plan.boxes.isEmpty()) return

        val compound = CompoundCollisionShape()
        plan.boxes.forEach { box ->
            if (box.halfX > 0f && box.halfY > 0f && box.halfZ > 0f) {
                compound.addChildShape(
                    BoxCollisionShape(Vector3f(box.halfX, box.halfY, box.halfZ)),
                    Vector3f(box.centerX, box.centerY, box.centerZ)
                )
            }
        }
        val body = PhysicsRigidBody(compound, 0f).apply {
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
        section.body = body
        if (section.sectionPos in desiredActive.get()) {
            world.addCollisionObject(body)
            section.active = true
        }
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
        }
    }

    fun destroy(world: PhysicsWorld?) {
        sections.values.forEach { section ->
            section.revision.incrementAndGet()
            section.buildFuture?.cancel(false)
            discardBody(world, section.body)
            section.body = null
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

    fun getStats(): String = "chunks=${chunks.size}, sections=${sections.size}, active=${sections.values.count { it.active }}"
}
