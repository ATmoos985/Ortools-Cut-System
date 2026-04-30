# OrTools—Cut-Sysytem
> 主要解决一维切割➕集中分配的专业薄膜生产工具

APS 一维切割模块。

当前项目只保留与切割求解和 APS 接口相关的后端能力，核心入口为：

- `POST /api/aps/v1/optimize`
- `POST /api/aps/v1/jobs`
- `GET /api/aps/v1/jobs/{jobId}`
- `GET /api/aps/v1/jobs/{jobId}/result`
