# DJX188 结构化局部花型宇宙设计

## 1. 背景与问题定位

上一轮生产中性移动闭环实验已经得到三个互相独立的结论：

1. 在固定自动22方案和完整7717花型宇宙下，第一层单位2对2移动完整生成252个规范化支持、975个唯一使用状态，其中466个满足使用层“奇数1、使用次数1为0”；
2. 对252个已知支持开放任意整数系数后，固定支持子MIP在约2秒内穷尽466个奇偶兼容系数解，全部与第一层状态重复，新增状态为0；
3. 排除已知支持后，直接在7717花型上联合选择每侧1到3个新支持，SCIP在约300秒内返回 `NOT_SOLVED`、节点0，没有生成新支持，也没有证明不存在新支持。

因此当前瓶颈不是固定支持下的系数枚举，也不是订单兼容性核，而是“如何从7717花型中产生有结构性理由的新支持组合”。继续增加全局主MIP时间预算不能改变这个建模起点过大的事实。

本设计把一次性启发式剪枝改造成三层嵌套、可追溯、可量化覆盖范围的局部候选宇宙，并分别报告：

```text
universeScope       局部宇宙按什么规则构造，规则本身是否穷尽
supportGeneration   在该局部宇宙内支持搜索是否穷尽
exactEvaluation     已生成候选的精确兼容性评价是否穷尽
```

三种状态互相独立。任何局部层的完成都不能被表述成完整7717宇宙已经穷尽。

## 2. 已确认事实与约束

- 自动22方案的精确兼容性最小组数为28，额外拆分为6，奇数组1，单车组0；
- 当前精确分析产生6个 `PatternSplit` 见证，每个见证包含被拆花型、花型使用次数、完整配置块、变化宽幅及相关订单；
- 人工26方案只作为回归锚点，不得为局部宇宙提供签名、使用次数、距离或评分；
- 使用层奇偶只是廉价必要条件，最终组数、奇数组和单车组仍由完整订单配置阈值MIP判定；
- 兼容性拆分责任可以在花型间转移，不能把当前6个见证永久固化成单花型罚分或硬约束；
- 所有局部宇宙必须包含当前基线支持，保持搜索状态可表示；
- 本阶段只新增研究侧代码和测试，不修改生产候选生成、列生成、Stage4、Stage5、Phase2或LNS。

## 3. 方案比较与选择

### 3.1 随机或按静态分数截取

从7717花型中随机抽取固定数量，或按利用率、废料、宽幅数量等单花型分数保留前N个，实现简单，但无法解释被排除花型为什么不重要，也无法量化对当前兼容性缺陷的覆盖。即使局部搜索穷尽，结论也缺乏诊断价值。

本阶段不采用。

### 3.2 所有冲突宽幅触达花型一次性并池

把所有在当前变化宽幅上具有非零系数的花型一次性加入，具有直接业务解释，但范围可能仍然过大；同时“碰到同一个宽幅”不能证明它能参与生产中性重组，容易把局部宇宙重新扩张成缺乏约束的大池。

该集合只作为覆盖率分母，不直接等同于搜索池。

### 3.3 三层结构化局部宇宙

采用三层嵌套结构：

1. `WITNESS_DIRECT`：能按当前拆分配置块车数直接参与中性修补的最小范围；
2. `NEUTRAL_CLOSURE`：完整加入从当前基线支持出发的一步单位2对2生产中性闭包；
3. `TRANSFER_BRIDGE_1_HOP`：允许直接修补花型与一个当前基线花型共同形成下一条中性等式，把责任转移到新宽幅的一跳桥接范围。

每一层都保留前一层全部花型和全部进入依据。该方案牺牲全局完备性，换取可执行、可解释和可逐层扩大的局部证明力。

## 4. 输入、规范化与不变量

### 4.1 输入

局部宇宙构造器接收：

- 完整花型宇宙 `U`，由现有 `CompletePatternEnumerator` 确定性生成；
- 当前整数使用向量 `x`，首个实验固定为自动22方案；
- 每个花型的真实使用上界 `upper[p]`；
- 已证明最优组层形态的 `OrderCompatibilityKernelAnalyzer.Analysis`；
- 当前需求宽幅集合及求解器参数；
- 显式的构造时间、方程数量和花型数量预算。

构造器拒绝以下输入：

