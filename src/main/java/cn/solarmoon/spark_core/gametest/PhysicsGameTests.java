package cn.solarmoon.spark_core.gametest;

import cn.solarmoon.spark_core.SparkCore;
import cn.solarmoon.spark_core.physics.SparkPhysics;
import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import cn.solarmoon.spark_core.physics.level.PhysicsLevel;
import cn.solarmoon.spark_core.physics.level.PhysicsLevelState;
import cn.solarmoon.spark_core.physics.query.TerrainRayHit;
import cn.solarmoon.spark_core.physics.query.TerrainRayQuery;
import cn.solarmoon.spark_core.util.PPhase;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Vector3f;
import kotlin.Unit;
import cn.solarmoon.spark_core.physics.PhysicsHost;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

@GameTestHolder(SparkCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhysicsGameTests {
    private PhysicsGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void asyncTerrainCollision(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        AtomicReference<PhysicsRigidBody> bodyRef = new AtomicReference<>();
        BlockPos floor = helper.absolutePos(BlockPos.ZERO);
        float startY = floor.getY() + 4.0f;

        physics.submitImmediateTask(PPhase.ALL, () -> {
            PhysicsRigidBody body = new PhysicsRigidBody(new BoxCollisionShape(.25f), 1f);
            body.setPhysicsLocation(new Vector3f(floor.getX() + .5f, startY, floor.getZ() + .5f));
            body.setCollisionGroup(CollisionGroups.PHYSICS_BODY);
            body.setCollideWithGroups(CollisionGroups.TERRAIN);
            body.setKinematic(true);
            physics.addBody(body);
            bodyRef.set(body);
            return Unit.INSTANCE;
        });

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        physics.getState() == PhysicsLevelState.RUNNING,
                        "PhysicsLevel did not enter RUNNING"))
                .thenWaitUntil(() -> helper.assertTrue(
                        bodyRef.get() != null && physics.getLatestFrame().getBodies().containsKey(bodyRef.get()),
                        "Rigid body was not published in an immutable physics snapshot"))
                .thenWaitUntil(() -> helper.assertTrue(
                        physics.getTerrainManager().getActiveSectionCount() > 0,
                        "No terrain section became active: " + physics.getTerrainManager().getStats()))
                .thenExecute(() -> physics.submitImmediateTask(PPhase.ALL, () -> {
                    bodyRef.get().setKinematic(false);
                    bodyRef.get().activate(true);
                    return Unit.INSTANCE;
                }))
                .thenIdle(80)
                .thenExecute(() -> {
                    PhysicsRigidBody body = bodyRef.get();
                    var frame = physics.getLatestFrame().getBodies().get(body);
                    helper.assertTrue(frame != null, "Rigid body vanished from the latest snapshot");
                    double terrainTop = Double.NEGATIVE_INFINITY;
                    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                    for (int y = floor.getY() - 4; y <= floor.getY() + 3; y++) {
                        cursor.set(floor.getX(), y, floor.getZ());
                        var shape = helper.getLevel().getBlockState(cursor)
                                .getCollisionShape(helper.getLevel(), cursor);
                        if (!shape.isEmpty()) {
                            terrainTop = Math.max(terrainTop, y + shape.max(Direction.Axis.Y));
                        }
                    }
                    helper.assertTrue(Double.isFinite(terrainTop),
                            "No Minecraft collision surface was found under the test body");
                    double expectedRestY = terrainTop + .25;
                    helper.assertTrue(frame.getPosition().y < startY - 1.0,
                            "Gravity did not move the rigid body: y=" + frame.getPosition().y);
                    helper.assertTrue(Math.abs(frame.getPosition().y - expectedRestY) < .35,
                            "Rigid body did not settle on generated Minecraft terrain: y="
                                    + frame.getPosition().y + ", expected=" + expectedRestY);
                    helper.assertTrue(Math.abs(frame.getLinearVelocity().y) < .1,
                            "Rigid body reached terrain but did not settle: vy="
                                    + frame.getLinearVelocity().y);
                    physics.removeBody(body);
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void pawnBodyUsesSpawnPosition(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos spawn = new BlockPos(2, 1, 2);
        BlockPos absolute = helper.absolutePos(spawn);
        Pig pig = helper.spawn(EntityType.PIG, spawn);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        SparkPhysics.host(pig).getAllPhysicsBodies().containsKey("body"),
                        "Pawn body was not registered on the entity host"))
                .thenWaitUntil(() -> {
                    PhysicsCollisionObject body = SparkPhysics.host(pig).getAllPhysicsBodies().get("body");
                    var frame = physics.getLatestFrame().getBodies().get(body);
                    helper.assertTrue(frame != null, "Pawn body was not published in the physics snapshot");
                    helper.assertTrue(frame.getPosition().distanceToSqr(absolute.getX() + .5, frame.getPosition().y, absolute.getZ() + .5) < 4.0,
                            "Pawn body spawn location drifted too far: " + frame.getPosition());
                    helper.assertTrue(frame.getPosition().distanceToSqr(Vec3.ZERO) > 1.0,
                            "Pawn body remained at the Bullet origin: " + frame.getPosition());
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void pawnBodyRemovedWhenEntityLeaves(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(1, 1, 1));
        AtomicReference<PhysicsCollisionObject> bodyRef = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    PhysicsHost host = SparkPhysics.host(pig);
                    PhysicsCollisionObject body = host.getAllPhysicsBodies().get("body");
                    helper.assertTrue(body != null, "Pawn body was not created before discard");
                    bodyRef.set(body);
                })
                .thenExecute(pig::discard)
                .thenWaitUntil(() -> helper.assertTrue(
                        SparkPhysics.host(pig).getAllPhysicsBodies().isEmpty(),
                        "Entity host still holds physics bodies after leave"))
                .thenWaitUntil(() -> helper.assertTrue(
                        !physics.getLatestFrame().getBodies().containsKey(bodyRef.get()),
                        "Removed pawn body remained in the published snapshot"))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void springJointHoldsMassAboveTerrain(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos floor = helper.absolutePos(BlockPos.ZERO);
        float anchorY = floor.getY() + 4.0f;
        AtomicReference<PhysicsRigidBody> massRef = new AtomicReference<>();

        physics.submitImmediateTask(PPhase.ALL, () -> {
            PhysicsRigidBody anchor = new PhysicsRigidBody(new BoxCollisionShape(.2f), 0f);
            anchor.setPhysicsLocation(new Vector3f(floor.getX() + .5f, anchorY, floor.getZ() + .5f));
            anchor.setCollisionGroup(CollisionGroups.PHYSICS_BODY);
            anchor.setCollideWithGroups(CollisionGroups.NONE);
            physics.addBody(anchor);

            PhysicsRigidBody mass = new PhysicsRigidBody(new BoxCollisionShape(.2f), 2f);
            mass.setPhysicsLocation(new Vector3f(floor.getX() + .5f, anchorY - 1.0f, floor.getZ() + .5f));
            mass.setCollisionGroup(CollisionGroups.PHYSICS_BODY);
            mass.setCollideWithGroups(CollisionGroups.TERRAIN);
            physics.addBody(mass);
            physics.addJoint(cn.solarmoon.spark_core.physics.body.PhysicsJoints.springBetween(
                    anchor,
                    mass,
                    new Vector3f(0f, 0f, 0f),
                    new Vector3f(0f, 0f, 0f),
                    -0.4f,
                    0.4f,
                    80f,
                    8f
            ));
            massRef.set(mass);
            return Unit.INSTANCE;
        });

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(massRef.get() != null, "Spring mass was not created"))
                .thenIdle(40)
                .thenExecute(() -> {
                    var frame = physics.getLatestFrame().getBodies().get(massRef.get());
                    helper.assertTrue(frame != null, "Spring mass vanished from the snapshot");
                    helper.assertTrue(frame.getPosition().y > floor.getY() + 1.2,
                            "Spring mass fell through the joint onto terrain: y=" + frame.getPosition().y);
                    helper.assertTrue(frame.getPosition().y < anchorY - 0.2,
                            "Spring mass did not hang below the anchor: y=" + frame.getPosition().y);
                    physics.removeBody(massRef.get());
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void kinematicPoseUpdatesOnPhysicsThread(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos floor = helper.absolutePos(BlockPos.ZERO);
        AtomicReference<PhysicsRigidBody> bodyRef = new AtomicReference<>();
        physics.submitImmediateTask(PPhase.ALL, () -> {
            PhysicsRigidBody body = new PhysicsRigidBody(new BoxCollisionShape(.2f), 0f);
            body.setKinematic(true);
            body.setCollisionGroup(CollisionGroups.PHYSICS_BODY);
            body.setCollideWithGroups(CollisionGroups.NONE);
            physics.addBody(body);
            cn.solarmoon.spark_core.physics.body.PhysicsKinematicsKt.applyKinematicPose(
                    physics,
                    body,
                    new cn.solarmoon.spark_core.physics.body.KinematicBodyPose(
                            new Vector3f(floor.getX() + 2f, floor.getY() + 3f, floor.getZ() + 1f),
                            new com.jme3.math.Quaternion(),
                            new Vector3f(1f, 0f, 0f),
                            new Vector3f()
                    )
            );
            bodyRef.set(body);
            return Unit.INSTANCE;
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(bodyRef.get() != null, "kinematic body missing"))
                .thenWaitUntil(() -> {
                    var frame = physics.getLatestFrame().getBodies().get(bodyRef.get());
                    helper.assertTrue(frame != null, "kinematic pose was not published");
                    helper.assertTrue(Math.abs(frame.getPosition().x - (floor.getX() + 2f)) < 0.05,
                            "kinematic location was not applied: " + frame.getPosition());
                })
                .thenExecute(() -> physics.removeBody(bodyRef.get()))
                .thenSucceed();
    }


    @GameTest(template = "empty", timeoutTicks = 80)
    public static void guidedWheelAxleHasNoBuiltInSpring(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos floor = helper.absolutePos(BlockPos.ZERO);
        AtomicReference<com.jme3.bullet.joints.New6Dof> jointRef = new AtomicReference<>();
        AtomicReference<PhysicsRigidBody> wheelRef = new AtomicReference<>();
        physics.submitImmediateTask(PPhase.ALL, () -> {
            PhysicsRigidBody chassis = new PhysicsRigidBody(new BoxCollisionShape(.4f), 5000f);
            chassis.setKinematic(true);
            chassis.setPhysicsLocation(new Vector3f(floor.getX() + .5f, floor.getY() + 2f, floor.getZ() + .5f));
            chassis.setCollisionGroup(CollisionGroups.PHYSICS_BODY);
            chassis.setCollideWithGroups(CollisionGroups.NONE);
            physics.addBody(chassis);

            PhysicsRigidBody wheel = new PhysicsRigidBody(new BoxCollisionShape(.12f), 180f);
            wheel.setPhysicsLocation(new Vector3f(floor.getX() + .5f, floor.getY() + 1f, floor.getZ() + .5f));
            wheel.setCollisionGroup(CollisionGroups.WHEEL);
            wheel.setCollideWithGroups(CollisionGroups.NONE);
            physics.addBody(wheel);

            var joint = cn.solarmoon.spark_core.physics.body.PhysicsJoints.guidedWheelAxle(
                    chassis, wheel,
                    new Vector3f(0f, -1f, 0f), new Vector3f(),
                    new com.jme3.math.Matrix3f(), new com.jme3.math.Matrix3f(),
                    -0.30f, 0.22f
            );
            physics.addJoint(joint);
            jointRef.set(joint);
            wheelRef.set(wheel);
            return Unit.INSTANCE;
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(jointRef.get() != null, "guidedWheelAxle was not created"))
                .thenExecute(() -> {
                    var joint = jointRef.get();
                    helper.assertTrue(!joint.isSpringEnabled(cn.solarmoon.spark_core.physics.body.PhysicsJoints.LIN_Y),
                            "guidedWheelAxle must not enable the built-in New6Dof spring");
                    helper.assertTrue(Math.abs(joint.get(com.jme3.bullet.joints.motors.MotorParam.LowerLimit,
                            cn.solarmoon.spark_core.physics.body.PhysicsJoints.LIN_Y) + 0.30f) < 1.0e-6f,
                            "guidedWheelAxle lower travel is wrong");
                    helper.assertTrue(Math.abs(joint.get(com.jme3.bullet.joints.motors.MotorParam.UpperLimit,
                            cn.solarmoon.spark_core.physics.body.PhysicsJoints.LIN_Y) - 0.22f) < 1.0e-6f,
                            "guidedWheelAxle upper travel is wrong");
                    physics.removeJoint(jointRef.get());
                    physics.removeBody(wheelRef.get());
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void wheelSuspensionDampingParameterIsClampedToSolverRange(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos floor = helper.absolutePos(BlockPos.ZERO);
        AtomicReference<com.jme3.bullet.joints.New6Dof> jointRef = new AtomicReference<>();
        physics.submitImmediateTask(PPhase.ALL, () -> {
            PhysicsRigidBody chassis = new PhysicsRigidBody(new BoxCollisionShape(.4f), 0f);
            chassis.setKinematic(true);
            chassis.setPhysicsLocation(new Vector3f(floor.getX() + .5f, floor.getY() + 2f, floor.getZ() + .5f));
            chassis.setCollisionGroup(CollisionGroups.PHYSICS_BODY);
            chassis.setCollideWithGroups(CollisionGroups.NONE);
            physics.addBody(chassis);

            PhysicsRigidBody wheel = new PhysicsRigidBody(new BoxCollisionShape(.12f), 180f);
            wheel.setPhysicsLocation(new Vector3f(floor.getX() + .5f, floor.getY() + 1f, floor.getZ() + .5f));
            wheel.setCollisionGroup(CollisionGroups.WHEEL);
            wheel.setCollideWithGroups(CollisionGroups.NONE);
            physics.addBody(wheel);

            var joint = cn.solarmoon.spark_core.physics.body.PhysicsJoints.wheelSuspension(
                    chassis, wheel,
                    new Vector3f(0f, -1f, 0f), new Vector3f(),
                    new com.jme3.math.Matrix3f(), new com.jme3.math.Matrix3f(),
                    -0.30f, 0.22f, 180000f, 60000f, 0f
            );
            physics.addJoint(joint);
            jointRef.set(joint);
            return Unit.INSTANCE;
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(jointRef.get() != null, "wheelSuspension was not created"))
                .thenExecute(() -> {
                    var joint = jointRef.get();
                    float solverDamping = joint.get(com.jme3.bullet.joints.motors.MotorParam.Damping,
                            cn.solarmoon.spark_core.physics.body.PhysicsJoints.LIN_Y);
                    helper.assertTrue(solverDamping >= 0f && solverDamping <= 1f,
                            "wheelSuspension must clamp damping to the dimensionless solver range, got " + solverDamping);
                    physics.removeJoint(jointRef.get());
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void suspensionCarrierLocksYaw(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos floor = helper.absolutePos(BlockPos.ZERO);
        AtomicReference<PhysicsRigidBody> sprungRef = new AtomicReference<>();
        physics.submitImmediateTask(PPhase.ALL, () -> {
            com.jme3.math.Quaternion yaw = new com.jme3.math.Quaternion().fromAngles(0f, (float) Math.PI / 2f, 0f);
            PhysicsRigidBody controller = new PhysicsRigidBody(new BoxCollisionShape(.15f), 0f);
            controller.setKinematic(true);
            controller.setPhysicsLocation(new Vector3f(floor.getX() + .5f, floor.getY() + 4f, floor.getZ() + .5f));
            controller.setPhysicsRotation(yaw);
            controller.setCollisionGroup(CollisionGroups.PHYSICS_BODY);
            controller.setCollideWithGroups(CollisionGroups.NONE);
            physics.addBody(controller);

            PhysicsRigidBody sprung = new PhysicsRigidBody(new BoxCollisionShape(.3f), 40f);
            sprung.setPhysicsLocation(new Vector3f(floor.getX() + .5f, floor.getY() + 4f, floor.getZ() + .5f));
            sprung.setPhysicsRotation(yaw);
            sprung.setCollisionGroup(CollisionGroups.PHYSICS_BODY);
            sprung.setCollideWithGroups(CollisionGroups.NONE);
            physics.addBody(sprung);
            physics.addJoint(cn.solarmoon.spark_core.physics.body.PhysicsJoints.suspensionCarrier(
                    controller, sprung,
                    new Vector3f(), new Vector3f(),
                    new com.jme3.math.Matrix3f(), new com.jme3.math.Matrix3f(),
                    -0.6f, 0.6f, -0.4f, 0.4f
            ));
            sprungRef.set(sprung);
            return Unit.INSTANCE;
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(sprungRef.get() != null, "sprung body missing"))
                .thenIdle(20)
                .thenExecute(() -> {
                    var frame = physics.getLatestFrame().getBodies().get(sprungRef.get());
                    helper.assertTrue(frame != null, "sprung body vanished");
                    org.joml.Vector3f euler = frame.getRotation().getEulerAnglesYXZ(new org.joml.Vector3f());
                    helper.assertTrue(Math.abs(Math.abs(euler.y) - (float) Math.PI / 2f) < 0.25f,
                            "carrier failed to keep yaw near 90 degrees: " + euler.y);
                    physics.removeBody(sprungRef.get());
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void terrainRaycastIsThreadConfinedAndFiltersNonTerrain(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos floor = helper.absolutePos(new BlockPos(1, 0, 1));
        helper.setBlock(new BlockPos(1, 0, 1), net.minecraft.world.level.block.Blocks.STONE);
        AtomicReference<PhysicsRigidBody> blockerRef = new AtomicReference<>();
        AtomicReference<TerrainRayHit> hitRef = new AtomicReference<>();

        boolean rejectedMainThread = false;
        try {
            physics.raycastClosestTerrain(new TerrainRayQuery(0, 1, 0, 0, -1, 0));
        } catch (IllegalStateException expected) {
            rejectedMainThread = true;
        }
        helper.assertTrue(rejectedMainThread, "terrain raycast accepted a non-physics thread");

        physics.submitImmediateTask(PPhase.PRE, () -> {
            PhysicsRigidBody blocker = new PhysicsRigidBody(new BoxCollisionShape(.25f), 1f);
            blocker.setKinematic(true);
            blocker.setPhysicsLocation(new Vector3f(floor.getX() + .5f, floor.getY() + 3f, floor.getZ() + .5f));
            blocker.setCollisionGroup(CollisionGroups.PHYSICS_BODY);
            blocker.setCollideWithGroups(CollisionGroups.TERRAIN);
            physics.addBody(blocker);
            blockerRef.set(blocker);
            return Unit.INSTANCE;
        });

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        physics.getTerrainManager().getActiveSectionCount() > 0,
                        "terrain did not activate for raycast"))
                .thenWaitUntil(() -> {
                    physics.submitImmediateTask(PPhase.PRE, () -> {
                        hitRef.set(physics.raycastClosestTerrain(new TerrainRayQuery(
                                floor.getX() + .5f, floor.getY() + 5f, floor.getZ() + .5f,
                                floor.getX() + .5f, floor.getY() - 2f, floor.getZ() + .5f
                        )));
                        return Unit.INSTANCE;
                    });
                    helper.assertTrue(hitRef.get() != null, "terrain ray produced no terrain hit");
                })
                .thenExecute(() -> {
                    TerrainRayHit hit = hitRef.get();
                    helper.assertTrue(hit.getHitFraction() > .45f,
                            "closer non-terrain blocker was not filtered: " + hit);
                    helper.assertTrue(Math.abs(hit.getPointY() - (floor.getY() + 1f)) < .1f,
                            "fraction-to-point conversion is wrong: " + hit);
                    helper.assertTrue(hit.getNormalY() > .9f, "terrain normal is not normalized/upward: " + hit);
                    helper.assertTrue(hit.getSurfaceVelocityX() == 0f && hit.getSurfaceVelocityY() == 0f &&
                            hit.getSurfaceVelocityZ() == 0f, "static terrain velocity must be zero");
                    physics.removeBody(blockerRef.get());
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void preAndPostTasksRunInOrder(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        physics.submitImmediateTask(PPhase.PRE, () -> {
            order.add("PRE");
            return Unit.INSTANCE;
        });
        physics.submitImmediateTask(PPhase.POST, () -> {
            order.add("POST");
            return Unit.INSTANCE;
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(order.size() >= 2, "PRE/POST tasks did not execute"))
                .thenExecute(() -> helper.assertTrue(
                        "PRE".equals(order.get(0)) && "POST".equals(order.get(1)),
                        "phase order was " + order))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void localHeightFieldTurnsOneBlockStepIntoRamp(GameTestHelper helper) {
        BlockPos floor = helper.absolutePos(BlockPos.ZERO);
        helper.setBlock(new BlockPos(2, 1, 1), net.minecraft.world.level.block.Blocks.STONE);
        var field = cn.solarmoon.spark_core.physics.terrain.LocalHeightField.capture(
                helper.getLevel(),
                new net.minecraft.world.phys.Vec3(floor.getX() + 1.5, floor.getY() + 1.0, floor.getZ() + 1.5),
                3
        );
        var low = field.sample(floor.getX() + 0.5, floor.getZ() + 1.5);
        var mid = field.sample(floor.getX() + 1.5, floor.getZ() + 1.5);
        helper.assertTrue(mid.getHeightY() > low.getHeightY(),
                "step sample must rise toward the stone: low=" + low.getHeightY() + " mid=" + mid.getHeightY());
        helper.assertTrue(mid.getHeightY() - low.getHeightY() < 1.05,
                "smoothed cliff must be less than a raw 1 m wall: " + (mid.getHeightY() - low.getHeightY()));
        helper.succeed();
    }
}
