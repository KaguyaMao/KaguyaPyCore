package cn.solarmoon.spark_core.physics

import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

fun Vec3.toBullet() = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
fun org.joml.Vector3f.toBullet() = Vector3f(x, y, z)
fun org.joml.Quaternionf.toBullet() = Quaternion(x, y, z, w)
fun Vector3f.toMinecraft() = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
fun Quaternion.toJoml() = org.joml.Quaternionf(x, y, z, w)

fun com.jme3.bounding.BoundingBox.toMinecraftAabb(): AABB {
    val center = getCenter(null)
    return AABB(
        (center.x - xExtent).toDouble(), (center.y - yExtent).toDouble(), (center.z - zExtent).toDouble(),
        (center.x + xExtent).toDouble(), (center.y + yExtent).toDouble(), (center.z + zExtent).toDouble()
    )
}
