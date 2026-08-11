# T9 有限宽花型池召回审计设计

## 1. 目标与决策门槛

本轮只回答束评价的先决问题：在物化深池配置之前，能否用不读取人工答案的信号，把固定 31 列对应的 27 种目标花型召回到有限候选范围。

固定输出 Top60、Top120、Top300、Top600 的目标花型命中数和目标列命中数。决策门槛为：

- 任一策略在 Top300 内命中超过 20/27 种目标花型：有限宽池成立，下一阶段才允许研究配置物化和束评价；
- 所有策略都需要超过 300 个花型才能达到 20/27：单花型预识别不成立，停止接入束评价并重新评估当前候选空间的质量上限。

该审计没有“求解成功/失败”之分；两个结果都用于路线裁决。

## 2. 范围

允许修改：

- opt-in 的 `T9KeyPatternSelectionAuditTest` 及其测试侧分析代码；
- 本设计文档和审计完成后的结果文档、CSV。

明确不修改：

- `CuttingSolver`、生产 API、Packing、关键花型生产排序、深池生成、候选池上限和时间预算；
- 人工 31/65 列不得进入候选排序，只能在排序完成后做命中统计；
- 本轮不物化新配置、不执行束 MIP、不运行 180 秒 T9 整数求解。

当前工作区包含既有 `src/test -> src/main` 迁移；测试侧审计改动不与该迁移一起提交。最终只提交独立的设计和结果文档。

## 3. LP dual 的唯一采集口径

现有真实链路已经满足“首次闭合 OPTIMIZATION LP”的时机要求，不新增 Engine 钩子，也不二次重求 LP：

1. FEASIBILITY 阶段第一次得到 `artificial <= epsilon` 后，只切换 `phase=OPTIMIZATION` 并立即 `continue`，期间不定价；
2. 下一轮用同一列池求解第一份 OPTIMIZATION LP；
3. 该解仍满足 `artificial <= epsilon` 时，在调用 pricing oracle 之前立即 `break`；
4. 这份 `lastLp.columnValues()` 和 `lastLp.dual()` 随后原样传入 `OrderGroupIntegerCandidatePoolBuilder`；
5. 已有 opt-in `AuditSnapshot` 会保存完整 `lpValues` 和同一份 `referenceDual`。

测试必须断言：

- 恰好存在一条 OPTIMIZATION trace；
- 该 trace 的 `maxArtificial <= artificialEpsilon`；
- 该 trace 的 `addedColumns == 0`；
- `AuditSnapshot.referenceDual` 非空，`lpValues` 非空。

因此本轮使用同步采集到的第一份闭合 OPTIMIZATION dual，不使用最后定价轮 dual，也不使用重新求解得到的替代 dual。

## 4. 三种离线策略

所有排序先完成，再关联固定 27 种目标花型。

### 4.1 LP_POOL_DENSITY

从 `AuditSnapshot.lpValues` 的完整列签名提取不同花型。`columnValues` 在 GLOP 解后按输入列逐一写入，因此包括零值列，不等于仅取正活动列。

排序：

1. 同花型 LP 列数量降序；
2. 同花型正 LP 取值之和降序；
3. 花型签名升序。

同时报告完整 LP 花型池的自然规模和总召回率。该策略衡量“花型是否已经被 LP 列生成器物化过”，不把 LP 解值当作 dual。

### 4.2 ORDER_COVERAGE_FRONTIER

候选为 universe 中覆盖至少一个 generation residual `DemandKey` 的花型。花型尚未绑定订单配置，因此这里是宽度层面的可物化上界，不宣称具体配置已经可行。

对每个花型计算：

- 覆盖的残差订单项数；
- 覆盖的残差需求数量；
- `sum(residualQuantity / frontierAlternativeCount(width))` 的稀缺覆盖分；
- 非残差宽度数；
- 单车废边与残差目标单车废边的匹配度。

排序依次按稀缺覆盖分、覆盖订单项、覆盖数量降序，再按非残差宽度数升序、废边匹配度降序、签名升序。

### 4.3 LP_DUAL_WEIGHTED

候选与 ORDER_COVERAGE_FRONTIER 相同。由于花型尚无订单配置，不能计算完整 GroupColumn reduced cost；本轮只计算明确标注为上界的需求 dual 潜力：

```text
dualUpperBound(pattern)
  = Σ stationCount(width)
      × max(0, max demandDual(order at width))
```

不加入 cars、waste、odd、one-car、family dual，因为这些量在花型阶段尚未确定。排序依次按 dual 上界、稀缺覆盖分降序，再按非残差宽度数升序、废边匹配度降序、签名升序。

## 5. 输出与验真

输出到独立目录，不覆盖上一轮关键花型审计：

- `target/t9-finite-pattern-pool-audit/<runId>/t9-finite-pattern-pool-audit.csv`
- `target/t9-finite-pattern-pool-audit/<runId>/t9-finite-pattern-pool-audit.md`
- `target/t9-finite-pattern-pool-audit/<runId>/t9-finite-pattern-pool-audit.properties`

CSV 至少记录花型、三种名次、LP 列数量、LP 正活动度、覆盖指标、缺失宽度数、废边匹配度、dual 上界及人工目标计数。

验证要求：

1. opt-in 审计测试实际执行 1/1，不能把 skipped 当作通过；
2. 既有快照等价性测试继续通过，证明旁路审计不改变候选结果；
3. 27 种目标花型、31 列和既有目标 SHA-256 保持不变；
4. Top K 每种策略恰好按同一不可变排名前缀统计；
5. 报告明确区分“LP 列池成员”“正 LP 活跃”“dual 上界”和“宽度覆盖上界”。
