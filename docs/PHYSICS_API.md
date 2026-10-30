# 物理 API

更新时间：2026-10-22
范围：通用 Bullet 能力。车辆专用逻辑见 `SuperbWarfare:docs/physics/`。

## Detached 地形缓存（1.20.1-1.4.3-physics-metrics 工作树）

`PhysicsChunkManager.requestRegion(consumerId, bounds)` 在游戏线程续期一个消费者的区域；
`releaseRegion(consumerId)` 在关闭时释放。`snapshotRegion(bounds)` 返回完整 detached
`TerrainRegionSnapshot` 或 `Missing`；已知空 section 可返回 Ready，缺失/脏/未应用 section
必须等待。`revisionTokenAt(bounds)` 只读取缓存元数据，适合跨线程验证，无几何复制。

物理线程通过 `withRevisionToken(bounds, expected) { ... }` 在同一 revision 锁内校验并提交
短暂的碰撞体替换；不匹配返回 null。回调不得等待游戏线程、执行异步任务或读取世界。
region request ID 由调用者保证在同一 manager 内唯一；车型、mesh 规划、坡面和接触策略属于消费方。
完整语义见 `docs/TERRAIN_COLLISION.md`。

## 获取关卡和实体宿主

Java：

```java
PhysicsLevel physics = SparkPhysics.get(level);
PhysicsHost host = SparkPhysics.host(entity);
```

Kotlin：

```kotlin
val physics = level.physicsLevel
val bodies = entity.allPhysicsBodies
```

Mixin 未生效时 API 会抛出带目标类名的异常，而不是静默返回空对象。

## 创建与加入刚体

原生 shape/body 必须在物理线程创建：

```java
CompletableFuture<PhysicsRigidBody> future = PhysicsTasks.call(physics, () -> {
    PhysicsRigidBody body = new PhysicsRigidBody(new BoxCollisionShape(.5f), 10f);
    body.setPhysicsLocation(new Vector3f(0, 80, 0));
    body.setCollisionGroup(CollisionGroups.PHYSICS_BODY);
    body.setCollideWithGroups(CollisionGroups.TERRAIN);
    PhysicsBodies.setName(body, "crate");
    physics.addBody(body);
    return body;
});
```

移除也使用 `physics.removeBody(body)`；该方法可跨线程调用并会排入物理队列。移除时 Spark
owner 和运行时监听器会清理。

`addBody`/`removeBody`/`addJoint`/`removeJoint` 在物理线程上同步执行并把异常抛给调用方；
从其他线程调用时返回 `CompletableFuture`，关闭竞态中以异常完成，而不是静默丢弃注册。

## 碰撞组

- `NONE`：不主动匹配任何组。
- `TERRAIN`：Minecraft 地形静态刚体。
- `PHYSICS_BODY`：普通动态刚体。
- `PAWN`：默认实体代理。
- `TRIGGER`：传感器。
- `CHASSIS`：载具车体。
- `WHEEL`：悬挂轮。
- `ALL`：全部位。

`collisionGroup` 表示本 body 所属位，`collideWithGroups` 表示它希望碰撞的位。要获得地形，
动态 body 的 mask 必须包含 `TERRAIN`。地形静态体自身的 mask 可以是 `NONE`：
`PhysicsWorld.needsCollision` 仍允许动态体与地形接触。

自带运动的实体若自己创建刚体，应实现 `SkipDefaultPawn`，否则加入世界时会再挂一个
kinematic AABB pawn。

## 关节

`PhysicsJoints` 提供 `New6Dof` 辅助：

- `setRotationMotor(dof, axis, velocity, maxForce)`：通用角电机，velocity 使用约束坐标。
- `wheelSuspension(...)`：约束 Y 为悬挂行程并启用内置弹簧，X 为滚动轴。
- `guidedWheelAxle(...)`：同样的行程/轮轴，但关闭内置弹簧。
- `applyPairedForce(bodyA, bodyB, upWorld, forceN, anchorWorld)`：成对力数值原语。
- `setTravelLimits(dof, min, max)`：本帧收紧 LIN_Y 行程。
- `springBetween(...)`：仅平移弹簧，锁定旋转。
- `PhysicsBodies.setAnisotropicFriction(body, vec, mode)`：各向异性摩擦。

### 轴限制：`lock` / `limit` / `free`

三个辅助写的是 Bullet 的六轴限位，语义必须分清：

