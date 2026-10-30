package cn.solarmoon.spark_core.physics.level

import cn.solarmoon.spark_core.event.NeedsCollisionEvent
import cn.solarmoon.spark_core.event.PhysicsContactConceivedEvent
import cn.solarmoon.spark_core.event.PhysicsContactEvent
import cn.solarmoon.spark_core.physics.body.*
import com.jme3.bullet.CollisionConfiguration
import com.jme3.bullet.PhysicsSoftSpace
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.SolverMode
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.collision.PersistentManifolds
import com.jme3.bullet.objects.PhysicsBody
import com.jme3.math.Vector3f
import net.minecraftforge.common.MinecraftForge

class PhysicsWorld(val level: PhysicsLevel) : PhysicsSoftSpace(
    Vector3f(-Int.MAX_VALUE.toFloat(), -1_000f, -Int.MAX_VALUE.toFloat()),
    Vector3f(Int.MAX_VALUE.toFloat(), 15_000f, Int.MAX_VALUE.toFloat()),
    PhysicsSpace.BroadphaseType.DBVT,
    CollisionConfiguration(8192, 0)
) {
    init {
        setGravity(Vector3f(0f, -9.81f, 0f))
        PhysicsBody.setDeactivationDeadline(3f)
        addTickListener(level)
        isForceUpdateAllAabbs = false
        solverInfo.setMode(
            SolverMode.SIMD or SolverMode.WarmStart or SolverMode.CacheFriendly or
                SolverMode.CacheDirection or SolverMode.ArticulatedWarmStart or SolverMode.Interleave
        )
    }

    override fun needsCollision(bodyA: PhysicsCollisionObject, bodyB: PhysicsCollisionObject): Boolean {
        if (bodyA.isStatic && bodyB.isStatic) return false
        // One-way masks: terrain uses collideWithGroups=NONE, so a dynamic body
        // still hits it when its own mask contains TERRAIN.
        var result = CollisionRules.masksAllow(
            bodyA.collisionGroup, bodyA.collideWithGroups,
            bodyB.collisionGroup, bodyB.collideWithGroups,
        )
        val ownerA = bodyA.owner
        val ownerB = bodyB.owner
        if (result && ownerA != null && ownerA === ownerB &&
            !CollisionRules.ownerAllows(
                bodyA.collideWithOwnerGroups, bodyB.collisionGroup,
                bodyB.collideWithOwnerGroups, bodyA.collisionGroup,
            )
        ) {
            result = false
        }
        val event = NeedsCollisionEvent(bodyA, bodyB, result)
        MinecraftForge.EVENT_BUS.post(event)
        return event.shouldCollide
    }

    override fun onContactConceived(
        manifoldPointId: Long,
        manifoldId: Long,
        bodyA: PhysicsCollisionObject,
        bodyB: PhysicsCollisionObject,
    ): Boolean {
        // Bullet calls this from btManifoldResult::addContactPoint BEFORE the
        // point enters the manifold, so a brand-new contact can be configured
        // for the same substep's solver. onContactProcessed only sees points
        // that already existed on the previous refresh.
        acceptConceivedContact(bodyA, bodyB, manifoldPointId)
        return true
    }

    override fun onContactProcessed(bodyA: PhysicsCollisionObject, bodyB: PhysicsCollisionObject, manifoldPointId: Long) {
        val pointA = ManifoldPoint(manifoldPointId, 0)
        val pointB = ManifoldPoint(manifoldPointId, 1)
        bodyA.isColliding = true
        bodyB.isColliding = true
        val frame = level.tickCount
        bodyA.triggerBodyEvent(PhysicsBodyEvent.Contact(bodyA, bodyB, pointA, pointB, snapshotContact(manifoldPointId, 0, bodyA, bodyB, frame)))
        bodyB.triggerBodyEvent(PhysicsBodyEvent.Contact(bodyB, bodyA, pointB, pointA, snapshotContact(manifoldPointId, 1, bodyB, bodyA, frame)))
        MinecraftForge.EVENT_BUS.post(PhysicsContactEvent(bodyA, bodyB, pointA, pointB, manifoldPointId))
    }

    /**
     * First-chance hook for a contact point that is about to be created.
     *
     * Runs before the point is added to the manifold, so a tangential target
     * configured here applies to the same substep's solver instead of waiting
     * for the next refresh. The point already carries its normal, local
     * positions and part indices at this stage.
     *
     * Listeners must not reject the contact by returning false: filtering here
     * would silently remove collision response that `needsCollision` already
     * approved.
     */
    private fun acceptConceivedContact(
        bodyA: PhysicsCollisionObject,
        bodyB: PhysicsCollisionObject,
        manifoldPointId: Long,
    ) {
        val pointA = ManifoldPoint(manifoldPointId, 0)
        val pointB = ManifoldPoint(manifoldPointId, 1)
        bodyA.triggerBodyEvent(PhysicsBodyEvent.ConceivedContact(bodyA, bodyB, pointA, pointB))
        bodyB.triggerBodyEvent(PhysicsBodyEvent.ConceivedContact(bodyB, bodyA, pointB, pointA))
        MinecraftForge.EVENT_BUS.post(PhysicsContactConceivedEvent(bodyA, bodyB, pointA, pointB, manifoldPointId))
    }

    /** Called after solver completion, while manifold pointers still belong to this substep. */
    fun dispatchResolvedContacts() {
        val frame = level.tickCount
        for (manifold in listManifoldIds()) {
            val bodyA = PhysicsCollisionObject.findInstance(PersistentManifolds.getBodyAId(manifold)) ?: continue
            val bodyB = PhysicsCollisionObject.findInstance(PersistentManifolds.getBodyBId(manifold)) ?: continue
            for (point in PersistentManifolds.listPointIds(manifold)) {
                val a = snapshotContact(point, 0, bodyA, bodyB, frame)
                val b = snapshotContact(point, 1, bodyB, bodyA, frame)
                bodyA.triggerBodyEvent(PhysicsBodyEvent.ResolvedContact(bodyA, bodyB, a))
                bodyB.triggerBodyEvent(PhysicsBodyEvent.ResolvedContact(bodyB, bodyA, b))
            }
        }
    }

    override fun addCollisionObject(body: PhysicsCollisionObject) {
        super.addCollisionObject(body)
        body.triggerBodyEvent(PhysicsBodyEvent.AddedToWorld)
    }

    override fun removeCollisionObject(body: PhysicsCollisionObject) {
        super.removeCollisionObject(body)
        body.triggerBodyEvent(PhysicsBodyEvent.RemovedFromWorld)
    }
}
