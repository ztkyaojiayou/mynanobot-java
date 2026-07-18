---
id: C-002
slug: message-bus
status: done
created: 2025-06-07
owner: Owner Agent
---

# C-002 消息总线

## 用户故事

作为 Agent 系统，我需要一个异步消息总线解耦消息生产者（通道适配器）和消费者（AgentLoop），支持入站队列和会话响应匹配，以便多个通道可以并发发送消息而不阻塞。

## 验收标准

- AC-1: `MessageBus.publishInbound()` 将消息放入有界阻塞队列（默认容量100）
- AC-2: `MessageBus.consumeInbound(timeout)` 带超时消费，超时返回 null
- AC-3: `MessageBus.publishOutbound()` 将响应写入 sessionResponses Map 用于同步匹配
- AC-4: `InboundMessage` Builder 模式，sessionId 自动生成（channel:senderId:chatId）
- AC-5: `OutboundMessage` 支持流式标记 (_stream_delta, _stream_end, _progress)
- AC-6: 生命周期方法: start()/stop()/shutdown()，running 状态由 AtomicBoolean 控制
- AC-7: 已移除 outboundQueue，SSE/WS 响应通过 StreamResponseCallback 直推

## 边界情况

- 当队列满时 publishInbound 阻塞等待（防止 OOM）
- 当 MessageBus 未运行时发布消息，打印 warn 日志并丢弃
- 当 sessionResponses 中无匹配 requestId 时，waitForSessionResponse 超时返回 null

## 非功能需求

| 维度 | 指标 |
|------|------|
| 队列容量 | 默认 100，可配置 |
| 线程安全 | ConcurrentHashMap + BlockingQueue |
| 响应匹配 | 轮询 50ms，支持自定义超时 |
