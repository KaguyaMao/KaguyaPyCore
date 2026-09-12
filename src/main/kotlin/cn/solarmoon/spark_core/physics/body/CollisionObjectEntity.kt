package cn.solarmoon.spark_core.physics.body

import cn.solarmoon.spark_core.physics.SparkPhysics
import cn.solarmoon.spark_core.physics.physicsLevel
import cn.solarmoon.spark_core.physics.toBullet
import cn.solarmoon.spark_core.util.getQuaternion
import cn.solarmoon.spark_core.util.getVec3
import cn.solarmoon.spark_core.util.putQuaternion
import cn.solarmoon.spark_core.util.putVec3
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.math.Vector3f as BulletVector3f
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

abstract class CollisionObjectEntity(type: EntityType<*>, level: Level) : Entity(type, level) {
    companion object {
        @JvmField val DATA_ROTATION = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.QUATERNION)
        @JvmField val DATA_SCALE = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.VECTOR3)
        @JvmField val DATA_ANISOTROPIC_FRICTION = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.VECTOR3)
        @JvmField val DATA_COLLISION_GROUP = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.INT)
        @JvmField val DATA_COLLISION_WITH_GROUPS = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.INT)
        @JvmField val DATA_CCD_MOTION_THRESHOLD = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_CCD_SWEEP_RADIUS = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_CONTACT_STIFFNESS = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_CONTACT_DAMPING = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_CONTACT_THRESHOLD = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_FRICTION = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_ROLLING_FRICTION = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_SPINNING_FRICTION = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_DEACTIVATION_TIME = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_RESTITUTION = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_ACTIVATION_STATE = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.INT)
        @JvmField val DATA_CONTACT_RESPONSE = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.BOOLEAN)
        @JvmField val DATA_ACTIVE = SynchedEntityData.defineId(CollisionObjectEntity::class.java, EntityDataSerializers.BOOLEAN)
    }

    abstract val body: PhysicsCollisionObject
    protected var updatingFromPhysics = false
    private var lerpPosition = Vec3.ZERO
    private var lerpViewAngles = Vec2.ZERO
    private var lerpSteps = 0
    private val physicsApplicators = LinkedHashMap<EntityDataAccessor<*>, () -> Unit>()

    var rotationO = Quaternionf()
    open var rotation by syncField(DATA_ROTATION) { body.setSparkRotation(it.toBullet()) }
    var scaleO = Vec3.ZERO
    open var scale by syncField(DATA_SCALE, { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }, { Vector3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }) { body.setSparkScale(it.toBullet()) }
    var anisotropicFriction by syncField(DATA_ANISOTROPIC_FRICTION, { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }, { Vector3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }) { body.setAnisotropicFriction(it.toBullet(), 0) }
    var collisionGroup by syncField(DATA_COLLISION_GROUP) { body.collisionGroup = it }
    var collideWithGroups by syncField(DATA_COLLISION_WITH_GROUPS) { body.setCollideWithGroups(it) }
    var ccdMotionThreshold by syncField(DATA_CCD_MOTION_THRESHOLD) { body.ccdMotionThreshold = it }
    var ccdSweptSphereRadius by syncField(DATA_CCD_SWEEP_RADIUS) { body.ccdSweptSphereRadius = it }
    var contactStiffness by syncField(DATA_CONTACT_STIFFNESS) { body.contactStiffness = it }
    var contactDamping by syncField(DATA_CONTACT_DAMPING) { body.contactDamping = it }
    var contactProcessingThreshold by syncField(DATA_CONTACT_THRESHOLD) { body.contactProcessingThreshold = it }
    var friction by syncField(DATA_FRICTION) { body.friction = it }
    var rollingFriction by syncField(DATA_ROLLING_FRICTION) { body.rollingFriction = it }
    var spinningFriction by syncField(DATA_SPINNING_FRICTION) { body.spinningFriction = it }
    var deactivationTime by syncField(DATA_DEACTIVATION_TIME) { body.deactivationTime = it }
    var restitution by syncField(DATA_RESTITUTION) { body.restitution = it }
    var activationState by syncField(DATA_ACTIVATION_STATE)
    var isContactResponse by syncField(DATA_CONTACT_RESPONSE) { body.setSparkContactResponse(it) }
    var isActive by syncField(DATA_ACTIVE) { body.activate(it) }

    override fun baseTick() {
        rotationO = Quaternionf(rotation)
        scaleO = scale
        super.baseTick()
        if (!level().isClientSide) {
            physicsLevel.latestFrame.bodies[body]?.let { frame ->
                updatingFromPhysics = true
                try {
                    isActive = frame.active
                    activationState = frame.activationState
                    isContactResponse = frame.contactResponse
                    if (frame.active) {
                        super.setPos(frame.position.x, frame.position.y, frame.position.z)
                        rotation = frame.rotation
                    }
                } finally {
                    updatingFromPhysics = false
                }
            }
        } else if (lerpSteps > 0) {
            val amount = 1.0 / lerpSteps
            super.setPos(x + (lerpPosition.x - x) * amount, y + (lerpPosition.y - y) * amount, z + (lerpPosition.z - z) * amount)
            yRot = (yRot + (lerpViewAngles.y - yRot) * amount.toFloat())
            xRot = (xRot + (lerpViewAngles.x - xRot) * amount.toFloat())
            lerpSteps--
        }
    }

    override fun lerpTo(x: Double, y: Double, z: Double, yRot: Float, xRot: Float, steps: Int, teleport: Boolean) {
        lerpPosition = Vec3(x, y, z)
        lerpViewAngles = Vec2(xRot, yRot)
        lerpSteps = steps
    }

    fun onRawPosUpdated(x: Double, y: Double, z: Double) {
        if (!updatingFromPhysics && isAddedToWorld) {
            submitBodyLocation(x, y, z)
        }
    }

    override fun onAddedToWorld() {
        super.onAddedToWorld()
        body.owner = SparkPhysics.host(this)
        val x = this.x
        val y = this.y
        val z = this.z
        physicsLevel.submitImmediateTask {
            body.setSparkLocation(BulletVector3f(x.toFloat(), y.toFloat(), z.toFloat()))
            physicsApplicators.values.forEach { it() }
        }
        level().addPhysicsBody(body)
    }

    override fun onSyncedDataUpdated(key: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(key)
        if (updatingFromPhysics) return
        val applicator = physicsApplicators[key] ?: return
        physicsLevel.submitImmediateTask { applicator() }
    }

    override fun onRemovedFromWorld() {
        level().removePhysicsBody(body)
        super.onRemovedFromWorld()
    }

    override fun defineSynchedData() {
        entityData.define(DATA_ROTATION, Quaternionf())
        entityData.define(DATA_SCALE, Vector3f(1f))
        entityData.define(DATA_ANISOTROPIC_FRICTION, Vector3f(1f))
        entityData.define(DATA_COLLISION_GROUP, CollisionGroups.PHYSICS_BODY)
        entityData.define(DATA_COLLISION_WITH_GROUPS, CollisionGroups.NONE)
        entityData.define(DATA_CCD_MOTION_THRESHOLD, 0f)
        entityData.define(DATA_CCD_SWEEP_RADIUS, 0f)
        entityData.define(DATA_CONTACT_STIFFNESS, 1e18f)
        entityData.define(DATA_CONTACT_DAMPING, .1f)
        entityData.define(DATA_CONTACT_THRESHOLD, 1e18f)
        entityData.define(DATA_FRICTION, .5f)
        entityData.define(DATA_ROLLING_FRICTION, 0f)
        entityData.define(DATA_SPINNING_FRICTION, 0f)
        entityData.define(DATA_DEACTIVATION_TIME, 0f)
        entityData.define(DATA_RESTITUTION, 0f)
        entityData.define(DATA_ACTIVATION_STATE, 0)
        entityData.define(DATA_CONTACT_RESPONSE, true)
        entityData.define(DATA_ACTIVE, true)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        if (tag.contains("physics_rotation")) rotation = tag.getQuaternion("physics_rotation")
        if (tag.contains("physics_scale")) scale = tag.getVec3("physics_scale")
        if (tag.contains("physics_anisotropicFriction")) anisotropicFriction = tag.getVec3("physics_anisotropicFriction")
        if (tag.contains("physics_collisionGroup")) collisionGroup = tag.getInt("physics_collisionGroup")
        if (tag.contains("physics_collideWithGroups")) collideWithGroups = tag.getInt("physics_collideWithGroups")
        if (tag.contains("physics_ccdMotionThreshold")) ccdMotionThreshold = tag.getFloat("physics_ccdMotionThreshold")
        if (tag.contains("physics_ccdSweptSphereRadius")) ccdSweptSphereRadius = tag.getFloat("physics_ccdSweptSphereRadius")
        if (tag.contains("physics_contactStiffness")) contactStiffness = tag.getFloat("physics_contactStiffness")
        if (tag.contains("physics_contactDamping")) contactDamping = tag.getFloat("physics_contactDamping")
        if (tag.contains("physics_contactProcessingThreshold")) contactProcessingThreshold = tag.getFloat("physics_contactProcessingThreshold")
        if (tag.contains("physics_friction")) friction = tag.getFloat("physics_friction")
        if (tag.contains("physics_rollingFriction")) rollingFriction = tag.getFloat("physics_rollingFriction")
        if (tag.contains("physics_spinningFriction")) spinningFriction = tag.getFloat("physics_spinningFriction")
        if (tag.contains("physics_deactivationTime")) deactivationTime = tag.getFloat("physics_deactivationTime")
        if (tag.contains("physics_restitution")) restitution = tag.getFloat("physics_restitution")
        if (tag.contains("physics_isContactResponse")) isContactResponse = tag.getBoolean("physics_isContactResponse")
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        tag.putQuaternion("physics_rotation", rotation)
        tag.putVec3("physics_scale", scale)
        tag.putVec3("physics_anisotropicFriction", anisotropicFriction)
        tag.putInt("physics_collisionGroup", collisionGroup)
        tag.putInt("physics_collideWithGroups", collideWithGroups)
        tag.putFloat("physics_ccdMotionThreshold", ccdMotionThreshold)
        tag.putFloat("physics_ccdSweptSphereRadius", ccdSweptSphereRadius)
        tag.putFloat("physics_contactStiffness", contactStiffness)
        tag.putFloat("physics_contactDamping", contactDamping)
        tag.putFloat("physics_contactProcessingThreshold", contactProcessingThreshold)
        tag.putFloat("physics_friction", friction)
        tag.putFloat("physics_rollingFriction", rollingFriction)
        tag.putFloat("physics_spinningFriction", spinningFriction)
        tag.putFloat("physics_deactivationTime", deactivationTime)
        tag.putFloat("physics_restitution", restitution)
        tag.putBoolean("physics_isContactResponse", isContactResponse)
    }

    protected fun <A : Any> syncField(accessor: EntityDataAccessor<A>, apply: ((A) -> Unit)? = null) =
        syncField(accessor, { it }, { it }, apply)

    protected fun <A : Any, T> syncField(
        accessor: EntityDataAccessor<A>,
        getter: (A) -> T,
        setter: (T) -> A,
        apply: ((T) -> Unit)? = null
    ): ReadWriteProperty<CollisionObjectEntity, T> {
        if (apply != null) {
            physicsApplicators[accessor] = { apply(getter(entityData.get(accessor))) }
        }
        return object : ReadWriteProperty<CollisionObjectEntity, T> {
            override fun getValue(thisRef: CollisionObjectEntity, property: KProperty<*>): T = getter(thisRef.entityData.get(accessor))
            override fun setValue(thisRef: CollisionObjectEntity, property: KProperty<*>, value: T) {
                thisRef.entityData.set(accessor, setter(value))
            }
        }
    }

    private fun submitBodyLocation(x: Double, y: Double, z: Double) {
        physicsLevel.submitImmediateTask {
            body.setSparkLocation(BulletVector3f(x.toFloat(), y.toFloat(), z.toFloat()))
        }
    }
}
