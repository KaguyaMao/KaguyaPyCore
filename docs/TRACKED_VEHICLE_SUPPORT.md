# 履带车辆接入 KaguyaPyCore 的边界

更新时间：2026-10-23  
状态：K1 通用能力已实现（代码归并为 `e90984a`）；车型层仍未接线

## 1. 核心库负责什么

KaguyaPyCore 只提供通用物理基础设施：

- 每 Level 一个物理线程、固定 60 Hz、每 MC tick 三子步；
- 主线程快照 -> 数值计划 -> 物理线程 Bullet 对象；
- 通用 Minecraft voxel/AABB 地形碰撞；
- 通用刚体、形状、关节、owner、碰撞组和生命周期；
- 通用动态履带运行段 `TrackRun`；
- 不可变 `PhysicsFrameSnapshot`（含通用帧诊断计数与合并/丢帧请求计数）、`TrackRunSnapshot` 和通用插值；
- 任务接纳门、可等待关闭和统一 native teardown；
- 通用 joint 局部 constraint frame 与关节坐标观测；
- 通用六轴限位辅助 `lock` / `limit` / `free`，其中 `free` 使用倒置窗口表达无界，
  并有原生测试验证其线性与角轴行为。

核心库不认识 ZTZ-99A、轮骨骼、履带节数、车型质量、差速输入或 SBW 资源路径。
核心也不规定车辆前进轴、电机符号或悬挂力律；这些由 SBW 在调用点映射。

## 2. 地形

基础地形是 Minecraft 方块 `VoxelShape` 转成的 AABB：

```text
LevelChunk -> SectionSnapshot -> TerrainShapePlan -> 静态 AABB 刚体
```

要求：

- 保留完整方块合并。
- 保留半砖、楼梯等多 AABB 形状。
- 保留 revision、区块卸载和 Level 清理。
- 不生成车辆高度场、楔面或支撑对。

## 3. 履带物理

核心库提供通用 `TrackRun`：

```text
TrackRunSpec -> 物理线程 link body + joint -> TrackRunSnapshot
```

核心库只提供 link/joint primitive，不决定：

- 轮角色和履带拓扑。
- 物理节距和可见节距。
- 驱动、差速和牵引。
- 台阶辅助。
- 渲染路径和可见链节。
- 履带包络层字段：逐轮 wrap、可见下垂 `sag`、可见节距、驱动分组、
  侧向摩擦。这些字段在 MCSP 中也不进入 `createBelt` 入参，属于可见路径或
  驱动响应层，因此更不应进入通用物理描述。

`withLinkBodies` 允许 SBW 在物理线程拿到受控的 link body 列表，用于建立外部约束或读取
接触。它不把 native 句柄变成长期公共 API，也不替 SBW 决定“闭链必须固定一块参考板”。

真实承载路径仍是：

```text
地形 AABB -> 动态履带板 -> 轮板接触 -> 负重轮 -> 悬挂 -> 车体
```

## 4. 不应进入核心库的内容

- `LocalHeightField`、`TerrainSupportField`、`HeightFieldContact`。
- `SUPPORT_PAIR`、`HEIGHT_FIELD`、`SLOPE`、`RUN_BLOCKS`。
- 供履带支撑使用的固定楔面或按车型长度计算的坡长。
- ZTZ-99A 的轮骨骼名、质量或履带 pitch。
- 车型专用的爬坡 if/else。
- 车辆前进轴约定、电机符号映射和悬挂力律。
- 把循环履带中的某块参考板固定到车体。
- 直接修改 Minecraft Entity 的位置/旋转。
- 第二套全局 Bullet world 或第二套物理调度器。
- 可见链节模板和履带渲染。

## 5. 核心测试要求

在 SBW 接线前，核心至少要有：

1. 普通 voxel/AABB 地形坐标和形状测试；
2. 通用 body/joint 单位和生命周期测试；
3. `TrackRun` 创建、约束、销毁、漂移和插值测试；
4. 物理线程创建/替换/销毁测试；
5. 区块卸载和 Level 销毁清理测试；
6. 插值不写回物理的测试；
7. 任务接纳与关闭竞态测试；
8. 部分装配失败回滚测试；
9. 非 identity joint frame 与关节坐标测试。

## 6. 与 SBW 的接口边界

SBW 负责：

- `TrackedVehicleSpec`、轮角色、物理运行段和车型数据；
- 车体/板/轮的碰撞组和 owner；
- 悬挂、张紧、传动、差速、输入和渲染状态；
- 台阶辅助和普通地形策略；
- 车型批准、测试记录和常规获取过滤。

SBW 不得绕过核心线程规则创建或销毁 native 对象。核心 API 变化必须先更新本文件、
`PHYSICS_API.md`、`TRACK_RUN_API.md`、`PHYSICS_INTERPOLATION.md`、
`THREADING_AND_LIFECYCLE.md` 和下游状态记录。

## 6.1 当前下游状态（2026-10-26）

- SBW 消费 `cn.solarmoon:spark_core:1.20.1-1.3.2`（本地 `publishToMavenLocal`），
  并实际 import `TrackRunSpec`、`TrackRunRuntime` 等类型。
- `TrackRunRuntime` 已在 SBW S2 接线：`TrackedVehicleAssemblyLedger` 持有生产 runtime，
  车辆创建/回滚/关闭都走它。跨仓消费已越过类型与编译层。
- 1.3.2 新增 `PhysicsLevel.addShutdownParticipant`（关闭前有序 native 清理）、公开
  `addCloseListener`，以及 `TrackRunRuntime.closeNow` / `resetNow` 同步入口。
  关闭 participant 运行在物理线程、停止接纳之后、teardown 之前，不得再 `submitTask`。

## 7. 已撤销接口

以下接口不再属于核心库目标范围：

- `LocalHeightField`
- `TerrainSupportField` / `TerrainSupportFieldBuilder`
- `GroundSupportPair` / `SUPPORT_PAIR`
- `TerrainSlopePlanner` / `TerrainSlopeBodies` / `SLOPE`
- `ProbeSuspensionUnit`
- `PlanarTrackSupport`

清理计划见 `SuperbWarfare:docs/physics/LEGACY_CLEANUP.md`。
