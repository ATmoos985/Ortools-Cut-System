# DJX188 Target-24 残量可行性研究设计

## 1. 状态与目标

日期：2026-07-27。

本设计承接 `2026-07-26-djx188-residual-bundle-pricing-design.md` 已完成的
31→25 组研究结果。当前最好自主解为：

- 序号组：25；
- 奇数组：1；
- 单车组：0；
- 总车数：169；
- 总废料：36,870mm；
- 逐订单需求精确满足。

本轮目标不是继续泛化地“尽量减少组数”，而是建立一个语义严格、结论可审计的
**Target-24 残量可行性研究链**，回答：

> 在保持逐订单需求、169车、36,870mm、奇数组1、单车组0以及
> family 全局唯一的前提下，DJX188 是否存在不超过24个真实序号组的解？

第一阶段只做测试侧 opt-in 研究，不修改 `src/main/java`，不接入生产链。

## 2. 已有证据与问题裁决

### 2.1 已经确认的事实

1. 完整花型骨架宇宙有 7,717 条，29组见证和人工26花型的骨架均可枚举；
   问题不在初始花型骨架缺失。
2. 31→29 缺列审计证明：主要损失发生在物化后的单列 reduced cost 门禁和
   全局选择预算；15条新增见证列的单列 reduced cost 非负。
3. 残量列束定价不改变花型宇宙，只把接受单位从单列提升到守恒列束：
   - 束≤4：31→28；
   - 束≤6：31→25；
   - 全程保持需求、车数、废料、奇数和单车指标精确守恒。
4. 25组解已经完成约5,400个高优先级束证否：
   - 尺寸2完整300束；
   - 尺寸3完整2,300束；
   - 尺寸4、5各前1,000束；
   - 尺寸6前794束；
   - 全部零交换、零候选截断。
5. 当前实现按两两订单支持重叠度为移除束排序。束规模继续增加时，
   组合数量与残量配置列数量同时爆炸。
6. 历史“24花型中间态”的真实兼容性最小组数为27。花型支持数不是
   序号组数代理，Target-24 必须直接建模完整订单配置组列。

### 2.2 当前瓶颈判断

25组之后的主要瓶颈是：

1. **邻域连通规模不足**：改进可能需要同时破坏超过6个当前组；
2. **破坏集合选择粗糙**：两两支持重叠度不能表达多列车数、废料、奇偶和
   family 的补偿链；
3. **残量配置列物化爆炸**：大束下完整枚举所有
   `花型 × 车数 × 逐订单配置` 列不再经济；
4. **缺少目标值裁判**：现有 pricer 以首改进循环工作，尚无独立的
   “≤24组可行/已证无解/不确定”判定接口。

本轮不把“增加运行时间”视为独立算法路线。

## 3. 研究边界

### 3.1 允许范围

- `src/test/java/test/demo/apsmodule/generator/NewSolver/` 下新增或最小修改
  研究类与测试；
- 复用已经提交的：
  - `OrderGroupResidualBundlePricer`；
  - `OrderGroupRestrictedMaster`；
  - `OrderGroupColumnPricingPrototype.Input`；
  - `GroupColumn`；
  - `OrderGroupColumnPricingEngine.convertSelected(...)`；
  - `SequenceGroupPostProcessor.computeGroupStats(...)`；
- 首次从 `target/djx188-residual-bundle-incumbent.txt` 校验来源后，
  固化一份带输入哈希、生成提交和签名哈希的25组测试基线；后续研究不得
  只依赖会被 `clean` 删除的 `target` 文件；
- 使用 SCIP/GLOP 和固定随机种子、单线程；
- 新增研究基线资源时必须记录来源、哈希和生成方式。

### 3.2 明确禁止

- 不修改 `src/main/java`；
- 不接入 API、页面或生产求解流程；
- 不读取人工26方案、automatic22见证或历史24中间态作为候选、排序信号、
  热启动或提示；
- 历史方案只允许作为独立的语义反例/回归输入；
- 不把花型数量、pattern family 数量或代理目标当作真实序号组数；
- 不把超时、节点上限、候选截断、求解器异常写成“无24组”；
- 不改动现有未提交的九个实验文件和
  `LegacyOrderPatternSelectionSolver` 工作区改动；
