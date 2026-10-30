# 物理 API

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

移除也使用 `physics.removeBody(body)`；该方法可跨线程调用并会排入物理队列。移除时 Spark owner 和运行时监听器会清理。

## 碰撞组

- `NONE`：不主动匹配任何组。
- `TERRAIN`：Minecraft 地形静态刚体。
- `PHYSICS_BODY`：普通动态刚体。
- `PAWN`：默认实体代理。
- `TRIGGER`：传感器。
- `CHASSIS`：载具车体。车体之间要互相碰撞时，mask 需包含 `CHASSIS`。
- `WHEEL`：悬挂轮。默认只与地形接触；同实体内轮与车体由 `collideWithOwnerGroups`（默认 `NONE`）隔离。
- `ALL`：全部位。

`collisionGroup` 表示本 body 所属位，`collideWithGroups` 表示它希望碰撞的位。要获得地形，动态 body 的 mask 必须包含 `TERRAIN`。地形静态体自身的 mask 可以是 `NONE`：`PhysicsWorld.needsCollision` 仍允许动态体与地形接触。

自带运动的实体若自己创建刚体，应实现 `SkipDefaultPawn`，否则加入世界时会再挂一个 kinematic AABB pawn。

## 关节

`PhysicsJoints` 提供 `New6Dof` 辅助：

- `wheelSuspension(...)`：约束 Y 为悬挂行程并启用内置弹簧，X 为滚动轴。`damping` 是无量纲求解器阻尼比。
- `guidedWheelAxle(...)`：同样的行程/轮轴，但**关闭**内置弹簧。独立悬挂应使用本 API，再在每个物理子步调用 `applyOleoForce`。
- `applyOleoForce(chassis, wheel, upWorld, forceN, anchorWorld)`：在锚点对车体/轮体施加成对 oleo 力。
- `setTravelLimits(dof, min, max)`：本帧收紧 LIN_Y 行程。
- `setRollMotor(dof, velocity, maxForce)`：轮端速度电机。
- `springBetween(...)`：仅平移弹簧，锁定旋转。
- `PhysicsBodies.setAnisotropicFriction(body, vec, mode)`：各向异性摩擦；支撑轮应保持各向同性且 friction=0。

当前事实：`LocalHeightField.capture(level, center)` 是采样辅助。它生成有限列网格、向下削高处并双线性查询。SBW 现有 `VehicleBody.trackGroundFallback` 在探针 miss 后使用平滑采样法线，并配 raw column 高度。该函数不是授权支撑接口，调用它也不表示高度场唯一支撑已经实现。

目标禁令：新履带架构不得把它当作 probe fallback，也不得让它与原始 terrain AABB 并行提供第二份向上支撑。实现完成前，下游不得把“高度场唯一支撑”标成已实现。

SBW 目标要求核心库提供版本化的 `TerrainSupportField`/`HeightFieldContact` 契约：主线程快照 -> builder 纯数值表面 -> 物理线程支撑对象/接触仲裁。输出必须同时包含有效性、raw/support 高度、法线、signed distance/penetration、表面类别、摩擦和 terrain revision。未知、空气、洞和越界查询不得伪造一个低高度。

P1.1/P1.2 已有纯数据 `TerrainSupportFieldBuilder.build(snapshot, policy)`。它只读取 `SectionSnapshot`，不读取 Level，也不创建 Bullet。越界查询返回 `NONE`，不夹到边缘。非整格碰撞不扩成整格支撑；未列入临时映射的方块是 `UNKNOWN`，不是硬地面。`query` 是向下表面采样，采样高度和法线来自同一角点补丁，不是形状侵入求解，也不发射 `STRUCTURAL_OBSTACLE`。`PhysicsChunkManager` 会在物理线程安装 `LIVE` 支撑对。它按已写入的 1 格上限下削共享边，不把 2 格立面削成坡。原生测试中，这张网格的第一次求解向上冲量出现在物理步之后的 `physicsTick`，同一子步开始前为 0。因此不在 `onContactProcessed` 里改写 manifold。选择 `SUPPORT_PAIR` 才与它碰撞；现有 `DRIVABLE` 掩码碰不到。未分类方块不生成支撑网格，也不会被复制成结构顶面。因此还不能把“高度场唯一支撑”标成已实现。调用方必须显式传入累计下削上限和可选的高差过渡上限；测试里的 0.25 或 1 不是已冻结验收限值。

配套边界见 `docs/TRACKED_VEHICLE_SUPPORT.md`；SBW 阶段和验收见其仓库
`docs/physics/HEIGHT_FIELD_DESIGN.md` 与 `IMPLEMENTATION_PLAN.md`。

必须在物理线程创建关节，并用 `physics.addJoint` / `removeJoint` 注册。

## Body 元数据

Kotlin 通过扩展属性访问：

```kotlin
body.name = "turret"
body.owner = entityHost
body.collideWithOwnerGroups = CollisionGroups.PHYSICS_BODY
```

Java 使用 `PhysicsBodies.getName/setName/getOwner/setOwner`。owner 改变时 body 会自动从旧宿主表移除并加入新宿主表。

## 实体绑定

`PhysicsBodies.attachToEntity(rigidBody, entity)` 或 Kotlin `rigidBody.attachToEntity(entity)` 会让每帧开始时把 kinematic body 移到实体 AABB 中心。该机制适合非物理实体的代理碰撞体。

需要由物理驱动 Minecraft 实体时，可继承 `RigidBodyEntity`：

- 子类提供 `body`；
- 服务端 `baseTick` 从快照同步位置、旋转和速度；
- 客户端使用 vanilla entity 插值；
- 质量、重力、阻尼、速度、CCD、摩擦等写入 synched entity data 和 NBT；
- `onAddedToWorld/onRemovedFromWorld` 自动加入和移除 body。

子类仍需正常注册 `EntityType`、实现渲染和创建自己的碰撞 shape。

## 读取结果

```java
BodyFrame frame = PhysicsBodies.latestFrame(physics, body);
if (frame != null) {
    Vec3 position = frame.getPosition();
    Vec3 velocity = frame.getLinearVelocity();
}
```

`BodyFrame.colliding` 表示该物理帧内收到 processed contact；休眠刚体可能已经稳定接触地面但本帧为 false，因此持续接触判断应同时考虑位姿、速度或自定义接触状态。
