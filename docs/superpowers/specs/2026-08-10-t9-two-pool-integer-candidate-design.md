# T9 双池整数候选质量修复设计

## 1. 目标与结论

修复 direct QUALITY 在 T9EST188 上“LP 已闭合，但整数候选池无法形成精确解”的问题。

采用双池分离：LP 定价池继续限制为 500 列，只负责闭合 LP；LP 闭合后再构建独立的 1000 列整数候选池，以 450 列广度候选、54 组 Packing 残差反馈和 550 列关键花型深度配置提高整数结构质量。

旧 QUALITY 不作为 180 秒生产回退。人工 65 组方案只用于离线审计，不得向生产候选生成注入其花型、配置或列签名。

## 2. 已验证事实

- 旧 QUALITY 关闭 SPR 时在 180.7 秒硬超时，最好只观察到 72 组中间解。
- 旧 QUALITY 开启 SPR 时在 181.8 秒硬超时，最好只观察到 71 组中间解；SPR 尚未执行。
- 当前关键花型实验在 90.475 秒内完成，但最终返回 111 组 FAST 回退；该诊断测试没有质量断言。
- 当前运行的第一组目标达到 `artificial=0.0`，随后整数主问题和修复主问题均为 `INFEASIBLE`。
- 当前残差流程保留 21 组、关闭 60 个订单、残留 56 个订单；1000 个结构候选仍被截断。
- 已知 65 组审计方案在 54 组 Packing 上可以关闭 101 个订单，说明“54 组关闭订单数”能够区分弱候选池与正确结构。
- 既有审计显示目标 44 种花型中 39 种可进入前 1000，但只命中 65 个目标列配置中的 2 个；瓶颈是同一花型下的订单配置深度，不是继续扩张无关花型总量。

## 3. 当前实验实现的偏差

当前工作区中的 Phase 1 和 `OrderGroupIntegerCandidatePoolBuilder` 只是实验中间态，不能直接提交或接入：

1. `qualityOptions.maxColumns` 被改为 1000，导致 LP 池也扩张到 1000，违反双池分离。
2. Builder 只拼接种子列和 LP 活跃列，最多返回 450 列；深度池尚未生成。
3. Builder 未接入 `OrderGroupColumnPricingEngine`，54 组 Packing 也未参与构建。
4. 当前关键花型评分没有使用 LP 对偶信息，残差评分只按宽度需求量累加，无法表达订单候选稀缺度和资源互补性。
5. 当前 T9 诊断只打印日志，即使回退 111 组也会显示 JUnit 通过。

实现时应保留已经验证有效的“LP collector 不应用关键花型配额”修正；删除或改造未接入的旧关键花型过滤代码，不保留两套相互竞争的配额逻辑。

## 4. 范围与非目标

允许修改：

- `OrderGroupColumnPricingEngine`
- `OrderGroupColumnPricingOracle`
- `OrderGroupColumnPricingPrototype.Options`
- `OrderGroupIntegerCandidatePoolBuilder`
- `CuttingSolver` 中 direct QUALITY 的候选池参数编排
- 与上述链路直接相关的单元测试和 T9 诊断测试

明确不修改：

- `LegacyOrderPatternSelectionSolver`
- 旧 QUALITY 的 LNS、SPR、Phase2 编排
- FAST 输出逻辑、API DTO、前端和持久化
- Maven/Surefire 配置
- 九个现有 DJX188 未跟踪实验文件
- 人工 65 组方案和历史归档文件

本次不扫描或保留 7000 个无差别花型候选，不新增跨请求全局缓存，不改变 530 车、119670 废边及 odd/one-car 精确约束语义。

## 5. 参数边界

LP 与整数候选池必须使用两个独立上限：

```text
lpColumnCapacity = 500
integerColumnCapacity = 1000
broadColumnCapacity = 450
deepColumnCapacity = 550
feedbackPackingGroupCap = 54
acceptancePackingGroupCap = 54
minimumClosedDemands = 80
keyPatternMin = 40
keyPatternMax = 60
deepConfigurationsPerPatternMin = 8
deepConfigurationsPerPatternMax = 12
```