- 不为了跑测试修改 Maven/Surefire 配置。

## 4. 成功语义与判定状态

### 4.1 可行解语义

一个 Target-24 解必须同时满足：

```text
逐 DemandKey 覆盖 = 原始需求
总车数 = 169
总废料 = 36,870
奇数组数 = 1
单车组数 = 0
每个 family 最多选择1列
选择的 GroupColumn 数量 <= 24
```

提取解后必须再次通过：

1. 研究模型直接汇总；
2. `convertSelected(...)` 转换；
3. `computeGroupStats(...)` 展示语义复核；
4. 逐订单生产量重新聚合。

四者不一致时结果无效。

### 4.2 三态结果

所有研究入口统一返回以下状态之一：

#### `FEASIBLE`

- 找到不超过目标组数的完整解；
- 所有硬约束和转换后语义复核通过；
- 必须落盘完整列签名、指标、运行参数和输入哈希。

#### `PROVEN_INFEASIBLE`

仅当以下条件同时成立：

- 当前破坏集合下的候选列枚举被证明完整；
- 没有候选上限截断；
- 没有墙钟、节点或内存预算中止；
- SCIP 返回 `INFEASIBLE`；
- family 过滤与保留集状态包含在证明键中。

该结论只对当前固定保留集、残量、候选宇宙和目标组数有效。

#### `INCONCLUSIVE`

任何以下情况都必须返回不确定：

- 候选截断；
- 生成或求解超时；
- SCIP 仅返回 `NOT_SOLVED`、`ABNORMAL` 或未知状态；
- 求解器找到的解未通过完整语义复核；
- 输入快照、参数或落盘签名校验失败。

## 5. 方案比较

### 5.1 方案A：继续穷扫束≤6长尾

优点：

- 复用现有代码；
- 可增强25组局部最优证明。

缺点：

- 尺寸5/6长尾数量大，单束成本高；
- 即使扫完也只证明束≤6；
- 不能覆盖31↔29审计中17↔15级别的连通交换。

裁决：只允许做小规模偏移抽样和性能标定，不作为主线。

### 5.2 方案B：引导式大邻域 Target-24 残量修复

从25组 incumbent 中选择结构相关的破坏集合，固定其余列，把残量问题直接
约束为少一组或更少的精确可行性问题。

优点：

- 直接优化真实组数；
- 复用当前残量枚举和小型精确MIP；
- 避免枚举所有 `C(25,k)` 组合；
- 每个破坏集合都有明确的可行/证明无解/不确定边界；
- 是从当前束≤6实现过渡到更大连通交换的最小机制升级。

缺点：

- 仍可能受残量候选物化上限约束；
- 破坏集合生成质量决定命中率。

裁决：本轮推荐主线。

### 5.3 方案C：残量内配置列生成

在固定保留集的局部主问题内按需生成完整 GroupColumn，不预先物化所有残量列。

与已证伪的全局单列定价不同：

- 主问题是固定残量和目标组数的订单级等式模型；
- 对象是 `花型 × 车数 × 逐订单配置` 完整组列；
- 目标是恢复一个少组可行覆盖，不以全局宽度级单列RC作为最终接受门禁；
- 新列仍需由完整残量主问题联合接受。

优点：

- 可覆盖大于6、甚至17↔15级别交换；
- 不受一次性候选物化上限支配。

缺点：

- 需要新的局部 pricing/synthesis 子问题和收敛判定；
- 证明完整性与工程复杂度显著提高；
- 如果局部信号仍无法重现已知31→29交换，路线应立即终止。

裁决：方案B遇到候选爆炸或结构性截断后再进入。

## 6. 推荐架构

### 6.1 `TargetGroupFeasibilityHarness`

职责：

- 加载并校验 incumbent；
- 接收 `targetGroups`、总预算和研究开关；
- 调度破坏集合；
- 汇总三态结果；
- 发现改进后落盘；
- 生成可审计报告。

它不负责生成破坏集合或求解单个残量。

建议入口：

