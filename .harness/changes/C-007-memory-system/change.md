---
id: C-007
slug: memory-system
status: done
created: 2025-06-12
owner: Owner Agent
---

# C-007 记忆系统

## 用户故事

作为 Agent 系统，我需要长期记忆和对话历史压缩能力，让 Agent 能在跨会话保持关键信息，并在上下文窗口接近上限时自动压缩旧消息。

## 验收标准

- AC-1: `MemoryStore` 文件持久化（JSONL），读写会话历史
- AC-2: `Dream` 长期记忆：从对话提取关键信息 → 存储 → 按需检索 → 整合新记忆
- AC-3: `Consolidator` 历史压缩：token 估算 → 超预算时 LLM 总结旧消息 → 替换原始消息
- AC-4: `CompactState` 在状态机中检查 token 预算，超限自动触发压缩
- AC-5: NANOBOT.md 项目记忆：每次对话 BuildState 自动加载项目根目录的 NANOBOT.md
- AC-6: `/init` 命令分析项目并生成 NANOBOT.md（Java 收集元数据 + LLM 生成内容）

## 边界情况

- 当压缩失败时，warn 日志 + 继续使用原始消息
- 当 NANOBOT.md 不存在时，静默跳过
- 当 consolidator 为 null 时，COMPACT 直接跳转到 BUILD

## 非功能需求

| 维度 | 指标 |
|------|------|
| 存储格式 | JSONL (history.jsonl) + JSON (metadata.json) |
| 压缩策略 | 基于 token 估算，90% 阈值触发 |
| 记忆上限 | 可配置 (maxMemories) |
