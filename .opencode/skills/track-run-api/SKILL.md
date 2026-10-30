---
name: Track Run API
description: Implement, review, or test KaguyaPyCore's generic dynamic track-run API: link bodies, pin/hinge joints, owner lifecycle, collision filtering, and immutable snapshots.
---

# 通用 TrackRun API 技能

先阅读：

1. `docs/TRACK_RUN_API.md`
2. `docs/CORE_SCOPE.md`
3. `docs/PHYSICS_API.md`
4. `docs/THREADING_AND_LIFECYCLE.md`
5. `docs/TESTING.md`
6. `SuperbWarfare:docs/physics/CORE_API_CONTRACT.md`

## 硬边界

- `TrackRunSpec` 只描述物理 link、pin/hinge、碰撞过滤和 owner。
- 不包含左/右履带业务语义、轮角色、车型 ID、模型骨骼或可见链节。
- 不包含地形策略、台阶参数或高度场。
- 只在物理线程创建、修改、加入、移除和销毁 Bullet 对象。
- joint 两端 body 进入同一物理空间后才注册。
- 创建、reset、remove、Level unload 和失败恢复必须幂等。
- 不为单一车型增加分支。

## 工作顺序

1. 定义 `TrackRunSpec`、`TrackRunLinkSpec`、`TrackRunJointSpec` 和 `TrackRunSnapshot`。
2. 实现物理线程 runtime 和 handle。
3. 设置 owner、碰撞组和同 owner 过滤。
4. 实现 reset、rebuild 和销毁顺序。
5. 发布不可变快照。
6. 添加 T1 原生测试。

## 测试

- 两 link 运行段。
- 短闭链和多 link 闭链。
- pin 闭合误差和长期漂移。
- 物理线程创建和销毁。
- owner 生命周期和碰撞过滤。
- 休眠/唤醒。
- reset/rebuild。
- 快照插值不写回物理。

## 驱动接口

核心库只提供低层 motor 和有界扭矩能力。差速、油门、制动和牵引预算由 SBW 决定。
不得在核心库写车辆控制逻辑。

## 记录

API 版本、单位和语义变化记录到 `docs/TRACK_RUN_API.md`，并向
`SuperbWarfare:docs/physics/records/events.jsonl` 追加带 `core_commit` 的事件。
