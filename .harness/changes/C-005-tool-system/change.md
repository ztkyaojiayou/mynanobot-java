---
id: C-005
slug: tool-system
status: done
created: 2025-06-09
owner: Owner Agent
---

# C-005 工具系统

## 用户故事

作为 Agent 系统，我需要一个可扩展的工具框架，支持工具注册、Schema 验证、参数默认值、权限检查、并行执行，以及 17 个内置工具覆盖文件操作、Shell、Web 搜索、任务追踪、子 Agent 等场景。

## 验收标准

- AC-1: `Tool` 接口：getName/getDescription/getParameters/isReadOnly/execute
- AC-2: `ToolRegistry` 注册/查找/执行/缓存工具定义 + `getDefinitions(readOnlyOnly)` 过滤
- AC-3: 参数验证：Schema 校验，空参数友好提示
- AC-4: 文件工具 (6): read_file/write_file/edit_file/list_dir/glob/grep
- AC-5: Shell 工具 (1): exec (PowerShell/pwsh 自动检测，避免 cmd /c 吃转义符)
- AC-6: Web 工具 (2): web_search (百度/Brave/Bing)/web_fetch
- AC-7: 任务工具 (3): task_create/task_list/task_update
- AC-8: 交互工具 (2): ask_user/get_current_time
- AC-9: 子 Agent 工具 (2): spawn/spawn_check
- AC-10: list_dir/glob/grep 对默认值参数不标记 required

## 边界情况

- 当工具名不存在时，返回 "Tool 'xxx' not found" 含可用工具列表
- 当参数校验失败时，对空参数给出提示 "参数为空，请检查是否忘记传参"
- 当 Plan Mode 时，getDefinitions(true) 排除所有写工具

## 非功能需求

| 维度 | 指标 |
|------|------|
| 工具注册 | 支持 @ToolDef 注解扫描 + 手动注册 |
| Schema | JSON Schema (Jackson ObjectNode) |
| 权限 | 每个工具执行前经过 PermissionManager 管道 |
