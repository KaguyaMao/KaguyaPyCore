# 可复用开发 Prompt

你正在维护当前检出的 KaguyaPyCore 仓库。它是从历史路径
`/home/shu/IdeaProjects/Spark-Core-1201-1.21.1-neoforge` 独立迁移出的 Forge 1.20.1
Spark Core，模组 ID 必须保持 `spark_core`。历史机器路径只说明来源，不是当前工作目录。

先完整阅读 `README.md`、`plan.md`、`docs/CORE_SCOPE.md`、`docs/ARCHITECTURE.md`、`docs/THREADING_AND_LIFECYCLE.md`、`docs/TERRAIN_COLLISION.md`、`docs/PHYSICS_API.md`、`docs/TRACK_RUN_API.md`、`docs/PHYSICS_INTERPOLATION.md`、`docs/TRACKED_VEHICLE_SUPPORT.md`、`docs/MCP_INTEGRATION.md` 和 `docs/TESTING.md`，再修改代码。若任务来自 SuperbWarfare 履带物理，还要阅读 SuperbWarfare 仓库内 `docs/physics/CONSTRAINTS.md`、`docs/physics/ARCHITECTURE.md` 和 `docs/physics/MCP_OPERATIONS.md`。跨仓库引用写成 `SuperbWarfare:docs/physics/...`。

核心库只提供通用 Bullet 能力：物理线程、body/shape/joint 生命周期、通用 `TrackRun`、不可变快照和插值、普通 Minecraft voxel/AABB 地形。不要在核心库实现车辆高度场、`LocalHeightField`、`TerrainSupportField`、`SUPPORT_PAIR`、楔面、台阶辅助、车型驱动或履带渲染；这些属于 SuperbWarfare。

固定技术基线：Minecraft 1.20.1、Forge 47.4.22、ForgeGradle 6、Java 17、Kotlin 2.0.0、Kotlin for Forge 4.11.0、Libbulletjme 23.2.0。首版原生平台仅 Windows/Linux x86_64，必须使用 `SpMtRelease`。不要加入 NeoForge API、Electrostatic4j、JSnapLoader 或物理前置模组。

线程规则不可破坏：

1. Minecraft 主线程负责读取 Level/Chunk/BlockState、提交命令和消费不可变帧快照。
2. 地形 builder 线程只处理不含 Level 引用的 `SectionSnapshot`，只输出纯 JVM `TerrainShapePlan`。
3. 只有对应 `PhysicsLevel` 的物理线程可以创建、修改、加入、移除或销毁 Bullet 原生对象。
4. 物理线程不得直接读写 Minecraft 世界或实体可变状态。
5. 跨线程结果必须通过 `PhysicsFrameSnapshot`、并发队列或 future 传递。

实现修改时优先沿用 `SparkPhysics`、`PhysicsTasks`、`PhysicsBodies`、`PhysicsLevel` 和 `PhysicsChunkManager`，不要再建立第二套调度器或全局 Bullet 世界。保持每个 Level 一个物理线程，固定 60 Hz，每 MC tick 三个子步，最多一个待执行物理帧。

地形更新必须遵守“主线程快照 → builder 生成计划 → 物理线程创建/替换原生对象”。区块卸载和 Level 卸载必须取消任务并清理物理对象；客户端失效包必须限制数量并在网络线程之外应用。

每次改动后至少运行：

```bash
./gradlew test
./gradlew runGameTestServer
./gradlew build
```

若涉及启动、Mixin、资源或平台加载，再运行 `./gradlew runClient`。检查 `run/logs/latest.log`，区分 Minecraft 自带 `flite` 旁白器警告与 Spark Core 错误。

交付时报告：修改的行为、线程所有权是否变化、执行过的命令和结果、成品 JAR 路径与 SHA-256、已知平台限制。不得用“编译通过”替代实际 GameTest；遇到 API 或版本不确定时先查本地 Forge/原项目源码，仍不确定再查对应官方文档或上游仓库。
