# 架构说明

更新时间：2026-10-22

## 组件关系

`SparkCore` 是 Java `@Mod` 入口，在模组构造阶段加载并校验原生库、注册网络通道和 Forge
事件处理器。

`LevelMixin` 为每个 `Level` 创建一个 `PhysicsLevel`：客户端使用 `ClientPhysicsLevel`，
服务端使用 `ServerPhysicsLevel`。`EntityMixin` 为实体提供并发 body 表。公开访问入口是
`SparkPhysics.get(level)` 和 `SparkPhysics.host(entity)`。

`PhysicsLevel` 拥有：

- 单线程 executor 和 Bullet `PhysicsWorld`；
- 立即任务/去重任务队列；
- 注册刚体、跟踪实体和生命周期状态；
- 最新不可变 `PhysicsFrameSnapshot`；
- `PhysicsChunkManager` 与 `BlockShapeManager`；
- 通用 `TrackRun` 运行段管理。

## 数据流

主线程到物理线程：

1. Level tick 抓取实体 AABB 和上一帧地形激活范围。
2. 调用方通过 `PhysicsTasks`、`PhysicsLevel.submitTask` 或 `submitImmediateTask` 投递原生操作。
   返回 Future 的入口都经过同一接纳门，关闭竞态不会留下挂起 Future。
3. 单飞调度确保最多只有一个待执行物理帧。

物理线程：

1. 处理任务、实体附着姿态和已完成的地形计划。
2. 以 `1/60s` 连续推进三个子步。
3. 将 body 位姿、AABB、速度和状态复制为不可变帧，并附上各阶段耗时与工作量计数供诊断。
4. 将 `TrackRun` link 状态复制为不可变快照。

物理线程到主线程：

- `AtomicReference<PhysicsFrameSnapshot>` 发布完整帧；
- 实体逻辑只读最新帧，不直接查询 Bullet；
- 客户端可依据完成时间计算插值比例。

地形流水线：

1. 主线程从 `LevelChunk` 生成 `SectionSnapshot`。
2. 最多两个 builder 线程把方块碰撞箱合并为 `TerrainShapePlan`。
3. 物理线程将计划转换为 `CompoundCollisionShape` 和静态刚体。

通用地形消费者可以通过 `requestRegion(consumerId, bounds)` 声明不参与 `TERRAIN` 激活
查询的区域，调用 `snapshotRegion` 获取 detached geometry，并在短暂的 `withRevisionToken`
回调内校验并提交依赖该区域 revision 的原生替换。Section 的请求 revision 单调递增；同一
section 同时最多一个 snapshot/build/apply，旧计划不能覆盖较新 solver body。该 API 不携带
车型、坡面或接触策略。

## 履带流水线

```text
主线程/资源加载
 -> SBW 车型编译
 -> 通用 TrackRunSpec
 -> 物理线程创建 link body + joint
 -> 每子步发布 TrackRunSnapshot

客户端
 -> 读取 PhysicsFrameSnapshot + TrackRunSnapshot
 -> 核心通用插值
 -> SBW 重建 MCSP 履带路径
 -> 采样可见链节并渲染
```

核心库不包含车型、轮角色、台阶策略或可见链节。

## 故障与关闭

物理步异常时会销毁并重建 world，最多恢复三次，同时重绑已注册 body、TrackRun 和地形；
world 重建会推进 `PhysicsFrameSnapshot.epoch`，因此不会跨恢复点插值。
无法恢复后状态变为 `FAILED`，不再请求物理帧。

Level 卸载时状态依次进入 `STOPPING`、`STOPPED`，取消地形构建、移除 body 和 TrackRun、
销毁 world、清缓存并关闭 executor。任何 native 清理失败都会让关闭 Future 异常完成，
而不是记录日志后报告成功。不会建立跨 Level 的全局物理空间。

## 边界

- 核心库只提供通用 Bullet 能力。
- 车辆地形、台阶、驱动和渲染在 SuperbWarfare。
- 不提供车辆高度场、`SUPPORT_PAIR` 或楔面。
- 详细职责见 [`CORE_SCOPE.md`](CORE_SCOPE.md)。
