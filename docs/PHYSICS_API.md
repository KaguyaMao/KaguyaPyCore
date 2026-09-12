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

局部高度场 `LocalHeightField.capture(level, center)`：7×7 列、只向下削悬崖、双线性查询。不替换 chunk AABB 地形刚体，只给轮/履带接触提供连续高度和法线。

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