```java
static Result search(
        Input input,
        List<GroupColumn> incumbent,
        int targetGroups,
        Options options)
```

### 6.2 `OrderGroupDestroySetPlanner`

职责：从 incumbent 生成数量受控、去重且可复现的破坏集合。

列关系图节点为当前 GroupColumn。边和超边特征包括：

- 共享 `DemandKey` 数量及覆盖量；
- 共享宽度；
- 车数互补距离；
- 废料互补距离；
- 奇数组锚点关系；
- family 冲突关系；
- 当前自主残量列束六次成功交换留下的邻域足迹。

不允许使用人工方案或automatic22见证特征。

破坏集合阶段：

1. 尺寸7～10；
2. 尺寸12；
3. 尺寸15；
4. 尺寸17。

每个阶段同时保留：

- 高连接集合；
- 围绕历史自主成功交换扩展的集合；
- 固定种子的多样化集合；
- 少量低重叠对照集合。

输出按稳定签名排序，并带生成原因、得分和覆盖摘要。

### 6.3 `ResidualTargetSolver`

给定固定保留集和移除集：

1. 汇总移除列的逐订单残量、车数、废料、奇数和单车目标；
2. 计算残量组数上限：

```text
targetResidualGroups = targetGroups - kept.size()
```

对于从25组解破坏 `k` 列、目标24组的情况：

```text
targetResidualGroups = k - 1
```

3. 获取候选 GroupColumn；
4. 建立精确残量MIP；
5. 返回三态结果和完整证明元数据。

### 6.4 精确残量MIP

每个候选列 `c` 建立二进制变量 `x[c]`。

约束：

```text
对每个 DemandKey d：
    Σ coverage[c,d] * x[c] = residualDemand[d]

Σ cars[c] * x[c] = residualCars
Σ waste[c] * x[c] = residualWaste
Σ odd[c] * x[c] = residualOdd
Σ one[c] * x[c] = residualOne
Σ x[c] <= targetResidualGroups

对每个 family f：
    Σ x[c in f] <= 1
```

候选生成阶段已排除与保留集 family 冲突的列。

目标函数使用 `min Σx`。组数之外的车数、废料、奇数和单车均为等式，
不加入软权重。变量和约束按稳定签名顺序创建，SCIP固定种子、单线程。

### 6.5 `ResidualCandidateProvider`

第一阶段复用 `OrderGroupResidualBundlePricer.enumerateResidualColumns(...)`，
但必须把以下元数据提升为正式返回值：

- 枚举是否完整；
- 候选上限是否触发；
- 墙钟预算是否触发；
- 被保留集 family 阻塞的集合；
- 已扫描花型、车数和配置计数；
- 候选签名哈希。

完整签名去重，继续保留 resource-signature 孪生列。

第二阶段才增加按需残量列生成 provider；两种 provider 使用同一
`ResidualTargetSolver` 和结果语义。

### 6.6 `Target24ProofArchive`

持久化：

- 输入数据哈希；
- incumbent 25组签名哈希；
- 目标组数；
- 保留集和移除集签名；
- provider 类型和全部参数；
- 候选完整性/截断信息；
- SCIP状态、节点、耗时；
- 结果列签名；
- 直接与转换后指标；
- 证明可复用所需的阻塞family集合。

归档用于续跑和审计，不直接作为生产输入。

## 7. 研究阶段

### R0：语义裁判验真

在搜索24之前，建立以下回归：

1. 当前25组落盘解在上限25下 `FEASIBLE`；
2. 同一解的直接指标与转换后指标一致；
3. 自主25组签名固化为测试基线，记录输入哈希、生成提交和签名哈希，
   并验证从 `target` 首次导入与测试基线逐字节一致；
4. 历史24花型/27组中间态不能被误判为24序号组；
5. 人工26/automatic22只作为独立语义回归时，不进入候选或排序链；
6. 候选截断、超时和求解器异常必须返回 `INCONCLUSIVE`。

R0不通过时禁止进入真实24搜索。

### R1：无见证重现31→29

目的：验证新机制真的能处理已知联合改进，而不是只会读取好答案。

输入：

