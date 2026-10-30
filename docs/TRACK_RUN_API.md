# 通用 TrackRun API

更新时间：2026-10-23  
状态：代码已归并为 `e90984a`（不含文档）；T0/T1 通过（核心全量 151 项，0 失败）；T2 曾通过 16 项 GameTest，本轮未重跑  
对应下游文档：`SuperbWarfare:docs/physics/CORE_API_CONTRACT.md`

## 1. 目标

为下游提供通用动态履带运行段的物理创建、销毁和状态读取能力。核心库不认识车型、轮角色、
模型骨骼或渲染对象。

## 2. 已实现类型

```text
src/main/kotlin/cn/solarmoon/spark_core/physics/track/
  TrackRunSpec.kt             描述、校验、拓扑、预算、不可变旋转
  TrackRunSnapshot.kt         不可变快照、link 帧、joint 帧
  TrackRunAssembly.kt         纯 Bullet 逐项装配（runtime、下游 native 诊断测试共用；
                              公开为构造单个 link body 的受控入口，不替代 TrackRunRuntime）
  TrackRunAssemblyLedger.kt   部分装配的回滚账本
  TrackRunCapture.kt          物理线程读取、关节坐标与误差测量
  TrackRunInputReceipt.kt     输入接纳结果与完成 Future
  TrackRunLimits.kt           输入钳制
  TrackRunRuntime.kt          PhysicsLevel 生命周期、事务创建、快照发布、输入
  TrackMath.kt                包内有限性判断
```

### 2.1 `TrackRunSpec`

不可变描述：

```text
name
links: massKg, shape(Box|Cylinder), position, rotation(x,y,z,w)
joints: previousIndex, nextIndex, pivotInPrevious, pivotInNext, axis, halfAngleRad,
        frameRotationInPrevious, frameRotationInNext
collisionGroup / collideWithGroups / collideWithOwnerGroups
linearDamping / angularDamping / friction / restitution / continuousCollision
explicitGravity: Vec3?          // null = 继承世界重力
maxLinkForceN                   // 单次力的上限，N
maxLinkTorqueImpulseNms         // 单次力矩冲量的上限，N*m*s
```

构造时执行 `problems()` 校验并抛出 `IllegalArgumentException`。规则：

- link 非空，最多 4096；joint 最多 8192。
- 质量、形状尺寸、位置、四元数、显式重力和预算必须有限；质量、尺寸和预算大于 0。
- 四元数不能是零向量；旋转以四个标量保存，读取 `rotation` 返回副本。
- 两端 joint frame 也必须有限且非零，装配前归一化。
- 多 link 必须有 joint。
- 同一对 link 不能重复连接（正反方向都拒绝），不能自连。
- 每个 link 至少连接一个 joint，最多连接两个。
- 所有 link 必须属于同一个连通运行段。
- 恢复系数在 `[0,1]`，阻尼和摩擦非负。

`links` 和 `joints` 在构造时复制。不包含：车型 ID、左右履带语义、轮角色、模型骨骼、
可见链节、地形策略。

**也不包含履带包络层字段。** 逐轮 wrap、可见下垂 `sag`、可见节距、驱动分组
和侧向摩擦都属于车型几何、渲染或驱动策略，由 SBW 持有。核心只接受已解算的
link 形状、质量、销点与铰链。若下游发现缺少某项非车型能力，应先评估是否属于
通用物理原语，而不是直接把 MCSP 的字段搬进核心。

### 2.2 重力语义

默认**不调用** `setProtectGravity`，link 继承 `PhysicsWorld` 的世界重力。只有
`explicitGravity` 非空时才保护并设置该向量。核心库从不修改世界重力。

### 2.3 碰撞过滤

`collideWithOwnerGroups` 是**同 owner 的允许列表**，不是拒绝列表：

| 组合 | 默认行为 |
|---|---|
| link ↔ link（同 owner） | 过滤 |
| link ↔ 同车车轮 | 碰撞 |
| link ↔ 同车车体 | 碰撞 |
| link ↔ 地形 | 碰撞 |

规则实现在 `CollisionRules`，不依赖 native 对象，因此可以直接单测。

TrackRun 未传入 owner 时，runtime 会分配一个**私有 owner token**，而不是把 `owner == null`
当作“同一 owner”。否则世界中任意两个无 owner 刚体都会被同 owner 规则过滤，互相穿透。
私有 token 只让同一运行段的 link 互相过滤，不影响它们与其他无 owner body 的正常碰撞。

### 2.4 `TrackRunSnapshot`

```text
name, runId, poseEpoch, physicsFrame, revision
links: position, rotation(x,y,z,w), linearVelocity, angularVelocity, active, colliding
joints: previousIndex, nextIndex, angleX/Y/Z, positionError, angleError
jointPositionError, jointAngleError
linkBodyIds
```

- link、joint 和 `linkBodyIds` 在构造时复制。
- 旋转读取返回新对象，消费者无法改写已发布快照。
- `runId` 是产生该快照的 runtime 身份，用于拒绝跨运行段插值。
- `poseEpoch` 在 reset/teleport 时递增；不同 epoch 的快照不得插值。
- `physicsFrame` 是 `PhysicsLevel.tickCount`，与 `PhysicsFrameSnapshot.tick` 同一计数器，
  因此两类快照可按帧号对齐。