- 基线包含完整宇宙外花型；
- 基线花型缺少上界或使用次数超过上界；
- 兼容性分析未证明组数和形态最优；
- 拆分见证引用完整宇宙中不存在的花型；
- 输入花型签名重复。

以上输入错误在构造开始前以 `IllegalArgumentException` 失败，不生成局部宇宙结果，也不把调用方错误包装成“搜索未完成”。

### 4.2 花型向量

对每个花型 `p` 使用确定性宽幅系数向量 `A[p]`。生产中性等式还必须包含车数坐标：

```text
sum(left coefficients)  = sum(right coefficients)
A * delta = 0
sum(delta[p]) = 0
```

任何进入依据中保存的中性等式都必须重新计算并验证这两个等式，不能只相信哈希命中。

当前废料口径为 `totalWidth - patternWidth`。因此上述两个等式还会自动保持总废料：

```text
sum(waste[p] * delta[p])
= totalWidth * sum(delta[p]) - sum(width * (A * delta)[width])
= 0
```

构造器仍需在应用移动后的状态上独立重算169车和36870废料，防止实现错误破坏该推导。

### 4.3 冲突宽幅与残量目标

冲突宽幅集合定义为当前全部 `PatternSplit.varyingWidths` 的并集，不硬编码DJX188具体数字。

残量目标定义为：

```text
ResidualTarget = (splitPatternSignature,
                  configurationSignature,
                  configurationCars)
```

同一花型下配置签名和车数均相同的重复目标规范化为一条；不同配置签名即使车数相同也分别保留，以避免丢失订单兼容性语义。

## 5. 结构化进入依据

进入依据必须是一等数据，而不是日志文本。每个花型可以同时拥有多条依据，不能用后来的理由覆盖先前理由。

```java
record LocalPatternUniverse(
        UniverseScope scope,
        UniverseBuildStatus buildStatus,
        List<PatternCandidate> patterns,
        Map<String, List<InclusionEvidence>> evidenceByPattern,
        CoverageProfile coverage,
        BuildMetrics metrics) {
}
```

`InclusionEvidence` 使用结构化子类型：

### 5.1 `BaselineSupportEvidence`

保存：

- 花型签名；
- 当前使用次数；
- 当前真实上界。

所有三层局部宇宙都必须包含每个基线支持花型及该依据。

### 5.2 `SplitWitnessEvidence`

保存：

- 被拆花型签名和使用次数；
- 配置签名及各配置块车数；
- 变化宽幅；
- 变化宽幅对应的订单消息。

该依据只描述当前缺陷，不把花型标记为永久坏花型。

### 5.3 `ResidualComplementEvidence`

保存：

- 对应残量目标；
- 规范化有符号单位移动 `unitDelta`；
- 采用的整数倍数 `scale`；
- 从见证花型实际移出的车数；
- 该移动的 `tMax`；
- 平衡涉及的全部宽幅。

只有满足以下条件时才能创建：

```text
unitDelta 对生产量和车数精确平衡
unitDelta 的负侧包含对应见证花型
-unitDelta[witness] * scale = configurationCars
1 <= scale <= tMax
应用移动后所有使用次数位于 [0, upper]
```

### 5.4 `NeutralEquationEvidence`

保存：

- 规范化等式左右两侧花型及其整数重数；
- 对应有符号 `unitDelta`；
- 平衡宽幅集合；
- 等式来源层；
- 规范化方程签名。

本阶段的 `NEUTRAL_CLOSURE` 只宣称完整覆盖“从当前基线支持对出发的一步单位2对2等式”，不宣称任意系数、3对3或全局整数核空间闭包。

### 5.5 `TransferBridgeEvidence`

保存：

- 起始拆分见证；
- 对应的 `ResidualComplementEvidence.evidenceId`；
- 一个由该直接修补移动正向引入的 `WITNESS_DIRECT` 非基线花型；
- 与其配对的当前基线花型；
- 精确生产中性等式；
- 直接修补后的中间使用向量签名；
- 第二步采用的整数倍数及该中间状态下的 `tMax`；
- 冲突宽幅；
- 桥接共现宽幅；
- 新引入宽幅；
- 路径中的花型签名；
- 固定跳数 `1`。

桥接依据必须同时满足：

