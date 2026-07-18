---
id: C-010
slug: command-system
status: done
created: 2025-07-15
owner: Owner Agent
---

# C-010 命令系统

## 用户故事

作为 CLI 用户，我需要通过 `/` 前缀命令控制 Agent 行为（退出、帮助、初始化、切换模式、恢复会话），命令系统采用 Command 模式实现统一注册和分发。

## 验收标准

- AC-1: `Command` 接口：name() + aliases() + description() + execute()
- AC-2: `CommandRegistry` 注册/查找/执行/帮助文本
- AC-3: `ExitCommand`：`/exit` (/q /quit) 退出 CLI 进程
- AC-4: `HelpCommand`：`/help` 列出所有可用命令
- AC-5: `InitCommand`：`/init` Java 收集元数据 + LLM 生成 NANOBOT.md
- AC-6: `ModeCommand`：`/mode plan/default/accept_edits/bypass` + `/plan approve`
- AC-7: `ResumeCommand`：`/resume` 列出 + `/resume <key>` 恢复
- AC-8: `/clear` 清空上下文 (inline 处理)
- AC-9: `CommandContext` record 注入所有依赖

## 边界情况

- 当命令不存在时，提示 "未知命令" + 建议 /help
- 当 `/plan approve` 但不在 plan 模式时，提示无需审批
- 当 `/resume` 无历史会话时，提示 "暂无历史会话"

## 非功能需求

| 维度 | 指标 |
|------|------|
| 模式 | Command 模式 + CommandRegistry 统一分发 |
| 扩展 | 新增命令只需实现 Command 接口并 register |
