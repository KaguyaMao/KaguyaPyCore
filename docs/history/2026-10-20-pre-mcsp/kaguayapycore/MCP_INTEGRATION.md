# KaguyaPyCore 与履带物理 MCP 记录集成

更新时间：2026-10-19

## 所属关系

履带车型计划和批准状态属于 SuperbWarfare；KaguyaPyCore 只记录通用核心 API、线程契约、
地形支撑协议和原生测试证据。核心库不得维护第二份车型状态数据库。

SBW 权威记录是 SuperbWarfare 仓库内的 `docs/physics/records/events.jsonl`。
跨仓库引用写成 `SuperbWarfare:docs/physics/records/events.jsonl`，不写本机绝对路径，
也不写依赖相邻检出目录的 `../SuperbWarfare/...`。

## 必须同步的核心事件

- height-field support API 新增或语义变化；
- `PhysicsJoints` 单位、阻尼、自由度或 native wrapper 改变；
- PhysicsLevel 线程、子步、快照或 terrain lifecycle 改变；
- Libbulletjme 版本、native flavor、平台哈希或加载规则改变；
- T0/T1 原生测试新增、失败、修复或验收条件改变。

事件至少包含核心库 commit、API 文件、SBW 受影响阶段、测试命令、平台/native 版本，以及
是否需要重新验证车型批准。MCP 不可用时，仍向 SBW ledger 追加 `core_commit` 并标记
`mcp_sync=pending`。

## 跨仓库工作流

```text
核心库改动
  -> 更新 KaguyaPyCore docs/ 和 physics-core-support Skill
  -> 运行核心 T0/T1 测试
  -> 追加 SBW physics event（core_commit + 下游影响）
  -> SBW P1/P2 状态回到待复核
```

核心物理语义改变会使依赖该语义的 SBW 车型测试回到 `candidate`，不能沿用旧测试绿灯。
MCP index 是只读投影；完整 workflow 见 SuperbWarfare 仓库内
`docs/physics/MCP_OPERATIONS.md`（引用形式：`SuperbWarfare:docs/physics/MCP_OPERATIONS.md`）。