```text
直接修补花型 d 在对应 ResidualComplementEvidence 的 unitDelta 正侧
源对的另一个花型属于当前基线支持
目标对与源对生产向量和车数完全相等
先应用直接修补移动得到 x1，再从 x1 应用桥接移动时存在 1 <= scale <= tMax(x1)
存在 c -> d -> h -> r -> n 的可验证宽幅-花型路径
其中 c 是见证冲突宽幅，h 是 d 与目标花型 r 的共现宽幅
r 不属于 NEUTRAL_CLOSURE，n 属于 r 但不属于 d 且不是冲突宽幅
```

由于目标对与源对逐宽幅相等，`n` 必须由基线配对花型提供；实现必须显式校验 `n` 也存在于该基线花型中。这里的“一跳”指直接修补之后再跨一条生产中性等式，不是指宽幅-花型二部图只有一条边。

二跳及以上路径不属于本设计的首轮范围。

### 5.6 规范化与查询

- 花型、宽幅、等式两侧和路径均按稳定签名排序；
- 每条依据生成确定性 `evidenceId`；
- 同一花型上的完全相同依据去重，不同依据全部保留；
- 任何入池花型的依据列表不能为空；
- 构造结果提供按花型、依据类型、宽幅、见证和方程签名查询的只读接口。

## 6. 三层局部宇宙构造规则

三层必须满足以下非严格包含关系：

```text
baselineSupport ⊆ WITNESS_DIRECT
WITNESS_DIRECT ⊆ NEUTRAL_CLOSURE
NEUTRAL_CLOSURE ⊆ TRANSFER_BRIDGE_1_HOP
```

这里使用的是 `⊆`，不是 `⊊`。某个小型数据集可能没有额外闭包或桥接花型，导致相邻两层集合相等；集合相等不能被误报为构造失败。DJX188是否形成真子集链由实际构造结果决定。

### 6.1 预计算：完整基线单位2对2方程集

复用第一层的数学边界，但构造器要保留方程依据：

1. 枚举当前基线支持的无序多重集合移出对；
2. 计算源对精确系数向量和；
3. 通过补向量哈希索引在完整宇宙中查找全部无序目标对；
4. 对哈希命中执行逐宽幅精确校验；
5. 抵消左右两侧重复花型，拒绝零移动；
6. 根据当前使用次数和真实上界计算 `tMax`；
7. 按规范化方程签名去重。

该步骤完成后得到 `BaselineUnitEquationSet`。其范围与上一轮第一层的252个规范化支持一致，但额外保存具体方程和可行倍数边界。

### 6.2 `WITNESS_DIRECT`

该层加入：

1. 全部基线支持花型；
2. 全部当前拆分见证花型；
3. 对每个残量目标，遍历 `BaselineUnitEquationSet` 的全部可行倍数；若某个方程能够按该配置块车数精确移出对应见证花型，则加入该方程涉及的全部花型，并记录 `ResidualComplementEvidence`；
4. 同一方程同时修补多个残量目标时，为每个目标分别记录依据。

该层不是“所有触及冲突宽幅的花型”，而是“具有可执行直接中性修补证据的花型”。所有触及冲突宽幅的可用花型仍作为覆盖分母，用于量化该层遗漏风险。

### 6.3 `NEUTRAL_CLOSURE`

在 `WITNESS_DIRECT` 基础上，加入 `BaselineUnitEquationSet` 中每条可行规范化方程涉及的全部花型，并记录 `NeutralEquationEvidence`。

如果构造过程未触及时间、方程数量或花型数量上限，则该层可以声明：

> 当前基线支持、固定完整宇宙和真实上下界下，一步单位2对2生产中性方程闭包已完整构造。

它不能声明任意系数2对2、3对3、多步路径或完整7717宇宙的新支持已经穷尽。

### 6.4 `TRANSFER_BRIDGE_1_HOP`

在 `NEUTRAL_CLOSURE` 基础上：

