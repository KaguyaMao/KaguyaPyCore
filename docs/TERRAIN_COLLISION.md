# 地形碰撞

## 三阶段构建

1. 主线程：`SectionSnapshot.snapshotFromChunk` 遍历指定区段，复制 `BlockState` 和 voxel collision AABB。
2. Builder：`TerrainShapePlan.build` 把连续完整方块沿 X 轴合并，保留非完整方块的各子碰撞箱。
3. 物理线程：为计划创建 `CompoundCollisionShape` 和质量为零的静态 `PhysicsRigidBody`。

这条边界保证 builder 不访问 Minecraft 世界，物理对象也不在 builder 或主线程创建。

## 坐标

计划中的子形状中心相对于区段中心 `(8,8,8)`。区段刚体放在 `section.minBlock + (8,8,8)`，二者相加还原方块世界坐标。完整方块使用半尺寸 `.5`；非完整 voxel box 按原始 min/max 转换。

## 激活和预取

只有碰撞 mask 包含 `CollisionGroups.TERRAIN` 的非静态 body 会生成激活 AABB。主线程据此：

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

区块卸载会取消 section build、递增 revision 并在物理线程移除 body。Level 销毁会停止 builder executor 并清空全部集合。

## 当前形状策略

当前实现忠实使用方块 collision voxel shape，但只进行区段内 X 方向完整方块合并。它不生成视觉网格，也不处理流体碰撞。更激进的三维贪心合并可以作为后续优化，但必须保留台阶、栅栏等非完整形状。
