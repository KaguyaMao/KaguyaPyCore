# 测试与诊断

## JUnit

```bash
./gradlew test
```

`NativePhysicsLoaderTest` 覆盖：

- Linux/Windows x86_64 平台选择；
- ARM64/macOS 快速拒绝；
- 内嵌 native 加载、版本与线程安全校验；
- 动态刚体在静态地面上稳定的原生求解冒烟测试。

## Forge GameTest

```bash
./gradlew runGameTestServer
```

`PhysicsGameTests` 覆盖：

- `pawnBodyUsesSpawnPosition`：普通实体代理刚体使用加入世界时的坐标，而不是 Bullet 原点；
- `pawnBodyRemovedWhenEntityLeaves`：实体离开后不会留下幽灵刚体；
- `springJointHoldsMassAboveTerrain`：`New6Dof` 弹簧关节能把质量悬挂在锚点下方、不掉到地形上；
- `asyncTerrainCollision`：Forge 服务端真实创建 `PhysicsLevel`，异步创建动态刚体，地形激活后刚体受重力下落并在真实方块上静止。

成功标志为：

```text
All 4 required tests passed :)
BUILD SUCCESSFUL
```

测试结构由 `generateGameTestStructure` 在构建目录生成，不需要手工维护 NBT 二进制。

## 客户端

```bash
./gradlew runClient
```

确认日志含：

```text
Loaded Libbulletjme 22.0.3 from natives/linux/x86_64/libbulletjme.so
Kotlin For Forge Enabled!
```

Linux 没有系统 `flite` 时会记录 narrator 初始化异常，但客户端仍继续加载声音、纹理和主菜单。这不是 Spark Core 失败。真正的物理加载失败会在模组构造阶段中止并明确报告资源路径、平台或哈希。

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
