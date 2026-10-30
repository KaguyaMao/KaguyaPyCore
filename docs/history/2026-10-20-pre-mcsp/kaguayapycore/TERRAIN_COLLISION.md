# 地形碰撞

## 三阶段构建

1. 主线程：`SectionSnapshot.snapshotFromChunk` 遍历指定区段，复制 `BlockState` 和 voxel collision AABB。
2. Builder：`TerrainShapePlan.build` 把连续完整方块沿 X 轴合并，保留非完整方块的各子碰撞箱。
3. 物理线程：为计划创建 `CompoundCollisionShape` 和质量为零的静态 `PhysicsRigidBody`。

这条边界保证 builder 不访问 Minecraft 世界，物理对象也不在 builder 或主线程创建。

## 坐标

计划中的子形状中心相对于区段中心 `(8,8,8)`。区段刚体放在 `section.minBlock + (8,8,8)`，二者相加还原方块世界坐标。完整方块使用半尺寸 `.5`；非完整 voxel box 按原始 min/max 转换。

## 激活和预取

请求 `DRIVABLE`、`SUPPORT_PAIR`（含只请求其中一位的物体）或属于 `CHASSIS` 的动态/运动学刚体会生成激活 AABB。`SUPPORT_PAIR` 不碰撞原始方块，但区段构建负责安装高度场，所以 opt-in 物体也必须激活地形。主线程据此：

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

## 履带高度场接入状态

当前事实分两部分，不能只写其中一部分：

- section box bodies 仍是普通 voxel terrain collision；
- `TerrainShapePlan.build` 不再调用 `TerrainSlopePlanner`。实时区段不创建 `SLOPE` 刚体。规划器源码还在，只供历史测试，不能写成算法已经从仓库删除。`DRIVABLE` 仍包含 `SLOPE` 这一位，但实时世界没有对应刚体。
- `LocalHeightField` 仍是采样辅助，不是履带车的唯一支撑表面。

不能在下游文档中把当前实现写成已完成的车辆唯一支撑。实时区段不再安装楔面，但规划器源码仍在，车辆掩码仍会请求那个空组。

`PhysicsChunkManager` 会在 builder 线程用 `TerrainSupportInstallPolicy.LIVE` 生成纯数据支撑场，再由物理线程创建对应刚体。该策略只允许 1 格共享边下削，2 格立面不下削。没有有效支撑格时不创建刚体。实时区段不再安装固定楔面。`DRIVABLE` 不含 `SUPPORT_PAIR`。该 builder 的 `query` 还不是形状接触。

支撑对里的结构体不再逐块复制未拥有的整格顶面。被高度场拥有的顶面下方、连续埋住的整格会合并成棱柱；未知、非地面和未分类非整格不生成可向上支撑的体积。两格及以上的有效列立面仍然保留，section 边界上的立面由较高一侧生成唯一边界面。边界上的一格台阶按 margin 证据下削，未知 margin 不生成坡。缓存淘汰、区块卸载和 Level 销毁都移除支撑对。这不是“高度场已经是车辆唯一支撑”：请求 `DRIVABLE` 的物体仍碰撞原始方块顶面，不再得到 6 格楔面。石头仍是 `UNKNOWN`。

下一阶段必须增加独立的纯数值支撑表面协议，并遵守：

1. raw 表面、surface class、layer、clearance、friction 和 revision 从 `SectionSnapshot`
   产生；builder 不持有 Level 或 Bullet 对象；
2. 只允许有限向下平滑，不跨空气、未知区块、不同层或不连续硬/软表面；
3. 物理线程创建/替换支撑对象，所有下游接触读取同一 revision；
4. 高度场支撑和结构障碍接触必须仲裁，不能重复提供向上支撑；
5. 区块更新、卸载和 Level 关闭必须让旧支撑失效并清理原生对象。
