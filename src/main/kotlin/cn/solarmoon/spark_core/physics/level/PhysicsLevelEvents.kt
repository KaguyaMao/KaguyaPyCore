package cn.solarmoon.spark_core.physics.level

import cn.solarmoon.spark_core.physics.SkipDefaultPawn
import cn.solarmoon.spark_core.physics.SparkPhysics
import cn.solarmoon.spark_core.physics.body.*
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.level.ChunkEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent

object PhysicsLevelEvents {
    @SubscribeEvent(priority = EventPriority.HIGH)
    fun onLevelLoad(event: LevelEvent.Load) {
        (event.level as? Level)?.let { SparkPhysics.get(it).start() }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    fun onLevelUnload(event: LevelEvent.Unload) {
        (event.level as? Level)?.let { SparkPhysics.get(it).close() }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    fun onLevelTick(event: TickEvent.LevelTickEvent) {
        if (event.phase == TickEvent.Phase.START) SparkPhysics.get(event.level).requestStep()
    }

    @SubscribeEvent
    fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.level as? Level ?: return
        val chunk = event.chunk as? LevelChunk ?: return
        SparkPhysics.get(level).terrainManager.onChunkLoaded(chunk)
    }

    @SubscribeEvent
    fun onChunkUnload(event: ChunkEvent.Unload) {
        val level = event.level as? Level ?: return
        SparkPhysics.get(level).terrainManager.onChunkUnloaded(event.chunk.pos)
    }

    // Ticket levels describe ticking eligibility, not chunk lifetime. Loaded
    // neighbors may still supply collision for a nearby dynamic body. The local
    // terrain cache evicts out-of-range sections; ChunkEvent.Unload removes the
    // remaining bodies when the actual chunk leaves the level.

    @SubscribeEvent
    fun onEntityJoin(event: EntityJoinLevelEvent) {
        val entity = event.entity
        val physics = SparkPhysics.get(event.level)
        physics.trackEntity(entity)
        if (entity is HangingEntity || entity is CollisionObjectEntity || entity is SkipDefaultPawn) return
        val box = entity.boundingBox
        val center = box.center
        val half = Vector3f(
            (box.xsize / 2).toFloat().coerceAtLeast(.01f),
            (box.ysize / 2).toFloat().coerceAtLeast(.01f),
            (box.zsize / 2).toFloat().coerceAtLeast(.01f)
        )
        physics.submitImmediateTask {
            if (!physics.isTracked(entity)) return@submitImmediateTask
            val body = PhysicsRigidBody(BoxCollisionShape(half), 1f).apply {
                name = "body"
                owner = SparkPhysics.host(entity)
                isKinematic = true
                isContactResponse = false
                collisionGroup = CollisionGroups.PAWN
                setCollideWithGroups(CollisionGroups.NONE)
                setPhysicsLocation(Vector3f(center.x.toFloat(), center.y.toFloat(), center.z.toFloat()))
                attachToEntity(entity)
            }
            physics.addBody(body)
        }
    }

    @SubscribeEvent
    fun onEntityLeave(event: EntityLeaveLevelEvent) {
        val physics = SparkPhysics.get(event.level)
        physics.untrackEntity(event.entity)
        physics.removeAttachedBodies(event.entity)
        // Only the kinematic proxy this listener created. A vehicle host also
        // owns wheels, runs and joints that must be torn down in dependency
        // order by their runtime; sweeping every owner body here would remove
        // those bodies while their joints are still live.
        // Use the map the entity mixin actually implements. The Kotlin default
        // method on PhysicsHost is not a reliable call target after reobf.
        SparkPhysics.host(event.entity).allPhysicsBodies["body"]?.let(physics::removeBody)
    }
}
