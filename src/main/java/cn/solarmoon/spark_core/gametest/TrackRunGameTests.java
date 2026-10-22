package cn.solarmoon.spark_core.gametest;

import cn.solarmoon.spark_core.SparkCore;
import cn.solarmoon.spark_core.physics.PhysicsTasks;
import cn.solarmoon.spark_core.physics.SparkPhysics;
import cn.solarmoon.spark_core.physics.level.PhysicsLevel;
import cn.solarmoon.spark_core.physics.track.TrackRunJointSpec;
import cn.solarmoon.spark_core.physics.track.TrackRunLinkFrame;
import cn.solarmoon.spark_core.physics.track.TrackRunLinkShape;
import cn.solarmoon.spark_core.physics.track.TrackRunLinkSpec;
import cn.solarmoon.spark_core.physics.track.TrackRunRuntime;
import cn.solarmoon.spark_core.physics.track.TrackRunSnapshot;
import cn.solarmoon.spark_core.physics.track.TrackRunSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle checks for the generic TrackRun API on a real PhysicsLevel.
 *
 * These tests prove creation, per-substep snapshot publication, reset, bounded
 * force application and complete teardown. They deliberately assert nothing
 * about vehicles, wheel roles or visible links: those belong to the downstream
 * tracked runtime.
 */