1. 取每个具有 `ResidualComplementEvidence` 的非基线直接修补花型 `d`；
2. 限定 `d` 必须位于对应直接修补 `unitDelta` 的正侧，并先应用该依据记录的倍数，得到中间状态 `x1`；
3. 将 `d` 与每个当前基线支持花型 `b` 组成无序源对；
4. 使用完整宇宙补向量索引查找所有目标对 `(p,q)`，并逐宽幅验证 `A[d] + A[b] = A[p] + A[q]`；
5. 抵消源对与目标对的重复花型，拒绝零移动，并要求该第二步在 `x1` 上至少存在一个可行整数倍数；
6. 对目标对中每个 `NEUTRAL_CLOSURE` 外花型 `r`，查找并验证路径 `c -> d -> h -> r -> n`：`c` 属于对应见证冲突宽幅，`h` 是 `d` 与 `r` 的非冲突共现宽幅，`n` 属于 `r` 和 `b`、但不属于 `d` 且不属于冲突宽幅；
7. 仅当存在上述可执行第二步和合法桥接路径时，加入目标方程涉及花型；
8. 为新增花型记录完整 `TransferBridgeEvidence`。

该规则捕捉“先直接修补，再与当前支持重组，把拆分责任转移到新位置”的最小两步结构，但不假设这些花型一定能改善组数，也不把当前见证变成硬约束。

## 7. 构造状态与范围声明

### 7.1 `UniverseScope`

```text
WITNESS_DIRECT
NEUTRAL_CLOSURE
TRANSFER_BRIDGE_1_HOP
```

### 7.2 `UniverseBuildStatus`

- `EXHAUSTED_WITHIN_RULE`：该层声明的全部源对、补向量和方程均已遍历，且没有触及预算；
- `CAPPED`：达到显式花型数、方程数或桥接路径数上限；
- `TIMED_OUT`：达到构造总时间上限；
- `ABNORMAL`：非预期异常。

调用方输入不合法时按第4.1节直接抛出异常，不返回 `UniverseBuildStatus`。构造过程中若发现内部生成的依据无法通过精确重算，则视为实现不变量被破坏并返回 `ABNORMAL`，不能降级成普通的未覆盖结果。

`EXHAUSTED_WITHIN_RULE` 必须与具体 `UniverseScope` 同时展示，禁止省略为“宇宙已穷尽”。

### 7.3 三维完成状态

每层实验结果必须同时输出：

```text
scopeBuildStatus
supportGenerationStatus
exactEvaluationStatus
```

示例：

```text
scope=NEUTRAL_CLOSURE
scopeBuild=EXHAUSTED_WITHIN_RULE
selectedPatterns=184/7717
supportGeneration=EXHAUSTED
exactEvaluation=PARTIAL(18/41)
```

该结果只能解释为“184花型的基线单位2对2闭包构造完整，且其内部支持搜索完成；仍有23个生成状态没有精确判定”。

## 8. 覆盖向量

覆盖程度不压缩成单一“信心百分数”，而使用可重算的 `CoverageProfile`。每项同时保存分子、分母和分母定义。

### 8.1 冲突宽幅覆盖

```text
selectedConflictWidths / allWitnessConflictWidths
```

统计局部层中的非基线花型是否触及每个见证变化宽幅。

### 8.2 冲突宽幅花型覆盖

```text
selectedUsablePatternsTouchingConflictWidth
/ allUsableUniversePatternsTouchingConflictWidth
```

“可用”表示真实上界至少为2，避免把必然产生使用次数1或根本不可使用的花型计入有效分母。

### 8.3 系数类别覆盖

按 `(conflictWidth, coefficientAtWidth)` 统计：

```text
selectedCoefficientClasses / allUsableCoefficientClasses
```

### 8.4 残量互补目标覆盖

```text
residualTargetsWithAtLeastOneExactEvidence / allResidualTargets
```

同时输出每个未覆盖残量目标，而不是只输出总数。

### 8.5 中性方程覆盖

分母为完整 `BaselineUnitEquationSet`：

```text
equationsFullyContainedInLayer / allBaselineUnitEquations
```

按构造定义，`NEUTRAL_CLOSURE` 和更高层在未受限时应为100%。

### 8.6 一跳责任转移桥覆盖

分母为按第6.4节源对规则完整枚举出的合法一跳桥方程：

```text
bridgeEquationsFullyContainedInLayer / allEligibleOneHopBridgeEquations
```

只有 `TRANSFER_BRIDGE_1_HOP` 在未受限时应为100%；低层报告 `NOT_APPLICABLE`，不能用0%混淆“未设计覆盖”和“尝试但未覆盖”。

### 8.7 宇宙规模比例

```text
selectedPatterns / fullUniversePatterns
```

