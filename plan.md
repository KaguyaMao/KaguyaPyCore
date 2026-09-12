# Spark Core Forge 1.20.1 迁移实施计划

状态：首次迁移范围已实现并通过自动化验证。

## 目标和约束

- 在 `/home/shu/Java/Projects/KaguyaPyCore` 建立独立 Forge 1.20.1 项目。
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
