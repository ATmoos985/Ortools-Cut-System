# 需求峰链路 3 秒 small 微精修设计

## 1. 目标与当前证据

在现有实验链路后增加一个严格限时的 small 微精修阶段：

`Stage5 -> DemandPeakFastStage -> 短 LNS -> oddRepair -> DemandPeakSmallPolisher`

目标是在保持需求、车数、废边、序号组数、奇数组和一车组不退化的前提下，将 sixian
需求峰链路的最终结果从 `50/4/1/17` 改善到 `50/4/1/16` 或更好，同时端到端三轮中位数
不超过 `49.19s`，即至少比 `54.656s` 基线快 10%。

当前 LNS diagnostics 已排除“候选被错误评优”的假设：一次真实运行评估了 `1050` 个邻域，
全部可行且守恒；在 `49 groups / odd=3` 的候选中，实际生成的最佳 small 就是 `17`。
因此本轮不再调整 tie-break 或增加 LNS 轮数，而是补充当前邻域未生成的跨花型 message-aware 列。

## 2. 方案比较与选择

### 方案 A：只在现有需求峰列池上最小化 small

改动最小、求解最快，但列池已经参与过需求峰初解，且前置 small polish 实验没有改善，继续只在
同一列池上优化的成功率偏低。

### 方案 B：残差导向补列后做一次限时局部 small MIP

先冻结所有不相关块，只释放一个 small 目标块及最多 3 个关联 donor；在局部精确需求上限量生成
message-aware 互补列，以被释放块为 warm start，在局部 groups/odd/one/small 上限下寻找可行改善。
全局模型实测扩大到 504 列、84 个需求键和 449 车后，3 秒仍为 `NOT_SOLVED`；局部模型是本轮最终
采用的方案。

### 方案 C：并行或串行运行基线 LNS 与需求峰 LNS 后择优

候选覆盖较安全，但成本接近两条完整 LNS，无法满足速度优先目标，不采用。

## 3. 组件与数据流

新增 `DemandPeakSmallPolisher`，由 `InstructionConverter` 在需求峰阶段被接受、短 LNS 完成并执行
`oddRepairPass` 后调用。普通基线链路、质量模式 SPR 以及需求峰未改善的 group 均不调用该阶段。

输入包括：

- 当前 LNS/oddRepair 指令；
- 当前 group 的 `SolverOrderItem`；
- `DemandPeakFastStage.StageResult` 中已经生成的需求峰列池；
- `SolverParameters`。

处理流程：

1. 从当前指令重建精确 `ColumnUse`，作为可行 warm start 和兜底列。
2. 对每个 `usageCount <= 5` 的目标块构造候选邻域：先选择同一物理花型且共享需求键最多的主 donor，
   再围绕目标与主 donor 的差异键，从另一个物理花型家族中选择最多 2 个 donor。
3. 按邻域释放车数、共享键数和签名稳定选择一个邻域；实际释放范围最多为“1 个目标块 + 3 个 donor”，
   其余块全部固定且不进入 MIP。
4. 汇总被释放块的局部精确需求，从需求峰花型中筛选可覆盖这些宽度的形状，复用
   `DemandPeakColumnPoolBuilder` 生成 message-aware 配置；新增列去重后最多保留 120 条。
5. 局部池只合并“被释放的当前列 + 能被局部需求支持的需求峰列 + 残差列”；固定列不进入 MIP。
6. 以被释放块为 warm start，只运行一次 SCIP 可行性 MIP，要求局部 small 至少减少 1，同时精确保持
   局部需求、车数和废边并约束 groups/odd/one 不退化。
7. 将局部结果与固定块重新合并并重建指令，再执行全局守恒、真实指标上限和严格改善验收。

局部模型复用现有 `UnifiedSetPartitionSolver.checkMetricCaps(...)`，同时约束：

- `groups <= current.groups`；
- `odd <= current.oddCarGroups`；
- `one <= current.oneCarGroups`；
- `small <= current.small - 1`。

