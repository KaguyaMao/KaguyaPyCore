---
name: Physics Core Testing
description: Run and record KaguyaPyCore T0/T1 native Bullet tests for threading, terrain AABBs, TrackRun constraints, lifecycle, and snapshot interpolation.
---

# 核心物理测试技能

先阅读：

1. `docs/TESTING.md`
2. `docs/CORE_SCOPE.md`
3. `docs/TRACK_RUN_API.md`
4. `docs/PHYSICS_INTERPOLATION.md`
5. `SuperbWarfare:docs/physics/TEST_MATRIX.md`

## 命令

```bash
./gradlew test
./gradlew runGameTestServer
./gradlew runClient
```

发布审计：

```bash
./gradlew build
jar tf build/libs/spark_core-1.20.1-1.0.0.jar
```

## 覆盖范围

核心测试只验证：

- 平台和 native 加载。
- 物理线程、固定步进、单飞帧和快照。
- 普通 voxel/AABB 地形。
- 通用 body/joint 单位、行程、弹簧和阻尼。
- TrackRun 创建、约束、闭合、漂移、销毁和插值。
- owner、碰撞过滤、休眠/唤醒。
- 区块卸载和 Level 销毁清理。

核心测试不验证：

- 车型驱动、差速和批准。
- 车辆台阶辅助。
- 履带渲染路径和可见链节。
- 高度场、`SUPPORT_PAIR` 或楔面。

## 证据规则

- 测试失败保留现场，修复后追加新记录。
- 只打印 probe 数值不算通过；必须有断言。
- 未冻结限值只记录测量。
- 核心测试通过不能把车型标为实机通过或批准。
- 测试命令、平台、native 版本和结果写入事件账本。

## 记录

向 `SuperbWarfare:docs/physics/records/events.jsonl` 追加 `core_commit` 和测试结果。
MCP 不可用时 `mcp_sync=unavailable`。