- 自主31组 incumbent；
- 完整7,717花型宇宙；
- 不加载automatic22见证列或签名。

成功判据：

- 找到不超过29组；
- 全资源和展示语义守恒；
- 结果不依赖见证数据；
- 重复运行结果或最优指标一致。

停止判据：

- 如果在候选完整、预算充分的受控邻域内仍不能重现31→29，
  先修正破坏集合/残量候选设计，不得直接烧25→24预算。

### R2：25→24引导式大邻域

输入固定为已校验的25组落盘解，跳过 seeder。

执行顺序：

1. 7～10列破坏集合；
2. 12列；
3. 15列；
4. 17列。

每个阶段：

- 固定破坏集合数量上限和总预算；
- 先运行候选规模探针；
- 完整枚举可控时运行精确MIP；
- 候选爆炸时返回 `INCONCLUSIVE`，原因码为
  `CANDIDATE_EXPLOSION`，不盲目提高上限；
- 找到24组后立即完整复核、落盘并停止。

R2结束后输出：

- `FEASIBLE_24`；或
- 已证明无改进的破坏集合清单；或
- 因候选爆炸/超时留下的明确缺口。

### R3：残量内配置列生成探针

只在R2出现候选物化瓶颈时进入。

第一验收目标仍是无见证重现31→29。

探针至少回答：

1. 局部残量主问题的LP/人工变量是否能稳定暴露未覆盖结构；
2. 按需生成器能否产出参与联合交换、但单列全局RC非负的列；
3. 新列加入后整数残量主问题能否从不可行变为可行；
4. `NO_COLUMN_FOUND` 是否具备证明含义，还是仅为启发式停止。

如果不能重现31→29，R3路线判为失败，不推广到25→24。

### R4：跨数据集与稳定性

至少包含：

- T42：现有10组应保持，无假改进；
- DJX188：同输入重复运行；
- 一个已有真实数据集：验证不会因为规模变化出现候选静默截断。

记录：

- 实际测试数、成功数、失败数、错误数、跳过数；
- 每阶段耗时；
- 候选规模；
- 节点数；
- 证明/不确定状态分布；
- 结果签名哈希。

## 8. 测试设计

### 8.1 单元测试

`OrderGroupDestroySetPlannerTest`

1. 相同输入输出顺序逐字节一致；
2. 不生成重复破坏集合；
3. 尺寸和数量上限生效；
4. 高连接、扩展和对照来源均可追踪；
5. 不读取见证/人工资源。

`ResidualTargetSolverTest`

1. 合成实例2组→1组可行；
2. 目标上限不足时、候选完整且SCIP不可行，返回 `PROVEN_INFEASIBLE`；
3. 候选截断时返回 `INCONCLUSIVE`；
4. family冲突正确拒绝；
5. 车数、废料、奇数、单车任一不守恒均拒绝；
6. resource-signature孪生列不会被错误去重；
7. 相同输入结果和报告稳定。

`Target24ProofArchiveTest`

1. 签名round-trip；
2. 输入或参数哈希变化使证明失效；
3. 被阻塞family离开保留集使旧证否失效；
4. 不完整运行不能写成证明记录。

### 8.2 DJX188 opt-in实验

建议入口：

```text
-Dtest=Djx188Target24ResidualFeasibilityExperimentTest
-Dcutting.test.target24=true
```

子入口：

- `semanticGate`：R0；
- `rediscover31To29`：R1；
- `guided25To24`：R2；
- `residualColumnGenerationProbe`：R3，默认关闭；
- `resume`：从证明归档或25组签名续跑。

### 8.3 回归测试

- `OrderGroupResidualBundlePricerTest`；
- `OrderGroupColumnPricingPrototypeTest`；
- `OrderGroupMissingColumnAuditTest`；
- T42对应真实回归；
- 新增单元和opt-in实验。

正常 `mvn test` 不应自动运行长时Target-24实验。

## 9. 可观测性

每个破坏集合输出一行结构化摘要：

```text
destroyId
destroySize
keptSize
targetResidualGroups
residualOrders/cars/waste/odd/one
candidateCount
candidateComplete
candidateTruncated
blockedFamilies
solverStatus
nodes
elapsedMs
resultGroups
proofState
```

