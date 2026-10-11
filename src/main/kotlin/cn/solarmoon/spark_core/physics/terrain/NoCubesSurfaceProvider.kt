package cn.solarmoon.spark_core.physics.terrain

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockBehaviour

/** Optional Forge adapter: no direct linkage to NoCubes. */
internal class NoCubesSurfaceProvider(private val level: Level) : TerrainSurfaceProvider {
    private class Api {
        val type = Class.forName("io.github.cadiboo.nocubes.collision.TerrainTriangleApi")
        val capture = type.getMethod("capture", Level::class.java, BlockPos::class.java, BlockPos::class.java, Long::class.javaPrimitiveType)
        val version = type.getMethod("configurationVersion")
        val handler = Class.forName("io.github.cadiboo.nocubes.NoCubes").getField("smoothableHandler").get(null)
        val smoothable = Class.forName("io.github.cadiboo.nocubes.smoothable.SmoothableHandler")
            .getMethod("isSmoothable", BlockBehaviour.BlockStateBase::class.java)
        val snapshot = Class.forName("io.github.cadiboo.nocubes.collision.TerrainTriangleSnapshot")
        val complete = snapshot.getMethod("complete")
        val triangles = snapshot.getMethod("triangles")
        val source = snapshot.getMethod("source")
        val triangle = Class.forName("io.github.cadiboo.nocubes.collision.TerrainTriangleSnapshot\$Triangle")
        val corners = arrayOf("a", "b", "c").map { triangle.getMethod(it) }
        val friction = triangle.getMethod("friction")
        val vertex = Class.forName("io.github.cadiboo.nocubes.collision.TerrainTriangleSnapshot\$Vertex")
        val axes = arrayOf("x", "y", "z").map { vertex.getMethod(it) }
    }
    private val api = try { Api() } catch (_: ReflectiveOperationException) { null }
    override val configurationVersion: Long
        get() = (api?.version?.invoke(null) as? Number)?.toLong() ?: -1

    override fun capture(key: TerrainMeshKey): TerrainMeshSnapshot? {
        val api = api ?: return null
        val result = api.capture.invoke(null, level, BlockPos(key.x * 16, key.y * 16, key.z * 16), BlockPos(16, 16, 16), key.revision)
        if (api.complete.invoke(result) != true) return null
        val triangles = api.triangles.invoke(result) as List<*>
        if (triangles.isEmpty() || triangles.size > TerrainMeshSnapshot.MAX_TRIANGLES) return null
        val vertices = DoubleArray(triangles.size * 9)
        val friction = FloatArray(triangles.size)
        triangles.forEachIndexed { index, triangle ->
            api.corners.forEachIndexed { corner, method ->
                val vertex = method.invoke(triangle)
                api.axes.forEachIndexed { axis, getter ->
                    vertices[index * 9 + corner * 3 + axis] = (getter.invoke(vertex) as Number).toDouble()
                }
            }
            friction[index] = (api.friction.invoke(triangle) as Number).toFloat()
        }
        return TerrainMeshSnapshot(vertices, IntArray(triangles.size * 3) { it }, key.revision,
            key.configurationVersion, api.source.invoke(result) as String, friction)
    }

    override fun replacesBlock(x: Int, y: Int, z: Int): Boolean {
        val api = api ?: return false
        return api.smoothable.invoke(api.handler, level.getBlockState(BlockPos(x, y, z))) == true
    }
}
