# 架构说明

## 组件关系

`SparkCore` 是 Java `@Mod` 入口，在模组构造阶段加载并校验原生库、注册网络通道和 Forge 事件处理器。

`LevelMixin` 为每个 `Level` 创建一个 `PhysicsLevel`：客户端使用 `ClientPhysicsLevel`，服务端使用 `ServerPhysicsLevel`。`EntityMixin` 为实体提供并发 body 表。公开访问入口是 `SparkPhysics.get(level)` 和 `SparkPhysics.host(entity)`。

`PhysicsLevel` 拥有：

- 单线程 executor 和 Bullet `PhysicsWorld`；
- 立即任务/去重任务队列；
- 注册刚体、跟踪实体和生命周期状态；
- 最新不可变 `PhysicsFrameSnapshot`；
- `PhysicsChunkManager` 与 `BlockShapeManager`。

## 数据流

主线程到物理线程：

1. Level tick 抓取实体 AABB 和上一帧地形激活范围。
2. 调用方通过 `PhysicsTasks` 或 `submitImmediateTask` 投递原生操作。
3. 单飞调度确保最多只有一个待执行物理帧。

物理线程：

1. 处理任务、实体附着姿态和已完成的地形计划。
2. 以 `1/60s` 连续推进三个子步。
3. 将 body 位姿、AABB、速度和状态复制为不可变帧。

物理线程到主线程：

- `AtomicReference<PhysicsFrameSnapshot>` 发布完整帧；
- 实体逻辑只读最新帧，不直接查询 Bullet；
- 客户端可依据完成时间计算插值比例。

地形流水线：

1. 主线程从 `LevelChunk` 生成 `SectionSnapshot`。
2. 最多两个 builder 线程把方块碰撞箱合并为 `TerrainShapePlan`。
3. 物理线程将计划转换为 `CompoundCollisionShape` 和静态刚体。

## 故障与关闭

物理步异常时会销毁并重建 world，最多恢复三次，同时重绑已注册 body 和地形。无法恢复后状态变为 `FAILED`，不再请求物理帧。

Level 卸载时状态依次进入 `STOPPING`、`STOPPED`，取消地形构建、移除 body、销毁 world、清缓存并关闭 executor。不会建立跨 Level 的全局物理空间。