- `PhysicsFrameSnapshot.metrics` 提供帧调度分项耗时、合并/丢帧请求计数和 body/回调访问计数，仅供诊断；不属于
  TrackRun 语义、不参与插值，也不是性能验收阈值。
- `linkBodyIds` 按 spec 顺序给出每个 link 的稳定 `bodyId`，供下游关联外部约束与接触，
  不必依赖 runtime 内部命名。
- `jointPositionError` 是两个世界坐标销点的距离。
- `jointAngleError` 是**超出铰链允许范围的角度**加锁轴坐标偏差，合法转动记为 0；
  它直接由 native 约束坐标计算，不使用姿态轴角近似。
- `joints[i].angleX/Y/Z` 来自 native `New6Dof.getAngles`，是约束坐标（弧度），
  不是姿态近似值；仅在采样它的那个子步内有效。
- **不发布关节载荷/冲量。** 本 wrapper 的 6-Dof 约束不维护 `appliedImpulse`
  （无论载荷多大都读回 0），因此不提供该字段，避免伪造数据。
  接触冲量可用于估计接触载荷与牵引反馈，但不能唯一恢复闭链内单个关节的反力；
  不要把二者等同。

### 2.5 `TrackRunRuntime`

- 构造只保存 spec，不创建 Bullet 对象；分配稳定 `runId`。
- `create()`：物理线程**事务式**装配 link 和 `New6Dof`。
  - 每个对象创建后立即记入 `TrackRunAssemblyLedger`（在配置其余属性之前），
    因此属性配置失败也不会留下无人拥有的 native 对象。
  - 只有全部注册、listener 安装和**首帧采集**都成功后，运行段才原子提交为 `ACTIVE`；
    `create()` 完成时 `snapshot()` 已包含首帧，而不是等下一个子步。
  - 任一步失败：先逆序移除 joint，再逆序移除 body，清理异常挂到原异常上后重新抛出。
  - 若回滚本身失败，残留对象仍由 runtime 记账，`closeAsync()` 会重试，不能报告成功。
  - Level 不接受任务或运行段已先关闭时，`create()` 立即以异常结束；关闭后不会“复活”。
- **每个物理子步结束后自动发布快照**，由 `PhysicsLevel.addPostStepListener` 驱动。
- `publish()`：物理线程内手动刷新；正常路径不需要调用。
- `snapshot()`：任意线程读取最新快照。
- `reset(poses)`：校验数量、有限性和 Float 可表示性，入队前复制列表；整体传送，不重建 joint；
  同时递增 `poseEpoch`，避免插值跨越传送点。
- `withLinkBodies { bodies -> ... }`：在物理线程以 spec 顺序提供当前 native link body，
  供下游建立外部约束或读取接触；回调返回后不得保留列表或 body。
  回调创建的 joint 由调用方拥有，必须在本运行段关闭前移除。
- 输入接口区分「已接纳」与「已施加」：

  | 方法 | 返回 |
  |---|---|
  | `applyLinkForce(index, force)` | 钳制后的**接纳**大小 |
  | `applyLinkForceAsync(index, force)` | `TrackRunInputReceipt.Future` |
  | `applyLinkTorqueImpulse(index, axis, magnitude)` | 钳制后的**接纳**大小 |
  | `applyLinkTorqueImpulseAsync(...)` | `TrackRunInputReceipt.Future` |

  `Future.completion` 在物理线程真正执行后完成；运行段先关闭时以异常完成。
- `setLinkGravity(index, gravity)`：只改该 link，不动世界。
- `linkVelocity(index)`：仅物理线程可读；被钉住（零质量）的 link 返回零速度。
- `closeAsync()`：幂等，同一 runtime 返回同一 Future。
  - Level 仍在接纳任务：排入物理线程，移除 joint → body 后完成。
  - Level 已停止/失败：不触碰 native 对象，改为在 Level 的统一 teardown 完成后完成，
    因此不会把「还没清理」当成成功。
  - 关闭在 create 之前被接纳时，create Future 以异常结束，运行段不会在关闭后复活。
- `close()`：`closeAsync()` 的便捷形式。
- Level 关闭或 FAILED 时，runtime 收到通知、丢弃引用并使未完成任务失败。

## 3. 铰链语义

- 三个线性自由度全部锁定。
- 只允许一个角自由度在 `±halfAngleRad` 内转动。
- 其余两个角自由度锁定。
- 不启用任何内置弹簧；张力属于轮包络和驱动耦合。
- `frameRotationInPrevious/Next` 指定两端局部 constraint frame；`axis` 在该 frame 中解释。
  两端初始姿态不同或左右镜像时必须显式给出，identity 默认保持旧行为。
- 圆柱形状的 `axis` 决定长轴，X/Y/Z 都被支持。

