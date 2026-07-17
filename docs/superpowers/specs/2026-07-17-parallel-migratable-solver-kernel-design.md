# 可迁移并行求解器内核编排设计

## 1. 目标

本次改造同时完成三件事：

1. 建立不依赖 OR-Tools 类型的稳定求解器内核契约，使未来可以整体替换当前 NewSolver。
2. 把线程、资源上限、请求级配置传播和稳定归并从具体算法中抽离为通用执行能力。
3. 在不改变业务目标顺序的前提下，并行执行当前互相独立的 A 层候选、Stage5 + LNS 基础候选和 SPR 入围候选。

本次不修改花型生成、列生成、A 层模型、Stage5、LNS、SPR 的数学含义与默认质量参数，也不宣称全局最优。

## 2. 当前问题

当前顶层 `CuttingSolverAlgorithm` 可以切换完整算法，但只返回 `List<CuttingInstruction>`，无法表达：

- 内核身份和版本；
- 成功、可行未证明、最优、超时、失败等状态；
- 车数、废边、序号组、odd、one、small 等统一指标；
- 候选来源、耗时、gap 和诊断；
- 内核支持的取消、证明、并行和 warm start 能力。

当前并行能力位于 `output` 包内，并直接服务 LNS/SPR。`SolverRuntimeProperties` 使用 `ThreadLocal`，父线程的请求级参数不会自动传播到工作线程。候选转换器还通过“已处理候选的最好组数”进行 SPR 剪枝，因此不能直接把现有循环替换为 `parallelStream()`。

## 3. 方案选择

采用“稳定语义契约 + 整核适配器 + 通用执行器”的混合方案。

不采用以下两种方案：

- 直接在线程池中调用现有全部阶段：会把 SCIP、Stage5、LNS、SPR 的结构固化到外层编排，未来整核迁移仍需重写。
- 为每个内部阶段建立公共 SPI：抽象面过大，并会迫使未来内核复制当前阶段划分。

公共层只认识完整求解请求、完整求解结果、候选质量指标和通用任务；当前 A 层/Stage5/LNS/SPR 的拆分保留在 OR-Tools NewSolver 实现内部。

## 4. 稳定契约

新增内核契约层：

- `SolverKernel`：完整内核接口，提供稳定 ID、能力判断和求解入口。
- `SolverKernelRequest`：请求拥有的订单快照、求解配置和显式执行上下文。
- `SolverKernelResult`：内核 ID、状态、指令、统一指标、耗时与诊断。
- `SolverKernelStatus`：区分成功、可行未证明、已证明最优、超时和失败。
- `SolverKernelCapabilities`：声明并行、取消、warm start、最优性证明等能力。
- `PlanQuality`：不含任何 OR-Tools 类型的业务质量向量。
- `DeterministicPlanSelector`：集中承载现有 `isBetterPlan` 的字典序。

第一版契约仍使用项目已有的 `SolverOrderItem`、`SolverConfig` 和 `CuttingInstruction` 作为项目语义 DTO；所有列表和诊断在契约边界做不可变快照。未来若内核拆成独立进程，再在该边界增加序列化 DTO，不影响当前内核与调度器。

当前 `CuttingSolver` 通过 `CurrentNewSolverKernelAdapter` 接入契约。`UnifiedPatternSolver` 只依赖 `SolverKernel` 执行 NewSolver，不再直接依赖其内部算法类。旧固定宽度和可变宽度算法暂时保留原接口，避免扩大改动面。

## 5. 通用执行器

把现有有界执行器提升到内核执行包：

- `SolverTaskExecutor`：按输入顺序返回结果的通用任务接口。
- `DirectSolverTaskExecutor`：串行基准和降级路径。
- `BoundedSolverTaskExecutor`：固定工作线程、进程级公平信号量、异常传播和有序归并。
- `SolverExecutionContext`：不可变请求级属性快照。

执行器提交任务时捕获当前 `SolverExecutionContext`，工作线程执行前显式安装，结束后恢复原上下文。系统属性仍作为进程级默认值，但不再依赖 `ThreadLocal` 的隐式继承。

每个并行任务必须新建自己的 OR-Tools solver 实例，不共享 `MPSolver`、变量、约束或可变候选转换器。

