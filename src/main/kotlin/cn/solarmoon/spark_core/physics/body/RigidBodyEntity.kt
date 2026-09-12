package cn.solarmoon.spark_core.physics.body

import cn.solarmoon.spark_core.physics.physicsLevel
import cn.solarmoon.spark_core.physics.toBullet
import cn.solarmoon.spark_core.util.getVec3
import cn.solarmoon.spark_core.util.putVec3
import com.jme3.bullet.objects.PhysicsRigidBody
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

abstract class RigidBodyEntity(type: EntityType<*>, level: Level) : CollisionObjectEntity(type, level) {
    companion object {
        @JvmField val DATA_GRAVITY = SynchedEntityData.defineId(RigidBodyEntity::class.java, EntityDataSerializers.VECTOR3)
        @JvmField val DATA_GRAVITY_PROTECTED = SynchedEntityData.defineId(RigidBodyEntity::class.java, EntityDataSerializers.BOOLEAN)
        @JvmField val DATA_ANGULAR_FACTOR = SynchedEntityData.defineId(RigidBodyEntity::class.java, EntityDataSerializers.VECTOR3)
        @JvmField val DATA_ANGULAR_VELOCITY = SynchedEntityData.defineId(RigidBodyEntity::class.java, EntityDataSerializers.VECTOR3)
        @JvmField val DATA_ANGULAR_DAMPING = SynchedEntityData.defineId(RigidBodyEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_ANGULAR_SLEEP = SynchedEntityData.defineId(RigidBodyEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_LINEAR_FACTOR = SynchedEntityData.defineId(RigidBodyEntity::class.java, EntityDataSerializers.VECTOR3)
        @JvmField val DATA_LINEAR_VELOCITY = SynchedEntityData.defineId(RigidBodyEntity::class.java, EntityDataSerializers.VECTOR3)
        @JvmField val DATA_LINEAR_DAMPING = SynchedEntityData.defineId(RigidBodyEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_LINEAR_SLEEP = SynchedEntityData.defineId(RigidBodyEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField val DATA_INVERSE_INERTIA = SynchedEntityData.defineId(RigidBodyEntity::class.java, EntityDataSerializers.VECTOR3)
        @JvmField val DATA_MASS = SynchedEntityData.defineId(RigidBodyEntity::class.java, EntityDataSerializers.FLOAT)
    }

    abstract override val body: PhysicsRigidBody

    var isKinematic: Boolean
        get() = level().isClientSide || body.isKinematic
        set(value) { physicsLevel.submitImmediateTask { body.isKinematic = value } }

    var gravity by syncField(DATA_GRAVITY, ::vec3FromJoml, ::vec3ToJoml) { body.setGravity(it.toBullet()) }
    var isGravityProtected by syncField(DATA_GRAVITY_PROTECTED) { body.setProtectGravity(it) }
    var angularFactor by syncField(DATA_ANGULAR_FACTOR, ::vec3FromJoml, ::vec3ToJoml) { body.setAngularFactor(it.toBullet()) }
    var angularVelocity by syncField(DATA_ANGULAR_VELOCITY, ::vec3FromJoml, ::vec3ToJoml) { body.setAngularVelocity(it.toBullet()) }
    var angularDamping by syncField(DATA_ANGULAR_DAMPING) { body.angularDamping = it }
    var angularSleepingThreshold by syncField(DATA_ANGULAR_SLEEP) { body.angularSleepingThreshold = it }
    var linearFactor by syncField(DATA_LINEAR_FACTOR, ::vec3FromJoml, ::vec3ToJoml) { body.setLinearFactor(it.toBullet()) }
    var linearVelocity by syncField(DATA_LINEAR_VELOCITY, ::vec3FromJoml, ::vec3ToJoml) { body.setLinearVelocity(it.toBullet()) }
    var linearDamping by syncField(DATA_LINEAR_DAMPING) { body.linearDamping = it }
    var linearSleepingThreshold by syncField(DATA_LINEAR_SLEEP) { body.linearSleepingThreshold = it }
    var inverseInertiaLocal by syncField(DATA_INVERSE_INERTIA, ::vec3FromJoml, ::vec3ToJoml) { body.setInverseInertiaLocal(it.toBullet()) }
    var mass by syncField(DATA_MASS) { body.mass = it }

    override fun baseTick() {
        super.baseTick()
        if (!level().isClientSide) {
            physicsLevel.latestFrame.bodies[body]?.let { frame ->
                updatingFromPhysics = true
                try {
                    angularVelocity = frame.angularVelocity
                    linearVelocity = frame.linearVelocity
                } finally {
                    updatingFromPhysics = false
                }
            }
        }
    }

    override fun recreateFromPacket(packet: ClientboundAddEntityPacket) {
        super.recreateFromPacket(packet)
        isKinematic = true
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_GRAVITY, Vector3f(0f, -9.81f, 0f))
        entityData.define(DATA_GRAVITY_PROTECTED, false)
        entityData.define(DATA_ANGULAR_FACTOR, Vector3f(1f))
        entityData.define(DATA_ANGULAR_VELOCITY, Vector3f())
        entityData.define(DATA_ANGULAR_DAMPING, 0f)
        entityData.define(DATA_ANGULAR_SLEEP, 1f)
        entityData.define(DATA_LINEAR_FACTOR, Vector3f(1f))
        entityData.define(DATA_LINEAR_VELOCITY, Vector3f())
        entityData.define(DATA_LINEAR_DAMPING, 0f)
        entityData.define(DATA_LINEAR_SLEEP, .8f)
        entityData.define(DATA_INVERSE_INERTIA, Vector3f())
        entityData.define(DATA_MASS, 1f)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        if (tag.contains("physics_isKinematic")) isKinematic = tag.getBoolean("physics_isKinematic")
        if (tag.contains("physics_gravity")) gravity = tag.getVec3("physics_gravity")
        if (tag.contains("physics_isGravityProtected")) isGravityProtected = tag.getBoolean("physics_isGravityProtected")
        if (tag.contains("physics_angularFactor")) angularFactor = tag.getVec3("physics_angularFactor")
        if (tag.contains("physics_angularVelocity")) angularVelocity = tag.getVec3("physics_angularVelocity")
        if (tag.contains("physics_angularDamping")) angularDamping = tag.getFloat("physics_angularDamping")
        if (tag.contains("physics_angularSleepingThreshold")) angularSleepingThreshold = tag.getFloat("physics_angularSleepingThreshold")
        if (tag.contains("physics_linearFactor")) linearFactor = tag.getVec3("physics_linearFactor")
        if (tag.contains("physics_linearVelocity")) linearVelocity = tag.getVec3("physics_linearVelocity")
        if (tag.contains("physics_linearDamping")) linearDamping = tag.getFloat("physics_linearDamping")
        if (tag.contains("physics_linearSleepingThreshold")) linearSleepingThreshold = tag.getFloat("physics_linearSleepingThreshold")
        if (tag.contains("physics_mass")) mass = tag.getFloat("physics_mass")
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putBoolean("physics_isKinematic", isKinematic)
        tag.putVec3("physics_gravity", gravity)
        tag.putBoolean("physics_isGravityProtected", isGravityProtected)
        tag.putVec3("physics_angularFactor", angularFactor)
        tag.putVec3("physics_angularVelocity", angularVelocity)
        tag.putFloat("physics_angularDamping", angularDamping)
        tag.putFloat("physics_angularSleepingThreshold", angularSleepingThreshold)
        tag.putVec3("physics_linearFactor", linearFactor)
        tag.putVec3("physics_linearVelocity", linearVelocity)
        tag.putFloat("physics_linearDamping", linearDamping)
        tag.putFloat("physics_linearSleepingThreshold", linearSleepingThreshold)
        tag.putFloat("physics_mass", mass)
    }

    private fun vec3FromJoml(value: Vector3f) = Vec3(value.x.toDouble(), value.y.toDouble(), value.z.toDouble())
    private fun vec3ToJoml(value: Vec3) = Vector3f(value.x.toFloat(), value.y.toFloat(), value.z.toFloat())
}