汇总报告必须区分：

- 找到可行改进；
- 证明无改进；
- 候选截断；
- 超时；
- 求解器失败；
- 语义复核失败；
- 因已有证明记忆跳过。

禁止只打印不沉淀。关键指标必须进入断言、归档或设计文档结果章节。

## 10. 预算与停止条件

### 10.1 预算原则

- 先探测候选规模，再分配MIP预算；
- 每个破坏集合有独立上限；
- 每个尺寸阶段有总预算；
- 发现24组立即停止；
- 连续出现候选爆炸时进入R3评估，不继续盲目提高候选上限；
- 长尾偏移扫描只用于估计命中密度和证明覆盖，不挤占主线预算。

首轮默认预算固定为：

| 阶段 | 破坏规模 | 最多破坏集合 | 单集合候选探针 | 单集合MIP | 阶段墙钟上限 |
|---|---|---:|---:|---:|---:|
| R1 | 7～17 | 200 | 10s | 10s | 30min |
| R2-A | 7～10 | 200 | 10s | 10s | 30min |
| R2-B | 12 | 100 | 10s | 15s | 30min |
| R2-C | 15 | 50 | 15s | 20s | 30min |
| R2-D | 17 | 25 | 20s | 30s | 30min |

共同默认：

```text
maxCandidateColumns = 20,000
SCIP threads = 1
SCIP random seed = 42
找到 <=24 组后立即停止
```

阶段墙钟上限优先于“最多破坏集合”。某一破坏集合超时不会挪用下一阶段预算。
首轮结束后只能依据候选完整率、MIP状态分布和发现率调整预算；禁止仅因没有24组
就无证据翻倍。

### 10.2 路线停止条件

#### 停止方案B并修正设计

- 不能无见证重现31→29；
- 转换后组数与直接模型不一致；
- candidate completeness无法诚实判定；
- 证明归档无法随保留集/family变化正确失效。

#### 从方案B升级到方案C

- R1通过；
- R2的大邻域主要失败原因是候选物化截断；
- 破坏集合本身有明确补偿结构；
- 增加候选上限的边际覆盖迅速下降。

#### 结束本轮研究

- 找到并完整复核24组；或
- 完成预定大邻域且获得清晰的证明/不确定边界；
- 形成下一阶段是否值得开发残量列生成的证据化裁决。

## 11. 预期文件边界

可能新增：

```text
src/test/resources/research-baselines/
  djx188-target24-incumbent-v1.txt

src/test/java/test/demo/apsmodule/generator/NewSolver/
  OrderGroupDestroySetPlanner.java
  OrderGroupDestroySetPlannerTest.java
  ResidualTargetSolver.java
  ResidualTargetSolverTest.java
  Target24ProofArchive.java
  Target24ProofArchiveTest.java
  Djx188Target24ResidualFeasibilityExperimentTest.java
```

可能最小修改：

```text
src/test/java/test/demo/apsmodule/generator/NewSolver/
  OrderGroupResidualBundlePricer.java
```

修改仅用于复用候选生成结果或补齐完整性元数据，不改变现有默认行为。

明确不修改：

```text
src/main/java/**
现有九个未跟踪 Djx188*ExperimentTest.java
LegacyOrderPatternSelectionSolver.java 的工作区改动
```

实际实施前必须重新读取文件当前内容，并以最小文件数为优先；若可以通过
包内复用减少新类，不为匹配本清单而机械创建文件。

## 12. 提交与交付

建议提交拆分：

```text
docs：设计DJX188目标24组残量可行性研究
test：建立目标组数可行性裁判与语义回归
test：实现引导式大邻域Target-24实验
docs：回填Target-24实验结果与路线裁决
```

如果进入残量列生成，另开设计和提交，不与R0～R2混在同一实现提交。

功能实施和测试完成后必须报告：

- 实际执行数；
- 成功数；
- 失败数；
- 错误数；
- 跳过数；
- 未执行的长时实验及原因；
- 证明边界与不确定边界；
- 提交哈希。