## 6. 当前 NewSolver 并行拓扑

### 6.1 A 层候选

把 `(demandOrder, seed, alignmentLambda, parityPenalty)` 和 nested-width 组合规范化为稳定有序任务列表，并行求解。每个任务新建 `MultiStageMIPSolver`，结果按任务序号归并后再按方案签名去重。

### 6.2 Stage5 + LNS 基础候选

对去重后的 A 层候选并行执行 `Stage5 + LNS + odd repair`，但暂不执行 SPR。每个候选使用独立 `InstructionConverter`。

候选级并行属于外层并行。为避免嵌套执行器死锁和过量占用，候选任务内部关闭 LNS/SPR 的子任务并行；LNS 的串行归并语义与原并行邻域归并保持一致。机器只有一个全局槽位时自动退化为串行。

### 6.3 SPR 入围候选

先在基础候选结果上串行计算最好组数，并按现有 `skipGapThreshold` 得到稳定入围名单。只对入围候选并行执行 SPR，完成后按原候选序号归并。

这替代“处理到哪个候选就用当前最好值剪枝”的顺序依赖，使串行和并行使用同一入围集合。候选最终选择仍使用统一业务字典序。

### 6.4 稳定归并

所有并行阶段均遵守：

1. 输入先建立稳定序号；
2. 任务完成顺序不参与选择；
3. 结果按输入序号归并；
4. 最终候选使用 `DeterministicPlanSelector`；
5. 完全相同时使用原候选序号破平局。

## 7. 资源和错误处理

- 默认全局 solver 槽位为 `availableProcessors - 1`，最少为 1，可通过 `cutting.parallel.globalThreads` 覆盖。
- A 层、候选基础求解和 SPR 分别提供并行开关与并行度参数。
- 单个候选异常记录为失败候选；只要仍有可用候选，整个组继续求解。
- 所有候选失败时沿用当前组失败逻辑。
- 线程中断必须恢复中断标记并终止对应任务批次。
- 报告文件只在协调线程写入，工作线程只返回不可变结果，避免并发写报告。

## 8. 迁移方式

未来内核只需实现 `SolverKernel`：

- 可以继续使用当前通用执行器，也可以在适配器内部使用自己的并行机制；
- 可以采用完全不同的单体 MIP、CP-SAT、branch-and-price 或远程服务；
- 不需要复刻 A 层、Stage5、LNS、SPR 的阶段结构；
- 必须返回统一状态、指令和 `PlanQuality`，并通过同一业务验证与选择规则。

迁移采用影子双跑：旧内核保持权威，新内核接收同一请求快照，比较需求守恒、车数、废边、序号组、odd、one、small、状态和耗时。允许并列最优方案的具体指令顺序不同，不允许业务守恒或质量门禁失真。

## 9. 验证门禁

### 9.1 单元测试

- 内核适配器保持输入、输出和异常语义；
- 执行上下文能跨工作线程传播且任务结束后不泄漏；
- 有界执行器保持输入顺序和进程级并发上限；
- 串行与并行的任务归并结果一致；
- `DeterministicPlanSelector` 完整覆盖当前业务字典序；
- SPR 入围名单与候选完成顺序无关。

### 9.2 现有回归

- `CuttingSolverTest`；
- `BoundedSolverTaskExecutorTest`；
- `OptimizationExecutionServiceTest`；
- `UnifiedPatternSolverTest`；
- 一个真实 NewSolver 数据回归。

测试报告必须给出实际执行数、成功数、失败数和跳过数。`0 tests found` 或跳过全部测试不得视为通过。

### 9.3 业务等价

同一输入至少核对：

- 需求和 `(width|message)` 分配守恒；
- 总车数；
- 总废边；
- 序号组数；
- odd、one、small；
- 最终候选名称与候选质量向量；
- 串行与并行重复运行的稳定性。

## 10. 交付和回滚

本次交付设计文档、内核契约、当前内核适配器、通用执行器、并行编排、测试和中文提交记录，不修改前端/API 契约。

并行阶段均保留系统属性开关。发现性能或稳定性异常时可逐阶段退回串行，而无需回退内核契约和适配器基底。
