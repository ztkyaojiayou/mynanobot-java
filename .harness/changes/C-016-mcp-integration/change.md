---
id: C-016
slug: mcp-integration
status: done
created: 2025-06-20
owner: Owner Agent
---

# C-016 MCP 协议集成

## 用户故事

作为系统架构师，我需要支持 Model Context Protocol，让 Agent 可以动态加载第三方工具而不修改核心代码。

## 验收标准

- AC-1: `MCPClient` 接口：callTool/listTools/readResource/getPrompt/close/isConnected
- AC-2: `StdioMCPClient` 通过进程 stdin/stdout 通信（如 npx git-mcp）
- AC-3: `HttpMCPClient` 通过 HTTP/SSE 通信远程 MCP 服务
- AC-4: `MCPManager` 管理多个 MCP 服务器：初始化/发现工具/注册到 ToolRegistry
- AC-5: `MCPToolWrapper` 将 MCP 工具包装为 Nanobot Tool 接口
- AC-6: 工具命名规则：mcp_<server>_<tool>，如 mcp_git_status
- AC-7: 配置驱动：config.yaml mcp_servers 段定义服务器列表

## 边界情况

- 当 MCP 服务器启动失败时，warn 日志 + 跳过该服务器
- 当 MCP 连接断开时，isConnected() 返回 false
- 当工具超时时，callTool 返回超时错误

## 非功能需求

| 维度 | 指标 |
|------|------|
| 传输协议 | stdio + streamableHttp |
| 工具超时 | 可配置，默认 30s |
| 启用控制 | enabled_tools: ["*"] 或具体列表 |
