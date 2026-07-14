# 预览核心指标与统一打包流程设计

## 目标

方案预览页突出生产决策更关注的得率、序号组和奇偶车指标，并将仓库现有打包能力收敛为唯一、可重复验真的一键发布流程。

本次不调整求解算法、排样结果或导出业务逻辑。

## 方案预览卡片

预览页固定展示五张卡片：

1. 总用卷数。
2. 得率，同一卡片内左右展示“含废边得率”和“有效宽度得率”。
3. 奇数车数。
4. 单次搭切。
5. 序号组数。

原“总废料”卡片由“奇数车数”替代。后端继续返回 `totalWaste`，用于导出和旧客户端兼容，不删除现有字段。

### 指标定义

- 含废边得率：`Σ(产品有效宽度 × 长度 × 车数) ÷ Σ(标准总宽 × 长度 × 车数) × 100%`。这是当前平均利用率的既有口径，标准总宽与实际选用卷宽之间的外侧废边计入损耗。
- 有效宽度得率：`Σ(产品有效宽度 × 长度 × 车数) ÷ Σ(实际选用卷宽 × 长度 × 车数) × 100%`。只衡量实际选用卷宽内部的搭切利用程度。
- 奇数车数：预览范围内 `usageCount % 2 != 0` 的序号组数量，执行一次的组也计入奇数车。
- 单次搭切：预览范围内 `usageCount == 1` 的序号组数量，是奇数车的子集。
- 序号组数：预览范围内的方案分组数量。

当接口按业务组筛选预览时，五项统计都必须基于筛选后的指令和预览分组计算。

### 异常与兼容口径

- 实际选用卷宽缺失或非正数时，有效宽度得率的分母回退到标准总宽。
- 实际选用卷宽不得小于产品有效宽度；统计时取两者较大值，避免脏数据产生超过 100% 的得率。
- 无有效分母时得率返回 `0.0`。
- 新增接口字段 `effectiveUtilizationRate` 和 `oddUsageGroups`；保留 `utilizationRate`、`totalWaste` 和 `singleUsageGroups`。

## 数据链路

统计链路保持单一数据源：

`CuttingOptimizationResult.CuttingInstruction`
→ `CuttingStatistics.summarizeLegacyInstructions(...)`
→ `CuttingExportController /v2/preview`
→ `PreviewData`
→ `Preview.tsx`。

两个得率由后端 `CuttingStatistics` 统一按长度和车数加权计算。奇数车与单次搭切由控制器在筛选后的 `previewGroups` 上统计，前端只负责显示，不重复计算业务指标。

## 统一打包入口

仓库只保留并增强现有两层入口：

- `打包发布包.bat`：Windows 用户双击入口。
- `package-release.ps1`：唯一打包实现。

不新增第二套打包脚本。

### 默认流程

1. 校验 Node、npm、Java 和 Maven Wrapper 可用。
2. 若前端 `node_modules` 不存在，则执行 `npm ci`；已存在时直接构建以节省时间。
3. 在 `src/main/resources/cutting-optima` 执行 `npm run build`。
4. 清空并同步 `cutting-optima/dist` 到 `src/main/resources/static`，防止旧哈希资源残留。
5. 默认执行 `mvnw.cmd -DskipTests clean package`；指定 `-RunTests` 时执行带测试的 `clean package`。
6. 定位最新可运行 JAR，并验证其中存在 `BOOT-INF/classes/static/index.html` 和本次前端入口引用的哈希资源。
7. 更新 `Solartron-Cut-Release/app.jar`。
8. 检查配置、启动脚本、说明文件和 Excel 模板齐全。
9. 生成 `dist/Solartron-Cut-Release-<时间戳>.zip`。
10. 再次检查 ZIP 内的关键发布文件，输出路径和文件大小。

保留现有 `-SkipBuild` 和 `-IncludeLogs` 能力，新增 `-RunTests`。任何步骤失败都立即终止，不生成看似成功的发布包。

## 修改范围

允许修改：

- `src/main/java/test/demo/apsmodule/service/CuttingStatistics.java`
- `src/main/java/test/demo/rest/CuttingExportController.java`
- `src/main/resources/cutting-optima/pages/Preview.tsx`
- `src/main/resources/cutting-optima/types.ts`
- 对应后端测试
- `package-release.ps1`
- `打包发布包.bat`（仅在需要改善参数透传或错误码时）

明确不修改：

- NewSolver 求解阶段、目标函数和候选选择逻辑。
- Excel 导出字段及内容。
- 发布目录中的业务配置、启动参数和模板内容。

## 验证与交付

1. 单元测试验证两个得率的长度/车数加权、卷宽回退和不超过 100% 的保护。
2. 单元测试验证奇数车包含单次搭切，并与偶数执行次数区分。
3. 执行前端生产构建。
4. 执行相关后端测试，报告实际执行数、成功数、失败数、错误数和跳过数。
5. 实际运行一次统一打包脚本。
6. 验证 JAR 内静态资源和 ZIP 发布结构。
7. 按仓库规范提交功能改动，提交信息使用中文全角冒号。
