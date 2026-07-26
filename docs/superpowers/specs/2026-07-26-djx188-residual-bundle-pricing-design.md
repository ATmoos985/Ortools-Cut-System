# DJX188 残量列束定价实验设计

## 1. 背景

2026-07-23 缺列审计（`2026-07-23-djx188-order-group-missing-column-audit-design.md`）
已经证明：

- 29 组见证列的骨架 100% 在 7717 宇宙内，`PATTERN_NOT_IN_UNIVERSE=0`、
  `CAR_COUNT_PRUNED=0`、`BEAM_PRUNED=0`；
- 28/29 的损失发生在物化之后：14 条 `MATERIALIZED_NONNEGATIVE`、
  13 条 `NEGATIVE_GLOBAL_SELECTION_PRUNED`、1 条已在池未选；
- 31↔29 对称差交换全资源守恒、`groupDelta=-2`，其中 15 条新增列单列
  reduced cost 非负——单列 RC 门禁原理性看不见联合改进；
- 唯一的物化截断是 830 宽幅局部配置排第 9 撞上限 8。

审计 §10.3 据此给出下一步：incumbent residual bundle pricing。
后续 9 个未提交实验系统性关闭了竞争方向（扩池、LP 对偶召回、批次列
定价、大池冷启动），但没有一个真正实现列束定价。本轮实现它。

## 2. 本轮目标

在订单级组列研究原型的 31 组 incumbent 上实现**残量列束交换**：

1. 从 incumbent 移出 2~K 条相关组列（bundle）；
2. 以移出列的资源向量（逐订单覆盖、车数、废料、奇数组数、单车组数）
   作为**残量等式约束**；
3. 在残量内从 7717 宇宙联合枚举替换候选——**束内允许单列 RC 非负**，
   不做逐宽度子集过滤（PatternNeighborhood 的 `fits()` 分量级子集过滤
   在结构上禁止补偿交换，不复用）；
4. 用小型精确 MIP 联合选择替换束，仅当替换列数严格小于移出列数时
   整束换入；
5. 迭代至无改进或预算耗尽。

同时补做审计 §10.4 的最早阶段前置实验：`maxLocalConfigsPerWidth`
8→10 的单变量审计复跑，验证 `LOCAL_CONFIGURATION_PRUNED` 归零。

成功判据：在 169 车 / 36,870mm / 奇 1 / 单 0 全守恒下组数 ≤30，目标 29。
阴性结果同样有效：若 2~4 束全阴性，按 31↔29 对称差的实际连通结构
（|removed|=17）升级束规模，而不是放弃方向。

## 3. 严格边界

- 不修改 `src/main/java`；
- 不读取人工 26 组或 automatic22 花型作为候选、排序或提示；
- 不修改用户已有的九个未跟踪实验文件与工作区中
  `LegacyOrderPatternSelectionSolver` 的未提交改动；
- 单变量纪律：列束实验不同时改动候选枚举参数；局配上限实验不同时
  启用列束；
- 结果必须断言化或写回本文档，禁止只打印不沉淀。

## 4. 组件设计

### 4.1 `OrderGroupResidualBundlePricer`（测试侧）

```java
static Result improve(Input input, List<GroupColumn> incumbent, Options options)
```

`Options`：`minBundleSize`、`maxBundleSize`、`maxBundlesPerSize`、
`maxCandidateColumns`、`perBundleMipMs`、`totalBudgetMs`、`maxSwaps`。

`Result`：最终列集与 `Metrics`、`bundlesScanned`、`bundlesTruncated`、
`swapsApplied`、`budgetExhausted`、逐次 `SwapRecord`（removed/added
签名与 groupDelta）、耗时。

#### 束枚举

- 尺寸从小到大逐层扫描；每层枚举全部组合并按**订单支持重叠度**
  （束内两两 coverage 键交集大小之和）降序 + 签名字典序排序，
  截取 `maxBundlesPerSize`（截断如实计数）；
- 首改进即接受并从最小尺寸重新开始（first-improvement + restart）。

#### 残量候选枚举（精确，不用对偶）

对每个宇宙花型（签名序）：

- 花型每个宽幅必须存在残量订单，否则跳过；
- 车数 k 从 1 递增，宽幅可行性判据 `Σ_o min(slots, ⌊R[o]/k⌋) ≥ slots`
  随 k 单调收紧，失效即断；`rOdd=0` 时剪掉奇数 k，`rOne=0` 时剪掉 k=1；
- 逐宽幅枚举乘数 `m_o ≤ ⌊R[o]/k⌋`、`Σm_o=slots` 的完整局部结构，
  跨宽幅笛卡尔积物化为 `GroupColumn`（复用 `GroupColumn.create`，
  签名口径与主问题一致）；
- 排除 familySignature 与保留列冲突的候选；按 resourceSignature 支配
  去重（与 `ColumnPool` 同规则）；
- bundle 自身列始终加入候选（允许部分保留式交换）；
- 超出 `maxCandidateColumns` 时停止并标记截断，不得静默。

#### 束 MIP

SCIP（种子 42、单线程，同 `OrderGroupRestrictedMaster`），0-1 变量：

- 逐订单覆盖、车数、废料、奇数组、单车组**等式** = 残量向量；
- 候选内 family ≤ 1；与保留列的 family 冲突已在枚举阶段排除；
- 改进约束 `Σx ≤ |bundle| − 1`（可行即改进）；
- 目标 = Σ(1.0 + small?1e-4)·x，与主问题 `optimizationCost` 同口径。

换入后对完整列集重验：需求等式、车数/废料/奇/单与 Input 精确目标
一致、family 全局唯一；再经 `convertSelected` +
`SequenceGroupPostProcessor.computeGroupStats` 验证输出语义一致。
为此将 `OrderGroupColumnPricingEngine.convertSelected` 从 private
放宽为包内可见（唯一的既有文件改动）。

### 4.2 `Djx188ResidualBundlePricingExperimentTest`（opt-in）

- `-Dcutting.test.residualBundlePricing=true`：完整 7717 自主路径
  （默认 Options，先断言 31/1/0/169/36,870 基线），运行列束定价，
  打印逐束/逐交换报告，断言守恒与语义一致、`groups ≤ 31`；
- `-Dcutting.test.localConfigCapAudit=true`：`localConfigs=10` 的
  自主复跑 + 29 组见证缺列审计，断言审计 `COMPLETE` 且
  `LOCAL_CONFIGURATION_PRUNED=0`。

关键旋钮均可 `-D` 覆盖（束尺寸、每层束数、候选上限、单束 MIP 时限、
总预算）。

### 4.3 `OrderGroupResidualBundlePricerTest`（单元）

合成小实例至少覆盖：

1. 可证明的 2 换 1 / 3 换 2 交换被发现且全守恒；
2. 与保留列 family 冲突的候选被排除；
3. 宇宙中不存在守恒替换时零交换、扫描计数如实；
4. 相同输入重复运行结果逐字节一致（确定性）;
5. 候选截断如实上报。

## 5. 验证

- 单元测试全部通过并报告实际执行数；
- DJX188 opt-in 实验按 §4.2 断言；T42 已有回归不受影响（不改共享代码
  路径，`convertSelected` 仅放宽可见性）；
- 实验结果（组数、交换明细、耗时、截断）写回本文档 §6。

## 6. 实施结果（待实验后回填）

待回填。

## 7. 提交边界

```text
docs：设计残量列束定价实验
test：实现残量列束定价实验
```

实现提交不包含用户未跟踪实验文件与 `LegacyOrderPatternSelectionSolver`
的工作区改动。
