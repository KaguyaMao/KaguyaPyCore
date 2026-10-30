# 2026-10-20 清理前基线

本目录保存切换到 MCSP 风格 Bullet 架构前的 KaguyaPyCore 文档快照，仅用于追溯，不是当前
实现或验收输入。

- `kaguayapycore/`：旧 API、地形、线程、测试、MCP 和计划文档。
- `SHA256SUMS.txt`：归档时源文件哈希。
- `GIT_STATUS.txt`：归档时工作树状态。

新架构：

- `SuperbWarfare:docs/physics/ARCHITECTURE.md`
- `SuperbWarfare:docs/physics/CONSTRAINTS.md`
- `SuperbWarfare:docs/physics/IMPLEMENTATION_PLAN.md`
- `docs/CORE_SCOPE.md`
- `docs/TRACK_RUN_API.md`
- `docs/PHYSICS_INTERPOLATION.md`

归档中的高度场、探针、楔面和支撑对方案已撤销，不得重新作为默认路径。

归档副本是原样快照，其中的相对链接和阶段编号属于当时状态，可能无法在当前目录解析。
需要追溯时以 `SHA256SUMS.txt` 和 `GIT_STATUS.txt` 为准，不修改归档正文。

已知限制：`.opencode/skills/` 在归档时是未跟踪目录，旧 Skill 正文已被就地改写，没有单独
快照。当前职责边界以 `docs/CORE_SCOPE.md` 和 `SuperbWarfare:docs/physics/SKILLS.md` 为准。
