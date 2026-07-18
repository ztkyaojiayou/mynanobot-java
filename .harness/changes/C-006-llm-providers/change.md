---
id: C-006
slug: llm-providers
status: done
created: 2025-06-10
owner: Owner Agent
---

# C-006 LLM 提供商

## 用户故事

作为 Agent 系统，我需要支持多种 LLM 提供商（OpenAI、DeepSeek），提供统一的 `LLMProvider` 接口，并通过 `ProviderFactory` 策略工厂根据模型名自动匹配对应的 Provider 实现。

## 验收标准

- AC-1: `LLMProvider` 接口：chat/chatStream/chatWithRetry，支持流式与非流式
- AC-2: `OpenAIProvider` 实现 OpenAI API (gpt-*, o1, o3, o4)
- AC-3: `DeepSeekProvider` 实现 DeepSeek API (deepseek-chat)
- AC-4: `ProviderFactory` 策略工厂按模型前缀匹配，支持注册自定义策略
- AC-5: API Key 解析优先级：配置文件 → 环境变量 (OPENAI_API_KEY/DEEPSEEK_API_KEY)
- AC-6: DeepSeek 流式 tool_calls 的 arguments 逐字符拼接 (ToolCallAccumulator)
- AC-7: LLMResponse 三种类型：success / toolCalls / error

## 边界情况

- 当 API Key 未配置时，抛出 IllegalStateException
- 当模型名无匹配策略时，兜底 OpenAI 兼容模式
- 当 DeepSeek 流式 arguments 为逐字符 delta 时，用 StringBuilder 拼接后统一解析

## 非功能需求

| 维度 | 指标 |
|------|------|
| 超时 | HTTP 请求 300s |
| 重试 | 默认最多 3 次，仅对可重试错误 |
| 流式 | SSE 逐行解析，content delta 实时回调 |
