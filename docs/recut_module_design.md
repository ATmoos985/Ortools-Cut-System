# 重新搭切模块设计

## 目标

把“重新搭切”做成一个可复用的后端模块能力，而不是只服务当前页面的临时行为。

这个模块需要同时支持两种场景：

1. APS 以后可以把“重新搭切”当成正式后端能力来调用。
2. 当前“搭切明细”页面可以复用同一套后端流程，并直接看到重新搭切后的结果。

## 当前问题

现有实现虽然能用，但仍然强绑定旧前端流程：

- 它依赖 [OptimizationContext.java](D:/Github/Solartron-Cut/src/main/java/test/demo/rest/context/OptimizationContext.java) 里的“最后一次优化结果”。
- 它从预览 `Map` 数据里反推重新搭切输入，本质上是在拿展示数据反推业务数据。
- 它会直接删除旧 instruction，再把新 instruction 追加到当前结果对象里。
- 页面目前按 `sequenceNumber` 选组，这只是展示编号，不是稳定的业务主键。
- `PendingPoolService` 虽然存在，但当前 `reCut()` 并没有把它当作唯一可信的数据来源。

这意味着当前流程很难直接、安全地暴露给 APS。

## 目标架构

把“重新搭切”拆成两层：

1. 重新搭切核心引擎
2. 页面兼容工作区

### 1. 重新搭切核心引擎

这是后续真正可迁移、可复用的后端模块。

建议包路径：

`test.demo.apsmodule.recut`

建议组件：

- `ReCutApplicationService`
- `ReCutCommand`
- `ReCutSelectionResolver`
- `ReCutDemandBuilder`
- `ReCutPlanAssembler`
- `ReCutJobService`

### 2. 页面兼容工作区

这层只服务当前页面，不让 APS 直接依赖前端编辑状态。

它负责维护页面编辑过程中的临时状态，例如：

- 已删除的行
- 待搭切池
- 撤销栈
- 当前预览 revision

建议包路径：

`test.demo.rest.workspace`

这样可以保持当前页面继续工作，同时不把 UI 状态带进 APS 正式接口。

## 核心领域对象

### PlanAggregate

表示一条优化方案主线。

建议字段：

- `planId`
- `baseJobId`
- `currentRevisionId`
- `createdAt`
- `updatedAt`

### PlanRevision

表示方案的一个不可变版本。

建议字段：

- `revisionId`
- `planId`
- `sourceOrders`
- `solverConfig`
- `summary`
- `sequenceGroups`
- `createdBy`
- `createdAt`

### SequenceGroup

表示界面上展示的一组搭切结果。

建议字段：

- `sequenceGroupId`
- `displaySequenceNumber`
- `groupKey`
- `rollWidth`
- `length`
- `surfaceTreatment`
- `usageCount`
- `subRolls`
- `rows`
- `instructionRefs`
- `isNewGroup`

### SequenceGroupRow

表示界面上一条可见行。

建议字段：

- `rowId`
- `sequenceGroupId`
- `messageText`
- `salesperson`
- `width`
- `rolls`
- `stationCount`
- `isOverproduction`
- `sourceOrderRef`

### ReleasedSlot

表示从被选中组里释放出来、准备重新搭切的槽位或行。

建议字段：

- `rowId`
- `sourceSequenceGroupId`
- `messageText`
- `salesperson`
- `width`
- `quantity`
- `length`
- `surfaceTreatment`
- `groupKey`

### ReCutCommand

表示重新搭切核心引擎的正式输入。

建议字段：

- `planId`
- `baseRevisionId`
- `selectedSequenceGroupIds`
- `selectedRowIds`
- `requestId`
- `idempotencyKey`
- `triggeredBy`

### ReCutResult

建议字段：

- `recutJobId`
- `planId`
- `baseRevisionId`
- `newRevisionId`
- `removedSequenceGroupIds`
- `addedSequenceGroupIds`
- `summary`

## 求解器调用模型

重新搭切不应该拥有第二套求解流水线。

它必须始终调用首轮优化使用的同一个优化核心：

- 构建新的 `OptimizationCommand`
- 复用 `SolverConfig`
- 调用同一个 `OptimizationCoreService.optimize(command)`

建议内部命令模型：

```text
OptimizationCommand
- orders: List<ProductionOrder>
- config: SolverConfig
```

目标调用链：

```text
ReCutApplicationService
  -> ReCutSelectionResolver
  -> ReCutDemandBuilder
  -> OptimizationCoreService.optimize(command)
  -> ReCutPlanAssembler
```

也就是说，“重新搭切”只是重新组织输入并重新装配结果，而不是再养一套新的求解器体系。

## 数据流

### A. 首次优化

```text
APS/UI 请求
  -> 请求映射
  -> OptimizationCommand
  -> OptimizationCoreService
  -> 生成 PlanAggregate + PlanRevision(REV-1)
  -> 返回预览/结果
```

### B. 重新搭切

```text
UI/APS 发送 ReCutCommand(planId, baseRevisionId, selectedSequenceGroupIds)
  -> 加载 PlanRevision(REV-1)
  -> 把选中组/行解析成 ReleasedSlots
  -> 构建重新搭切订单
  -> 克隆并调整求解配置
  -> 调用 OptimizationCoreService
  -> 组装新 revision REV-2
  -> 返回 REV-2 的预览/结果
```

### C. 撤销

撤销不应该再依赖“可变对象快照”。

目标模型：

