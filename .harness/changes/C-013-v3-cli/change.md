---
id: C-013
slug: v3-cli-mode
status: done
created: 2025-07-15
owner: Owner Agent
---

# C-013 V3 CLI 交互模式

## 用户故事

作为开发者，我想要一个类 Claude Code 的命令行交互体验，支持自然语言对话、Markdown 流式渲染、Esc 键中断回复、交互式权限确认、命令系统和会话管理。

## 验收标准

- AC-1: `NanobotCliApplication` Spring Boot 容器启动，Profile "cli"，禁用 Web 服务器
- AC-2: `CliChannel` Scanner 循环读取用户输入，空行跳过，`/` 前缀路由到 CommandRegistry
- AC-3: JLine `Terminal` 跨平台 Esc 键检测，独立于 Scanner，Esc → 中断流式回复
- AC-4: `MarkdownRenderer` 流式渲染 Markdown 内容到终端（代码块、标题、列表）
- AC-5: `setupInteractivePermission()` 注入 1/2/3 交互确认处理器
- AC-6: `setupAskUserHandler()` 注入 AskUserTool 的 CLI 交互处理器
- AC-7: `--workspace` / `-w` 指定工作目录，默认取当前目录
- AC-8: `--resume <key>` 恢复指定会话
- AC-9: 共享 Scanner：权限确认、AskUser、主循环共用一个 Scanner 实例

## 边界情况

- 当终端初始化失败时，回退到 Enter 中断
- 当 cwd 没有 pom.xml 时，自动向上查找项目根
- Scanner 空闲时 cancelMonitor 每 50ms 轮询一次 Esc

## 非功能需求

| 维度 | 指标 |
|------|------|
| 终端库 | JLine 3.25.1 |
| 跨平台 | Mac (stty raw) + Windows (CONIN$) |
| Markdown | 流式渲染，支持代码块/标题/列表 |
