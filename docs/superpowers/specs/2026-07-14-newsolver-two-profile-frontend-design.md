# NewSolver 双模式前端与参数治理设计

## 1. 目标

将“智能排版”页面收敛为唯一的 NewSolver 产品入口，移除固定幅宽、自由幅宽和新求解器三个算法页签。母卷宽度不再决定求解器类型，而是作为业务参数直接配置。

用户默认无需理解算法参数：导入订单、确认宽度和超产、选择快捷解或精确解，即可开始排版。默认模式为快捷解。

本次不改变车数、废边、超产、需求守恒和序号组评优口径，不改导入导出格式，也不宣称精确解已经证明数学全局最优。

## 2. 当前问题

当前前端和后端存在四类错位：

1. 前端仍展示 `PatternSolver`、`PatternSolverFour`、`CuttingSolver` 三个算法入口，但实际产品方向已经转向 NewSolver。
2. 2026-07-14 的兼容改动使所有 NewSolver 请求都被后端强制为质量模式，前端无法真正提供快捷解。
3. 高级参数散落在主页、`AppContext` 和右上角 `SettingsContext`，同一含义存在两套状态和默认值。
4. `newSolverSeqGroupAlpha`、`newSolverSeqGroupBeta` 和 `newSolverStage4TimeLimit` 虽能从页面传到参数对象，但当前生产求解链没有实际读取；“求解时限”也只是部分 MIP 子阶段上限，并非全流程截止。

当前真实入口链为：

`Home -> OptimizationRequest -> SolverConfigFactory -> OptimizationExecutionService -> UnifiedPatternSolver -> CuttingSolver -> InstructionConverter`

## 3. 方案比较

### 方案 A：只改前端显示

保留现有 DTO 和后端强制质量逻辑，只在前端重新显示快捷/精确按钮。实现最少，但快捷按钮仍会被后端改成质量模式，属于界面与实际行为不一致，不采用。

### 方案 B：集中式求解档位（采用）

新增明确的 `FAST` / `QUALITY` 求解档位，由 `OptimizationExecutionService` 一次性展开为请求级运行属性。前端只提交档位和少量真实参数，不直接控制 LNS、SPR、需求峰等内部开关。

优点是模式含义稳定、APS 可复用、参数不会继续散落；后续替换算法阶段时，只需要修改后端档位定义。

### 方案 C：把所有运行属性开放给前端

将 LNS、SPR、parity、节点数、线程数和各阶段预算全部做成输入项。灵活但不可维护，也会让普通用户产生大量无效或危险组合，不采用。

## 4. 页面设计

### 4.1 主页面

主页只保留四段：

1. 导入数据。
2. 业务参数：最小幅宽、最大幅宽、步长、标准总宽、超产上限。
3. 求解档位：快捷解、精确解。
4. 开始智能排版。

不再展示“固定幅宽 / 自由幅宽 / 新求解器”页签，也不再显示“实验性”提示。若要表达固定幅宽，用户将最小幅宽和最大幅宽设为相同值即可。

模式卡片显示业务可理解的说明：

- 快捷解：优先控制时间，使用已验证的快速改善链，适合日常试排和多数订单。
- 精确解：扩大候选和邻域并执行跨花型精修，适合最终方案；耗时显著更长，不承诺数学全局最优。

默认选中快捷解。模式和业务参数按用户保存在浏览器本地；旧版保存的 `fixed`、`flexible`、`newsolver` 模式统一迁移为 NewSolver + 快捷解，避免旧本地状态恢复旧算法入口。

### 4.2 右上角设置

复用现有设置按钮和右侧抽屉，分为：

- 界面显示：保留当前结果卡片显隐。
- 求解器高级设置：只展示当前算法实际读取且允许用户安全调整的参数。
- 当前模式说明：只读展示快捷解/精确解会启用的内部阶段。
- 恢复推荐值：同时恢复高级参数和模式推荐值。

高级设置保留：

| 界面名称 | 请求字段 | 实际用途 |
| --- | --- | --- |
| 允许超产宽度数量 | `newSolverTopK` | 选取允许受控超产的高需求宽度 |
| 列生成最大迭代 | `maxIterations` | `ColumnGenerationSolver` 迭代上限 |
| 最大花型池 | `newSolverMaxPatterns` | 初始生成和列生成花型池上限 |
| 单花型最大宽度种类 | `newSolverMaxDistinctWidths` | 花型生成和邻域扩展宽度种类上限 |
| MIP 单阶段安全时限 | `timeoutMs` | A 层和 Stage5 等子 MIP 的安全上限；明确标注不是全流程时限 |

以下参数从前端删除：

- `newSolverSeqGroupAlpha`、`newSolverSeqGroupBeta`：生产链未读取。
- `newSolverStage4TimeLimit`：生产链未读取。
- `newSolverUseOptimizedAssignment`：生产模式固定开启，不允许用户关闭。
- `newSolverUnderPenalty`：属于可行性底线，固定为后端推荐值。
- `lnsEnabled`、`lnsEnrichPatterns`：由档位统一管理，富花型增强继续默认关闭。
- `qualityMode`：前端由新档位替代，DTO 暂时保留兼容。

删除仅指页面状态和用户可编辑入口。现有 DTO 字段第一版保留并标记兼容，避免破坏 APS 或旧调用方。

