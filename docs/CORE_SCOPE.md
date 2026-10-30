# KaguyaPyCore 核心物理范围

更新时间：2026-10-22  
状态：MCSP 风格架构下的核心库职责定义

## 1. 核心库负责什么

KaguyaPyCore 只提供通用 Bullet 基础设施：

- 每 Level 一个物理线程、固定 60 Hz、每 MC tick 三子步。
- 主线程快照 -> 数值计划 -> 物理线程 Bullet 对象。
- 通用 Minecraft voxel/AABB 地形碰撞构建。
- detached `TerrainRegionSnapshot`、revision token 和按 consumer ID 管理的区域请求；不含车辆策略。
- 通用刚体、形状、关节、owner、碰撞组和生命周期。
- 通用动态履带运行段 `TrackRun` 的创建、销毁和状态读取。
- 不可变 `PhysicsFrameSnapshot`（含通用逐帧调度/工作量指标；可见合并请求与丢帧请求）、
  `TrackRunSnapshot` 和通用插值。
- 物理任务队列、故障恢复和 Level 生命周期。
- 单一任务接纳门、可等待关闭和统一 native teardown。
- 通用 joint 的两端局部 constraint frame 与关节坐标观测。
- 不含车型语义的一维 SI 弹簧—阻尼原语：行程窗口、压缩/回弹阻尼、缓冲块、
  对称绝对力上限，返回带符号力。

## 2. 核心库不负责什么

- `LocalHeightField`、`TerrainSupportField`、`HeightFieldContact`。
- `SUPPORT_PAIR`、`HEIGHT_FIELD`、`SLOPE`、`RUN_BLOCKS`。
- 车辆台阶规则、爬坡高度、车型坡面。
- ZTZ-99A 或其他车型 ID、资源路径、轮骨骼名。
- 模型、材质、Bedrock/Geckolib 类型。
- 差速、油门、制动和车辆输入语义。
- 车型前进轴约定、电机符号映射、车型悬挂定义、轮角色和载荷分配策略。
- 核心可以提供不含车型语义的一维 SI 弹簧—阻尼数学原语（`PhysicsSuspensionRow`），
  由 SBW 决定如何把车型数据编译成它。
- 把循环履带中的某块参考板固定到车体。
- 可见链节模板、履带路径重建和渲染。
- 第二套全局物理世界或调度器。
- 关节载荷/冲量：当前 Bullet wrapper 不维护该值，核心不提供伪造字段。

## 3. 地形职责

核心库保留的通用地形能力：

```text
LevelChunk
 -> SectionSnapshot
 -> TerrainShapePlan
 -> 静态 AABB 刚体
```

要求：

- 保留完整方块合并。
- 保留非完整方块的多 AABB。
- 保留 revision、区块卸载和 Level 清理。
- 不生成车辆高度场、楔面或支撑对。

## 4. TrackRun 职责

详见 [`TRACK_RUN_API.md`](TRACK_RUN_API.md)：

- 只描述物理 link、pin/hinge、碰撞过滤和 owner。
- 不包含车型角色、模型骨骼或渲染对象。
- 在物理线程创建和销毁。
- 发布不可变 `TrackRunSnapshot`。
- 通过 `withLinkBodies` 提供受控的外部约束装配点，不长期暴露 native 句柄。
- 不规定车辆前进轴、电机符号或“固定参考履带板”的闭链方案。

## 5. 插值职责

详见 [`PHYSICS_INTERPOLATION.md`](PHYSICS_INTERPOLATION.md)：

- 只插值不可变物理快照。
- 不访问 Bullet native body。
- 不生成模型变换或可见链节。

## 6. 测试职责

核心测试只验证：

- 线程所有权和生命周期。
- 普通 voxel/AABB 地形。
- 通用 body/joint 行为。
- TrackRun 创建、约束、销毁、漂移和插值。

核心测试不验证：

- 任何车型的驱动、差速、渲染或批准。
- 车辆台阶辅助。

## 7. 文档同步规则

任何核心 API、单位、线程行为或 native 依赖变化，必须更新：

- `docs/CORE_SCOPE.md`
- `docs/TRACK_RUN_API.md`
- `docs/PHYSICS_INTERPOLATION.md`
- `docs/PHYSICS_API.md`
- `docs/TRACKED_VEHICLE_SUPPORT.md`
- `docs/ARCHITECTURE.md`
- `docs/THREADING_AND_LIFECYCLE.md`
- `docs/TESTING.md`
- `docs/MCP_INTEGRATION.md`

并在 SuperbWarfare 事件账本记录 `core_commit` 和受影响阶段。
