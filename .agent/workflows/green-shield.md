---
description: 绿盾加密环境下的开发规范
---

# 绿盾加密环境注意事项

本项目运行在绿盾(Green Shield)数据加密环境中，需要遵循以下规则：

## 文件操作规则

### ✅ 可以使用的方法
1. **读取文件**：使用 `view_file` 工具直接读取
2. **编辑文件**：使用 `replace_file_content` 或 `multi_replace_file_content` 工具
3. **创建文件**：使用 `write_to_file` 工具
4. **搜索代码**：使用 `grep_search` 工具
5. **查看文件大纲**：使用 `view_file_outline` 工具

### ❌ 避免使用的方法
1. **PowerShell 读取/写入文件内容**：如 `Get-Content`、`Set-Content` 等命令可能导致乱码
2. **通过命令行修改文件编码**：可能破坏加密文件

### 运行命令（安全）
以下命令可以正常使用：
- `mvn spring-boot:run` - 启动 Spring Boot
- `mvn clean compile` - 编译项目
- `mvn test` - 运行测试
- 其他不涉及文件内容读写的命令

## 常见问题

### 如果遇到文件读取错误
错误信息可能包含：
- "not valid utf8"
- "unsupported mime type application/octet-stream"
- "failed to detect charset"

**解决方法**：
1. 请用户在 VS Code 中打开并保存该文件
2. 之后再尝试使用 `view_file` 工具读取

### 如果需要修改文件
直接使用代码编辑工具，不要用 PowerShell 命令行操作文件内容。
