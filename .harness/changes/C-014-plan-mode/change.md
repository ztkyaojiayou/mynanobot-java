---
id: C-014
slug: plan-mode
status: done
created: 2025-07-16
owner: Owner Agent
---

# C-014 Plan Mode 规划模式

## 用户故事

作为开发者，我需要一个规划模式工作流：进入 Plan Mode 后 Agent 只能读取文件和分析项目，产出结构化实现计划供我审查，我确认后再切换到执行模式开始编码。对标 Claude Code 的 `/plan` 功能。

## 验收标准

- AC-1: `/mode plan` 或 `/plan` 进入规划模式：planMode=true + PermissionMode.PLAN
- AC-2: Plan Mode 时 System Prompt 注入规划专用指令（禁止贴代码，要求结构化计划）
- AC-3: Plan Mode 时工具列表过滤为只读: `registry.getDefinitions(true)`
- AC-4: Plan Prompt 内容包括工作目录路径、探索建议、结构化输出格式
- AC-5: `/plan approve` 关闭 planMode，切换 PermissionMode.ACCEPT_EDITS
- AC-6: `/plan approve` 自动发布执行指令到 AgentLoop（使用正确的 sessionId）
- AC-7: `CommandContext` 传入 AgentLoop 和 sessionId 以支持 Plan 工作流

## 边界情况

- 当 `/plan approve` 但不在 plan 模式时，提示无需审批
- 当 planMode=true 时，AgentLoop 的 processMessage() 使用 getDefinitions(true)
- Plan Mode 提示词包含禁止事项和必须事项的明确列表

## 非功能需求

| 维度 | 指标 |
|------|------|
| 对标 | Claude Code /plan 功能 |
| 权限 | PLAN 模式自动限制工具列表 |
| 提示词 | 动态注入，含 cwd 和探索建议 |
