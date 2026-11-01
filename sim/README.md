# 大师模式仿真器（SimMain）

纯 JVM 本地仿真 harness，**不进 APK**。在「AI 地主（大师搜索 + 人类建模）vs 人类农民（humanPolicy）+ AI 农民（大师搜索）」局型下，用与线上一致的配置批量对局，输出地主胜率，供**评估函数校准 / 回归验证 / 旋钮扫描**使用。

## 编译与运行

推荐用脚本（自动编译并透传全部参数）：

```bash
./sim/run-sim.sh [games] [deadlineMs] [nodeLimit] [threads] [pModel] [baseSeed] [panicBombLdSize] \
                 [effTurnsBeatCoef] [controlWeight] [initWEndgameSize] [bombTempoNear] [bombTempoFar]
```

示例（确定性基线，等价于生产默认权重）：

```bash
./sim/run-sim.sh 300 0 3000000 6 800 20261029 2 1.0 6 6 6 2
```

也可手动编译运行：

```bash
kotlinc app/src/main/java/com/doudizhu/game/model/*.kt \
        app/src/main/java/com/doudizhu/game/logic/MasterSearch.kt \
        sim/SimMain.kt -d /tmp/dz-sim/classes
java -cp /tmp/dz-sim/classes com.doudizhu.sim.SimMain \
        300 0 3000000 6 800 20261029 2 1.0 6 6 6 2
```

## 参数说明

| # | 参数 | 含义 | 说明 |
|---|------|------|------|
| 1 | `games` | 对局数 | — |
| 2 | `deadlineMs` | 墙钟上限(ms) | **0 = 确定性模式**（纯 `nodeLimit` 收口、不读墙钟），同参数同种子完全可复现 |
| 3 | `nodeLimit` | 每局搜索节点预算 | 决定搜索深度，越大越强越慢 |
| 4 | `threads` | 并行线程数 | — |
| 5 | `pModel` | 人类建模权重 | 与线上一致用 `800` |
| 6 | `baseSeed` | 发牌种子 | 同种子序列可跨配置配对对比 |
| 7 | `panicBombLdSize` | 地主残牌触发炸弹兜底阈值 | — |
| 8 | `effTurnsBeatCoef` | effTurns 牌质 tempo 系数 | 默认 `1.0` |
| 9 | `controlWeight` | control() 控牌资源权重 | 默认 `6` |
| 10 | `initWEndgameSize` | initW 残局抢权阈值（↑=更早抢控制权） | 默认 `6` |
| 11 | `bombTempoNear` | 竞速胶着（\|差\|≤2）时炸弹 tempo 权重 | 默认 `6` |
| 12 | `bombTempoFar` | 差距悬殊时炸弹 tempo 权重 | 默认 `2` |

> 旋钮 8–12 的默认值即生产基线。旧调用若不传这些参数，构造器走默认值，**行为等价于未调参的线上版本**（向后兼容）。

## 输出

- 每局一行：`GAME <i> <0|1>`（`1` = 地主胜）。
- 结尾汇总：`== 地主胜率 x% ± SE (n=..., 耗时)`。

多配置共享同一 `baseSeed` 时，可逐局配对做 **McNemar 显著性检验**（分析过程与结论见同目录 `sweep_analysis.md`）。

## 目录文件

- `SimMain.kt`：仿真入口（最新可用）。
- `run-sim.sh`：编译 + 运行脚本。
- `sweep_analysis.md`：本次大师模式胜率优化（评估旋钮 OFAT 扫描）的方向、过程与结论。