## 5. 两种求解档位

### 5.1 快捷解 FAST

快捷解使用当前已验证的快速改善链：

`PatternGenerator -> ColumnGeneration -> 单 A 层候选 -> Stage5 -> DemandPeakFastStage -> 单套限时 LNS -> DemandPeakSmallPolisher -> 保底结果`

请求级属性至少包括：

- `cutting.quality=false`
- `cutting.lns.enabled=true`
- `cutting.lns.enrichPatterns=false`
- `cutting.demandPeak.enabled=true`
- `cutting.demandPeak.smallPolish.enabled=true`
- `cutting.spr.enabled=false`
- A 层 parity 使用 `{0}`

任何快速阶段失败或无改善都回退到最近一次通过守恒验证的结果。

### 5.2 精确解 QUALITY

精确解对应当前质量模式：

`PatternGenerator -> ColumnGeneration -> 多 A 层 parity 候选 -> 每候选 Stage5 -> 默认 LNS + 扩大 LNS -> odd repair -> SPR -> 全局评优`

请求级属性至少包括：

- `cutting.quality=true`
- `cutting.lns.enabled=true`
- `cutting.lns.enrichPatterns=false`
- `cutting.demandPeak.enabled=false`
- `cutting.spr.enabled=true`
- `cutting.spr.reverseTiePass=true`
- `cutting.spr.reverseTieMaxIterations=1`
- `cutting.spr.portfolioPass=false`
- A 层 parity 默认使用 `{0, 0.1}`

精确解的“精确”表示业务守恒和更深的质量搜索，不表示已经获得全局最优性证明。四线当前已验证基线为 `42/2/0/11`、462 车、废边 102240、超产 0，约 201 秒。

## 6. API 与兼容规则

新增明确的求解档位字段 `solverProfile=FAST|QUALITY`。前端始终提交 `useNewSolver=true`。

兼容优先级：

1. 显式 `solverProfile` 最高优先级。
2. 旧请求未传档位但传 `qualityMode=true`，映射为 `QUALITY`。
3. 旧请求仅传 `useNewSolver=true` 时，为保持 2026-07-14 已发布行为，映射为 `QUALITY`。
4. 新前端默认显式提交 `FAST`，因此打开即用仍是快捷解。

后端旧固定/自由求解器第一版保留用于旧 API 兼容，但前端不再暴露。待 APS 完成迁移并确认无旧调用后，再单独删除旧求解器和兼容字段。

## 7. 状态与参数单一来源

合并 `AppContext` 与 `SettingsContext` 中重复的算法参数。业务参数、求解档位和高级设置使用一个持久化配置对象，并包含配置版本号。

加载旧 localStorage 时执行一次迁移：

- 删除失效字段。
- 将旧算法模式迁移为 NewSolver。
- 缺失的新字段补推荐值。
- 非法范围回退推荐值。

前端推荐值与后端默认值必须在映射测试中逐项核对。日志和求解报告记录实际使用的档位及展开后的关键属性，避免页面显示与运行链不一致。

## 8. 错误处理

- 未导入订单时禁止开始求解。
- 宽度范围、步长、总宽或超产非法时，在主页就地提示，不发送请求。
- 未知档位或旧字段冲突时，后端按兼容优先级解析并记录警告。
- 快捷/精确内部阶段异常时返回已验证保底结果；没有合法结果时返回明确错误，不伪造成功。
- 页面结果区显示实际档位和实际耗时。

## 9. 验证

自动化验证必须覆盖：

- 新前端始终提交 `useNewSolver=true`。
- 默认快捷解提交 `FAST`，切换后提交 `QUALITY`。
- FAST 展开需求峰、单 LNS、small polish，并关闭 SPR。
- QUALITY 展开 parity 多候选、双 LNS 和 SPR，并关闭需求峰快速阶段。
- 旧 `qualityMode=true` 和旧 NewSolver 请求的兼容映射。
- 已删除参数不再出现在前端状态和请求中。
- 宽度、步长、总宽、超产仍完整进入 `SolverParameters`。
- localStorage 旧配置迁移和恢复推荐值。

执行验证：

- 前端生产构建。
- `OptimizationExecutionServiceTest`、`SolverConfigFactoryTest`、NewSolver 参数与回退测试。
- 快捷解四线回归，记录结果、车数、废边、超产和耗时。
- 精确解四线回归，目标不劣于当前 `42/2/0/11`，并如实记录耗时。
- 打包后检查 jar 内静态资源并启动 HTTP 冒烟验证。
- 报告实际测试数、成功数、失败数、错误数和跳过数。

## 10. 修改范围与交付

允许修改：

- `Home.tsx`、`AppContext.tsx`、`SettingsContext.tsx`、`SettingsPanel.tsx` 和直接相关类型。
- `OptimizationRequest`、求解档位定义、`SolverConfigFactory`、`OptimizationExecutionService`。
- 直接相关测试、静态资源、设计说明和发布说明。

不修改：

- 导入导出格式、业务指标定义、订单守恒口径。
- PatternGenerator、ColumnGeneration、Stage5、LNS、SPR 的数学模型实现。
- OR-Tools 版本和 Maven 构建配置。

交付包括设计提交、功能提交、真实测试报告和可外发 ZIP。
