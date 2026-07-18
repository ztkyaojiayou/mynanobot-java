# 变更清单路线图 (Change Backlog Roadmap)

> 所有卡片 status: done（代码已实现，卡片为回溯记录）。

## 状态总览

| 阶段 | 数量 |
|------|------|
| done | 18 |

## 变更索引

| ID | 模块 | 状态 |
|----|------|------|
| C-001 | 项目脚手架 (Java 17 + Maven + Spring Boot + V1/V2/V3) | done |
| C-002 | 消息总线 (MessageBus + Inbound/Outbound) | done |
| C-003 | AgentLoop 引擎 (State 模式七状态机 + TurnContext) | done |
| C-004 | AgentRunner 循环 (LLM→Tool→递归 + 流式回调) | done |
| C-005 | 工具系统 (Tool 接口 + 17 内置工具 + Schema) | done |
| C-006 | LLM 提供商 (ProviderFactory + OpenAI + DeepSeek) | done |
| C-007 | 记忆系统 (MemoryStore + Dream + Consolidator + NANOBOT.md) | done |
| C-008 | 会话管理 (SessionManager + SessionStore + 重命名 Web UI) | done |
| C-009 | 安全权限 (PermissionManager + Guard + RuleEngine + 4 Mode) | done |
| C-010 | 命令系统 (Command 模式 + exit/help/init/mode/resume) | done |
| C-011 | 钩子系统 (AgentHook + CompositeHook + Metrics/Validation/Tracing) | done |
| C-012 | 身份系统 (SOUL + IDENTITY + USER + 首位/近因效应) | done |
| C-013 | V3 CLI (JLine + Markdown + Esc 中断 + 交互确认) | done |
| C-014 | Plan Mode (/plan → /plan approve 审批执行) | done |
| C-015 | V2 Spring Boot (REST + SSE + WebSocket + sessions.html) | done |
| C-016 | MCP 集成 (StdioMCP + HttpMCP + MCPManager) | done |
| C-017 | 设计模式重构 (State + Strategy + Repository + Command) | done |
| C-018 | 文档体系 (架构14章 + README + NANOBOT.md + .harness) | done |

## 依赖关系

```
C-001 脚手架
 ├── C-002 消息总线
 ├── C-003 AgentLoop ── C-004 AgentRunner
 │       ├── C-011 钩子
 │       ├── C-012 身份
 │       ├── C-014 Plan Mode
 │       └── C-017 重构
 ├── C-005 工具 ── C-006 Provider ── C-016 MCP
 ├── C-007 记忆
 ├── C-008 会话
 ├── C-009 安全
 ├── C-010 命令
 ├── C-013 V3 CLI
 ├── C-015 V2 Web
 └── C-018 文档
```
