package cn.solarmoon.spark_core.physics.terrain

/** Detached numerical geometry. Arrays are copied on both input and output. */
class TerrainMeshSnapshot(
    vertices: DoubleArray,
    indices: IntArray,
    val revision: Long = 0,
    val configurationVersion: Long = 0,
    val source: String = "blocks",
    friction: FloatArray = FloatArray(indices.size / 3) { .6f },
) {
    private val positions = vertices.copyOf()
    private val elements = indices.copyOf()
    private val coefficients = friction.copyOf()
    val vertices: DoubleArray get() = positions.copyOf()
    val indices: IntArray get() = elements.copyOf()
    val friction: FloatArray get() = coefficients.copyOf()
    val triangleCount: Int get() = elements.size / 3
    val vertexCount: Int get() = positions.size / 3

    init {
        require(positions.size % 3 == 0 && positions.all { it.isFinite() })
        require(elements.size % 3 == 0 && elements.all { it in 0 until vertexCount })
        require(coefficients.size == triangleCount && coefficients.all { it.isFinite() && it >= 0 })
        require(triangleCount <= MAX_TRIANGLES)
    }

    constructor(vertices: FloatArray, indices: IntArray, revision: Long = 0) :
        this(DoubleArray(vertices.size) { vertices[it].toDouble() }, indices, revision)

    fun isEmpty(): Boolean = elements.isEmpty()
    fun coordinate(vertex: Int, axis: Int): Double = positions[vertex * 3 + axis]
    fun index(element: Int): Int = elements[element]
    fun friction(triangle: Int): Float = coefficients[triangle]

    companion object { const val MAX_TRIANGLES = 16384 }
}

data class TerrainMeshKey(val dimension: String, val x: Int, val y: Int, val z: Int,
                          val revision: Long, val configurationVersion: Long, val parameters: Int = 1)

class TriangleMeshPlan(val key: TerrainMeshKey, val snapshot: TerrainMeshSnapshot) {
    init {
        require(key.revision == snapshot.revision && key.configurationVersion == snapshot.configurationVersion)
    }
    fun isCurrent(revision: Long, configurationVersion: Long): Boolean =
        key.revision == revision && key.configurationVersion == configurationVersion
}

/** Capture on the owning game thread; no Minecraft types in this contract. */
interface TerrainSurfaceProvider {
    val configurationVersion: Long
    fun capture(key: TerrainMeshKey): TerrainMeshSnapshot?
    fun replacesBlock(x: Int, y: Int, z: Int): Boolean
}