`Options.maxColumns` 继续表示 LP 工作池容量，不允许复用为整数池容量。整数池容量应使用语义明确的独立参数；若为减少构造器改动而复用现有 `integerRepairCandidates`，必须在命名或封装层明确它代表整数候选上限，不能继续同时表达修复档案容量。

所有阶段共享 direct QUALITY 的同一个绝对 deadline。任一阶段只能使用剩余预算，不得通过嵌套调用突破 180 秒墙钟。

## 6. 数据流

```text
种子列
  -> 500 列 LP 定价池
  -> RMP LP 闭合（artificial = 0）
  -> 450 列广度池
  -> 第一次 54 组 Packing（残差反馈）
  -> 40~60 个关键花型
  -> 550 列深度池
  -> 最多 1000 列整数候选池
  -> 第二次 54 组 Packing（质量门禁）
  -> 分层整数可行性诊断
  -> 精确整数主问题
  -> 守恒与展示语义校验
  -> 成功方案或 FAST 回退
```

第一次 Packing 用于产生残差，不作为最终门禁；第二次 Packing 在广度池和深度池合并后执行，只有它的关闭订单数用于决定是否进入昂贵整数求解。

## 7. 450 列广度池

广度池只从当前请求真实产生的种子列、LP 池和本轮定价档案中选择，不重新枚举无关大池。

确定性选择顺序：

1. 保留 LP 解中值大于 `1e-6` 的活跃列，按 LP 值降序、列签名升序排列。
2. 补充可行种子列，按角色、订单覆盖稀缺度和列签名稳定排序。
3. 对剩余候选按订单锚点轮询，优先候选数量最少的订单，避免热门订单占满池。
4. 在 `EVEN_CORE`、`ODD_REQUIRED`、`RESIDUAL_CLOSURE`、`SMALL_FALLBACK`、`ONE_CAR_FALLBACK` 之间保留角色覆盖。
5. 同一花型在广度池中最多保留 4 个不同配置，使 450 个槽位优先提供结构覆盖。
6. 按列签名和完整资源向量去重，最终不超过 450 列。

“LP 活跃”使用列变量值，不使用“花型具有高 dual value”这一不准确概念。对偶值属于主问题约束，只能作为订单紧张度和资源紧张度的信号。

## 8. 第一次 Packing 与关键花型识别

对广度池运行：

```text
solvePacking(input, broadPool, lpValues, maxGroups=54, timeLimit<=5s)
```

若 Packing 未返回 `OPTIMAL/FEASIBLE`，候选构建立即标记失败，不进入深度生成和整数 MIP。

关键花型评分只使用当前请求数据，由三类信号组成：

1. LP 活跃度：该花型的活跃列数量和 LP 使用值总和。
2. 残差稀缺度：花型覆盖的残差订单越缺少可生成配置，分数越高；不能只按残差需求量排序。
3. 资源互补度：在剩余车数、剩余废边、剩余 odd、剩余 one-car 范围内可行，并能更接近残差资源闭合的花型优先。

花型必须覆盖至少一个残差订单且能在残差资源上物化。按残差订单锚点轮询选择 40~60 个，最后以原始稳定排名和花型签名打破平局。不得读取人工方案签名。

## 9. 550 列深度池

深度池只枚举已选关键花型，复用 `OrderGroupColumnCandidateGenerator` 的车数候选、局部订单配置和 Beam 物化能力，但不要求列具有负 reduced cost。

选择规则：

1. 每个关键花型先争取 8 个不同订单配置。
2. 基础配额完成后按残差订单稀缺度轮询补充，每花型最多 12 个配置。
3. 优先能够完整关闭残差订单的列，其次按归一化残差覆盖、资源互补度和稳定签名排序。
4. family 冲突、超出残差订单、车数、废边、odd 或 one-car 上限的列在进入深度池前剔除。
5. 某花型不足 8 个可行配置时不伪造配置；空余槽位交给其他关键花型，深度池最多 550 列。
6. 深度池与广度池按签名和完整资源向量去重，最终整数池不超过 1000 列。

## 10. 第二次 Packing 质量门禁

合并池构建后再次运行 54 组 Packing，并记录：

