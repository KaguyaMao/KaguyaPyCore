---
name: Physics Core Support
description: Maintain KaguyaPyCore's thread-safe Bullet API, generic dynamic TrackRun, voxel/AABB terrain collision, snapshots, and lifecycle consumed by SuperbWarfare tracked vehicles.
---

# KaguyaPyCore 核心物理支持技能

先阅读：

1. `docs/CORE_SCOPE.md`
2. `docs/ARCHITECTURE.md`
3. `docs/THREADING_AND_LIFECYCLE.md`
4. `docs/TERRAIN_COLLISION.md`
5. `docs/PHYSICS_API.md`
6. `docs/TRACK_RUN_API.md`
7. `docs/PHYSICS_INTERPOLATION.md`
8. `docs/TRACKED_VEHICLE_SUPPORT.md`

## 硬边界

- Minecraft 主线程只能读取世界并制作普通值快照；Bullet 原生对象只在对应 PhysicsLevel
  物理线程创建、修改、加入、移除和销毁。
- 保持每 Level 一个物理线程、60 Hz、每 MC tick 三子步、单飞帧和不可变快照；不创建第二套
  全局物理世界或调度器。
- 只提供通用 Bullet 能力：body/shape/joint、TrackRun、快照和插值。
- 不实现车辆高度场、`LocalHeightField`、`TerrainSupportField`、`SUPPORT_PAIR`、
  `HEIGHT_FIELD`、`SLOPE`、`RUN_BLOCKS`、台阶辅助、车型驱动或渲染。
- 不把 SBW 车型、轮骨骼、物理节距、可见链节、Geckolib/Bedrock 或 Minecraft 模型放进核心库。
- 不把 Minecraft `Level`、`Chunk`、`BlockState` 或车型对象放进 worker 数据。
- 不依赖已撤销的探针、楔面或高度场接口。

## 实施顺序

1. 定义纯数据 `TrackRunSpec`、link/joint 描述和 `TrackRunSnapshot`。
2. 在物理线程实现 link body 和 joint 的创建、reset、销毁和 owner 生命周期。
3. 添加 T1 测试验证约束、闭合、漂移、碰撞过滤和销毁幂等。
4. 实现通用快照插值，只消费不可变快照。
5. 保持普通 voxel/AABB 地形和行为不变。
6. 核心 API 变化同步更新文档和下游事件账本。

## 地形

- 保留 `SectionSnapshot` -> `TerrainShapePlan` -> 静态 AABB 刚体。
- 保留完整方块合并和非完整方块多 AABB。
- 不生成车辆高度场、楔面或支撑对。
- 不把台阶段策略带入核心库。

## 用户窗口

核心库阶段只有证据确认门，没有车辆实机门。核心测试通过不能把 SuperbWarfare 车型标成
`manual_passed` 或 `approved`。用户确认核心证据后，才在 SBW 账本记录阶段可以进入下一阶段。

## 交付记录

每次核心 API、单位、线程或地形行为改变，记录 KaguyaPyCore commit、SBW 适配影响、测试命令、
平台/native 结果，并追加 SuperbWarfare 仓库内 `docs/physics/records/events.jsonl`。
跨仓库引用写成 `SuperbWarfare:docs/physics/records/events.jsonl`。

不要把构建产物、Maven cache、游戏运行目录或 MCP SQLite 加入仓库。