```text
undo = 把当前 revision 指针从 REV-2 切回 REV-1
```

当前页面阶段仍然可以保留会话级撤销栈，但持久化模型应该转成 revision 驱动。

## 重新搭切输入构建规则

`ReCutDemandBuilder` 负责把现在散在服务里的临时规则正式收口。

建议规则：

1. 从选中的序号组中提取被释放的行。
2. 把这些行转换成 `ProductionOrder`。
3. 只保留同一个 `groupKey` 范围内的数据。
4. 克隆基准求解配置。
5. 按策略计算允许补入的超产宽幅。
6. 用明确策略限制 filler 的来源。

建议的 filler 策略：

- 同 `groupKey`
- 优先同业务员
- 宽幅不能已经出现在释放集合里
- filler 行按 `quantity=0` 加入
- filler 的宽幅写入 `forceAllowOverWidths`

这实际上就是对 [SequenceGroupModificationService.java](D:/Github/Solartron-Cut/src/main/java/test/demo/apsmodule/service/SequenceGroupModificationService.java#L199) 中临时规则的模块化整理。

## 接口设计

### 面向 APS 的正式接口

主接口建议：

- `POST /api/aps/v1/plans/{planId}/revisions/{revisionId}/recuts`
- `GET /api/aps/v1/recut-jobs/{recutJobId}`
- `GET /api/aps/v1/recut-jobs/{recutJobId}/result`

如果第一版想先做同步预览，可以接受：

- `POST /api/aps/v1/plans/{planId}/revisions/{revisionId}/recuts:preview`

但即使是同步版，后端内部也仍然应该创建新的 revision，而不是原地改旧结果。

### 请求 DTO 草案

```json
{
  "requestId": "REQ-20260324-001",
  "idempotencyKey": "RE-CUT-001",
  "selectedSequenceGroupIds": ["sg-101", "sg-102"],
  "selectedRowIds": [],
  "options": {
    "fillerPolicy": "same-group-same-salesperson",
    "mode": "replace-selected-groups"
  }
}
```

### 响应 DTO 草案

```json
{
  "data": {
    "recutJobId": "recut-job-001",
    "planId": "plan-001",
    "baseRevisionId": "rev-001",
    "newRevisionId": "rev-002",
    "removedSequenceGroupIds": ["sg-101", "sg-102"],
    "addedSequenceGroupIds": ["sg-201", "sg-202"],
    "summary": {
      "totalRollsUsed": 123,
      "totalWaste": 4567,
      "utilizationRate": 97.8
    }
  }
}
```

## 当前页面接入方式

当前页面应该复用新模块，而不是永远保留一套特例逻辑。

### 页面保留，但底层状态模型要换

[SequenceGroups.tsx](D:/Github/Solartron-Cut/src/main/resources/cutting-optima/pages/SequenceGroups.tsx#L21) 这个页面可以继续保留，但它的状态模型要调整：

- 当前选中主键：从 `sequenceNumber` 改成 `sequenceGroupId`
- 删除行主键：从 `seq-message-width` 改成 `rowId`
- 重新搭切基准：从“最后一次结果”改成 `planId + revisionId`

### 预览响应需要补稳定 ID

当前预览 DTO 在 [types.ts](D:/Github/Solartron-Cut/src/main/resources/cutting-optima/types.ts#L90)，第一步不一定要推翻重做，但必须扩展：

- `planId`
- `revisionId`
- `sequenceGroupId`
- `rowId`
- `isNewGroup`

这样页面还能保持接近当前的展示方式，同时开始具备 revision 意识。

### 页面调用流程

建议的页面流程：

1. `fetchPreview(planId, revisionId)`
2. 用户按 `sequenceGroupId` 选择序号组
3. 用户点击“重新搭切”
4. 页面携带 `planId`、`revisionId`、`selectedSequenceGroupIds` 调用接口
5. 后端返回 `newRevisionId` 和新的预览数据
6. 页面用新 revision 替换当前预览
7. 新产生的组可通过 `isNewGroup` 做高亮

## 页面兼容策略

为了不立即打断当前页面，建议这样过渡：

1. 保留现有 `/api/cutting/v2/preview`
2. 保留现有 `/api/cutting/v2/sequence-groups/re-cut`
3. 这两个兼容接口内部统一转调新的 re-cut 模块
4. 响应仍然返回当前 `PreviewData` 结构，同时补充新的稳定 ID

这样你就能在 APS 正式迁移之前，直接在现有页面上把重新搭切效果复现出来。

## 推荐实施顺序

1. 引入 `PlanAggregate` 和 `PlanRevision`
2. 让预览生成逻辑基于 revision 工作
3. 引入 `ReCutCommand` 和 `ReCutApplicationService`
4. 让当前 `/v2/sequence-groups/re-cut` 转调新服务
5. 给预览 DTO 补稳定 ID
6. 修改 `SequenceGroups.tsx`，切到 `sequenceGroupId` 和 `revisionId`
7. 增加 APS 正式 re-cut 接口
8. 把快照式撤销升级成 revision 式撤销

## 结论

长期正确的设计应该是：

- 一个统一优化核心
- 一个重新搭切应用服务
- 不可变的方案版本 revision
- 当前页面只是这套模块上的一个可视化工作台

这样可以同时满足：

- APS 能正式调用重新搭切
- 当前页面还能直观看到重新搭切结果
- 求解器只保留一套
- 撤销、审计和后续迁移都会更干净