| 辅助 | 写入 | 含义 |
|---|---|---|
| `lock(dof, axis)` | `0..0` | 该轴固定在 bind 值 |
| `limit(dof, axis, lower, upper)` | `lower..upper` | 该轴在窗口内自由 |
| `free(dof, axis)` | `FREE_MARKER..-FREE_MARKER`（lower > upper） | 该轴无界 |

**`free` 不是“一个很大的有限窗口”。** 旧实现写 `±1e9`，读回的是一个真实行程，
在足够大的位移或角速度下仍会停住，而且调用方无法区分“自由”和“很长的行程”。
Bullet 用倒置窗口表达无界，`PhysicsJoints.FREE_MARKER` 只是该约定的可读标记，
真正起作用的是 lower > upper。

`NativeJointFreeAxisTest` 在真实 Bullet 空间里验证了：六轴读回为倒置窗口、自由线性轴
确实下落、自由角轴确实转动、锁定轴确实保持、非 identity frame 下自由轴仍是**局部**轴。

注意 `free` 只写限位，不改变 motor / servo / spring 状态。需要“只约束、不驱动”时，
调用方仍须显式关闭对应 actuator；`free` 不会替调用方做这件事。

### 逐站悬挂行原语

`PhysicsSuspensionRow` 是车辆无关的一维 SI 弹簧—阻尼原语。它只做数值：

- `SuspensionRowSpec`：单站质量、上下行程、刚度、压缩/回弹阻尼、绝对力上限、
  缓冲块起始比例与指数。
- `PhysicsSuspensionRow.force(spec, state)`：`k·travel + c·velocity + bump`，
  **带符号**，钳制在 `[-MaxForce, +MaxForce]`。
- 符号约定：`travel > 0` 为压缩，返回正力（推开）；`travel < 0` 为下垂，
  返回负力（拉回 bind pose）。被动悬挂是双向的，负向力不能被钳成零。
- `fromSag(...)`：由目标静载下沉和阻尼比反推刚度，避免调用方传入自相矛盾的参数。
- 它不知道轮角色、载荷分配、车型 ID 或悬挂策略；这些属于 SuperbWarfare。

`setRollMotor` 与 `applyOleoForce` 是历史兼容名，已标记 deprecated：前者隐含“+Z 为前进”
的车辆约定，后者隐含某种悬挂策略。新代码应使用 `setRotationMotor` 和 `applyPairedForce`，
由 SBW 决定轴映射与力律。`trackLinkHinge` / `trackLinkCarrier` 同样仅为历史调用保留；
核心不再把“固定一块参考履带板”作为闭链方案。

必须在物理线程创建关节，并用 `physics.addJoint` / `removeJoint` 注册。两者同样在物理线程
同步执行、跨线程排队并返回可等待 Future。

### 关节状态观测

`New6Dof.getAngles(Vector3f)` 返回按 `RotationOrder` 排列的约束坐标（弧度），可在物理线程
读取后作为普通值发布。`TrackRunSnapshot.joints` 即使用该通道，见
[`TRACK_RUN_API.md`](TRACK_RUN_API.md)。

**不要**用 `Constraint.getAppliedImpulse()` 当作关节载荷：本 wrapper 的 6-Dof 约束不维护
该值，无论载荷多大都读回 0，且未开启 `isFeedback` 时会直接抛异常。核心不提供关节载荷
字段，需要载荷时由调用方从接触冲量与施加力推导。

## 通用 TrackRun

通用动态履带运行段 API 见 [`TRACK_RUN_API.md`](TRACK_RUN_API.md)。它只描述物理 link、
pin/hinge、碰撞过滤和 owner，不包含车型、轮角色、模型骨骼或渲染对象。

## 通用插值

物理快照插值见 [`PHYSICS_INTERPOLATION.md`](PHYSICS_INTERPOLATION.md)。它只消费不可变
快照，不访问 native body，不生成渲染对象。

## Body 元数据

Kotlin 通过扩展属性访问：

```kotlin
body.name = "turret"
body.owner = entityHost
body.collideWithOwnerGroups = CollisionGroups.PHYSICS_BODY
```

Java 使用 `PhysicsBodies.getName/setName/getOwner/setOwner`。owner 改变时 body 会自动从
旧宿主表移除并加入新宿主表。

## 实体绑定

`PhysicsBodies.attachToEntity(rigidBody, entity)` 或 Kotlin `rigidBody.attachToEntity(entity)`
会让每帧开始时把 kinematic body 移到实体 AABB 中心。该机制适合非物理实体的代理碰撞体。

需要由物理驱动 Minecraft 实体时，可继承 `RigidBodyEntity`：

