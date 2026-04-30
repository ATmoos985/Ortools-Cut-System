# APS 一维切割模块重构方案

## 范围

这个仓库当前聚焦于一个单一目标：

构建一个可被 APS 调用的一维切割优化后端模块。

当前保留的核心能力：

- 接收 APS 订单数据和求解参数
- 执行一维切割优化
- 返回优化方案和汇总结果
- 可选支持异步任务提交、任务状态查询和结果查询

已经移出主目标的能力：

- 本地许可证校验与许可证生成
- EXE 打包、混淆、发布脚本
- 与模块主链路无关的历史壳层逻辑

## 模块接口

当前模块主接口如下：

- `POST /api/aps/v1/optimize`
  - 同步优化
  - 请求体包含订单数据和求解配置
  - 响应直接返回汇总和方案结果

- `POST /api/aps/v1/jobs`
  - 异步优化
  - 返回 `jobId`

- `GET /api/aps/v1/jobs/{jobId}`
  - 查询异步任务状态

- `GET /api/aps/v1/jobs/{jobId}/result`
  - 查询异步任务结果

## 当前约束

- 响应中会回传 `requestId`
- 异步任务创建支持 `idempotencyKey`，用于避免重复提交导致重复执行
- 核心求解代码已收口在 `apsmodule` 目录下

## 后续重构方向

下一阶段的重点不是再扩散接口，而是继续收紧边界：

1. 把页面兼容层和 APS 正式接口继续解耦
2. 把重新搭切、撤销、预览等能力逐步 revision 化
3. 持续减少对旧 `Map` 结构和“最后一次结果”的依赖
4. 把 APS 正式能力收敛成稳定 DTO 和稳定任务模型

## 迁移建议

如果后续要把 APS 模块迁到新项目，优先迁移以下内容：

- `src/main/java/test/demo/apsmodule`
- `src/main/java/test/demo/CuttingOptimizationApplication.java`
- `src/main/resources/application.properties`
- `src/main/resources/logback-spring.xml`
- `pom.xml`

如果迁移阶段还需要保留现有前端做联调验证，再额外带上：

- `src/main/java/test/demo/rest`
- `src/main/resources/cutting-optima`

## 结论

这个仓库当前已经不再是“前端演示系统顺带跑算法”的形态，而是：

一个以 APS 一维切割为中心、前端仍可作为验证壳存在的后端模块仓库。
