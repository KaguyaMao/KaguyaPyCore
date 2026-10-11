package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.PhysicsHost
import cn.solarmoon.spark_core.physics.level.PhysicsLevel
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.objects.PhysicsRigidBody
import net.minecraft.core.SectionPos
import net.minecraft.world.level.chunk.LevelChunk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

class PhysicsChunkSection(
    val sectionPos: SectionPos,
    override val physicsLevel: PhysicsLevel,
    val chunk: LevelChunk
) : PhysicsHost {
    override val allPhysicsBodies: MutableMap<String, PhysicsCollisionObject> = ConcurrentHashMap()
    internal val revision = AtomicLong(0)
    @Volatile internal var buildFuture: Future<*>? = null
    @Volatile internal var body: PhysicsRigidBody? = null
    @Volatile internal var meshBody: PhysicsRigidBody? = null
    @Volatile internal var active = false
    @Volatile internal var appliedRevision: Long = -1
    @Volatile internal var lastSnapshot: SectionSnapshot? = null

    fun isActive(): Boolean = active
    fun isBuilt(): Boolean = body != null || meshBody != null
}
