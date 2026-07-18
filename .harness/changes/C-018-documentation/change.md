---
id: C-018
slug: documentation
status: done
created: 2025-07-18
owner: Owner Agent
---

# C-018 文档体系建设

## 用户故事

作为项目维护者和新加入的开发者，我需要一套完整的文档体系，包括新手友好的架构说明、AI 概念演进概述、Claude Code 实战指南、CLI 使用手册、README 快速入门，以及项目级的 AI 记忆文件。

## 验收标准

- AC-1: `ARCHITECTURE_AND_LEARNING_ROADMAP.md` 包含 14 章节，覆盖 AI 概念、架构、实战、学习路线
- AC-2: 第二章 "AI 开发演进与核心概念"：AI Native + Vibe Coding/Spec Coding/Agent-Driven + 四大工程 + 名词速查
- AC-3: 第三章 "Claude Code 实战指南"：安装/命令/工作流/CLAUDE.md/权限对标/架构映射
- AC-4: 第四章 "nanobot CLI 实战指南"：命令速查/三大工作流/权限选择/会话管理/技巧
- AC-5: 第五章 "架构说明" 21 个子章节，覆盖所有 17 个模块 + State 模式 + 全链路 + 接口
- AC-6: `README.md` 含快速开始/使用指南/部署/配置/命令速查/技术栈
- AC-7: `NANOBOT.md` 项目 AI 记忆文件，格式正确可渲染
- AC-8: `.harness/` 规范体系引入并适配项目名/包名

## 边界情况

- 所有 Markdown 标题编号唯一（65 个 H3）
- 所有代码块成对闭合（178 个标记）
- NANOBOT.md 外层无 code block 包裹

## 非功能需求

| 维度 | 指标 |
|------|------|
| 总字数 | ~30,000 字 |
| 架构图 | 10+ 个 ASCII 架构图 |
| 表格 | 50+ 张速查表 |
| 符号 | 所有编号连续无重复 |
