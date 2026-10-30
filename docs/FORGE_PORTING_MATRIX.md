# NeoForge 1.21.1 → Forge 1.20.1 迁移矩阵

| 能力 | 原项目概念 | Forge 1.20.1 落点 | 状态 |
|---|---|---|---|
| 模组入口 | NeoForge/Kotlin 入口 | Java `@Mod("spark_core")` | 已迁移 |
| Level 物理扩展 | Level extension Mixin | `LevelPatch` + `LevelMixin` | 已迁移 |
| Entity body 表 | Entity extension Mixin | `EntityPatch` + `EntityMixin` | 已迁移 |
| 生命周期 | NeoForge Level/Tick events | Forge `LevelEvent` / `TickEvent` | 已迁移 |
| 区块生命周期 | NeoForge chunk events | Forge `ChunkEvent` / ticket event | 已迁移 |
| 方块失效 | ServerLevel 注入 | 1.20.1 `onBlockStateChange` Mixin | 已迁移 |
| 网络 | 1.21 payload API | Forge `SimpleChannel` | 已迁移 |
| 原生加载 | 原项目打包的 native/loader | 自校验释放器，无 JSnapLoader | 已迁移 |
| Libbullet wrapper | 原项目内置修改版源码 | 官方 23.2.0 wrapper + Spark 元数据扩展 | 已迁移，源兼容优先 |
| 异步世界 | `PhysicsLevel` | 每 Level 单线程 executor | 已迁移 |
| 地形形状 | chunk/section pipeline | snapshot/plan/native 三阶段 | 已迁移 |
| 刚体实体 | `CollisionObjectEntity` / `RigidBodyEntity` | 1.20.1 synched data、NBT、快照 | 已迁移 |
| GameTest | NeoForge GameTest | Forge `@GameTestHolder` + 生成 NBT | 已迁移 |
| 动画、声音、资源扩展 | 原 Spark Core 其他模块 | 不在首次物理迁移范围 | 未迁移 |

## 兼容定义

本项目保证模组 ID 和物理层核心概念一致，尽量保留 Kotlin 调用形态。Minecraft/加载器版本变化导致方法签名和包名变化时，以 Forge 1.20.1 正确行为为准。

原项目修改过 `PhysicsCollisionObject` 等上游类并直接加入 `name`、`owner`、事件字段。此移植不复制整份第三方源码，而使用弱耦合的 `BodyRuntime` side table 和扩展属性。Kotlin 调用方可继续使用 `body.name`、`body.owner` 等；Java 调用方使用 `PhysicsBodies`。如果具体下游模组依赖原 fork 的其他新增 Java 成员，应根据实际编译错误扩展 facade，而不是修改官方 wrapper 字节码。
