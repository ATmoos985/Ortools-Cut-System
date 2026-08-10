# T9 双池候选逐列去向审计设计

## 1. 目标

在不改变生产候选生成、Packing 约束、求解预算和默认开关的前提下，回答人工 65 列在当前新双池链路中的完整去向，并区分三类根因：

1. 候选已经生成，但被每花型配额截断；
2. 候选进入最终深池，但未被 Packing 采用；
3. 候选在车数、局部配置或 Beam 阶段就没有生成。

人工 65 列仅作为离线诊断 oracle，不得注入生产候选池，不得作为 warm start，也不得改变任何候选的生产评分。

## 2. 范围

允许：

- 在 `OrderGroupIntegerCandidatePoolBuilder` 增加不可变、无行为副作用的审计快照；
- 在 `T9QualityPricingAuditTest` 增加 opt-in 审计和离线对照；
- 输出逐列审计表、候选指纹及统计摘要；
- 对同一批 rawDeep 候选离线模拟每花型配额 12 和 16；
- 对每个离线池调用现有 `solvePacking`，保持相同约束、随机种子和时间上限。

禁止：

- 修改生产候选生成、排序、Packing 目标或时间预算；
- 修改 QUALITY 默认入口或运行参数；
- 把人工 65 列强制放入 broad、deep、LP 或 Packing；
- 因审计结果直接放宽 odd、one-car、总候选量或 54 组诊断上限；
- 在本轮提交算法功能变更。

## 3. 关键口径

### 3.1 rawDeep

`rawDeep` 指残差输入经过现有 completion oracle 生成，并通过单列残差可行性及已占 family 过滤之后、最终深池配额选择之前的固定候选集合。

每次审计必须计算候选签名排序后的 SHA-256 指纹。配额 12、配额 16 和后续可选排序实验必须共享同一指纹；若指纹不同，本次对照无效。

### 3.2 rawDeep 分数

当前深池不是按单一 reduced cost 全局取 Top550。审计不得伪造一个不存在的标量分数，应记录现有排序元组：

1. bundle member frequency，降序；
2. residual closure count，降序；
3. residual coverage fraction，降序；
4. cars，降序；
5. signature，升序。

审计表中的“rawDeep分数”使用以上五项独立字段表达；“排序位次”表示候选在同一花型内按该元组的稳定排名。

### 3.3 配额 12/16

- 配额 12：复现当前最终深池选择规则，总量上限 550；
- 配额 16：仅把每花型最高保留数从 12 改为 16，其余顺序、最低覆盖过程和总量 550 不变；
- 两组选择均为离线模拟，不修改生产常量；
- broad450 固定，Packing 使用 `broad450 + simulatedDeep`；
- 两组 Packing 使用相同 15 秒上限和 SCIP 固定种子。

由于总量仍为 550，提高单花型配额可能挤掉其他花型。报告必须同时记录新增目标命中、丢失目标命中、花型覆盖数和 Packing 关闭订单数，不能只报告命中增加量。

## 4. 逐列审计表

每个人工 oracle 列输出一行，至少包含：

| 字段 | 含义 |
|---|---|
| oracleColumnId | 稳定编号，按签名排序后从 1 开始 |
| signature | 完整列签名 |
| pattern | 花型签名 |
| cars | 车数 |
| odd | 是否奇数组 |
| oneCar | 是否一车组 |
| orderCoverage | 订单覆盖摘要 |
| inBroad | 是否存在于 broad450 |
| inRawDeep | 是否存在于固定 rawDeep |
| bundleFrequency | 当前排序元组第一项 |
| residualClosures | 当前排序元组第二项 |
| residualCoverageFraction | 当前排序元组第三项 |
| samePatternRank | 同花型内稳定排名 |
| keptAtQuota12 | 配额 12 是否保留 |
| keptAtQuota16 | 配额 16 是否保留 |
| inCurrentDeep550 | 是否进入当前 deep550 |
| packingSelected12 | 配额 12 Packing 是否选择 |
| packingSelected16 | 配额 16 Packing 是否选择 |
| lossStage | 首个损失阶段 |
| detail | 排名、上限或生成损失细节 |

`lossStage` 按首个失败点分类：

- `BROAD_KEPT`
- `RAW_DEEP_NOT_GENERATED`
- `QUOTA_12_PRUNED`
- `DEEP_550_KEPT_PACKING_REJECTED`
- `PACKING_SELECTED`

对于 `RAW_DEEP_NOT_GENERATED`，复用现有候选审计器进一步标记 `CAR_COUNT_PRUNED`、`LOCAL_CONFIGURATION_PRUNED`、`BEAM_PRUNED` 或其他已有阶段。不得复制一套生成算法。

## 5. 汇总指标

### 5.1 基础去向

- oracle 总数、花型数；
- broad 命中数；
- rawDeep 命中数；
- 当前 deep550 命中数；
- Packing 命中数；
- 各首损阶段计数；
- rawDeep 中 oracle 列的同花型排名分布：`<=8`、`9..12`、`13..16`、`>16`。

### 5.2 配额对照

对 quota12 和 quota16 分别报告：

- 深池列数和花型数；
- oracle 精确列命中数；
- 54 组 Packing 状态、关闭订单数；
- Packing 使用的 broad/deep 列数；
- Packing odd、one-car 使用数及资源残差；
- 总耗时和候选指纹。

## 6. 可选排序对照

只有基础审计证明 oracle 列大量存在于 rawDeep、但在 quota12/quota16 中丢失时，才执行排序对照。排序对照仍复用同一 rawDeep 指纹和总量 550：

1. `CURRENT_TUPLE`：当前 bundle/闭合/覆盖排序元组；
2. `RESIDUAL_CLOSURE`：闭合数、覆盖比例、车数、签名；
3. `ODD_AWARE_DIAGNOSTIC`：在 `RESIDUAL_CLOSURE` 基础上记录并优先满足诊断所需的 odd 候选覆盖，但不得改变最终精确约束。

本轮排序对照只形成证据，不修改生产排序。若 odd-aware 规则需要任意权重才能定义，则停止该分支并报告“缺少可证伪评分定义”，不得扫权重。

## 7. 决策规则

结果 A，配额不足：

- 至少 20 个 oracle 列存在于 rawDeep；
- 主要集中在同花型排名 13..16 或更后；
- quota16 明显提高精确命中，且 Packing 不退化。

结果 B，选择或组合结构错误：

- oracle 列已经大量进入 deep550；
- 但 Packing 很少采用，或关闭订单数仍明显低于人工池的 101；
- 此时不能用继续加配额替代组合层诊断。

结果 C，生成阶段损失：

- 至少 15 个 oracle 列不在 rawDeep；
- 首损集中在车数、局部配置或 Beam；
- 此时停止 Top550 排序实验，回到生成阶段审计。

若结果混合，则按实际计数报告，不强行归入单一结果。

## 8. 验证与交付

测试至少包括：

1. 审计开启和关闭时，生产选择签名完全一致；
2. quota12 离线模拟与当前 deep550 签名一致；
3. quota12 与 quota16 使用相同 rawDeep 指纹；
4. 两组 Packing 均报告真实状态，TIME_LIMIT 不得表述为不可行；
5. 报告 Maven 实际执行数、成功数、失败数和跳过数。

交付物：

- Markdown 审计报告；
- 至少前 20 行逐列表和完整 CSV/TSV 审计表；
- quota12/quota16 对照；
- 若满足触发条件，再附三种排序策略对照；
- 明确给出 A、B、C 或混合结论，以及下一步是否值得进入生产算法修改。

本轮完成标准是得到可信审计结论，不是让 T9 门禁转绿。
