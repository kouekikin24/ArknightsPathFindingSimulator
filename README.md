# 明日方舟寻路模拟器

这是一个无 UI、无外部依赖的 Java 21 模拟核心，用于逐帧复现明日方舟敌人在地图中的路线选择、转向、避障、碰撞、检查点和传送门行为。

项目采用固定 `30 FPS`，所有连续值均按 Java `float`（float32）计算，并刻意保持确定性的遍历顺序。它的重点不是画面，而是让每一帧的计算结果可读取、可比较、可校验。

## 已实现的规则

- 左上角为地图原点，`x` 向右、`y` 向下；左上第一个格子的中心为 `(0.5f, 0.5f)`。
- 以反向 SPFA 构建路线图，采用上、右、下、左的邻接遍历顺序及严格的小于号松弛。
- 对 `nextNode` 进行从下到上、从左到右的原地平整化，并使用改造 Bresenham 检查拐角和窄带阻挡。
- 通过高寻路代价处理箱子、坑等地块；坑的代价为 `1_000_000`。
- 每个全局帧号满足 `frame % 3 == 0` 时重算避障力，其余两帧复用缓存。
- 按 float32 计算给定方向、避障力、惯性、转向加速度与碰撞后的位移修正。
- 支持移动、巡逻、等待、消失、出现、提示等检查点，以及忽略检查点模式。
- 传送门会改变位置，但完整保留进入时的惯性速度方向和大小。
- 每帧生成 `FrameTrace`，可查看目标节点、给定方向、避障缓存、惯性前后值、位移、坐标和检查点切换。

## 目录

```text
src/main/                  模拟器源码
src/test/                  可执行的回归测试
out/                       编译产物，运行脚本会自动重建，不提交到 Git
run-tests.cmd              Windows 批处理测试入口
run-tests.ps1              PowerShell 测试入口
```

源码和测试均使用默认包，因此 `src/main` 与 `src/test` 下不再有额外的 Java 包目录。

## 运行测试

需要 Java 21。任选一种方式运行：

```bat
run-tests.cmd
```

```powershell
powershell -ExecutionPolicy Bypass -File .\run-tests.ps1
```

两个脚本都会先清空并重建 `out`，再编译源码和测试。目前回归测试覆盖浮点地块判定、SPFA 平局顺序、改造 Bresenham、原地平整化、两检查点惯性转弯、三帧避障缓存、传送门、碰撞反射、检查点忽略和跨传送门距离图。

## 最小使用示例

```java
import java.util.List;

GridMap map = new GridMap(20, 12);
map.setRule(new TileCoord(8, 5), TileRule.impassable());

Route route = new Route(
        new Vec2f(0.5f, 0.5f),
        new Vec2f(18.5f, 5.5f),
        List.of(Checkpoint.move(new Vec2f(10.5f, 5.5f))),
        MovementMode.GROUND,
        true,
        true,
        false);

PathfindingSimulator simulator =
        new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
FrameTrace frame = simulator.tick();
```

运行测试脚本后，可以执行演示程序查看两个检查点之间的惯性转弯：

```bat
java -cp out DemoMain
```

## 验证边界

本项目把目前已确认的规则实现为可审计的模型。若要验证到与原作录像逐帧完全一致，仍需要用实际关卡录像或帧数据作为 golden trace，对未确认的细节持续比对和补充。

## 研究资料

- [寻路机制研究文章](https://www.bilibili.com/opus/900558138389823489?spm_id_from=333.1387.0.0)
- [相关补充文章](https://www.bilibili.com/opus/1143300043428593668?from=search&spm_id_from=333.337.0.0)
