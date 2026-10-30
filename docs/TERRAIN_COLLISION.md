# 地形碰撞

更新时间：2026-10-20
范围：通用 Minecraft voxel/AABB 地形。车辆地形策略见
`SuperbWarfare:docs/physics/ARCHITECTURE.md`。

## 三阶段构建

1. 主线程：`SectionSnapshot.snapshotFromChunk` 遍历指定区段，复制 `BlockState` 和 voxel
   collision AABB。
2. Builder：`TerrainShapePlan.build` 把连续完整方块沿 X 轴合并，保留非完整方块的各子碰撞箱。
3. 物理线程：为计划创建 `CompoundCollisionShape` 和质量为零的静态 `PhysicsRigidBody`。

这条边界保证 builder 不访问 Minecraft 世界，物理对象也不在 builder 或主线程创建。

## 坐标

计划中的子形状中心相对于区段中心 `(8,8,8)`。区段刚体放在 `section.minBlock + (8,8,8)`，
二者相加还原方块世界坐标。完整方块使用半尺寸 `.5`；非完整 voxel box 按原始 min/max 转换。

## 激活和预取

请求 `DRIVABLE` 或属于 `CHASSIS` 的动态/运动学刚体会生成激活 AABB。主线程据此：

- 横向预取周围两区块；
- 纵向预取 body 所在区段上下各一区段；
- 仅把与膨胀后 AABB 相交的已建区段加入 Bullet world；
- body 远离后从 world 移除区段，但保留构建结果以便复用。

纵向限制避免为 5×5 区域的整个世界高度一次创建数百个地形刚体。

## 失效与网络

- `ServerLevelMixin.onBlockStateChange` 标记对应区段脏。
- 区段 revision 防止过时异步计划覆盖较新方块状态。
- 新构建会取消旧 future；即使取消来不及，revision 仍会丢弃旧结果。
- 服务端每次最多发送 4096 个脏区段到对应维度客户端。
- 客户端网络 handler 回到客户端执行队列后再标记本地地形脏。

区块卸载会取消 section build、递增 revision 并在物理线程移除 body。Level 销毁会停止
builder executor 并清空全部集合。

## 当前形状策略

当前实现忠实使用方块 collision voxel shape，但只进行区段内 X 方向完整方块合并。它不生成
视觉网格，也不处理流体碰撞。更激进的三维贪心合并可以作为后续优化，但必须保留台阶、
栅栏等非完整形状。

## 车辆地形边界

核心库不提供车辆高度场。以下内容不属于本文件范围：

- `LocalHeightField`、`TerrainSupportField`、`HeightFieldContact`。
- `SUPPORT_PAIR`、`HEIGHT_FIELD`、`SLOPE`、`RUN_BLOCKS`。
- 固定楔面、按车长坡长和车辆台阶辅助。

这些接口在 D1 清理后退出活动路径。车辆台阶辅助由 SuperbWarfare 独立实现，见
`SuperbWarfare:docs/physics/ARCHITECTURE.md` 和
`SuperbWarfare:docs/physics/LEGACY_CLEANUP.md`。

## 清理后要求

- `DRIVABLE` 只包含普通地形位。
- 区段构建只创建 voxel/AABB 地形刚体。
- 不安装高度场支撑对或楔面。
- 车辆和普通刚体使用同一套地形碰撞。

## Detached region snapshot（2026-10-28）

`Level` 逐方块读取只允许发生一次：在 `SectionSnapshot.snapshotFromChunk` 里。除它以外，
任何消费方都只能读缓存下来的不可变快照。

```kotlin
val result = terrainManager.snapshotRegion(bounds)   // Ready | Missing
when (result) {
    is TerrainRegionSnapshotResult.Missing -> /* 等待，不建部分几何 */
    is TerrainRegionSnapshotResult.Ready -> build(result.snapshot)
}
```

`TerrainRegionSnapshot`：

- 只含普通值与 `SectionSnapshot`；没有 `Level`、`LevelChunk`、`BlockState`、Bullet 对象。
- `revision: TerrainRevisionToken` 是各 section revision 的稳定混合，用来证明建出来的几何
  就是求解器看到的那一份。
- `sections` 与内部列表防御性复制并排序。

`snapshotRegion` 是严格的：任一被覆盖的 section 缺失、`inactive`、仍在 `dirtySections`、
或 `appliedRevision != revision`，整块区域返回 `Missing`。它不会返回部分区域，也不会退化为
读 `Level`。空 section 是合法 ready，不是 missing。

## 外部区域请求

有些消费方不与方块地形碰撞（履带车有自己的局部 mesh，刚体 mask 排除了 `TERRAIN`），
因此不会出现在激活查询里。它们必须显式声明需要的区域：

```kotlin
terrainManager.requestRegion(consumerId, bounds) // 主线程，每 tick 续期
terrainManager.releaseRegion(consumerId)         // 消费者关闭时立即释放
```

请求按稳定消费者 ID 管理，参与 `sectionsToBuild`、`sectionsToRetain` 与 active 集合。
消费者关闭时应调用 `releaseRegion`；TTL 仅作为异常退出保护。

几何来源仍然是 `SectionSnapshot`；`requestRegion` 只决定哪些 section 需要构建与保活。

## Revision validation and section build ordering

`revisionTokenAt(bounds)` 返回完整 section/revision map，且仅当覆盖区域每个 section
均 active、已应用、clean 并持有匹配的 applied snapshot。`withRevisionToken(bounds, token) { ... }`
在短暂回调期间锁住 revision 边界，供物理线程执行局部 mesh proxy swap；方块失效、section
替换和外部 mesh 安装使用同一边界，避免“验证后、安装前”地形 revision 被推进。

每个 manager 的 terrain revision 单调递增，section 淘汰后重建也不会复用旧 revision。
每个 section 同时至多一个 snapshot/build/apply；新编辑会使旧 build 失效，旧计划不能替换
当前 solver body。完整的新计划可用后才替换旧 section body。