- 子类提供 `body`；
- 服务端 `baseTick` 从快照同步位置、旋转和速度；
- 客户端使用 vanilla entity 插值；
- 质量、重力、阻尼、速度、CCD、摩擦等写入 synched entity data 和 NBT；
- `onAddedToWorld/onRemovedFromWorld` 自动加入和移除 body。

## 读取结果

```java
BodyFrame frame = PhysicsBodies.latestFrame(physics, body);
if (frame != null) {
    Vec3 position = frame.getPosition();
    Vec3 velocity = frame.getLinearVelocity();
}
```

`BodyFrame.colliding` 表示该物理帧内收到 processed contact；休眠刚体可能已经稳定接触地面
但本帧为 false，因此持续接触判断应同时考虑位姿、速度或自定义接触状态。

`BodyFrame` 是不可变值：旋转以四个标量保存，读取 `rotation` 返回新对象；`bodyId` 是稳定
身份，可跨线程作为快照键。`PhysicsFrameSnapshot.bodiesById` 提供按该 id 查询的视图，
`bodies`（以 native 对象为键）只供物理线程或兼容路径使用。快照构造时复制集合，插值结果
同时重写两份视图，`epoch` 在物理世界重建时改变且不同 epoch 不插值。

`PhysicsFrameSnapshot.metrics` 是通用性能诊断值，分开记录主线程 terrain request（含合并请求次数和
被 `stepQueued` 丢弃的请求数）、实体姿态采集、worker queue wait、worker terrain apply、Bullet update、
嵌套的 pre/post 回调成本、帧末 body capture，以及 body/terrain/callback 遍历数量。它不是硬实时保证，
也不是某个车型的验收限值。

`ContactSnapshot` 附带 `selfId`/`otherId` 与 `physicsFrame`，可在离开物理线程后关联接触；
`impulse` 是作用在 self 上的 N*s 冲量，`closingSpeed` 为沿法向的接近速度。

静态刚体（地形、零质量 body）不读取线/角速度：Libbulletjme 对静态体的速度 getter 有断言，
核心统一按 `isDynamic` 判断并报告零速度。

## 地形

核心库保留通用 Minecraft voxel/AABB 地形碰撞，见
[`TERRAIN_COLLISION.md`](TERRAIN_COLLISION.md)。

核心库不提供车辆高度场、`LocalHeightField`、`TerrainSupportField`、`SUPPORT_PAIR` 或
车辆台阶策略。车辆地形逻辑由 SuperbWarfare 实现。

## 任务与关闭

- `physics.submitImmediateTask { }`：即发即忘；状态为 `STOPPING`/`STOPPED`/`FAILED` 时
  直接丢弃并记录 debug 日志。
- `physics.submitTask(description) { value }`：泛型、可等待；返回 `CompletableFuture`，
  被拒绝或关闭抢在任务执行前时立即以 `PhysicsLevelNotRunningException` 失败。
- `physics.submitGuarded(description) { }`（internal）：同一协议的无返回值形式。
- `physics.addShutdownParticipant { }`：在停止接纳之后、close listener 和 native
  teardown 之前，于物理线程执行。用于车辆这类必须先拆 joint 再拆 body 的调用方。
  不能在其中 `submitTask`。异常会使 `closeAsync()` 失败，但不会跳过 native teardown。
  关闭开始后再注册不会被保存，返回的 `Subscription` 是空操作。
- `physics.addCloseListener { }`：公开。只用于丢引用和观察关闭，不做 native 清理。
  关闭开始后注册会立即调用，不会丢失关闭通知。
- `physics.closeAsync()`：幂等；Future 在 participant、已接纳任务、joint/body/world 清理完成后结束；
  native 清理失败时以异常结束，不会伪装成成功。
- `physics.close()`：委托 `closeAsync()` 并在非物理线程上最多等待十秒；超时只报告未完成，
  不会取消已排队的 cleanup。

`NEW`/`STARTING` 状态仍接纳任务（排队），因为实体可能在物理线程初始化完成前加入 Level。
关闭一旦开始就不可逆，启动完成不能把 Level 重新写回 `RUNNING`。

## 已撤销接口

以下接口不再属于核心库目标范围，D1 清理后退出活动路径：

- `LocalHeightField`
- `TerrainSupportField` / `TerrainSupportFieldBuilder`
- `GroundSupportPair` / `SUPPORT_PAIR`
- `TerrainSlopePlanner` / `TerrainSlopeBodies` / `SLOPE`
- `ProbeSuspensionUnit`
- `PlanarTrackSupport`

下游不得依赖这些接口。清理计划见
`SuperbWarfare:docs/physics/LEGACY_CLEANUP.md`。
