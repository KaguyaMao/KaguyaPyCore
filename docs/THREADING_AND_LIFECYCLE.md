# 线程与生命周期契约

更新时间：2026-10-28
状态：terrain cache consumer/revision API 与逐帧物理诊断位于工作树；K1 基线为 `e90984a`；本轮 Core 全量 159 项通过；T2（16 项 GameTest）曾通过，本轮未重跑

## 线程所有权

Minecraft 主线程可以：

- 读取 Level、Chunk、BlockState 和 Entity；
- 把实体状态复制成普通值；
- 调用 `requestStep`、提交物理任务；
- 读取 `latestFrame`。

地形 builder 可以：

- 读取不可变 `SectionSnapshot`；
- 生成只含数值的 `TerrainShapePlan`；
- 不创建 Bullet 对象，不生成车辆高度场或楔面。
- 不访问已卸载/已替换的 section 原生对象；过期构建通过 revision 和 section identity 丢弃。

物理线程可以：

- 创建和修改 Libbulletjme shape/body/world；
- 加入、移除、推进和销毁 Bullet 对象；
- 创建和销毁通用 `TrackRun` link body 和 joint；
- 发布帧快照与物理事件。

物理线程不得直接访问可变 Minecraft 世界状态。Forge 的 Spark 物理事件当前在物理线程发布，
订阅者若需修改世界必须转回服务器/客户端主线程。

## 固定步进

Minecraft 目标 tick rate 为 20 Hz，物理目标为 60 Hz。因此每次 Level tick 请求一帧，
物理线程执行三个 `1/60s` 子步。`stepQueued` 保证最多一个待执行帧；物理跟不上时增加
`droppedFrameCount`，不会无限堆积导致延迟持续增长。

`PhysicsFrameSnapshot.metrics` 提供逐帧诊断：主线程 terrain 调度与实体姿态复制、物理线程排队、
FRAME_PRE/ALL 任务、附着姿态更新、terrain body apply、三次 Bullet update、每子步前后回调和帧末
body capture。若多个主线程请求在同一个 worker 帧执行前合并，terrain 调度时间和调用次数会累计，
并记录其中被 `stepQueued` 丢弃的请求数；这些指标用于定位，不改变调度和验收语义。
World update 前后的回调各遍历一次 `pcoList`，对应访问数也单独记录；快照同时报告 body、dynamic、
static、kinematic、terrain 和 terrain-activation 数量。

## 任务阶段

- `PPhase.ALL`：普通任务队列，每个物理回调阶段均可处理。
- `PPhase.PRE`：物理子步前。
- `PPhase.POST`：物理子步后。
- 去重任务用 key 覆盖旧值，适合频繁更新同一对象的目标状态。

Java 优先使用 `PhysicsTasks.run/call`，它会返回 `CompletableFuture` 并传播任务异常。
两者都委托 `PhysicsLevel.submitTask`，因此被关闭拒绝时会立即失败，不会留下挂起 Future。
Kotlin 可使用同一 `submitTask`；`submitImmediateTask` 仅用于明确接受 fire-and-forget 语义的工作。

## 任务接纳门

`PhysicsLifecycleGate` 是唯一允许「提交」与「关闭」相遇的地方。提交和状态转换在同一把锁内
完成，因此不存在「检查通过后被关闭清空队列」的窗口。

| 状态 | 是否接纳 |
|---|---|
| `NEW` / `STARTING` | 接纳并排队（实体可能在物理线程启动完成前加入 Level） |
| `RUNNING` | 接纳 |
| `STOPPING` / `STOPPED` / `FAILED` | 拒绝 |

保证：

- 被拒绝的任务立即以 `PhysicsLevelNotRunningException` 失败，不会永久挂起。
- 已接纳但尚未执行的任务，如果关闭先到，也以同一异常完成，不会在 teardown 中创建新对象。
- 需要观测完成的任务使用 `PhysicsLevel.submitTask(...)`（泛型值）或 `submitGuarded(...)`。
- 去重任务同样经过接纳门；关闭开始后不能插入已排空的队列。
- 起动完成也经过同一把锁：关闭抢先后不能把 Level 重新写回 `RUNNING`。
- `closeAsync()` 幂等，重复调用返回同一 Future。
- 关闭后才注册的 close listener 立即执行一次，不会滞留在已清空的列表里。

## 状态机

```text
NEW -> STARTING -> RUNNING -> STOPPING -> STOPPED
                    |
                    +-> FAILED
```

world 初始化失败或连续三次恢复失败会进入 `FAILED`。`FAILED` 仍保留可观测状态，但必须走
完整 teardown；不会只清引用而留下 native 对象。

关闭是幂等的；非物理线程调用 `close()` 会等待 `closeAsync()` 最多十秒，物理线程自身调用
不会阻塞（否则会死锁在必须由它执行的清理上）。等待超时只记录“尚未完成”，不会调用
`shutdownNow()`，因为那会取走唯一已排队的 cleanup 任务，把暂时变慢变成永久泄漏。

## 统一清理顺序

`runShutdownCleanup` 是普通关闭、故障和拒绝提交共用的唯一路径：

1. 停止接纳新任务（在锁内完成状态转换）。
2. 在物理线程执行 shutdown participants。它们可以按依赖顺序直接 `removeJoint` /
   `removeBody`，不能再 `submitTask`。participant 抛错会让关闭 Future 失败，
   随后仍继续 teardown，避免把失败伪装成成功后留下 native 对象。
