# Spark Core Forge 1.20.1 迁移实施计划

状态：首次迁移范围已实现并通过自动化验证；下游履带物理支持按 MCSP 风格架构重新规划。

## 目标和约束

- 模组 ID 保持 `spark_core`，Java 17，官方 1.20.1 mappings。
- 迁移 `PhysicsLevel`、异步物理线程、地形碰撞和实体刚体 API。
- 使用现成 Libbulletjme，不引入物理前置模组。
- Windows/Linux x86_64 使用同版本线程安全原生库；不支持的平台快速失败。
- 优先保持调用模型与源代码概念兼容，不追求跨 Minecraft 版本二进制兼容。
- 核心库只提供通用 Bullet 能力，不实现车辆专用地形、驱动或渲染。

## 已完成阶段

1. 工程基线
   - ForgeGradle 6、Forge 47.4.22、Kotlin 2.0、Kotlin for Forge 4.11.0。
   - `mods.toml`、Mixin、AT、客户端/服务端/GameTest/DataGen run 配置。
2. Libbulletjme 集成
   - 固定 23.2.0 wrapper 与 `SpMtRelease` 原生包。
   - 构建时提取两平台原生库，发布 JAR 内嵌 wrapper。
   - 运行时按平台、架构、SHA-256、版本和线程安全标记校验。
3. PhysicsLevel 生命周期
   - `Level` Mixin 注入客户端/服务端物理实例。
   - 60 Hz 固定步长、单飞帧调度、丢帧计数、故障恢复和关闭清理。
   - 不可变帧快照作为物理线程到主线程的数据边界。
4. 地形碰撞
   - 区块事件、区段快照、异步形状计划、物理线程原生对象替换。
   - 动态刚体激活范围、横向两区块预取、纵向有限预取、脏区段更新和客户端通知。
5. 实体与刚体 API
   - Level/Entity patch、物理宿主、body 元数据和事件。
   - 抽象 `CollisionObjectEntity` / `RigidBodyEntity` 同步与 NBT。
   - Java `SparkPhysics`、`PhysicsTasks`、`PhysicsBodies` 和 Kotlin 扩展。
6. 验证与文档
   - JUnit 验证平台选择、原生加载和独立刚体求解。
   - Forge GameTest 验证真实服务端 Level、异步线程、Minecraft 地形和刚体快照。
   - `runClient` 验证到主菜单资源加载阶段。
   - 架构、集成、迁移矩阵、线程、地形、API、测试与许可文档。

## 验收标准

- `./gradlew test` 成功。
- `./gradlew runGameTestServer` 报告 `All 16 required tests passed` 并自动退出。
- `./gradlew runClient` 日志包含 `Loaded Libbulletjme 23.2.0`，无 Spark Core 致命错误。
- `./gradlew build` 成功。
- 成品 JAR 包含 `com/jme3/**`、Linux/Windows x86_64 原生库、Mixin 和 `mods.toml`。
- 成品 JAR 不包含 NeoForge 或 Electrostatic4j/JSnapLoader 类。

## 后续可选阶段

- 增加 macOS/ARM64 原生矩阵，但必须为每个平台补齐独立制品、哈希与 CI 真机验证。
- 添加调试渲染、性能计数器和配置文件。
- 为碰撞形状做更强的网格合并和跨区段缓存。
- 按实际下游模组编译错误补齐原项目修改版 Libbulletjme 的 Java 源兼容 facade。
- 迁移 Spark Core 的非物理模块；这些不属于本次首次迁移。

## 下游履带物理支持阶段（K1 已实现并提交，T0/T1/T2 通过）

这不是首次迁移验收的一部分，而是为 SuperbWarfare MCSP 风格履带架构建立通用核心能力。

1. 已定义 `TrackRunSpec`、`TrackRunLinkSpec`、`TrackRunJointSpec`、`TrackRunRuntime` 和
   `TrackRunSnapshot`。只描述物理运行段，不含车型、轮角色、模型骨骼或可见链节。
2. 在物理线程创建 link body 和 joint，设置 owner、碰撞组和同 owner 允许列表；创建、
   reset、remove、Level unload 和失败恢复必须幂等。创建事务化，首帧在提交前采集；
   快照逐物理子步发布，关闭可等待且 native 清理失败不会伪装成功。
3. 提供有上限的通用力/力矩冲量接口；差速和目标速度由 SBW 决定。
   `withLinkBodies` 提供受控的物理线程 link 访问点，供外部约束使用。
4. 已提供 `PhysicsStateInterpolator`：只消费不可变快照，不访问 native body，不生成渲染对象；
   保留 native 关节采样与 linkBodyIds，不跨 reset/world 恢复点插值。
5. 保留普通 Minecraft voxel/AABB 地形碰撞；不新增车辆高度场、`SUPPORT_PAIR` 或楔面。
6. 公共 `PhysicsTasks.call/run` 与 `PhysicsLevel.submitTask` 共用接纳门，关闭竞态不会留下
   挂起 Future；启动完成与关闭状态转换原子化。
7. 本地构建需加 `-Dnet.minecraftforge.gradle.check.certs=false`；ForgeGradle 的环境检查
   会在插件应用阶段做 SSL 连接，`--offline` 不能跳过。
8. 每次核心 API、native 单位或线程生命周期变化都要在下游 SuperbWarfare 的事件账本中
   记录 `core_commit` 并让受影响车型回到待复核状态。

配套文档：

- `docs/CORE_SCOPE.md`
- `docs/TRACK_RUN_API.md`
- `docs/PHYSICS_INTERPOLATION.md`
- `docs/TRACKED_VEHICLE_SUPPORT.md`

配套技能：`.opencode/skills/physics-core-support/SKILL.md`、
`.opencode/skills/track-run-api/SKILL.md`、
`.opencode/skills/physics-core-testing/SKILL.md`。

## 已撤销的下游支持

以下方案不再属于核心库：

- `LocalHeightField`
- `TerrainSupportField` / `TerrainSupportFieldBuilder`
- `GroundSupportPair` / `SUPPORT_PAIR`
- `TerrainSlopePlanner` / `TerrainSlopeBodies` / `SLOPE`
- `ProbeSuspensionUnit`
- `PlanarTrackSupport`

清理计划见 `SuperbWarfare:docs/physics/LEGACY_CLEANUP.md`。
