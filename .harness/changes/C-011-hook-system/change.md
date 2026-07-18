---
id: C-011
slug: hook-system
status: done
created: 2025-06-10
owner: Owner Agent
---

# C-011 钩子系统

## 用户故事

作为系统架构师，我需要一个生命周期钩子链，支持在 Agent 消息处理的不同阶段注入自定义逻辑（指标收集、链路追踪、内容验证），采用 Chain of Responsibility 模式。

## 验收标准

- AC-1: `AgentHook` 接口：beforeIteration/beforeExecuteTools/afterIteration/finalizeContent
- AC-2: `CompositeHook` 链式调用，任一 hook 可修改或拒绝内容
- AC-3: `MetricsHook` 收集 token 使用量、耗时、工具调用次数、错误率
- AC-4: `ValidationHook` 敏感词过滤 + 内容长度限制 + 自定义正则规则
- AC-5: `TracingHook` 记录完整调用链路日志
- AC-6: `HookLoader` 从配置加载 + 默认 logging/metrics 钩子

## 边界情况

- 当 hooks 为空时，finalizeContent 原样返回
- 当 hook 抛出异常时，捕获并继续下一个 hook
- 当内容长度超 maxContentLength 时，truncate: substring(0, max-3)+"..."

## 非功能需求

| 维度 | 指标 |
|------|------|
| 调用顺序 | 注册顺序依次执行 |
| 异步 | 所有方法返回 CompletableFuture |
| 配置 | config.yaml hooks.enabled |