3. 通知 close listeners，每个只执行一次；单个 listener 异常只记录，不中断清理。
   listener 只丢引用，不做有序 native 清理。
4. 若 world 已创建，排空所有阶段队列；若 world 从未创建，丢弃队列并让任务失败，
   避免在未初始化的 world 上执行 native 操作。
   - 每个任务独立捕获异常，失败任务不能带走同阶段后续任务。
5. 以异常完成仍未完成的已接纳任务。
6. 移除 joint（`destroy`），再移除 body 并清 owner/runtime 元数据；每项独立尝试。
7. 销毁 terrain、Bullet world、shape 注册表。
8. 完成 shutdown Future；主错误保留，清理错误作为 suppressed 附加。
   **任何 native 清理失败都会让 shutdown Future 异常完成**，不会被吞掉后报告 STOPPED 成功。

## 快照一致性

`PhysicsFrameSnapshot` 一次性包含 tick、body frame 表、稳定 id 表、地形激活 AABB、
worker 处理耗时、完成时间、world epoch 和 `PhysicsStepMetrics`。构造时复制 Map/List，发布后不再修改。

`stepNanos` 是物理 worker 从开始处理该帧到快照构造完成的总耗时；它不包含主线程请求前的
terrain/cache 调度与实体姿态采集。`metrics` 分项记录请求侧累计地形调度（含合并调用与丢弃帧数）、
实体姿态采集和 worker 排队，worker 侧任务、attached pose、terrain body 应用、三次 `world.update`
和帧末 body capture。
`preCallbackNanos` / `postCallbackNanos` 是 `bulletUpdateNanos` 的子项，不可重复相加；同时
记录每次回调的 body 遍历次数及快照中的动态、静态、地形 body 数。该数据只用于定位开销，
不改变调度、碰撞或快照成员集合，也不构成性能验收结果。

- `bodies` 以 native 对象为键，仅供物理线程/兼容路径使用。
- `bodiesById` 以稳定 `BodyFrame.bodyId` 为键，是跨线程使用的视图。
- 插值后的整帧会同时重写两份视图，不会出现按 id 和按 native 对象读到不同状态。
- `epoch` 在物理世界重建时改变；不同 epoch 的帧不插值。
- 主线程不能保存 `PhysicsRigidBody` 后直接读取 native getter。

通用 `TrackRunSnapshot` 遵循同一规则：只包含普通值，发布后不再修改，并携带 `runId`、
`poseEpoch` 和 `linkBodyIds`。`poseEpoch` 在 reset/teleport 时改变，跨 epoch 不插值。

静态刚体（地形、零质量 link）不读取线/角速度：Libbulletjme 对该调用有断言，读取会失败。
核心统一按 `isDynamic` 判断，静态体报告零速度。

通用地形消费者区域请求由稳定 `consumerId` 管理，必须显式释放；TTL 只作异常退出保护。
`snapshotRegion` 返回严格完整的 detached 几何，`withRevisionToken` 在物理线程短回调内
原子校验当前已应用 revision。回调不得等待主线程或运行 Minecraft world 查询。

## TrackRun 生命周期

```text
创建 spec（主线程或调用方）
 -> 物理线程逐项创建 link body，创建即记账（配置属性前已登记所有权）
 -> 设置 owner、碰撞组和同 owner 过滤
 -> 加入 physics world
 -> 逐项创建 joint 并注册
 -> 安装 post-step / close listener
 -> 采集首帧快照
 -> 原子提交为 ACTIVE（首帧已可读）
 -> 每个物理子步结束后读取状态并发布快照
 -> 停止输入与发布
 -> 移除/销毁 joint
 -> 移除 link body
 -> 清理 owner 和缓存
```

运行时状态：

```text
NEW -> CREATING -> ACTIVE -> CLOSING -> CLOSED
           |
           +-> FAILED（create 抛错，已回滚，残留由 close 重试）
```

要求：

- joint 两端 body 进入同一物理空间后才注册 joint。
- 创建失败必须回滚：joint 先于 body、逆序移除，清理异常不覆盖原始错误。
- 回滚失败的残留对象保留所有权，close 会重试，不得以成功结束。
- 关闭幂等，且可等待：`closeAsync()` 的 future 只有在确认本运行段 native 对象已移除，
  或 Level 统一 teardown 已经完成后才成功。
- 关闭 participant 内部使用 `closeNow()`，不要在关闭期再 `submitTask`；Level 已停止接纳，
  被拒绝的任务不能当作清理完成。`resetNow()` 同理，用于已经移动车体后的同步重定位。
- 关闭在创建前发生：`create()` 以异常结束，不会在关闭后重新激活运行段。
- Level 关闭或进入 FAILED 时 runtime 收到通知，丢弃引用并使未完成任务失败。
- 区块卸载和 Level 卸载必须清理运行段。
- 失败恢复后不留下幽灵 body。
- 关闭后向已停止的物理线程提交任务必须立即失败，不能挂起调用方。
- `close()` 的超时只报告未完成，不能取消唯一已排队的 cleanup。

## 插值边界

通用插值只消费不可变快照，不访问 native body，不修改物理状态。渲染和模型骨骼由下游实现。
