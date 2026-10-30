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
    @Volatile internal var bodies: List<PhysicsRigidBody> = emptyList()
    @Volatile internal var active = false
    @Volatile internal var appliedRevision: Long = -1
    @Volatile internal var lastSnapshot: SectionSnapshot? = null

    /**
     * Snapshot the currently installed collision was built from.
     *
     * [lastSnapshot] cannot serve this purpose: a newer build may already have
     * replaced it while the physics thread still runs on the previous revision.
     * Consumers that need the geometry the solver actually sees (a tracked
     * vehicle's local terrain mesh) must read this field, never `lastSnapshot`.
     */
    @Volatile internal var appliedSnapshot: SectionSnapshot? = null

    /**
     * Snapshots indexed by revision, so the applying side can recover the exact
     * geometry of the plan it installs. Bounded by [rememberSnapshot].
     */
    private val snapshotsByRevision = ConcurrentHashMap<Long, SectionSnapshot>()

    internal fun rememberSnapshot(snapshot: SectionSnapshot) {
        snapshotsByRevision[snapshot.revision] = snapshot
        if (snapshotsByRevision.size > MAX_REMEMBERED_SNAPSHOTS) {
            val newest = snapshotsByRevision.keys.maxOrNull() ?: return
            snapshotsByRevision.keys.removeIf { it < newest - MAX_REMEMBERED_SNAPSHOTS }
        }
    }

    internal fun snapshotAt(revision: Long): SectionSnapshot? = snapshotsByRevision[revision]

    internal fun installedSnapshot(revision: Long): SectionSnapshot? {
        val snapshot = snapshotsByRevision[revision] ?: return null
        appliedSnapshot = snapshot
        snapshotsByRevision.keys.removeIf { it < revision }
        return snapshot
    }

    internal fun forgetSnapshots() {
        snapshotsByRevision.clear()
        appliedSnapshot = null
    }

    fun isActive(): Boolean = active
    fun isBuilt(): Boolean = bodies.isNotEmpty()

    private companion object {
        /** One revision can be built while the previous one is still applied. */
        const val MAX_REMEMBERED_SNAPSHOTS = 4
    }
}
