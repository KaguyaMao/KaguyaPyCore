package cn.solarmoon.spark_core.physics

import cn.solarmoon.spark_core.physics.terrain.TerrainBoxBodies
import cn.solarmoon.spark_core.physics.terrain.TerrainShapePlan
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.CollisionConfiguration
import com.jme3.bullet.PhysicsSoftSpace
import com.jme3.bullet.PhysicsTickListener
import com.jme3.bullet.SolverMode
import com.jme3.bullet.collision.ManifoldPoints
import com.jme3.bullet.collision.PersistentManifolds
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.collision.shapes.CompoundCollisionShape
import com.jme3.bullet.collision.shapes.MeshCollisionShape
import com.jme3.bullet.collision.shapes.infos.IndexedMesh
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.bullet.objects.PhysicsBody
import com.jme3.bullet.joints.motors.MotorParam
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlanarTrackSupportTest {
    private class Rig(val massScale: Float = 1f) : AutoCloseable {
        val chassis = PhysicsRigidBody(BoxCollisionShape(Vector3f(1f, .3f, 2f)), 9200f * massScale)
        val plates = listOf(-1.3f, 1.3f).map {
            PlanarTrackSupport(chassis, Vector3f(it, -.7f, 0f), .2f, 3f, 400f * massScale, .18f, .24f)
        }
        var demand = 0f
        var engine = false
        var brake = false
        var explicitGravity = false
        var repeatContactResponse = false
        var holdInitially = 0
        var wakeChassisOnly = false
        val space = object : PhysicsSoftSpace(
            Vector3f(-Int.MAX_VALUE.toFloat(), -1000f, -Int.MAX_VALUE.toFloat()),
            Vector3f(Int.MAX_VALUE.toFloat(), 15000f, Int.MAX_VALUE.toFloat()),
            BroadphaseType.DBVT, CollisionConfiguration(8192, 0)) {
            override fun needsCollision(a: PhysicsCollisionObject, b: PhysicsCollisionObject): Boolean {
                if ((a === chassis && plates.any { it.body === b }) || (b === chassis && plates.any { it.body === a })) return false
                return a.collisionGroup and b.collideWithGroups != 0 || b.collisionGroup and a.collideWithGroups != 0
            }
            override fun onContactProcessed(a: PhysicsCollisionObject, b: PhysicsCollisionObject, id: Long) {
                val plate = plates.firstOrNull { it.body === a || it.body === b } ?: return
                val isA = a === plate.body
                val normal = Vector3f().also { ManifoldPoints.getNormalWorldOnB(id, it) }.multLocal(if (isA) 1f else -1f)
                if (normal.y < .25f) return
                val other = (if (isA) b else a) as? PhysicsRigidBody
                val speed = plate.body.getLinearVelocity(null).z - (other?.takeUnless { it.isStatic }?.getLinearVelocity(null)?.z ?: 0f)
                plate.traction(id, isA, normal, speed, demand, 4f, engine, brake, .7f, .015f, 1f, 1f/60f)
            }
        }
        init {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            space.solverInfo.setMode(SolverMode.WarmStart or SolverMode.CacheDirection or SolverMode.Use2Directions)
            chassis.setPhysicsLocation(Vector3f(0f, 1.5f, 0f))
            chassis.setDamping(.01f, .15f)
            chassis.collisionGroup = 16
            chassis.setCollideWithGroups(1 or 2 or 16 or 32)
            space.add(chassis)
            plates.forEach {
                it.reset(chassis.getPhysicsLocation(null), Quaternion(), Vector3f(), Vector3f())
                it.body.collisionGroup = 32
                it.body.setCollideWithGroups(1 or 2 or 16)
                space.add(it.body); space.addJoint(it.joint)
            }
            space.addTickListener(object : PhysicsTickListener {
                override fun prePhysicsTick(s: PhysicsSpace, dt: Float) {
                    if (holdInitially > 0) {
                        (listOf(chassis) + plates.map { it.body }).forEach { it.isKinematic = true }
                        holdInitially--
                        return
                    }
                    if (chassis.isKinematic) {
                        (listOf(chassis) + plates.map { it.body }).forEach { it.isKinematic = false; it.activate(true) }
                    }
                    if (wakeChassisOnly) chassis.activate(true)
                    if (repeatContactResponse) plates.forEach { it.body.isContactResponse = true }
                    if (explicitGravity) {
                        rGravity(chassis)
                        plates.forEach { rGravity(it.body) }
                    }
                    plates.forEach { it.spring(4600f * massScale, 9.81f, 2.2f,
                        PlanarTrackSupport.dampingCoefficient(4600f * massScale, 2.2f,
                            if (it.compressionSpeed() >= 0f) .65f else .45f),
                        1_000_000f, dt) }
                }
                override fun physicsTick(s: PhysicsSpace, dt: Float) { }
            })
        }
        private fun rGravity(body: PhysicsRigidBody) = body.applyCentralForce(Vector3f(0f, -body.mass * 9.81f, 0f))
        fun floor(x: Float, z: Float, hx: Float, hz: Float, top: Float = 0f, mass: Float = 0f): PhysicsRigidBody {
            val floor = PhysicsRigidBody(BoxCollisionShape(Vector3f(hx, .5f, hz)).apply { margin = .001f }, mass)
            floor.setPhysicsLocation(Vector3f(x, top - .5f, z)); space.add(floor)
            return floor
        }
        fun sectionFloor(cx: Float, cz: Float, compound: Boolean = false): PhysicsRigidBody {
            val body = if (compound) {
                val shape = CompoundCollisionShape().apply {
                    addChildShape(BoxCollisionShape(Vector3f(8f, .5f, 8f)).apply { margin = .001f }, Vector3f(0f, -7.5f, 0f))
                }
                PhysicsRigidBody(shape, 0f).also { it.setPhysicsLocation(Vector3f(cx, 72f, cz)) }
            } else TerrainBoxBodies.create(listOf(
                TerrainShapePlan.ShapeBox(0f, -7.5f, 0f, 8f, .5f, 8f)), Vector3f(cx, 72f, cz)).single()
            return body.also {
                it.collisionGroup = 1
                it.setCollideWithGroups(0)
                space.add(it)
            }
        }
        fun meshFloor(): PhysicsRigidBody {
            // Match createTrackSurfaces(): a thin 8 cm plate can tunnel through
            // a zero-thickness mesh during its initial fall without CCD.
            plates.forEach {
                it.body.ccdMotionThreshold = .02f
                it.body.ccdSweptSphereRadius = .02f
            }
            val vertices = arrayOf(Vector3f(-10f, 0f, -10f), Vector3f(-10f, 0f, 10f),
                Vector3f(10f, 0f, 10f), Vector3f(10f, 0f, -10f))
            return PhysicsRigidBody(MeshCollisionShape(true,
                IndexedMesh(vertices, intArrayOf(0, 1, 2, 0, 2, 3))).apply { margin = .001f }, 0f)
                .also { space.add(it) }
        }
        fun steps(n: Int) = repeat(n) { space.update(1f/60f, 0, false, true, false) }
        fun supportCount(plate: PlanarTrackSupport): Int = space.listManifoldIds().sumOf { m ->
            val isA = PersistentManifolds.getBodyAId(m) == plate.body.nativeId()
            if (!isA && PersistentManifolds.getBodyBId(m) != plate.body.nativeId()) 0
            else PersistentManifolds.listPointIds(m).count { id ->
                val n = Vector3f().also { ManifoldPoints.getNormalWorldOnB(id, it) }
                n.y * (if (isA) 1f else -1f) > .25f && ManifoldPoints.getDistance1(id) <= .001f && ManifoldPoints.getAppliedImpulse(id) > 0f
            }
        }
        fun supportPoints(plate: PlanarTrackSupport): List<Vector3f> = space.listManifoldIds().flatMap { m ->
            val isA = PersistentManifolds.getBodyAId(m) == plate.body.nativeId()
            if (!isA && PersistentManifolds.getBodyBId(m) != plate.body.nativeId()) emptyList()
            else PersistentManifolds.listPointIds(m).filter { id ->
                val normal = Vector3f().also { ManifoldPoints.getNormalWorldOnB(id, it) }
                normal.y * (if (isA) 1f else -1f) > .25f &&
                    ManifoldPoints.getDistance1(id) <= .001f && ManifoldPoints.getAppliedImpulse(id) > 0f
            }.map { id ->
                Vector3f().also {
                    if (isA) ManifoldPoints.getPositionWorldOnA(id, it) else ManifoldPoints.getPositionWorldOnB(id, it)
                }
            }
        }
        override fun close() { space.destroy() }
    }

    @Test fun continuousSidesRestFor600StepsWithoutIndependentWheelSupports() {
        NativePhysicsLoader.load()
        Rig().use { r ->
            r.floor(0f, 0f, 10f, 10f)
            r.steps(600)
            val start = r.chassis.getPhysicsLocation(null)
            repeat(600) {
                r.steps(1)
                val delta = r.chassis.getPhysicsLocation(null).subtract(start)
                val v = r.chassis.getLinearVelocity(null)
                assertTrue(kotlin.math.hypot(delta.x, delta.z) <= .05f, "drift=$delta")
                assertTrue(kotlin.math.hypot(v.x, v.z) <= .02f, "speed=$v")
                r.plates.forEach { p ->
                    assertTrue(r.supportCount(p) > 0, "side lost real support")
                    assertTrue(p.travel() in -.241f.. .181f, "travel=${p.travel()}")
                }
            }
            assertEquals(.74f, r.chassis.getPhysicsLocation(null).y, .025f)
            r.plates.forEach { p ->
                val points = r.supportPoints(p)
                assertTrue(points.size >= 3, "a centre-point support is not a continuous face: $points")
                assertTrue(points.maxOf { it.z } - points.minOf { it.z } > 5f, "missing fore/aft contact distribution: $points")
                assertTrue(points.maxOf { it.x } - points.minOf { it.x } > .3f, "missing footprint width: $points")
            }
        }
    }

    @Test fun suspensionHonoursAuthoredDampingAndBoundsItsElasticRow() {
        NativePhysicsLoader.load()
        Rig(4.7f).use { r ->
            val plate = r.plates[0]
            for (damping in listOf(0f, 700f, 1200f)) {
                plate.spring(21620f, 9.81f, 2.2f, damping, 1_000_000f, 1f / 60f)
                assertEquals(damping, plate.joint.get(MotorParam.Damping, 1), .001f,
                    "authored damping was replaced by an implicit ratio floor")
            }
            val forceLimit = 1000f
            plate.spring(21620f, 9.81f, 2.2f, 1200f, forceLimit, 1f / 60f)
            val stiffness = plate.joint.get(MotorParam.Stiffness, 1)
            val equilibrium = plate.joint.get(MotorParam.Equilibrium, 1)
            assertEquals(forceLimit, kotlin.math.abs(stiffness * (plate.travel() - equilibrium)), .01f,
                "elastic preload ignored the authored force limit")
        }
    }

    @Test fun explicitSideRatioHasSIUnitsAndDoesNotLockTheSuspension() {
        NativePhysicsLoader.load()
        assertEquals(2f * 4600f * (2f * kotlin.math.PI.toFloat() * 2.2f) * .65f,
            PlanarTrackSupport.dampingCoefficient(4600f, 2.2f, .65f), .01f)
        assertEquals(0f, PlanarTrackSupport.dampingCoefficient(4600f, 2.2f, 0f))
        Rig().use { r ->
            r.floor(0f, 0f, 10f, 10f); r.steps(600)
            val before = r.chassis.getPhysicsLocation(null).y
            r.chassis.applyCentralImpulse(Vector3f(0f, -r.chassis.mass * .5f, 0f))
            r.chassis.activate(true); r.plates.forEach { it.body.activate(true) }
            var maximumCompression = 0f
            repeat(120) {
                r.steps(1)
                maximumCompression = maxOf(maximumCompression, r.plates.maxOf { it.travel() })
            }
            assertTrue(maximumCompression > .005f, "damper locked out real travel: $maximumCompression")
            assertTrue(maximumCompression < .18f, "moderate impulse hit bump stop: $maximumCompression")
            assertEquals(before, r.chassis.getPhysicsLocation(null).y, .005f)
            assertTrue(kotlin.math.abs(r.chassis.getLinearVelocity(null).y) < .02f)
        }
    }

    @Test fun nativeSuspensionImpulseRespectsForceLimitWithoutTerrainOrStops() {
        NativePhysicsLoader.load()
        for (speed in listOf(-.2f, 0f, .2f)) {
            val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
            try {
                space.setGravity(Vector3f())
                val chassis = PhysicsRigidBody(BoxCollisionShape(1f), 9200f)
                val plate = PlanarTrackSupport(chassis, Vector3f(), .2f, 3f, 400f, 10f, 10f)
                chassis.isContactResponse = false; plate.body.isContactResponse = false
                space.add(chassis); space.add(plate.body); space.addJoint(plate.joint)
                plate.body.setLinearVelocity(Vector3f(0f, speed, 0f))
                val dt = 1f / 60f
                val limit = 1000f
                plate.spring(4600f, 9.81f, 2.2f, 1200f, limit, dt)
                space.update(dt, 0, false, true, false)
                val impulse = chassis.getLinearVelocity(null).y * chassis.mass
                assertTrue(kotlin.math.abs(impulse) <= limit * dt + .001f,
                    "speed=$speed suspension exceeded force budget: impulse=$impulse")
                assertTrue(impulse > 0f, "limited suspension supplied no preload")
                assertEquals(400f * speed,
                    impulse + plate.body.mass * plate.body.getLinearVelocity(null).y, .001f)
            } finally { space.destroy() }
        }
    }

    @Test fun realTriangleMeshSupportsTheFaceAcrossItsWidth() {
        NativePhysicsLoader.load()
        Rig().use { r ->
            r.meshFloor()
            r.steps(600)
            val start = r.chassis.getPhysicsLocation(null)
            repeat(600) { step ->
                r.steps(1)
                val delta = r.chassis.getPhysicsLocation(null).subtract(start)
                val v = r.chassis.getLinearVelocity(null)
                assertTrue(kotlin.math.hypot(delta.x, delta.z) <= .05f, "mesh drift step=$step d=$delta")
                assertTrue(kotlin.math.hypot(v.x, v.z) <= .02f, "mesh speed step=$step v=$v")
                r.plates.forEach { assertTrue(r.supportCount(it) > 0, "mesh lost live load step=$step") }
            }
            val restingPoints = r.plates.map { r.supportPoints(it) }
            // Triangle/convex contact need not load every corner simultaneously.
            // Prove transverse and fore/aft extent by reversing roll and pitch
            // load and requiring live impulses at BOTH ends of the face, not by
            // counting redundant rows: a two-triangle mesh fills one manifold
            // point per triangle, so a resting span alone understates the face.
            val lateral = r.plates.associateWith { mutableListOf<Float>() }
            val foreAft = r.plates.associateWith { mutableListOf<Float>() }
            for (axis in 0..1) for (sign in listOf(-1f, 1f)) {
                r.chassis.applyTorqueImpulse(
                    if (axis == 0) Vector3f(0f, 0f, sign * 3000f) else Vector3f(sign * 3000f, 0f, 0f))
                r.chassis.activate(true); r.plates.forEach { it.body.activate(true) }
                repeat(180) {
                    r.steps(1)
                    r.plates.forEach { plate ->
                        val inverse = plate.body.getPhysicsRotation(null).inverse().toRotationMatrix()
                        val centre = plate.body.getPhysicsLocation(null)
                        r.supportPoints(plate).forEach { point ->
                            val local = inverse.mult(point.subtract(centre), null)
                            lateral.getValue(plate).add(local.x)
                            foreAft.getValue(plate).add(local.z)
                        }
                    }
                }
            }
            lateral.forEach { (_, xs) ->
                assertTrue(xs.any { it < -.15f } && xs.any { it > .15f },
                    "mesh cannot shift real load across width: min=${xs.minOrNull()} max=${xs.maxOrNull()}")
            }
            foreAft.forEach { (_, zs) ->
                assertTrue(zs.any { it < -2f } && zs.any { it > 2f },
                    "mesh cannot shift real load along the face: min=${zs.minOrNull()} max=${zs.maxOrNull()}")
            }
            restingPoints.forEachIndexed { index, points ->
                assertTrue(points.size >= 2, "mesh plate$index lost live resting support: $points")
            }
        }
    }

    @Test fun removedTriangleMeshHasNoStaleSupportOrHiddenVerticalHold() {
        NativePhysicsLoader.load()
        Rig().use { r ->
            val mesh = r.meshFloor()
            r.steps(600)
            r.plates.forEach { assertTrue(r.supportCount(it) > 0) }
            val assembly = listOf(r.chassis) + r.plates.map { it.body }
            // Internal suspension/bump-stop impulses must cancel out. Check
            // whole-assembly momentum, not just the chassis' transient travel.
            assembly.forEach { it.setDamping(0f, 0f) }
            val mass = assembly.sumOf { it.mass.toDouble() }
            fun momentum() = assembly.sumOf { (it.mass * it.getLinearVelocity(null).y).toDouble() }
            val before = momentum()
            r.space.remove(mesh)
            assembly.forEach { it.activate(true) }
            r.steps(12)
            r.plates.forEach { assertEquals(0, r.supportCount(it), "removed mesh retained support") }
            assertEquals(before - mass * 9.81 * (12.0 / 60.0), momentum(), mass * .001,
                "airborne assembly did not receive gravity alone")
        }
    }

    @Test fun triangleMeshCarriesReversedPitchLoadsAtBothFootprintEnds() {
        NativePhysicsLoader.load()
        Rig().use { r ->
            r.meshFloor(); r.steps(600)
            val longitudinal = r.plates.associateWith { mutableListOf<Float>() }
            for (sign in listOf(-1f, 1f)) {
                r.chassis.applyTorqueImpulse(Vector3f(sign * 15000f, 0f, 0f))
                r.chassis.activate(true); r.plates.forEach { it.body.activate(true) }
                repeat(300) {
                    r.steps(1)
                    r.plates.forEach { plate ->
                        val inverse = plate.body.getPhysicsRotation(null).inverse().toRotationMatrix()
                        val centre = plate.body.getPhysicsLocation(null)
                        longitudinal.getValue(plate).addAll(r.supportPoints(plate)
                            .map { inverse.mult(it.subtract(centre), null).z })
                    }
                }
                assertTrue(kotlin.math.abs(r.chassis.getAngularVelocity(null).x) < .02f,
                    "mesh did not arrest pitch: ${r.chassis.getAngularVelocity(null)}")
                assertTrue(kotlin.math.abs(r.chassis.getPhysicsRotation(null).x) < .01f,
                    "mesh did not recover pitch: ${r.chassis.getPhysicsRotation(null)}")
            }
            longitudinal.forEach { (_, zs) ->
                assertTrue(zs.any { it < -2.5f } && zs.any { it > 2.5f },
                    "mesh cannot carry pitch at footprint ends: min=${zs.minOrNull()} max=${zs.maxOrNull()}")
            }
        }
    }

    @Test fun pitchImpulseIsResistedByContactsAcrossTheFaceNotACentreForce() {
        NativePhysicsLoader.load()
        Rig().use { r ->
            r.floor(0f, 0f, 10f, 10f); r.steps(600)
            r.chassis.applyTorqueImpulse(Vector3f(15000f, 0f, 0f))
            r.chassis.activate(true); r.plates.forEach { it.body.activate(true) }
            r.steps(300)
            assertTrue(kotlin.math.abs(r.chassis.getAngularVelocity(null).x) < .02f)
            assertTrue(kotlin.math.abs(r.chassis.getPhysicsRotation(null).x) < .01f)
            r.plates.forEach { assertTrue(r.supportCount(it) > 0) }
        }
    }

    @Test fun movingDynamicSurfaceReceivesEqualAndOppositeDriveImpulse() {
        NativePhysicsLoader.load()
        Rig().use { r ->
            r.chassis.setDamping(0f, 0f)
            val platform = r.floor(0f, 0f, 20f, 30f, mass = 20000f)
            r.floor(0f, 0f, 21f, 31f, top = -1f).friction = 0f
            platform.friction = 0f
            r.steps(600)
            r.engine = true; r.demand = 1f
            r.chassis.activate(true); r.plates.forEach { it.body.activate(true) }; platform.activate(true)
            r.steps(60)
            val vehicleMomentum = r.chassis.getLinearVelocity(null).z * r.chassis.mass +
                r.plates.sumOf { (it.body.getLinearVelocity(null).z * it.body.mass).toDouble() }
            val platformMomentum = platform.getLinearVelocity(null).z * platform.mass
            assertTrue(vehicleMomentum > 1000f, "no actual drive impulse")
            assertTrue(platformMomentum < -1000f, "dynamic ground received no reaction")
            assertEquals(0.0, vehicleMomentum + platformMomentum, 20.0, "horizontal momentum was not conserved")
        }
    }

    @Test fun continuousFaceCrossesSectionSeamAtMinecraftCoordinates() {
        NativePhysicsLoader.load()
        Rig().use { r ->
            r.chassis.setPhysicsLocation(Vector3f(1024f, 66.5f, 1024f))
            r.plates.forEach { it.reset(r.chassis.getPhysicsLocation(null), Quaternion(), Vector3f(), Vector3f()) }
            r.floor(1024f, 1016f, 8f, 8f, top = 65f)
            r.floor(1024f, 1032f, 8f, 8f, top = 65f)
            r.steps(600)
            val origin = r.chassis.getPhysicsLocation(null)
            repeat(600) {
                r.steps(1)
                val delta = r.chassis.getPhysicsLocation(null).subtract(origin)
                val v = r.chassis.getLinearVelocity(null)
                assertTrue(kotlin.math.hypot(delta.x, delta.z) <= .05f, "seam drift=$delta")
                assertTrue(kotlin.math.hypot(v.x, v.z) <= .02f, "seam velocity=$v")
                r.plates.forEach { assertTrue(r.supportCount(it) > 0) }
            }
        }
    }

    @Test fun explicitGravityAndCcdDoNotDropRestingSurfaceContacts() {
        NativePhysicsLoader.load()
        Rig().use { r ->
            r.space.setGravity(Vector3f())
            r.explicitGravity = true
            r.space.solverInfo.setMode(SolverMode.SIMD or SolverMode.WarmStart or SolverMode.CacheFriendly or
                SolverMode.CacheDirection or SolverMode.ArticulatedWarmStart or SolverMode.Interleave or SolverMode.Use2Directions)
            r.plates.forEach {
                it.body.ccdMotionThreshold = .02f
                it.body.ccdSweptSphereRadius = .02f
                it.body.setSleepingThresholds(.02f, .02f)
            }
            r.chassis.setPhysicsLocation(Vector3f(1024.5f, 66.5f, 1024.5f))
            r.plates.forEach { it.reset(r.chassis.getPhysicsLocation(null), Quaternion(), Vector3f(), Vector3f()) }
            r.floor(1024f, 1016f, 8f, 8f, top = 65f)
            r.floor(1024f, 1032f, 8f, 8f, top = 65f)
            r.steps(240)
            r.wakeChassisOnly = false
            repeat(900) { step ->
                r.steps(1)
                r.plates.forEach { assertTrue(r.supportCount(it) > 0, "contact lost step=$step travel=${it.travel()} v=${it.body.getLinearVelocity(null)}") }
            }
        }
    }

    @Test fun heavySurfaceOnSectionBoxesRemainsSupportedAcrossSleepDeadline() {
        NativePhysicsLoader.load()
        PhysicsBody.setDeactivationDeadline(3f)
        Rig(4.7f).use { r ->
            r.space.setGravity(Vector3f())
            r.space.isForceUpdateAllAabbs = false
            r.explicitGravity = true
            r.repeatContactResponse = true
            r.holdInitially = 30
            r.wakeChassisOnly = true
            r.space.solverInfo.setMode(SolverMode.SIMD or SolverMode.WarmStart or SolverMode.CacheFriendly or
                SolverMode.CacheDirection or SolverMode.ArticulatedWarmStart or SolverMode.Interleave or SolverMode.Use2Directions)
            r.chassis.setSleepingThresholds(.05f, .05f)
            r.plates.forEach {
                it.body.ccdMotionThreshold = .02f
                it.body.ccdSweptSphereRadius = .02f
                it.body.setDamping(.01f, .15f)
                it.body.setSleepingThresholds(.02f, .02f)
            }
            r.chassis.setPhysicsLocation(Vector3f(1600.5f, 66.5f, 1024.5f))
            r.plates.forEach { it.reset(r.chassis.getPhysicsLocation(null), Quaternion(), Vector3f(), Vector3f()) }
            for (cx in listOf(1592f, 1608f)) for (cz in listOf(1016f, 1032f)) r.sectionFloor(cx, cz)
            r.steps(240)
            r.wakeChassisOnly = false
            repeat(900) { step ->
                r.steps(1)
                r.plates.forEach { assertTrue(r.supportCount(it) > 0,
                    "compound contact lost step=$step travel=${it.travel()} active=${it.body.isActive} v=${it.body.getLinearVelocity(null)}") }
            }
        }
    }

    @Test fun sectionFloorReplacementDoesNotRemoveSupportForASubstep() {
        NativePhysicsLoader.load()
        Rig(4.7f).use { r ->
            r.space.isForceUpdateAllAabbs = false
            r.chassis.setPhysicsLocation(Vector3f(1600.5f, 66.5f, 1024.5f))
            r.plates.forEach { it.reset(r.chassis.getPhysicsLocation(null), Quaternion(), Vector3f(), Vector3f()) }
            val floors = mutableListOf<PhysicsRigidBody>()
            for (cx in listOf(1592f, 1608f)) for (cz in listOf(1016f, 1032f)) floors += r.sectionFloor(cx, cz)
            r.steps(300)
            repeat(600) { step ->
                if (step % 30 == 0) {
                    val old = floors[3]
                    r.space.remove(old)
                    floors[3] = r.sectionFloor(1608f, 1032f)
                }
                r.steps(1)
                r.plates.forEach { assertTrue(r.supportCount(it) > 0, "replacement lost support step=$step travel=${it.travel()}") }
            }
        }
    }

    @Test fun tinyUpwardSurfaceImpulseDoesNotTurnIntoAFullSpringDrop() {
        NativePhysicsLoader.load()
        Rig(4.7f).use { r ->
            r.chassis.setPhysicsLocation(Vector3f(1600.5f, 66.5f, 1024.5f))
            r.plates.forEach { it.reset(r.chassis.getPhysicsLocation(null), Quaternion(), Vector3f(), Vector3f()) }
            for (cx in listOf(1592f, 1608f)) for (cz in listOf(1016f, 1032f)) r.sectionFloor(cx, cz)
            r.steps(300)
            val start = r.chassis.getPhysicsLocation(null)
            r.plates[0].body.applyCentralImpulse(Vector3f(0f, r.plates[0].body.mass * .001f, 0f))
            // A tiny geometric separation can also come from solver rounding
            // at Minecraft coordinates; it must not become a centimetre drop.
            val separation = 65f - r.plates[0].body.boundingBox(null).getMin(null).y + .00002f
            r.plates[0].body.setPhysicsLocation(r.plates[0].body.getPhysicsLocation(null).add(0f, separation, 0f))
            r.plates[0].body.activate(true)
            repeat(600) { step ->
                r.steps(1)
                val delta = r.chassis.getPhysicsLocation(null).subtract(start)
                val velocity = r.chassis.getLinearVelocity(null)
                assertTrue(kotlin.math.hypot(delta.x, delta.z) <= .05f, "tiny impulse drift step=$step d=$delta")
                assertTrue(kotlin.math.hypot(velocity.x, velocity.z) <= .02f, "tiny impulse speed step=$step v=$velocity")
                r.plates.forEach { assertTrue(it.travel() > -.01f, "tiny impulse drop step=$step travel=${it.travel()}") }
            }
        }
    }

    @Test fun slightlyRotatedAssemblyKeepsItsFullFaceContacts() {
        NativePhysicsLoader.load()
        for (heading in 0..3) Rig(4.7f).use { r ->
            val rotation = Quaternion().fromAngles(.0001f, heading * kotlin.math.PI.toFloat() / 2f + .0001f, -.0001f)
            r.chassis.setPhysicsLocation(Vector3f(1600.5f, 66.5f, 1024.656f))
            r.chassis.setPhysicsRotation(rotation)
            r.plates.forEach { it.reset(r.chassis.getPhysicsLocation(null), rotation, Vector3f(), Vector3f()) }
            for (cx in listOf(1592f, 1608f)) for (cz in listOf(1016f, 1032f)) r.sectionFloor(cx, cz)
            r.steps(300)
            val start = r.chassis.getPhysicsLocation(null)
            repeat(600) { step ->
                r.steps(1)
                val delta = r.chassis.getPhysicsLocation(null).subtract(start)
                val velocity = r.chassis.getLinearVelocity(null)
                assertTrue(kotlin.math.hypot(delta.x, delta.z) <= .05f, "heading=$heading step=$step drift=$delta")
                assertTrue(kotlin.math.hypot(velocity.x, velocity.z) <= .02f, "heading=$heading step=$step speed=$velocity")
                r.plates.forEach { assertTrue(r.supportCount(it) > 0,
                    "heading=$heading step=$step travel=${it.travel()} velocity=${it.body.getLinearVelocity(null)}") }
            }
        }
    }

    @Test fun compoundChildCullingReproducesTheCentimetreDropControl() {
        NativePhysicsLoader.load()
        Rig(4.7f).use { r ->
            r.chassis.setPhysicsLocation(Vector3f(1600.5f, 66.5f, 1024.5f))
            r.plates.forEach { it.reset(r.chassis.getPhysicsLocation(null), Quaternion(), Vector3f(), Vector3f()) }
            for (cx in listOf(1592f, 1608f)) for (cz in listOf(1016f, 1032f)) r.sectionFloor(cx, cz, compound = true)
            r.steps(300)
            val plate = r.plates[0]
            val separation = 65f - plate.body.boundingBox(null).getMin(null).y + .00002f
            plate.body.setPhysicsLocation(plate.body.getPhysicsLocation(null).add(0f, separation, 0f))
            plate.body.activate(true)
            r.steps(1)
            // Positive control for the old native compound path, never used as
            // the production acceptance assertion. The corrected path above
            // still requires no centimetre drop and the original drift limits.
            assertTrue(plate.travel() < -.025f, "control no longer reproduces compound culling: ${plate.travel()}")
            assertEquals(0, r.supportCount(plate))
        }
    }

    @Test fun slopedFaceUsesActualTerrainNormalAndRemainsStable() {
        NativePhysicsLoader.load()
        Rig().use { r ->
            val rotation = Quaternion().fromAngles(-.15f, 0f, 0f)
            val normal = rotation.toRotationMatrix().mult(Vector3f.UNIT_Y, Vector3f())
            val ramp = PhysicsRigidBody(BoxCollisionShape(Vector3f(10f, .5f, 10f)).apply { margin = .001f }, 0f)
            ramp.setPhysicsRotation(rotation)
            ramp.setPhysicsLocation(normal.mult(-.5f))
            r.space.add(ramp)
            r.chassis.setPhysicsRotation(rotation)
            r.plates.forEach { it.reset(r.chassis.getPhysicsLocation(null), rotation, Vector3f(), Vector3f()) }
            r.steps(600)
            val start = r.chassis.getPhysicsLocation(null)
            repeat(600) { step ->
                r.steps(1)
                val delta = r.chassis.getPhysicsLocation(null).subtract(start)
                val velocity = r.chassis.getLinearVelocity(null)
                assertTrue(kotlin.math.hypot(delta.x, delta.z) <= .05f, "ramp step=$step drift=$delta")
                assertTrue(kotlin.math.hypot(velocity.x, velocity.z) <= .02f, "ramp step=$step speed=$velocity")
                r.plates.forEach { assertTrue(r.supportCount(it) > 0, "ramp lost real face support") }
            }
            val contacts = r.plates.flatMap { r.supportPoints(it) }
            assertTrue(contacts.maxOf { it.y } - contacts.minOf { it.y } > .7f,
                "the sloped face was flattened: $contacts")
        }
    }

    @Test fun adjacentWallDoesNotReplaceFloorSupportAndBumpHasDistributedReactions() {
        NativePhysicsLoader.load()
        Rig().use { r ->
            r.floor(0f, 0f, 10f, 10f)
            // A 5 cm ledge under the front half, not a per-wheel probe.
            r.floor(0f, 2f, 3f, 1f, top = .05f)
            val wall = PhysicsRigidBody(BoxCollisionShape(Vector3f(.1f, 2f, 6f)).apply { margin = .001f }, 0f)
            wall.setPhysicsLocation(Vector3f(1.61f, 2f, 0f))
            r.space.add(wall)
            r.steps(600)
            r.plates.forEach { plate ->
                assertTrue(r.supportCount(plate) > 0, "wall became the only support")
                val points = r.supportPoints(plate)
                // Rear edge z=-3 and ledge edge z=1 are exactly 4 m apart;
                // a strict >4 assertion incorrectly fails on solver rounding.
                assertTrue(points.any { it.z < -2.9f } && points.any { it.z > .9f },
                     "ledge lost fore/aft load distribution: $points")
                assertTrue(points.maxOf { it.x } - points.minOf { it.x } > .3f,
                    "ledge lost footprint width: $points")
                assertTrue(points.maxOf { it.y } - points.minOf { it.y } > .04f,
                    "ledge was flattened: $points")
            }
            assertTrue(r.chassis.getPhysicsRotation(null).x.isFinite())
            assertTrue(r.chassis.getLinearVelocity(null).length() < .02f)
            val start = r.chassis.getPhysicsLocation(null)
            repeat(600) { step ->
                r.steps(1)
                val delta = r.chassis.getPhysicsLocation(null).subtract(start)
                val velocity = r.chassis.getLinearVelocity(null)
                assertTrue(kotlin.math.hypot(delta.x, delta.z) <= .05f, "ledge step=$step drift=$delta")
                assertTrue(kotlin.math.hypot(velocity.x, velocity.z) <= .02f, "ledge step=$step speed=$velocity")
                r.plates.forEach { assertTrue(r.supportCount(it) > 0, "ledge lost real support step=$step") }
            }
        }
    }

    @Test fun footprintBridgesLocalHoleButLosesLoadWhenEntireSideIsRemoved() {
        NativePhysicsLoader.load()
        Rig().use { r ->
            r.floor(1.3f, 0f, .4f, 8f)
            val rear = r.floor(-1.3f, -2.5f, .4f, 1.5f)
            val front = r.floor(-1.3f, 2.5f, .4f, 1.5f)
            r.steps(600)
            assertTrue(r.supportCount(r.plates[0]) > 0, "a local hole must not remove the whole plane")
            r.space.remove(rear); r.space.remove(front)
            r.steps(12)
            assertEquals(0, r.supportCount(r.plates[0]), "removed terrain retained native load")
            assertTrue(r.supportCount(r.plates[1]) > 0, "other side lost support")
        }
    }

    @Test fun engineOffCoastsButBrakeStopsAndDriveUsesSurfaceMotion() {
        NativePhysicsLoader.load()
        Rig().use { r ->
            r.floor(0f, 0f, 20f, 30f); r.steps(600)
            r.engine = true; r.demand = 1f
            r.chassis.activate(true); r.plates.forEach { it.body.activate(true) }
            r.steps(120)
            assertTrue(r.chassis.getLinearVelocity(null).z > 1f, "drive did not move the surface")
            r.engine = false; r.demand = 0f
            val before = r.chassis.getLinearVelocity(null).z
            r.steps(30)
            assertTrue(r.chassis.getLinearVelocity(null).z > before * .5f, "coasting was turned into parking")
            r.brake = true; r.steps(120)
            assertTrue(kotlin.math.abs(r.chassis.getLinearVelocity(null).z) < .02f)
        }
    }
}