该比例只描述计算规模，不代表结构覆盖质量。

## 9. 局部支持搜索与精确评价

### 9.1 支持搜索

每层局部宇宙均必须包含当前基线支持，并使用过滤后的真实上界调用现有 `SparseProductionNeutralMoveEnumerator`：

- 每侧支持仍限制为1到3个不同花型；
- 生产量、车数、使用层奇数1和使用次数1为0保持不变；
- 固定支持系数子问题和no-good cut语义不变；
- 见证依据只影响确定性排序，不进入可行性约束；
- 第一层已知状态继续按最终使用向量签名去重。

### 9.2 第一个工程验收目标

首个目标不是强制找到27组，而是证明局部化确实消除了“300秒0节点”的起步瓶颈。至少一个局部层必须在显式预算内满足以下任一条件：

1. 主MIP返回可行支持，并进入固定支持系数枚举；
2. 主MIP返回 `INFEASIBLE`，从而证明该局部层没有剩余支持；
3. 主MIP发生正数节点搜索，并报告有效求解状态和界。

如果三个局部层仍全部 `NOT_SOLVED` 且节点0，则本阶段工程验收失败，下一步应检查主模型松弛、对称性和no-good建模，而不是继续扩大局部宇宙。

### 9.3 快速上界与精确阈值

局部层生成候选后复用现有分层评价：

1. 校验逐宽幅生产、169车、36870废料、零超产和真实上界；
2. 校验使用层奇数1、使用次数1为0；
3. Phase2只提供可行上界和排序，不提供不可行证明；
4. 完整订单配置阈值MIP询问 `groups <= 27, oddGroups = 1, oneGroups = 0`；
5. 27阈值可行后运行完整兼容性分析，确认精确最小组数；
6. 超时无解保持 `UNKNOWN`。

不要求每层都精确评价全部候选，但必须报告实际评价数量和是否穷尽。

## 10. 确定性、预算与防污染

- 完整宇宙、基线支持、源对、目标对、花型和依据全部按稳定签名排序；
- 哈希只用于补向量索引，任何命中都必须执行精确向量校验；
- 所有枚举均采用稳定顺序；未受限构造及仅受数量上限约束的构造，在相同输入和预算下必须产生相同花型顺序、依据集合、覆盖向量和状态；
- 墙钟 `TIMED_OUT` 受机器负载影响，不承诺跨运行得到相同数量，但已返回内容必须是稳定枚举顺序的合法前缀，并真实标记 `TIMED_OUT`；
- 达到上限时按稳定顺序截断并标记 `CAPPED`，不能随机抽样；
- 所有构造与求解预算通过研究侧 `Options` 显式传入并原样输出；
- 不读取 `djx188-manual-patterns.csv`、人工使用次数或人工距离；
- 不修改 `src/main/java`；
- 不纳入工作树中已有的 `LegacyOrderPatternSelectionSolver.java` 修改和其他未跟踪实验文件。

## 11. 研究侧实现范围

计划新增：

- `StructuredLocalPatternUniverseBuilder`：构造三层局部宇宙、结构化依据和覆盖向量；
- `StructuredLocalPatternUniverseBuilderTest`：小型宇宙的方程、残量、桥接、嵌套和确定性测试；
- `Djx188StructuredLocalUniverseExperimentTest`：DJX188三层构造、局部支持MIP和精确评价入口；
- 必要的研究侧记录类型，优先作为构造器内部或同包类型，不新增生产领域对象。

计划复用且原则上不修改：

- `CompletePatternEnumerator`；
- `OrderCompatibilityKernelAnalyzer`；
- `UnitTwoForTwoMoveEnumerator`；
- `SparseProductionNeutralMoveEnumerator`；
- `ProductionNeutralMoveSearch`。

只有在实现证明现有研究接口无法提供结构化方程或三维状态时，才允许对上述测试侧类做最小包级接口扩展；不得改变既有求解语义和五个兼容性锚点结果。

## 12. 测试设计

### 12.1 小型方程宇宙

- 与暴力无序花型对枚举逐项对照，证明补向量索引不漏方程；
- 覆盖左右重复花型、抵消、零移动和真实上下界；
- 每条 `NeutralEquationEvidence` 重算 `A*delta=0` 和车数平衡；
- 同一方程的对称表达只保留一条规范化依据。

