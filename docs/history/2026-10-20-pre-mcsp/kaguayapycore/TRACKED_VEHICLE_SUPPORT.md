# 履带车辆接入 KaguyaPyCore 的边界

更新时间：2026-10-19  
状态：SBW 新履带架构的核心库配套设计，尚未实现全部 API

## 1. 核心库负责什么

KaguyaPyCore 只提供通用物理基础设施：

- 每 Level 一个物理线程、固定 60 Hz、每 MC tick 三子步；
- 主线程快照 -> builder 纯数值计划 -> 物理线程 Bullet 对象；
- 高度场/支撑表面的纯数据结构、版本、查询和失效边界；
- 求解前接触仲裁或等价的高度场支撑 shape 接口；
- 关节创建、单位语义、owner 元数据和生命周期 API；
- 不可变 PhysicsFrameSnapshot 和物理事件边界。

核心库不认识 ZTZ-99A、轮骨骼、履带节数、车型质量、差速输入或 SBW 资源路径。

## 2. 高度场要求

对于下游已批准的履带车辆，核心必须支持由高度场直接提供地面接触：

```text
TerrainSnapshot -> TerrainSupportField -> HeightFieldContact -> Bullet constraint
```

当前事实：`LocalHeightField` 只是采样参考，SBW 的 `VehicleBody.trackGroundFallback` 仍在探针 miss 后使用它。核心库仍保留 `TerrainSlopePlanner` 源码，但实时 `TerrainShapePlan` 已不再生成楔面。区段物理线程已安装 `LIVE` 高度场支撑对（1 格共享边下削），但 `DRIVABLE` 不包含它，现有车辆仍走原始方块顶面。点查询也不是形状接触。

目标禁令：下游新履带路径不得再把它当作探针 fallback。需要补充的授权接口包括有效/未知状态、表面层、有限下削误差、版本、跨 section 连续性和接触 authority。未知列不能用低高度伪造。这些接口完成前，不得写“核心库已经提供唯一支撑”。

## 3. 不应进入核心库的内容

- 供履带支撑使用的 `RUN_BLOCKS`、按车型长度计算的坡长或固定楔面。当前核心库仍保留 `TerrainSlopePlanner` 源码和 `RUN_BLOCKS = 6`，但实时区段不再安装其楔面；这是待清理现状，不是允许继续作为履带支撑的目标。
- ZTZ-99A 的轮骨骼名、质量或履带 pitch；
- 车型专用的爬坡 if/else；
- 直接修改 Minecraft Entity 的位置/旋转；
- 第二套全局 Bullet world 或第二套物理调度器。

## 4. 核心测试要求

在 SBW 接线前，核心至少要有：

1. 高度场坐标/插值/法线/有限下削测试；
2. 空气、洞、未知区块、多层表面和硬软边界测试；
3. 版本更新和旧计划丢弃测试；
4. 物理线程创建/替换/销毁支撑对象测试；
5. 动态板/箱体在高度场上的落体、分离、摩擦和失效测试；
6. 接触回调确实发生在 Bullet 求解前的时序测试；若不能证明，则改用支撑 collision shape。

## 5. 与 SBW 的接口边界

SBW 负责：

- `TrackedVehicleSpec`、轮板闭链和车型数据；
- 车体/板/轮的碰撞组和 owner；
- 传动、差速、输入和渲染状态；
- 车型批准、测试记录和常规获取过滤。

SBW 不得绕过核心线程规则创建或销毁 native 对象。核心 API 变化必须先更新本文件、
`PHYSICS_API.md`、`THREADING_AND_LIFECYCLE.md` 和下游状态记录。