@GameTestHolder(SparkCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TrackRunGameTests {
    private TrackRunGameTests() {
    }

    /** Ring whose pins are the midpoints between neighbouring link centres. */
    private static TrackRunSpec closedLoop(String name, double centerX, double centerY, double centerZ) {
        int count = 8;
        double radius = .5;
        List<Vec3> centres = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double angle = index * 2.0 * Math.PI / count;
            centres.add(new Vec3(
                centerX + radius * Math.cos(angle),
                centerY + radius * Math.sin(angle),
                centerZ
            ));
        }
        List<TrackRunLinkSpec> links = new ArrayList<>();
        for (Vec3 centre : centres) {
            links.add(new TrackRunLinkSpec(
                2f,
                new TrackRunLinkShape.Box(new Vec3(.08, .08, .08)),
                centre,
                new Quaternionf()
            ));
        }
        List<TrackRunJointSpec> joints = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int next = (index + 1) % count;
            Vec3 previous = centres.get(index);
            Vec3 following = centres.get(next);
            Vec3 midpoint = previous.add(following).scale(.5);
            joints.add(new TrackRunJointSpec(
                index, next,
                midpoint.subtract(previous), midpoint.subtract(following),
                2, 1.2f
            ));
        }
        return new TrackRunSpec(name, links, joints);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void trackRunPublishesEverySubstepAndClosesCompletely(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos floor = helper.absolutePos(BlockPos.ZERO);
        double x = floor.getX() + .5;
        double y = floor.getY() + 5.0;
        double z = floor.getZ() + .5;
        TrackRunRuntime run = new TrackRunRuntime(physics, closedLoop("gametest_run", x, y, z));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicReference<Long> firstRevision = new AtomicReference<>(0L);
        AtomicReference<Long> runId = new AtomicReference<>(0L);
        AtomicReference<Throwable> inputFailure = new AtomicReference<>();
        AtomicBoolean inputDone = new AtomicBoolean();
        AtomicBoolean closeDone = new AtomicBoolean();

        run.create().whenComplete((ignored, error) -> {
            if (error != null) failure.set(error);
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(failure.get() == null, "TrackRun create failed: " + failure.get());
                    helper.assertTrue(run.isCreated(), "TrackRun was not created");
                    TrackRunSnapshot snapshot = run.snapshot();
                    helper.assertTrue(snapshot.getLinkCount() == 8,
                        "TrackRun snapshot has " + snapshot.getLinkCount() + " links");
                    helper.assertTrue(snapshot.finite(), "TrackRun snapshot is not finite");
                    helper.assertTrue(snapshot.getRunId() == run.getRunId(),
                        "snapshot runId does not match the runtime");
                    helper.assertTrue(snapshot.getJoints().size() == 8,
                        "snapshot has " + snapshot.getJoints().size() + " joint frames");
                    for (cn.solarmoon.spark_core.physics.track.TrackRunJointFrame joint : snapshot.getJoints()) {
                        helper.assertTrue(joint.finite(), "joint frame is not finite: " + joint);
                    }
                    runId.compareAndSet(0L, snapshot.getRunId());
                    firstRevision.compareAndSet(0L, snapshot.getRevision());
                })
                .thenWaitUntil(() -> helper.assertTrue(
                    run.snapshot().getRevision() > firstRevision.get(),
                    "snapshot revision did not advance across physics substeps"))
                .thenWaitUntil(() -> helper.assertTrue(
                    run.snapshot().getRunId() == runId.get(),
                    "snapshot changed run identity while alive"))
                .thenExecute(() -> {
                    var receipt = run.applyLinkForceAsync(0, new Vec3(0.0, 20.0, 0.0));
                    helper.assertTrue(receipt.getAccepted(), "bounded force was not accepted");
                    receipt.getCompletion().whenComplete((ignored, error) -> {
                        inputFailure.set(error);
                        inputDone.set(true);
                    });
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(inputDone.get(), "link force future did not complete");
                    helper.assertTrue(inputFailure.get() == null,
                        "link force failed: " + inputFailure.get());
                    helper.assertTrue(physics.getTickCount() > 0, "physics did not advance");
                })
                .thenExecute(() -> run.closeAsync().whenComplete((ignored, error) -> {
                    closeFailure.set(error);
                    closeDone.set(true);
                }))
                .thenWaitUntil(() -> {
                    helper.assertTrue(closeDone.get(), "TrackRun close did not complete");
                    helper.assertTrue(closeFailure.get() == null, "TrackRun close failed: " + closeFailure.get());
                    helper.assertTrue(run.isClosed(), "TrackRun did not close");
                    helper.assertTrue(run.getLinkCount() == 0, "links survived teardown");
                    helper.assertTrue(run.snapshot().getLinkCount() == 0, "snapshot survived teardown");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void createAfterCloseFailsInsteadOfRevivingTheRun(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos floor = helper.absolutePos(BlockPos.ZERO);
        TrackRunRuntime run = new TrackRunRuntime(physics, closedLoop("gametest_race",
            floor.getX() + .5, floor.getY() + 5.0, floor.getZ() + .5));
        AtomicReference<Throwable> createFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicBoolean createDone = new AtomicBoolean();
        AtomicBoolean closeDone = new AtomicBoolean();

        helper.startSequence()
                .thenExecute(() -> {
                    // Close is admitted first, so create must not revive the run
                    // or report success for an assembly that never happened.
                    run.closeAsync().whenComplete((ignored, error) -> {
                        closeFailure.set(error);
                        closeDone.set(true);
                    });
                    run.create().whenComplete((ignored, error) -> {
                        createFailure.set(error);
                        createDone.set(true);
                    });
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(closeDone.get(), "close future never completed");
                    helper.assertTrue(createDone.get(), "create future never reached a terminal state");
                    helper.assertTrue(createFailure.get() != null,
                        "a create after close must fail, not succeed");
                    helper.assertTrue(closeFailure.get() == null, "close failed: " + closeFailure.get());
                    helper.assertTrue(run.isClosed(), "run stayed open after close");
                    helper.assertTrue(run.getLinkCount() == 0, "links survived teardown");
                    helper.assertTrue(run.snapshot().getLinkCount() == 0, "snapshot survived teardown");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void createPublishesTheFirstFrameBeforeCompleting(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos floor = helper.absolutePos(BlockPos.ZERO);
        TrackRunRuntime run = new TrackRunRuntime(physics, closedLoop("gametest_first_frame",
            floor.getX() + .5, floor.getY() + 5.0, floor.getZ() + .5));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<TrackRunSnapshot> snapshotAtCompletion = new AtomicReference<>();
        AtomicBoolean created = new AtomicBoolean();

        run.create().whenComplete((ignored, error) -> {
            if (error != null) {
                failure.set(error);
                return;
            }
            created.set(run.isCreated());
            snapshotAtCompletion.set(run.snapshot());
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(failure.get() == null, "create failed: " + failure.get());
                    helper.assertTrue(created.get(), "create completed without becoming active");
                    TrackRunSnapshot snapshot = snapshotAtCompletion.get();
                    helper.assertTrue(snapshot != null, "no snapshot existed when create completed");
                    helper.assertTrue(snapshot.getLinkCount() == 8,
                        "first snapshot has " + snapshot.getLinkCount() + " links");
                    helper.assertTrue(snapshot.getRevision() == 1L,
                        "first snapshot revision is " + snapshot.getRevision());
                    helper.assertTrue(snapshot.getJoints().size() == 8,
                        "first snapshot has " + snapshot.getJoints().size() + " joint frames");
                    helper.assertTrue(snapshot.getLinkBodyIds().size() == 8,
                        "first snapshot has " + snapshot.getLinkBodyIds().size() + " link ids");
                    helper.assertTrue(snapshot.getLinkBodyIds().stream().distinct().count() == 8,
                        "link body ids are not unique");
                    helper.assertTrue(snapshot.finite(), "first snapshot is not finite");
                })
                .thenExecute(() -> run.closeAsync())
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void withLinkBodiesRunsOnThePhysicsThreadAndMatchesTheSnapshot(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos floor = helper.absolutePos(BlockPos.ZERO);
        TrackRunRuntime run = new TrackRunRuntime(physics, closedLoop("gametest_links",
            floor.getX() + .5, floor.getY() + 5.0, floor.getZ() + .5));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean checked = new AtomicBoolean();
        AtomicReference<List<Long>> ids = new AtomicReference<>();

        run.create().thenCompose(ignored -> run.withLinkBodies(bodies -> {
            ids.set(bodies.stream().map(cn.solarmoon.spark_core.physics.PhysicsBodies::getId).toList());
            return bodies.size();
        })).whenComplete((size, error) -> {
            if (error != null) {
                failure.set(error);
                return;
            }
            checked.set(size == 8);
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(failure.get() == null, "withLinkBodies failed: " + failure.get());
                    helper.assertTrue(checked.get(), "link callback did not see eight bodies");
                    TrackRunSnapshot snapshot = run.snapshot();
                    helper.assertTrue(snapshot.getLinkBodyIds().equals(ids.get()),
                        "snapshot link ids do not match the native link bodies");
                })
                .thenExecute(() -> run.closeAsync())
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void thePublicJavaTaskEntryCompletesInsteadOfDroppingWork(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Integer> value = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean();

        PhysicsTasks.call(physics, () -> 21 * 2).whenComplete((result, error) -> {
            if (error != null) failure.set(error);
            value.set(result);
        });
        PhysicsTasks.run(physics, () -> ran.set(true)).whenComplete((ignored, error) -> {
            if (error != null) failure.set(error);
        });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(failure.get() == null, "PhysicsTasks failed: " + failure.get());
                    helper.assertTrue(value.get() != null && value.get() == 42,
                        "PhysicsTasks.call did not return its value");
                    helper.assertTrue(ran.get(), "PhysicsTasks.run did not execute");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void resetTranslatesTheWholeAssemblyWithoutBreakingPins(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos floor = helper.absolutePos(BlockPos.ZERO);
        TrackRunSpec spec = closedLoop("gametest_reset",
            floor.getX() + .5, floor.getY() + 5.0, floor.getZ() + .5);
        TrackRunRuntime run = new TrackRunRuntime(physics, spec);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Throwable> resetFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        run.create().whenComplete((ignored, error) -> {
            if (error != null) failure.set(error);
        });

        double lift = 7.0;
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(failure.get() == null, "TrackRun create failed: " + failure.get());
                    helper.assertTrue(run.isCreated(), "TrackRun was not created");
                })
                .thenExecute(() -> {
                    // A rigid translation keeps every authored pin offset valid;
                    // flattening all links onto one Y would open the loop.
                    List<TrackRunLinkFrame> poses = new ArrayList<>();
                    for (TrackRunLinkSpec link : spec.getLinks()) {
                        Vec3 position = link.getPosition();
                        poses.add(TrackRunLinkFrame.of(
                            new Vec3(position.x, position.y + lift, position.z),
                            link.getRotation(), Vec3.ZERO, Vec3.ZERO, true, false));
                    }
                    run.reset(poses).whenComplete((ignored, error) -> resetFailure.set(error));
                })
                .thenWaitUntil(() -> helper.assertTrue(resetFailure.get() == null,
                    "reset failed: " + resetFailure.get()))
                .thenWaitUntil(() -> {
                    TrackRunSnapshot snapshot = run.snapshot();
                    helper.assertTrue(snapshot.getLinkCount() == 8, "reset lost links");
                    for (int index = 0; index < snapshot.getLinkCount(); index++) {
                        double expectedY = spec.getLinks().get(index).getPosition().y + lift;
                        double actualY = snapshot.getLinks().get(index).getPosition().y;
                        helper.assertTrue(Math.abs(actualY - expectedY) < .5,
                            "link " + index + " did not reset: expected " + expectedY + " got " + actualY);
                    }
                })
                .thenExecute(() -> run.closeAsync().whenComplete((ignored, error) -> closeFailure.set(error)))
                .thenWaitUntil(() -> {
                    helper.assertTrue(closeFailure.get() == null, "close failed: " + closeFailure.get());
                    helper.assertTrue(run.isClosed(), "TrackRun did not close");
                })
                .thenSucceed();
    }
}
