# QUALITY 残差组合优化生产接入设计

## 1. 目标与结论

把已经通过历史回放的“组合候选 + LP 预筛 + 精确残差 MIP”接入 NewSolver 的实际 `QUALITY` 链路。

本次采用受控实际替换，而不是只记录 shadow 结果：新方案只有在真实序号组数严格减少，并且需求、车数、废边、奇偶组、单车组全部与原方案一致时才替换；其余情况无条件返回原方案。

`FAST`、未开启 NewSolver 的链路以及非精确覆盖结果保持不变。

## 2. 已有证据

- DJX188：25 组降到 24 组，169 车、36,870mm 废边、odd=1、one-car=0 全部守恒，三次独立运行签名一致。
- T42：10 组保持 10 组，没有假改善。
- T9EST188：65 组降到 63 组，车数、废边、odd、one-car 守恒。
- sixian：42 组降到 39 组，车数、废边、odd、one-car 守恒。
- 四组历史数据中三组改善、一组持平、零质量回退。

这些结果证明算法值得进入 `QUALITY`，但历史回放使用的是冻结解和归档花型。生产接入只能使用当前请求本轮实际发现的列，因此生产收益仍需通过真实链路回归确认，不能把历史改善幅度直接当作线上保证。

## 3. 方案比较与选择

### 方案 A：只做 shadow

风险最低，但不会改善实际输出，不满足当前目标。

### 方案 B：QUALITY 中受控替换（采用）

只在最终候选选定后执行一次；严格改善才替换，失败即回退。能够产生实际收益，同时把影响限制在质量模式。

### 方案 C：所有模式默认启用

会改变 FAST 时延和默认生产行为，当前证据不足，不采用。

## 4. 接入链路

当前链路保持不变直到最终候选选择：

```text
OptimizationExecutionService(QUALITY)
  -> CuttingSolver
  -> 多候选 Stage5 / LNS / SPR
  -> bestPlan
  -> QUALITY 残差组合优化（新增，仅一次）
  -> 严格守恒与改善校验
  -> 改善方案或原 bestPlan
```

不在每个候选内部执行长时间组合搜索，避免把组合预算乘以候选数。

## 5. 候选来源与数据边界

候选池只包含当前请求、当前分组求解过程中真实产生的可执行 `UnifiedSetPartitionSolver.Column`：

1. 当前候选转换与 SPR 在各自工作线程内通过现有 `SolverRunColumnArchive.capture(...)` 捕获列；
2. 捕获结果随 `CandidateEvaluation` 返回，不改成全局缓存；
3. 最终只使用胜出 `bestPlan` 对应的本轮列，加上 incumbent 自身列；
4. 按列签名去重并稳定排序；
5. 不读取人工方案、历史好解签名或跨请求累计的 7,000 个花型。

这样既保留候选质量，也避免无关大池带来的内存和组合负担。没有足够请求内候选时直接跳过。

## 6. 最小实现

优先复用生产已有类型和求解器，不把测试原型整套搬入 `src/main`：

- `UnifiedSetPartitionSolver.Column` / `ColumnUse`：列与使用次数；
- `SetPartitionRefiner.extractColumnUses(...)`：从现有指令恢复 incumbent；
- `SetPartitionRefiner.toInstructions(...)`：把改善列转回生产指令；
- `UnifiedSetPartitionSolver`：精确残差整数模型；
- OR-Tools GLOP：证明某个移除组合不可能减少组数时提前跳过；
- SCIP/CBC：只求解 LP 未排除的残差组合。

新增一个最小的生产残差组合入口，负责：

1. 按现有稳定规则枚举 size 2..6 的移除组合；
2. 候选上限按实际证明使用 Top1000 标准档，必要时允许 QUALITY 请求覆盖为 Top5000 深度档；
3. 对每个组合先做 LP 下界筛选；
4. 只有下界允许少一组时才运行精确 MIP；
5. 找到改善后立即应用并重新开始，直到预算耗尽或无新改善；
6. 任何候选截断、超时或求解异常都只表示本轮未改善，不输出“已证明最优”。

不新增接口层、工厂或持久化归档。

