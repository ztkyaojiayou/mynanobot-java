---
id: C-003
slug: agent-loop
status: done
created: 2025-06-08
owner: Owner Agent
---

# C-003 AgentLoop 核心引擎

## 用户故事

作为 Agent 系统，我需要一个状态机引擎管理消息处理的完整生命周期（恢复→压缩→命令→构建→运行→保存→响应），支持单线程串行消费保证会话安全。

## 验收标准

- AC-1: `AgentLoop` 主循环 daemon 线程消费 MessageBus 入站消息
- AC-2: 七状态状态机: RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE
- AC-3: `TurnContext` 管理会话上下文（消息列表、工具定义、最终内容、token 统计）
- AC-4: `TurnState` 枚举定义状态转换规则，next() 方法线性前进
- AC-5: 支持 `AgentHook` 链：beforeIteration → beforeExecuteTools → afterIteration → finalizeContent
- AC-6: 支持 `StreamResponseCallback` 多路注册（SSE + WebSocket 共存）
- AC-7: 后期重构为 State 模式：7 个独立 StateHandler 类 (core/state/)

## 边界情况

- 当处理异常时，捕获并发送错误响应，不影响后续消息处理
- 当会话无历史时，RESTORE 跳过加载，直接添加用户消息
- 当消息为命令时，COMMAND 状态拦截处理不进入 LLM 调用

## 非功能需求

| 维度 | 指标 |
|------|------|
| 线程模型 | 单 daemon 线程消费，保证串行 |
| 并发 | CopyOnWriteArrayList 存储回调 |
| 可扩展 | State 模式，新增状态只需实现 AgentState 接口 |