```text
broadColumns, deepColumns, totalColumns,
keyPatterns, min/median/maxConfigsPerKeyPattern,
feedbackPackingClosed, acceptancePackingClosed,
residualOrders, residualCars, residualWaste,
residualOdd, residualOne, elapsedMs
```

进入整数 MIP 的必要条件：

```text
acceptancePacking.feasible() == true
acceptancePacking.closedDemands() >= 80
remainingDeadlineMs > 0
```

门禁失败返回明确的 `INTEGER_CANDIDATE_GATE_FAILED` 诊断状态并使用既有 FAST 回退，不继续消耗完整整数预算。门禁失败不表示数学不可行，只表示本轮候选池质量不足。

## 11. 分层整数可行性与精确求解

门禁通过后，先调用现有分层诊断：

1. 订单、车数、废边和 family 精确约束；
2. 加入 odd 精确约束；
3. 再加入 one-car 精确约束。

日志必须明确是哪一层首次变为 `INFEASIBLE`。诊断只用于定位，不替代最终求解。

最终 `solveInteger` 使用完整的独立整数候选池，并继续严格满足：

- 每个订单精确覆盖；
- 530 车；
- 119670 废边；
- 当前 shape 的 odd 和 one-car 目标；
- family 不重复；
- 转换为 `CuttingInstruction` 后指标与列模型一致。

## 12. 测试设计

### 单元测试

- LP collector 不受关键花型配额影响。
- LP 池容量保持 500，整数池容量独立为 1000。
- 广度池稳定保留 LP 活跃列、订单锚点和所有必要角色。
- 广度池同花型最多 4 列。
- 关键花型不读取人工签名，并同时使用活跃度、残差稀缺度和资源互补度。
- 深度池对可生成花型保留 8~12 个不同配置。
- 合并池签名与资源向量去重，总量不超过 1000。
- 门禁低于 80 时不调用整数 MIP。
- 门禁通过时执行分层诊断和精确整数 MIP。
- deadline 在广度、两次 Packing、深度生成、诊断和整数求解之间共享。

### T9 快速门禁

替换当前只打印日志的诊断测试，直接断言：

```text
feedbackPacking.feasible() == true
acceptancePacking.feasible() == true
acceptancePacking.closedDemands() >= 80
```

该测试必须调用生产候选构建链，不能复制候选算法，也不能读取人工 65 组列作为输入。

### 完整回归

快速门禁通过后才运行三次严格 180 秒 T9：

- 三次均在墙钟内完整返回，不允许硬超时；
- 三次均不得回退到 111/124 组；
- 每次组数严格小于 111；
- 三次的组数、车数、废边、odd、one-car 指标一致；
- 车数固定 530，废边固定 119670，订单 under/over 均为 0；
- 记录结果签名差异，签名不同时不得隐瞒；
- 65~70 组是继续优化目标，不作为本轮首个接入门槛。

随后执行 direct QUALITY 相关核心单测、DJX188、T42 和 Maven 构建，报告实际执行数、成功数、失败数、错误数和跳过数。`0 tests found` 或 `Tests are skipped` 不得视为通过。

## 13. 失败与回退语义

- LP 未闭合：沿用现有 direct QUALITY 失败状态和 FAST 回退。
- 第一次 Packing 失败：候选反馈失败，停止本 shape。
- 第二次 Packing 关闭订单少于 80：候选门禁失败，停止本 shape。
- 分层诊断失败：记录首次失败约束层，停止本 shape。
- 整数 MIP 超时或不可行：记录真实状态，尝试预算内下一个 shape；预算耗尽后 FAST 回退。
- 任何截断、超时或候选上限只表示 `INCONCLUSIVE/候选不足`，不得宣称数学不可行。

## 14. 交付边界

1. 本设计文档独立提交，不夹带当前工作区源代码和实验文件。
2. 当前 Phase 1 和 Builder 只有在真实门禁通过后才能作为有效实现提交。
3. 实现完成后清理未接入的关键花型选择器、打印式诊断和无效中间代码。
4. 最终提交信息使用中文规范，例如 `feat：实现质量模式双池整数候选生成`。
5. 不推送、不创建 PR。