## 7. 启用与预算

仅当以下条件同时成立时运行：

```text
cutting.quality = true
cutting.residualBundle.enabled = true
当前方案可以完整恢复 ColumnUse
当前方案 under = 0 且 over = 0
候选池包含 incumbent 之外的列
```

API 的 `QUALITY` 配置显式开启 `cutting.residualBundle.enabled`；其他入口默认关闭。

首轮生产参数：

```text
minBundleSize = 2
maxBundleSize = 6
maxBundlesPerSize = 1000
totalBudgetMs = 120000
perBundleMipMs = 3000
maxSwaps = 16
```

深度档只作为请求级覆盖：Top5000、240000ms；本次不新增前端选项。

## 8. 接受与回退规则

候选方案必须同时满足：

```text
after.groups < before.groups
after.cars = before.cars
after.waste = before.waste
after.odd = before.odd
after.one = before.one
after.producedDemand = before.producedDemand
转换回 CuttingInstruction 后再次得到相同指标
```

family 直接复用生产 `Column.signature()`：它由宽度和订单配置唯一确定，不包含使用车数，与研究模型中的 `pattern + config` family 等价。候选按该签名唯一化，残差模型对每个 family 只建立一个整数使用变量，因此不会同时选择同一 family 的多个不同车数组。

以下情况返回原 `bestPlan`：

- 新组数相同或更多；
- 任一守恒项不一致；
- 指令恢复或转换失败；
- 候选池为空或不足；
- LP/MIP 超时、异常或无可行改善；
- 最终语义校验失败。

算法失败不得使整个请求失败。

## 9. 可观测性

每个分组记录一条汇总日志：

```text
poolColumns, bundlesScanned, lpRejected, mipSolved,
swaps, beforeGroups, afterGroups, elapsedMs,
budgetExhausted, accepted, fallbackReason
```

求解报告仍以最终实际返回方案为准；不新增数据库写入。

## 10. 验证

### 自动测试

- LP 下界能排除不可能改善的组合；
- 可行的 2→1、6→5 联合替换不被 LP 错杀；
- 需求、车数、废边、odd、one-car 任一变化均拒绝；
- 相同组数不替换；
- 超时、异常、候选不足返回原方案；
- 仅 QUALITY 开启，FAST 不运行；
- 捕获数据请求隔离，且并行候选不串池。

### 真实回归

重新执行 DJX188、T42、T9EST188、sixian：

- 对比接入前后组数、车数、废边、odd、one-car、under/over、耗时；
- 要求至少一个数据集严格改善，其余数据集不得变差；
- 报告实际执行数、成功数、失败数、错误数、跳过数；
- 重复 DJX188，核对结果签名稳定性；
- 最后执行相关 NewSolver 回归和 Maven 打包，确认测试未被跳过。

如果当前请求内候选池无法在任何历史数据上产生严格改善，则不保留实际替换接入，退回研究层继续改进候选生成。

## 11. 预计改动边界

允许最小修改：

```text
src/main/java/test/demo/apsmodule/generator/NewSolver/CuttingSolver.java
src/main/java/test/demo/apsmodule/generator/NewSolver/output/SetPartitionRefiner.java
src/main/java/test/demo/apsmodule/generator/NewSolver/output/SolverRunColumnArchive.java
src/main/java/test/demo/apsmodule/service/OptimizationExecutionService.java
src/test/java/... 对应最小回归测试
```

如果 `SetPartitionRefiner` 内已有残差方法足以承载组合入口，不新增生产类。实现前重新读取当前文件并以实际最小 diff 为准。

明确不修改：

- 当前工作区已修改的 `LegacyOrderPatternSelectionSolver.java`；
- 九个未跟踪的 DJX188 实验文件；
- Maven/Surefire 配置；
- FAST 参数和输出选择逻辑；
- API DTO 与前端。

## 12. 交付

1. 本设计文档单独提交；
2. 实现与测试通过后以 `feat：接入质量模式残差组合优化` 提交；
3. 不推送、不创建 PR；
4. 最终报告新旧结果、耗时、真实测试计数、回退边界和提交哈希。
