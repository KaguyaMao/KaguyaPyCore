package cn.solarmoon.spark_core.physics.terrain

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import net.minecraft.core.SectionPos
import net.minecraft.world.phys.AABB
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/**
 * Historical planner only. The live section plan no longer emits these wedges,
 * so these tests pin the legacy algorithm and its geometry. The live absence is
 * asserted separately by [TerrainShapePlanTest.livePlanDoesNotInstallAFixedClimbWedge].
 */
class TerrainSlopePlannerTest {
    /** Historical planner only. The live section plan no longer emits these wedges. */
    private fun legacyPlan(snapshot: SectionSnapshot) =
        TerrainShapePlan(snapshot.sectionPos, snapshot.revision, emptyList(), TerrainSlopePlanner.build(snapshot))

    private val full = listOf(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))

    /**
     * Ground column `(x, z)` is filled from y=0 up to `layers - 1`, so the
     * topmost full cube sits at `layers - 1` and its surface at `layers`.
     * [margin] supplies the one-block margin top *index* (-1..16, null for
     * unknown/air); margin columns are only read outside the core 16x16.
     */
    private fun snapshot(
        columns: (x: Int, z: Int) -> Int,
        margin: (x: Int, z: Int) -> Int? = { _, _ -> null },
    ): SectionSnapshot {
        val blocks = ArrayList<SectionSnapshot.BlockShapeSnapshot>()
        for (z in 0..15) for (x in 0..15) {
            for (y in 0 until columns(x, z)) {
                blocks.add(SectionSnapshot.BlockShapeSnapshot(x, y, z, full, true))
            }
        }
        val marginTops = IntArray(SectionSnapshot.MARGIN_CELLS) { SectionSnapshot.MARGIN_UNKNOWN }
        for (mx in -1..16) for (mz in -1..16) {
            if (mx in 0..15 && mz in 0..15) continue
            val top = margin(mx, mz) ?: continue
            marginTops[(mx + 1) * 18 + (mz + 1)] = top
        }
        return SectionSnapshot(SectionPos.of(0, 0, 0), 1, blocks, marginTops)
    }

    @Test
    fun oneBlockStepOverAClearFlatRunBecomesOneMergedRamp() {
        val plan = legacyPlan(snapshot({ x, _ -> if (x >= 9) 5 else 4 }))
        assertEquals(1, plan.slopes.size)
        val ramp = plan.slopes.single()
        // Face at x=9, three-block run back to x=6, low surface y=4, high y=5.
        assertEquals(-2f, ramp.lowX)
        assertEquals(1f, ramp.highX)
        assertEquals(-4f, ramp.lowY)
        assertEquals(-3f, ramp.highY)
        assertEquals(0f, ramp.lowZ)
        assertEquals(8f, ramp.halfWidth)
    }

    @Test
    fun tallerStepsAndDiagonalStaircasesKeepOriginalCollision() {
        assertTrue(legacyPlan(snapshot({ x, _ -> if (x >= 9) 6 else 4 })).slopes.isEmpty())
        // 45-degree staircase: every step is one block, but no flat run exists.
        assertTrue(legacyPlan(snapshot({ x, _ -> minOf(4 + x, 13) })).slopes.isEmpty())
        // One flat column behind the step is not a run.
        val narrow = snapshot({ x, _ -> when { x >= 9 -> 5; x == 6 -> 3; else -> 4 } })
        assertTrue(legacyPlan(narrow).slopes.isEmpty())
    }

    @Test
    fun tunnelFloorUnderASolidRoofStillGetsItsRamp() {
        // The authored corridor runs under natural hill layers: the same
        // columns stack the drivable floor and a solid roof just below the
        // section top. The roof must not hide the floor step, and must not
        // invent a ramp of its own.
        val base = snapshot({ x, _ -> if (x >= 9) 5 else 4 })
        val roof = ArrayList<SectionSnapshot.BlockShapeSnapshot>()
        for (z in 0..15) for (x in 0..15) {
            roof.add(SectionSnapshot.BlockShapeSnapshot(x, 14, z, full, true))
            roof.add(SectionSnapshot.BlockShapeSnapshot(x, 15, z, full, true))
        }
        val plan = legacyPlan(base.copy(blocks = base.blocks + roof))
        assertEquals(1, plan.slopes.size, "the shallow floor step under the roof must still get its ramp")
        val ramp = plan.slopes.single()
        assertEquals(-2f, ramp.lowX)
        assertEquals(1f, ramp.highX)
        assertEquals(-4f, ramp.lowY)
        assertEquals(-3f, ramp.highY)
        assertEquals(0f, ramp.lowZ)
        assertEquals(8f, ramp.halfWidth)
    }

    @Test
    fun stackedStoriesEachGetTheirOwnRamp() {
        // A floor step (3 -> 4) under a roof whose own story tops step at the
        // same column (12 -> 13). Both are legitimate one-block steps for a
        // vehicle on that level; neither story may hide the other.
        val base = snapshot({ x, _ -> if (x >= 9) 5 else 4 })
        val roof = ArrayList<SectionSnapshot.BlockShapeSnapshot>()
        for (z in 0..15) for (x in 0..15) {
            for (y in 10..if (x >= 9) 13 else 12) {
                roof.add(SectionSnapshot.BlockShapeSnapshot(x, y, z, full, true))
            }
        }
        val plan = legacyPlan(base.copy(blocks = base.blocks + roof))
        assertEquals(2, plan.slopes.size, "each readable story top step needs its own ramp")
        assertEquals(-4f, plan.slopes[0].lowY)
        assertEquals(-3f, plan.slopes[0].highY)
        assertEquals(-2f, plan.slopes[0].lowX)
        assertEquals(1f, plan.slopes[0].highX)
        assertEquals(5f, plan.slopes[1].lowY)
        assertEquals(6f, plan.slopes[1].highY)
        assertEquals(-2f, plan.slopes[1].lowX)
        assertEquals(1f, plan.slopes[1].highX)
    }

    @Test
    fun borderStepUsesReadableMarginAndSkipsUnknownChunks() {
        val readable = legacyPlan(snapshot({ _, _ -> 4 }, margin = { x, _ -> if (x == 16) 4 else null }))
        val ramp = readable.slopes.singleOrNull()
        assertNotNull(ramp)
        assertEquals(5f, ramp!!.lowX)
        assertEquals(8f, ramp.highX)
        assertEquals(-4f, ramp.lowY)
        assertEquals(-3f, ramp.highY)

        val unknown = legacyPlan(snapshot({ _, _ -> 4 }))
        assertTrue(unknown.slopes.isEmpty(), "an unread neighbour chunk must never be guessed")
    }

    @Test
    fun fractionalSurfaceStepsKeepOriginalCollision() {
        // A slab above the low surface leaves a one-block full-cube step behind
        // it, but the clearance cell is occupied: no ramp may be invented.
        val base = snapshot({ x, _ -> if (x >= 9) 5 else 4 })
        fun slabRow(x: Int) = (0..15).map {
            SectionSnapshot.BlockShapeSnapshot(x, 4, it, listOf(AABB(0.0, 0.0, 0.0, 1.0, .5, 1.0)), false)
        }
        val slabAtLowColumn = base.copy(blocks = base.blocks + slabRow(8))
        assertTrue(legacyPlan(slabAtLowColumn).slopes.isEmpty(),
            "a fractional surface on the low column must keep the original block collision")
        val slabOnRun = base.copy(blocks = base.blocks + slabRow(6))
        assertTrue(legacyPlan(slabOnRun).slopes.isEmpty(),
            "a fractional surface on the runway must keep the original block collision")
    }

    @Test
    fun holesCliffsAndCeilingsKeepOriginalCollision() {
        // A hole in the runway: one run column drops away, so no ramp.
        assertTrue(legacyPlan(snapshot({ x, _ -> when { x >= 9 -> 5; x == 6 -> 2; else -> 4 } }))
            .slopes.isEmpty())
        // A cliff on the other side is not a one-block step.
        assertTrue(legacyPlan(snapshot({ x, _ -> if (x >= 9) 2 else 4 })).slopes.isEmpty())
        // A low ceiling over the run (low + 2 occupied) must not be filled in.
        val base = snapshot({ x, _ -> if (x >= 9) 5 else 4 })
        val ceiling = base.copy(blocks = base.blocks + SectionSnapshot.BlockShapeSnapshot(7, 5, 8, full, true))
        val plan = legacyPlan(ceiling)
        assertEquals(2, plan.slopes.size, "only the column under the ceiling keeps the original collision")
        assertEquals(-4f, plan.slopes[0].lowZ)
        assertEquals(4f, plan.slopes[0].halfWidth)
        assertEquals(4.5f, plan.slopes[1].lowZ)
        assertEquals(3.5f, plan.slopes[1].halfWidth)
    }

    @Test
    fun blockedWedgeVolumeSplitsTheRampInsteadOfBurrowing() {
        val base = snapshot({ x, _ -> if (x >= 9) 5 else 4 })
        val blocked = base.copy(blocks = base.blocks + SectionSnapshot.BlockShapeSnapshot(
            7, 4, 3, listOf(AABB(0.0, 0.0, 0.0, 1.0, .5, 1.0)), false))
        val plan = legacyPlan(blocked)
        assertEquals(2, plan.slopes.size)
        assertEquals(-6.5f, plan.slopes[0].lowZ)
        assertEquals(1.5f, plan.slopes[0].halfWidth)
        assertEquals(2f, plan.slopes[1].lowZ)
        assertEquals(6f, plan.slopes[1].halfWidth)
    }

    @Test
    fun wedgeCarriesAnAlignedLoadWithoutSliding() {
        NativePhysicsLoader.load()
        val plan = legacyPlan(snapshot({ x, _ -> if (x >= 9) 5 else 4 }))
        assertEquals(1, plan.slopes.size)
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        try {
            val face = Vector3f(1000f, 64f, 1000f)
            TerrainBoxBodies.create(plan.boxes, face.clone()).forEach(space::addCollisionObject)
            TerrainSlopeBodies.create(plan.slopes, face.clone()).forEach(space::addCollisionObject)
            // Midway into the run the incline surface is 0.5 m above the low
            // floor; a load aligned with the 1:3 slope must rest exactly there
            // and friction must hold it in place.
            val theta = atan(1f / 3f)
            val surface = Vector3f(999.5f, 60.5f, 1000f)
            val normal = Vector3f(-sin(theta), cos(theta), 0f)
            val center = surface.add(normal.mult(.505f))
            val body = PhysicsRigidBody(BoxCollisionShape(Vector3f(.5f, .5f, .5f)), 10f).apply {
                friction = 1.5f
                setPhysicsLocation(center)
                setPhysicsRotation(Quaternion().fromAngles(0f, 0f, theta))
            }
            space.addCollisionObject(body)
            repeat(120) { space.update(1f / 60f, 0, false, true, false) }
            val location = body.getPhysicsLocation(null)
            assertEquals(center.y, location.y, .05f, "ramp must carry the load at the incline height: $location")
            val drift = Vector3f(location.x - center.x, 0f, location.z - center.z).length()
            assertTrue(drift < .1f, "aligned load with friction must not slide or drift: $location")
        } finally {
            space.destroy()
        }
    }
}
