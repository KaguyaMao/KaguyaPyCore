# 物理状态插值

更新时间：2026-10-22  
状态：K1 已实现并提交；T0 测试通过（含 body 帧与身份校验）

## 1. 目标

为下游提供通用物理快照插值，使客户端能在两个权威物理帧之间平滑渲染，而不访问 Bullet
native 对象，也不修改物理状态。

## 2. 已实现

```text
src/main/kotlin/cn/solarmoon/spark_core/physics/interpolation/PhysicsStateInterpolator.kt
```

| 方法 | 作用 |
|---|---|
| `vector(from, to, alpha)` | 位置/速度线性插值，比例钳制到 `[0,1]` |
| `rotation(from, to, alpha)` | 四元数最短弧插值，线性混合后归一化 |
| `phase(from, to, alpha, cycle)` | 周期相位按最短弧插值，结果落在 `[0,cycle)` |
| `phaseDistance(a, b, cycle)` | 相位距离，最大 `cycle/2`，仅用于诊断 |
| `aabb(from, to, alpha)` | AABB 六面线性插值 |
| `link(from, to, alpha)` | 单个 TrackRun link 位姿/速度插值 |
| `trackRun(from, to, alpha)` | 整段插值；身份或拓扑不一致时返回新快照 |
| `body(from, to, alpha)` | 单个 `BodyFrame` 插值 |
| `physicsFrame(from, to, alpha)` | 按稳定 bodyId 插值整帧 |
| `ratio(previousNanos, currentNanos, renderNanos)` | 渲染比例；退化输入返回 1 |

## 3. 规则

- 只消费不可变快照。
- 不持有 native 指针。
- 不修改物理 body。
- 不访问 Minecraft 世界或实体。
- 不生成渲染对象。
- 四元数退化为零向量时回退到源位姿。
- `TrackRunLinkFrame` 和 `BodyFrame` 以四个标量保存旋转，读取 `rotation` 返回副本。
- 周期相位不整圈回退。

### 3.1 身份与拓扑

- `TrackRunSnapshot.runId` 必须相同，且 `name` 与 link 数一致（`sameTopologyAs`）；
  `poseEpoch` 也必须相同（`samePoseSegmentAs`），否则直接采用新快照，绝不跨运行段或
  跨传送点混合。
- `BodyFrame.bodyId` 必须相同；不同 body 的帧不混合。
- `PhysicsFrameSnapshot` 只有在 `epoch` 和 `bodiesById` 键集合完全一致时才逐 body 插值；
  世界重建或 body 增删直接返回新帧。
- 插值后的整帧同时重写 `bodies` 与 `bodiesById`，两者不会互相矛盾。
- native 关节坐标属于目标子步的采样，不做数值插值；`trackRun()` 原样保留 `joints`。
- `linkBodyIds` 原样保留，供下游把插值后的 link 与外部约束/接触对应起来。

### 3.2 连续与离散字段

`body()` 对位置、旋转、AABB、线速度、角速度做插值；`active`、`colliding`、
`contactResponse`、`activationState` 是离散量，按 `alpha < 0.5` 取前一帧、否则取后一帧，
避免出现「半个激活」或「半个碰撞响应」的中间态。

AABB 插值只对同一 body 有效；形状改变时调用方应视为拓扑变化，直接使用新帧。

### 3.3 帧对齐

`TrackRunSnapshot.physicsFrame` 与 `PhysicsFrameSnapshot.tick` 来自同一个
`PhysicsLevel.tickCount`，因此车体帧和运行段帧可以按帧号对齐。下游不得把 tick 末帧的
车体状态与中间子步的履带状态混合而不检查帧号。

## 4. 测试

`PhysicsStateInterpolatorTest`（8 项）：

- 位置和速度线性插值。
- 渲染比例钳制和退化帧对。
- 350°→10° 跨接缝取最短弧，中点为 0°/360°，四分之一处为 355°。
- 相位 9→1（周期 10）中点为 0。
- 相位距离。
- link 插值。
- 拓扑变化返回新快照。
- 退化四元数回退。

`SnapshotIdentityTest`（13 项）：

- runId 传递、同名不同 run 不混合、插值保留目标 runId。
- `BodyFrame` 旋转读取不共享引用。
- 不同 bodyId 不混合。
- 连续字段插值、离散字段取较近帧、AABB 插值。
- body 集合不一致时整帧返回新值；一致时按 id 混合。
- native 关节采样与 linkBodyIds 在插值后保留。
- `poseEpoch` 或 world `epoch` 改变时不插值。
- `PhysicsFrameSnapshot` 构造时复制来源集合。
- `PhysicsStepMetrics` 随目标帧保留，不对性能计数做数值插值。
