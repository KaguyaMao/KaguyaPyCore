# Spark Core Forge 1.20.1 迁移实施计划

状态：首次迁移范围已实现并通过自动化验证。

## 目标和约束

- 原迁移在历史路径 `/home/shu/Java/Projects/KaguyaPyCore` 建立独立 Forge 1.20.1 项目。该路径只说明当时的来源，不是当前工作目录；后续引用使用仓库内路径。
- 模组 ID 保持 `spark_core`，Java 17，官方 1.20.1 mappings。
- 迁移 `PhysicsLevel`、异步物理线程、地形碰撞和实体刚体 API。
- 使用现成 Libbulletjme，不引入物理前置模组。
- Windows/Linux x86_64 使用同版本线程安全原生库；不支持的平台快速失败。
- 优先保持调用模型与源代码概念兼容，不追求跨 Minecraft 版本二进制兼容。

## 已完成阶段

1. 工程基线
   - ForgeGradle 6、Forge 47.4.22、Kotlin 2.0、Kotlin for Forge 4.11.0。
   - `mods.toml`、Mixin、AT、客户端/服务端/GameTest/DataGen run 配置。
2. Libbulletjme 集成
   - 固定 22.0.3 wrapper 与 `SpMtRelease` 原生包。
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
- `./gradlew runGameTestServer` 报告 `All 1 required tests passed` 并自动退出。
- `./gradlew runClient` 日志包含 `Loaded Libbulletjme 22.0.3`，无 Spark Core 致命错误。
- `./gradlew build` 成功。
- 成品 JAR 包含 `com/jme3/**`、Linux/Windows x86_64 原生库、Mixin 和 `mods.toml`。
- 成品 JAR 不包含 NeoForge 或 Electrostatic4j/JSnapLoader 类。

## 后续可选阶段

- 增加 macOS/ARM64 原生矩阵，但必须为每个平台补齐独立制品、哈希与 CI 真机验证。
- 添加调试渲染、性能计数器和配置文件。
- 为碰撞形状做更强的网格合并和跨区段缓存。
- 按实际下游模组编译错误补齐原项目修改版 Libbulletjme 的 Java 源兼容 facade。
- 迁移 Spark Core 的非物理模块；这些不属于本次首次迁移。

## 下游履带物理支撑阶段（未完成）

这不是首次迁移验收的一部分，而是为 SuperbWarfare 新履带架构建立通用核心契约的后续
阶段。当前不能把下游 `LocalHeightField` 采样辅助或 SBW 的候选 `TrackedRig` 视为已经
完成的高度场支撑。

1. 定义纯数据 `TerrainSupportField` / `HeightFieldContact`：有效性、raw/support 高度、
   法线、signed distance/penetration、surface class、friction 和 terrain revision。
   2026-10-19 已加入未接线 builder，并按审查修订表面覆盖、层连通、角点补丁和未知分类。
   不下削支撑对已由区段物理线程安装和清理，但现有车辆掩码碰不到它。
   当前 query 只是向下采样，不是 penetration 求解。
2. 在不破坏“主线程快照 -> builder 纯数值计划 -> 物理线程 Bullet”的边界下实现有限
   向下平滑和无效表面传播规则；未知、空气、洞和越界不得伪造支撑。
3. 证明支撑表面能够在 Bullet 求解前生成和移除真实接触，或实现等价的物理线程支撑
   collision shape；不能只在探针 miss 后提供法线。
4. 添加 T0/T1 坐标、插值、层、硬软类别、revision、接触时序、落体、分离、摩擦和
   区块卸载测试。
5. 每次核心 API、native 单位或线程生命周期变化都要在下游 SuperbWarfare 的事件账本
   中记录 `core_commit` 并让受影响车型回到待复核状态。

配套技能：`.opencode/skills/physics-core-support/SKILL.md`。配套记录边界：
`docs/MCP_INTEGRATION.md`。
