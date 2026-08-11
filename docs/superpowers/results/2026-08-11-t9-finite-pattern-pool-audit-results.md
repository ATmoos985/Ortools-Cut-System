# T9 有限宽花型池召回审计结果

## 结论

验收条件未触发。三类独立的单花型入口在 Top300 对固定目标 27 种花型的召回分别为 `10/27`、`1/27`、`1/27`，均未达到 `>20/27`。

扩大到 Top600 后，召回也只有 `10/27`、`4/27`、`1/27`。这不是 Top60 配额略小或排序权重略偏，而是固定目标花型在当前单花型信号下处于明显长尾。

因此，当前不具备直接进入束评价的有限宽候选入口。继续在 Top60/120/300 内调整单列评分或权重，没有证据支持。

## 固定审计对象

- 数据：`t9est188.csv`
- 人工 oracle：65 列
- 本轮固定损失对象：31 列、27 种花型
- 固定目标指纹：`5219717536aea57ddd68b90adb67710bcac44e55a26905628e42858b3c3d1aea`
- 明细 CSV：`2026-08-11-t9-finite-pattern-pool-audit.csv`
- CSV SHA-256：`04f6d7db9d422dac22aa8854723fc65c87e78e8902bc0daa1a5729050aa3f581`

人工目标只在三套排名完成后用于统计召回，没有进入任何评分项。

## 召回曲线

表格中的每个值均为“命中目标花型数 / 命中人工列数”；验收以花型数 `/27` 为准。

| 策略 | 候选空间 | Top60 | Top120 | Top300 | Top600 | 全空间目标命中 |
|---|---:|---:|---:|---:|---:|---:|
| LP_POOL_DENSITY | 213 | 6/8 | 7/10 | 10/13 | 10/13 | 10/13 |
| ORDER_COVERAGE_FRONTIER | 54,407 | 0/0 | 0/0 | 1/1 | 4/4 | 27/31 |
| LP_DUAL_WEIGHTED | 54,407 | 1/2 | 1/2 | 1/2 | 1/2 | 27/31 |

三套排名的口径：

- `LP_POOL_DENSITY`：使用首次闭合 LP 的完整列池成员，不只看正取值列；优先 LP 中同花型的列数量，再用正 LP 活跃度打破并列。
- `ORDER_COVERAGE_FRONTIER`：对覆盖首次 Packing 残差订单的全部花型，按稀缺订单覆盖、覆盖量、非残差宽度数和废边匹配排序。这是宽度层面的可物化上界，不等于订单配置已经生成。
- `LP_DUAL_WEIGHTED`：在同一宽候选空间上，使用需求 dual 构造乐观上界。花型阶段无法确定的 cars、waste、odd、one-car、family 贡献没有伪造进入评分。

## 超过 Top300 后的真实代价

验收要求是至少命中 21/27 种目标花型：

| 策略 | 达到 21/27 所需名次 | 判断 |
|---|---:|---|
| LP_POOL_DENSITY | 不可达到 | 完整 LP 列池中只有 10/27 种目标花型 |
| ORDER_COVERAGE_FRONTIER | 39,079 | 远超 300，且接近完整空间枚举 |
| LP_DUAL_WEIGHTED | 39,361 | 远超 300，且接近完整空间枚举 |

所以本轮不仅证明“Top300 不够”，还证明将上限扩大到 600 仍没有形成可用入口；要靠当前覆盖或 dual 单花型信号达到验收，需要约 3.9 万个花型，生产代价不可接受。

## LP dual 采集时点验真

- 首次闭合 OPTIMIZATION 迭代：`9`
- OPTIMIZATION trace 数量：`1`
- artificial / epsilon：`0 / 1e-7`
- 该轮新增列：`0`
- pricing termination：`NO_NEGATIVE_COLUMN_FOUND`
- LP 花型池指纹：`62a84268dcc81d07cf784e6b6e708e807aee81ae47d95b542006281feb7bff6d`
- dual 指纹：`50ba1f7277e6696fdfaafce293b2b5bddd4a3d31719af13e33aa1d1e4e8bef89`

调用链验真结果：FEASIBILITY 首次达到 `artificial <= epsilon` 后只切换 phase 并直接进入下一轮；下一轮在同一列池上求第一次 OPTIMIZATION LP，记录 trace 后立即退出，未调用 pricing oracle。随后 Builder 直接接收该 `lastLp.columnValues()` 和 `lastLp.dual()`。因此本次排名使用的 dual 没有被后续定价扰动污染。

## 当前 T9 链路状态

- 求解状态：`INTEGER_CANDIDATE_GATE_FAILED`
- LP 定价终止：`NO_NEGATIVE_COLUMN_FOUND`
- 两池规模：broad `450`、rawDeep `1699`、deep `550`、总池 `1000`
- feedback Packing：`FEASIBLE / 55`
- acceptance Packing：`FEASIBLE / 63`
- odd support：`INFEASIBLE / 0`
- 候选门禁：`false`

本次有限池审计耗时 `40,246 ms`；真实 T9 JUnit 用例耗时 `43.136 s`，Maven 全流程约 `62 s`。

## JUnit 验真

| 测试 | 实际执行 | 成功 | 失败 | Error | 跳过 |
|---|---:|---:|---:|---:|---:|
| OrderGroupKeyPatternAuditSnapshotTest | 1 | 1 | 0 | 0 | 0 |
| T9KeyPatternSelectionAuditTest | 1 | 1 | 0 | 0 | 0 |
| 合计 | 2 | 2 | 0 | 0 | 0 |

## 决策

本轮判定为“不满足束评价前提”，而不是审计失败：

1. 不继续对 `lpActivity`、覆盖权重或 Top60/120/300 配额做线性微调。
2. 不在当前 deep pool 上直接实现束评价，因为目标花型尚未以有限代价进入候选空间。
3. 下一阶段应重新评估整体上限，研究能够打破“先识别花型、再发现组合价值”循环依赖的配置级或联合物化入口；任何新入口先做同样的离线召回门禁，再承担 MIP 成本。

本次只增加 opt-in 测试审计和结果材料，没有改变生产选择、Packing 或时间预算。
