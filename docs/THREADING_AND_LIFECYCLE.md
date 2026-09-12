# 线程与生命周期契约

## 线程所有权

Minecraft 主线程可以：

- 读取 Level、Chunk、BlockState 和 Entity；
- 把实体状态复制成普通值；
- 调用 `requestStep`、提交物理任务；
- 读取 `latestFrame`。

地形 builder 可以：

- 读取不可变 `SectionSnapshot`；
- 生成只含数值的 `TerrainShapePlan`。

物理线程可以：

- 创建和修改 Libbulletjme shape/body/world；
- 加入、移除、推进和销毁 Bullet 对象；
- 发布帧快照与物理事件。

物理线程不得直接访问可变 Minecraft 世界状态。Forge 的 Spark 物理事件当前在物理线程发布，订阅者若需修改世界必须转回服务器/客户端主线程。

## 固定步进

Minecraft 目标 tick rate 为 20 Hz，物理目标为 60 Hz。因此每次 Level tick 请求一帧，物理线程执行三个 `1/60s` 子步。`stepQueued` 保证最多一个待执行帧；物理跟不上时增加 `droppedFrameCount`，不会无限堆积导致延迟持续增长。

## 任务阶段

- `PPhase.ALL`：普通任务队列，每个物理回调阶段均可处理。
- `PPhase.PRE`：物理子步前。
- `PPhase.POST`：物理子步后。
- 去重任务用 key 覆盖旧值，适合频繁更新同一对象的目标状态。

Java 优先使用 `PhysicsTasks.run/call`，它会返回 `CompletableFuture` 并传播任务异常。Kotlin 可直接使用 `submitImmediateTask`。

## 状态机

```text
NEW -> STARTING -> RUNNING -> STOPPING -> STOPPED
                    |
                    +-> FAILED
```

world 初始化失败或连续三次恢复失败会进入 `FAILED`。关闭是幂等的；非物理线程调用 `close()` 时会等待清理任务最多十秒。

## 快照一致性

`PhysicsFrameSnapshot` 一次性包含 tick、body frame 表、地形激活 AABB、步耗时和完成时间。Map/List 在发布后不再修改。主线程不能保存 `PhysicsRigidBody` 后直接读取其 native getter；必须使用同一 body 作为快照 key 获取 `BodyFrame`。
