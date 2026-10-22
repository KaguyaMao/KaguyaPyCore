package cn.solarmoon.spark_core.physics.body

import cn.solarmoon.spark_core.physics.PhysicsHost
import cn.solarmoon.spark_core.util.Subscription
import com.jme3.bullet.collision.PhysicsCollisionObject
import net.minecraft.world.entity.Entity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

data class BodyRuntimeData(
    /** Stable identity, unique for the lifetime of the process. */
    val id: Long = nextBodyId.incrementAndGet(),
    @Volatile var name: String = UUID.randomUUID().toString(),
    @Volatile var owner: PhysicsHost? = null,
    @Volatile var colliding: Boolean = false,
    @Volatile var collideWithOwnerGroups: Int = CollisionGroups.NONE,
    @Volatile var showDebugWithoutMask: Boolean = false,
    @Volatile var attachedEntity: Entity? = null,
    val listeners: ConcurrentHashMap<KClass<out PhysicsBodyEvent>, CopyOnWriteArrayList<(PhysicsBodyEvent) -> Unit>> = ConcurrentHashMap()
) {
    companion object {
        private val nextBodyId = java.util.concurrent.atomic.AtomicLong(0)
    }
}

object BodyRuntime {
    private val data = ConcurrentHashMap<PhysicsCollisionObject, BodyRuntimeData>()

    fun of(body: PhysicsCollisionObject): BodyRuntimeData = data.computeIfAbsent(body) { BodyRuntimeData() }

    fun remove(body: PhysicsCollisionObject) {
        data.remove(body)?.listeners?.clear()
    }
}

/**
 * Stable identity of a body, usable as a cross-thread snapshot key.
 *
 * The value survives world recreation because it lives in [BodyRuntime], not in
 * the native object. Creating it on demand means a body that never had Spark
 * metadata still gets a usable id.
 */
val PhysicsCollisionObject.bodyId: Long
    get() = BodyRuntime.of(this).id

var PhysicsCollisionObject.name: String
    get() = BodyRuntime.of(this).name
    set(value) { BodyRuntime.of(this).name = value }

var PhysicsCollisionObject.owner: PhysicsHost?
    get() = BodyRuntime.of(this).owner
    set(value) {
        val runtime = BodyRuntime.of(this)
        val event = PhysicsBodyEvent.OwnerChanged(runtime.owner, value)
        triggerBodyEvent(event)
        val old = runtime.owner
        val accepted = event.newOwner
        if (old === accepted) return
        old?.allPhysicsBodies?.remove(runtime.name, this)
        runtime.owner = accepted
        accepted?.allPhysicsBodies?.put(runtime.name, this)
        userObject = accepted
    }

var PhysicsCollisionObject.isColliding: Boolean
    get() = BodyRuntime.of(this).colliding
    internal set(value) { BodyRuntime.of(this).colliding = value }

var PhysicsCollisionObject.collideWithOwnerGroups: Int
    get() = BodyRuntime.of(this).collideWithOwnerGroups
    set(value) { BodyRuntime.of(this).collideWithOwnerGroups = value }

var PhysicsCollisionObject.shouldShowDebugBoxWhenNonColldeWith: Boolean
    get() = BodyRuntime.of(this).showDebugWithoutMask
    set(value) { BodyRuntime.of(this).showDebugWithoutMask = value }

internal var PhysicsCollisionObject.attachedEntity: Entity?
    get() = BodyRuntime.of(this).attachedEntity
    set(value) { BodyRuntime.of(this).attachedEntity = value }

inline fun <reified T : PhysicsBodyEvent> PhysicsCollisionObject.onBodyEvent(noinline handler: (T) -> Unit): Subscription {
    val runtime = BodyRuntime.of(this)
    val wrapper: (PhysicsBodyEvent) -> Unit = { handler(it as T) }
    runtime.listeners.computeIfAbsent(T::class) { CopyOnWriteArrayList() }.add(wrapper)
    return Subscription { runtime.listeners[T::class]?.remove(wrapper) }
}

internal fun PhysicsCollisionObject.triggerBodyEvent(event: PhysicsBodyEvent) {
    BodyRuntime.of(this).listeners[event::class]?.forEach { it(event) }
}

internal fun PhysicsCollisionObject.clearSparkRuntime() = BodyRuntime.remove(this)