### 12.2 残量直接修补

- 构造一个拆分见证，其配置块车数能被某条中性移动精确移出；
- 断言目标花型进入 `WITNESS_DIRECT`；
- 断言不匹配配置块车数的移动不能生成 `ResidualComplementEvidence`；
- 断言同一花型的基线、见证和残量依据可以同时保留。

### 12.3 一跳责任转移

- 构造“冲突宽幅 → 直接修补花型 → 共现宽幅 → 新目标花型”的合法桥；
- 断言新花型只在 `TRANSFER_BRIDGE_1_HOP` 出现；
- 断言路径、方程和跳数可查询且可重算；
- 拒绝无新宽幅、无精确方程或需要二跳的伪桥。

### 12.4 嵌套、确定性与预算

- 断言三层满足非严格包含关系，并覆盖相邻层相等的合法场景；
- 断言每个入池花型至少有一条有效依据；
- 未受限或仅受数量上限约束时，相同输入重复构造结果完全一致；
- 显式花型、方程上限分别产生确定性的 `CAPPED`；
- 通过可注入的单调时钟或包级截止时间判定器，在不使用真实睡眠的情况下稳定验证 `TIMED_OUT` 语义和前缀合法性；
- 受限状态不得误报 `EXHAUSTED_WITHIN_RULE`。

### 12.5 覆盖向量

- 每个分子、分母由测试独立重算；
- 未覆盖残量目标清单与计数一致；
- 低层桥接覆盖状态为 `NOT_APPLICABLE`；
- 未受限 `NEUTRAL_CLOSURE` 的基线单位方程覆盖为100%；
- 宇宙规模比例不参与结构性通过/失败判定。

### 12.6 DJX188研究实验

长时间DJX188实验使用显式系统属性开关，默认 `mvn test` 只运行快速、确定性的单元回归。

实验必须输出：

- 完整宇宙7717、基线支持22、拆分见证数及冲突宽幅；
- 每层花型数、构造状态、依据类型分布、覆盖向量和构造耗时；
- 每层支持主MIP状态、节点、界、支持数、系数子问题数和完成状态；
- 每层精确评价数量、可行/不可行/未知数量和完成状态；
- 是否达到“非0节点、找到支持或证明无支持”的首个工程目标；
- 是否找到精确不超过27组的自主方案；
- 所有预算和受限原因。

DJX188首次确定性构造完成后，可以把三层花型数和覆盖计数固化为回归锚点；不得预先编造期望数字。

## 13. 验证与交付

验证顺序：

1. `mvn -q -DskipTests test-compile`；
2. 运行 `StructuredLocalPatternUniverseBuilderTest` 并报告实际测试数；
3. 运行现有生产中性移动和兼容性核快速回归；
4. 使用显式开关运行DJX188三层构造普查；
5. 在几十到几百花型层上运行局部支持MIP，记录节点和完成状态；
6. 对生成候选执行有限精确阈值评价；
7. 联合运行本轮新增及修改测试，报告执行、成功、失败、错误和跳过数量；
8. `mvn -q -DskipTests package`；
9. `git diff --check`并核对暂存区范围。

交付拆成两个独立提交：

1. `docs：设计结构化局部花型宇宙`；
2. `test：实现结构化局部花型宇宙实验`。

## 14. 验收标准

- 三层局部宇宙满足确定性的非严格嵌套关系；
- 每个入池花型都能查询至少一条结构化、可验证进入依据；
- 残量依据精确对应配置块车数，不使用模糊静态相关分；
- 中性闭包保存并验证具体生产中性等式；
- 一跳桥保存见证、桥接宽幅、新宽幅、路径和具体等式；
- 覆盖向量的分子、分母和范围定义均可独立重算；
- `universeScope`、`supportGeneration`、`exactEvaluation` 分开报告；
- 局部层穷尽不被表述成完整7717宇宙穷尽；
- 至少一个局部层摆脱300秒0节点状态，或明确判定工程验收失败；
- Phase2不被误用为不可行证明，精确超时保持 `UNKNOWN`；
- 不读取人工签名、人工使用次数或人工距离；
- 不修改生产代码，不污染现有工作树；
- 默认测试入口不运行长时间研究实验；
- 无论是否找到27组，都只按三维完成状态和实际覆盖范围陈述结论。
