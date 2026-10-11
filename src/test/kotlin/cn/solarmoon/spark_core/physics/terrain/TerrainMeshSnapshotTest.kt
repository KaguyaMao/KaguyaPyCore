package cn.solarmoon.spark_core.physics.terrain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerrainMeshSnapshotTest {
    @Test fun detachedArraysAndRevision() {
        val vertices = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.5, 0.0)
        val indices = intArrayOf(0, 1, 2)
        val mesh = TerrainMeshSnapshot(vertices, indices, 5, 9)
        vertices[0] = 99.0
        indices[0] = 2
        mesh.vertices[0] = 88.0
        mesh.indices[0] = 1
        assertEquals(0.0, mesh.coordinate(0, 0))
        assertEquals(0, mesh.index(0))
        val plan = TriangleMeshPlan(TerrainMeshKey("overworld", -1, 4, 0, 5, 9), mesh)
        assertTrue(plan.isCurrent(5, 9))
        assertFalse(plan.isCurrent(6, 9))
        assertFalse(plan.isCurrent(5, 10))
    }

    @Test fun invalidIndicesAndNonFiniteVerticesRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TerrainMeshSnapshot(doubleArrayOf(0.0, 0.0, 0.0), intArrayOf(0, 1, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerrainMeshSnapshot(doubleArrayOf(Double.NaN, 0.0, 0.0), intArrayOf())
        }
    }
}
