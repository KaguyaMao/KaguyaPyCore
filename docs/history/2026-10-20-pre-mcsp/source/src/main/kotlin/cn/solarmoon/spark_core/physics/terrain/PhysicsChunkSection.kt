package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.PhysicsHost
import cn.solarmoon.spark_core.physics.terrain.support.TerrainSupportField
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
    @Volatile internal var bodies: List<PhysicsRigidBody> = emptyList()
    /** Vehicle-only slope wedges generated from the same snapshot. */
    @Volatile internal var slopeBodies: List<PhysicsRigidBody> = emptyList()
    /** Opt-in support pair. Null when the snapshot has no valid support cell. */
    @Volatile internal var supportPair: GroundSupportPair? = null
    /**
     * Immutable field published with the applied revision. Null until the first
     * successful apply. Debug reads may use it from the server thread; it is not
     * a Bullet object.
     */
    @Volatile internal var installedSupport: TerrainSupportField? = null
    @Volatile internal var active = false
    @Volatile internal var appliedRevision: Long = -1
    @Volatile internal var lastSnapshot: SectionSnapshot? = null

    fun isActive(): Boolean = active
    fun isBuilt(): Boolean = bodies.isNotEmpty() || slopeBodies.isNotEmpty()
}
