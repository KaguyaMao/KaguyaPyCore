package cn.solarmoon.spark_core.util

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

fun CompoundTag.putVec3(key: String, value: Vec3) {
    put(key, CompoundTag().also {
        it.putDouble("x", value.x)
        it.putDouble("y", value.y)
        it.putDouble("z", value.z)
    })
}

fun CompoundTag.getVec3(key: String): Vec3 {
    val value = getCompound(key)
    return Vec3(value.getDouble("x"), value.getDouble("y"), value.getDouble("z"))
}

fun CompoundTag.putQuaternion(key: String, value: Quaternionf) {
    put(key, CompoundTag().also {
        it.putFloat("x", value.x)
        it.putFloat("y", value.y)
        it.putFloat("z", value.z)
        it.putFloat("w", value.w)
    })
}

fun CompoundTag.getQuaternion(key: String): Quaternionf {
    val value = getCompound(key)
    return Quaternionf(value.getFloat("x"), value.getFloat("y"), value.getFloat("z"), value.getFloat("w"))
}
