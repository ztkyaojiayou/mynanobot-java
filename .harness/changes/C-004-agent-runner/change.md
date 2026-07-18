---
id: C-004
slug: agent-runner
status: done
created: 2025-06-08
owner: Owner Agent
---

# C-004 AgentRunner 执行循环

## 用户故事

作为 Agent 系统，我需要一个 LLM 调用循环引擎，支持"调用 LLM → 解析工具调用 → 并行执行工具 → 结果注入 → 递归"的多轮交互，以及流式输出、工具重试、降级兜底等功能。

## 验收标准

- AC-1: `AgentRunner.run()` 驱动递归调用循环，每轮调用 `runInternal()` 处理一个 LLM 往返
- AC-2: 终止条件: maxIterations/maxTurns 达上限、用户取消、连续工具失败 ≥3 次降级
- AC-3: `dropOrphanToolResults()` 清理孤立 tool 结果消息
- AC-4: `sanitizeToolCallHistory()` 清理不完整 tool_calls (DeepSeek 兼容)
- AC-5: `executeTools()` 并行执行同轮工具调用 (CompletableFuture.allOf)
- AC-6: `executeToolWithRetry()` 最多重试 3 次，结果截断至 16000 字符
- AC-7: Plan Mode 时工具列表过滤为只读 (getDefinitions(true))
- AC-8: 流式模式时 onDelta 回调实时推送内容增量

## 边界情况

- 当工具执行超时 (>30s) 时，返回超时错误并继续
- 当工具执行异常时，不中断同轮其他工具
- 当 maxCost > 0 且累计费用超标时，停止并返回费用提示

## 非功能需求

| 维度 | 指标 |
|------|------|
| 工具超时 | 30 秒 |
| 重试次数 | 3 次，间隔 1 秒 |
| 结果截断 | 16,000 字符 |
| 工具线程池 | 固定大小 = CPU 核心数 |