`TrackRunAssembly` 是 public 的逐项装配 helper：下游要在自己的 `PhysicsSpace` 里创建
与 runtime 完全相同的 link body 时使用它，而不是复制 shape/owner/mask/damping 配置。
它不管理生命周期、线程、快照或回滚账本，那些仍属于 `TrackRunRuntime`；生产车辆必须走
`TrackRunRuntime.create()` 与 `withLinkBodies(...)`。

`TrackRunHingeSemanticsTest` 与 `TrackRunHingeFrameTest` 直接测试 `TrackRunAssembly`，
即 runtime 使用的同一路径：

- 铰链保持 link 间距。
- 8 节闭链在重力下保持闭合。
- 铰链没有弹簧行。
- 默认 link 受世界重力影响；`explicitGravity` 可选退出。
- 圆柱三轴形状的包围盒正确。
- 非 identity 与镜像 frame 下闭链仍闭合，且误差按 frame 测量。

## 4. 驱动边界

核心库只提供低层能力：

- link 力矩冲量（有上限）。
- link 中心力（有上限）。
- 每 link 重力向量。
- 每子步关节坐标观测。
- `withLinkBodies` 提供的受控外部约束装配点。

差速、油门、制动、目标带速、滑移预算和反作用由 SBW 决定。核心库不包含任何车辆控制逻辑。

核心不提供“把参考履带板固定到车体”之类的闭链方案：循环履带必须能相对轮组运动，
真实承载路径仍是地形 → 履带板 → 轮板接触 → 负重轮 → 悬挂 → 车体。

## 5. 测试

T0/T1（归并提交 `e90984a` 后重验，2026-10-26 核心全量 151 项 0 失败）：

- `TrackRunSpecTest`：合法闭链、非法几何、正反向重复、拓扑连通性、不可变性、
  预算校验与钳制、joint frame 校验、Float 可表示性与大数四元数归一化。
- `TrackRunSnapshotTest`：列表复制、旋转副本、相等性、非有限检测、runId、linkBodyIds 防御性复制。
- `TrackRunHingeSemanticsTest`：生产装配路径、闭链承重、无弹簧、重力语义、圆柱轴向。
- `TrackRunHingeFrameTest`：identity/镜像 frame、frame 内限位、零四元数与 NaN 拒绝、
  归一化。
- `TrackRunJointStateTest`：native 关节坐标、坐标符号、未采样时不伪造误差。
- `TrackRunAssemblyFailureTest`：回滚顺序（joint 先于 body、逆序）、单个清理失败不中断、
  清理异常作为 suppressed、失败残留保留所有权、配置前已记账。
- `TrackRunHingeAngleErrorTest`：native 约束坐标的限位内/超限/锁轴判定，以及
  几何 swing-twist 检查的符号与 q/-q 等价性。
- `CollisionRulesTest`：同 owner 允许列表矩阵。
- `PhysicsStateInterpolatorTest`、`SnapshotIdentityTest`：见 `PHYSICS_INTERPOLATION.md`。
- `PhysicsLifecycleGateTest`：任务接纳、启动/关闭竞态、去重接纳与关闭后不执行，
  见 `THREADING_AND_LIFECYCLE.md`。

T2（已执行，`./gradlew runGameTestServer --offline -Dnet.minecraftforge.gradle.check.certs=false`，
16 项全部通过，`All 16 required tests passed`）：

- `TrackRunGameTests.trackRunPublishesEverySubstepAndClosesCompletely`
  （含 runId、joint 帧、关闭 Future 与清理断言）
- `TrackRunGameTests.createPublishesTheFirstFrameBeforeCompleting`
- `TrackRunGameTests.createAfterCloseFailsInsteadOfRevivingTheRun`
- `TrackRunGameTests.withLinkBodiesRunsOnThePhysicsThreadAndMatchesTheSnapshot`
- `TrackRunGameTests.thePublicJavaTaskEntryCompletesInsteadOfDroppingWork`
- `TrackRunGameTests.resetTranslatesTheWholeAssemblyWithoutBreakingPins`

## 6. 退出条件

- API 不出现 SBW、车型、模型或高度场概念：已满足。
- 原生测试通过，native 访问全部在物理线程：已满足。
- 快照逐子步发布且不可变，携带稳定 runId、poseEpoch、linkBodyIds 与 joint 坐标：
  T0/T1 覆盖。
- 生命周期幂等，Level 关闭不留幽灵对象：T0/T1/T2 覆盖。
- 公共 Java/Kotlin 任务入口不会因关闭竞态留下挂起 Future：T0/T2 覆盖。
- 文档和下游事件账本同步：本轮完成；T2 已执行通过，K1 仅剩提交动作。

## 7. 尚未实现

- 可见链节或渲染路径：属于 SBW。
- 轮包络求解：属于 SBW。
- 驱动耦合和差速：属于 SBW。
- 地形策略和台阶辅助：属于 SBW。
- 关节载荷/冲量观测：当前 wrapper 不支持，见 2.4；不得伪造。
- 单个闭链关节反力的恢复：需要额外求解，当前不提供。
- 车辆专用的电机符号、悬挂力律和轮包络：属于 SBW。
- `TrackRunHandle`：当前由 runtime 本身充当句柄；若下游需要独立句柄再补。
