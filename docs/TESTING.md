# 测试与诊断

更新时间：2026-10-22

地形缓存工作树 API：`TerrainRegionSnapshotTest` 覆盖 token insertion-order 稳定性、revision
敏感性、section/block 深复制与只读集合；manager 的 live cache readiness 仍由真实 Level
GameTest 覆盖。2026-10-28 Core 全量 159 项通过（新增每帧诊断值复制/插值回归，含合并请求和丢帧计数）。

## JUnit

```bash
./gradlew test
```

`NativePhysicsLoaderTest` 覆盖：

- Linux/Windows x86_64 平台选择；
- ARM64/macOS 快速拒绝；
- 内嵌 native 加载、版本与线程安全校验；
- 动态刚体在静态地面上稳定的原生求解冒烟测试。

通用 TrackRun 测试（K1 阶段已实现）：

`cn.solarmoon.spark_core.physics.track.*`：

- `TrackRunSpecTest`：合法闭链、非法几何、重复铰链、API 无车型/渲染概念、
  四元数规范化。
- `TrackRunHingeSemanticsTest`：直接测试 `TrackRunAssembly`（runtime 的生产路径）；
  铰链锁平移、8 节闭链承重、无弹簧行、默认继承世界重力、圆柱三轴。
- `TrackRunSnapshotTest`：列表复制、旋转副本、相等性、非有限检测、runId、linkBodyIds 防御性复制。
- `TrackRunHingeAngleErrorTest`：限位内为 0、超限报差值、锁轴旋转计为误差。
- `TrackRunHingeFrameTest`：identity/镜像 constraint frame、frame 内限位、
  零四元数与 NaN 拒绝、归一化。
- `TrackRunJointStateTest`：native 关节坐标与符号、未采样时不伪造误差。
- `TrackRunAssemblyFailureTest`：回滚顺序（joint 先于 body、逆序）、单个清理失败不中断、
  清理异常作为 suppressed。
- `CollisionRulesTest`：同 owner 允许列表矩阵。
- `PhysicsLifecycleGateTest`：任务接纳、启动/关闭竞态、去重接纳、关闭后不执行已排队任务、
  并发提交、关闭后拒绝、STARTING 排队、listener 恰好一次、重复关闭同一 Future、
  剩余任务失败、错误抑制、排空顺序。

`cn.solarmoon.spark_core.physics.level.*`：

- `PhysicsLifecycleGateTest`：见上。

`TrackRunGameTests`（T2，真实服务端 Level，**已执行通过，16 项总计**）：
创建、runId、逐子步快照递增、joint 帧有限、有界力、可等待关闭与清理断言；
create 完成时首帧已存在；create 在 close 之后必须失败且不复活；reset 用整体平移保持闭链几何；
`withLinkBodies` 的 link id 与快照一致；公共 Java 任务入口正常完成。

## 测试证据边界

- 单元/原生测试通过不等于 GameTest 通过，也不等于实机通过。
- T2 只在 `runGameTestServer` 真正跑过后才能标记通过，并需记录命令与结果。
  本轮已执行：16 项通过（含 TrackRun 首帧、关闭竞态、外部 link 访问与公共任务入口）。
- 编译通过不构成任何测试证据。

`cn.solarmoon.spark_core.physics.interpolation.*`：

- `PhysicsStateInterpolatorTest`：线性插值、渲染比例、最短弧、相位环绕、
  相位距离、拓扑变化、输入不可变、退化四元数。

只跑 K1 测试：

```bash
./gradlew test --offline -Dnet.minecraftforge.gradle.check.certs=false \
  --tests 'cn.solarmoon.spark_core.physics.track.*' \
  --tests 'cn.solarmoon.spark_core.physics.interpolation.*' \
  --tests 'cn.solarmoon.spark_core.physics.body.CollisionRulesTest'
```

## Forge GameTest

```bash
./gradlew runGameTestServer
```

`PhysicsGameTests` 覆盖：

- `pawnBodyUsesSpawnPosition`：普通实体代理刚体使用加入世界时的坐标；
- `pawnBodyRemovedWhenEntityLeaves`：实体离开后不会留下幽灵刚体；
- `springJointHoldsMassAboveTerrain`：`New6Dof` 弹簧关节能把质量悬挂在锚点下方；
- `asyncTerrainCollision`：真实服务端创建 `PhysicsLevel`，异步创建动态刚体，地形激活后
  刚体受重力下落并在真实方块上静止。

成功标志（本轮实际输出）：

```text
16 tests are now running!
All 16 required tests passed :)
BUILD SUCCESSFUL
```

当前批次包含 `PhysicsGameTests` 10 项与 `TrackRunGameTests` 6 项。

测试结构由 `generateGameTestStructure` 在构建目录生成，不需要手工维护 NBT 二进制。

## 核心测试边界

核心测试只验证通用物理、线程、地形和 TrackRun。以下不属于核心测试：

- 车型驱动、差速和批准。
- 车辆台阶辅助。
- 履带渲染路径和可见链节。
- 高度场、`SUPPORT_PAIR` 或楔面。

这些测试属于 SuperbWarfare，见 `SuperbWarfare:docs/physics/TEST_MATRIX.md`。

## 客户端

```bash
./gradlew runClient
```

确认日志含：

```text
Loaded Libbulletjme 23.2.0 from natives/linux/x86_64/libbulletjme.so
Kotlin For Forge Enabled!
```

Linux 没有系统 `flite` 时会记录 narrator 初始化异常，但客户端仍继续加载声音、纹理和主菜单。
这不是 Spark Core 失败。真正的物理加载失败会在模组构造阶段中止并明确报告资源路径、
平台或哈希。

## 发布审计

```bash
./gradlew build
jar tf build/libs/spark_core-1.20.1-1.0.0.jar
sha256sum build/libs/spark_core-1.20.1-1.0.0.jar
```

JAR 必须包含：

- `META-INF/mods.toml`
- `spark_core.mixins.json` 和 refmap
- `cn/solarmoon/spark_core/**`
- `com/jme3/**`
- `natives/linux/x86_64/libbulletjme.so`
- `natives/windows/x86_64/bulletjme.dll`
- `data/spark_core/structures/empty.nbt`

并应确认不存在 `net/neoforged/**`、`electrostatic4j/**` 或 JSnapLoader 类。