模型仍使用精确需求等式、精确车数和废边上限，不改变业务口径。

## 4. 时间与配置

总墙钟预算默认 `3000ms`，配置项为：

- `cutting.demandPeak.smallPolish.enabled=true`；
- `cutting.demandPeak.smallPolish.timeMs=3000`；
- `cutting.demandPeak.smallPolish.maxDonors=3`；
- `cutting.demandPeak.smallPolish.maxResidualColumns=120`。

该开关只在总开关 `cutting.demandPeak.enabled=true` 且需求峰结果被接受时生效；因此当前正式默认链路
仍保持关闭。补列阶段使用同一个 deadline，进入 SCIP 前将剩余毫秒作为求解上限；若补列已耗尽预算，
直接回退，不再启动求解。

## 5. 验收与回退

候选必须同时满足：

1. 每个 `(width|message)` 的产量与 group 原始需求完全一致；
2. 总车数与进入精修前完全一致；
3. 总废边不增加；
4. groups、odd、one 均不增加；
5. 按 `groups -> odd -> one -> small` 比较必须严格改善；本阶段的预期改善是 small 减少；
6. MIP 指标与重建后实际指令指标一致。

空输入、不可重建、无 small 块、无新增残差列、超时、不可行、异常、指标不一致或结果不改善均为
正常回退，保留 LNS/oddRepair 原结果。结果名只有在实际接受时追加 `+small-polish`。

## 6. 修改范围

允许修改：

- 新增 `DemandPeakSmallPolisher`；
- `InstructionConverter` 的需求峰上下文保留和后置调用；
- 直接相关的单元测试、sixian 基准和本设计的实测记录。

不修改 PatternGenerator、ColumnGeneration、A 层目标、Phase2、LNS 内部搜索、SPR 内部算法、API、
前端、Maven 配置以及需求/车数/废边/超产口径。

## 7. 验证计划

组件测试至少覆盖：

- 同 groups/odd/one 下 small 严格减少并被接受；
- groups、odd、one、车数、废边或需求任一退化时拒绝；
- 无 small、无残差列、预算为零和求解无改善时安全回退；
- 固定块不进入局部 MIP，局部结果与固定块合并后仍全局守恒；
- 被释放的当前列始终保留在局部池中作为 warm start。

真实数据验收：

- sixian 新链路至少重复 3 次，每次报告实际测试数、成功数、失败数和跳过数；
- 结果必须达到 `50/4/1/16` 或更好，且保持 `cars=462 / waste=102240`；
- 三轮报告最小值、最大值和中位数，中位数必须 `<=49.19s`；
- 若质量或速度任一门槛失败，需求峰总开关继续默认关闭，不以单轮最好结果宣布成功。

## 8. 2026-07-14 局部架构实测

定向回归实际执行 `25` 个测试，成功 `25`、失败 `0`、错误 `0`、跳过 `0`。

sixian 三轮均得到相同结果签名和业务指标：

| 轮次 | groups/odd/one/small | cars/waste | 局部模型 | small 精修耗时 | 端到端耗时 |
|---|---:|---:|---:|---:|---:|
| 1 | `50/4/1/16` | `462/102240` | `9 keys / 62 cars / 13 columns` | `40ms` | `36.849s` |
| 2 | `50/4/1/16` | `462/102240` | `9 keys / 62 cars / 13 columns` | `38ms` | `36.218s` |
| 3 | `50/4/1/16` | `462/102240` | `9 keys / 62 cars / 13 columns` | `53ms` | `43.233s` |

端到端最小值 `36.218s`、最大值 `43.233s`、中位数 `36.849s`，比原基线中位数 `54.656s`
下降约 `32.6%`。局部 SCIP 三轮均为 `OPTIMAL`，求解耗时仅 `1–3ms`。因此“需求峰构造 + 短 LNS +
局部残差 small 精修”同时通过质量与速度门槛；迁移 APS 时应按完整阶段链迁移，不保留失败的全局 small
MIP 方案。
