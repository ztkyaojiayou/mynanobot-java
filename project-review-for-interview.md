# Nanobot-Java 并发控制与锁机制 — 面试深度解析

> 本文档逐场景分析项目中所有锁/并发控制的使用，涵盖：**为什么需要锁、不用锁会怎样、为什么选这个锁而非其他方案**。

---

## 目录

1. [并发模型总览](#1-并发模型总览)
2. [场景1: MessageBus — 生产者-消费者 + 请求-响应匹配](#2-场景1-messagebus)
3. [场景2: SessionManager — 每会话独立锁](#3-场景2-sessionmanager)
4. [场景3: AgentLoop — 生命周期控制 + 回调安全迭代](#4-场景3-agentloop)
5. [场景4: ChannelServer — wait/notify 线程通信](#5-场景4-channelserver)
6. [场景5: TurnContext — volatile 保证跨线程可见性](#6-场景5-turncontext)
7. [场景6: MetricsHook — 指标采集的写保护](#7-场景6-metricshook)
8. [场景7: MCP 客户端 — 请求-响应配对](#8-场景7-mcp-客户端)
9. [场景8: WebSocket — 连接管理](#9-场景8-websocket)
10. [面试速查表](#10-面试速查表)

---

## 1. 并发模型总览

```
                        ┌─────────────────────────────┐
                        │        HTTP 请求线程池        │
                        │  (Spring Boot 默认 200 线程)  │
                        └─────────────┬───────────────┘
                                      │ publishInbound()
                                      ▼
                        ┌─────────────────────────────┐
                        │     ArrayBlockingQueue      │  ← 有界阻塞队列（生产者-消费者）
                        │        (容量 100)            │
                        └─────────────┬───────────────┘
                                      │ consumeInbound()
                                      ▼
                        ┌─────────────────────────────┐
                        │   AgentLoop (单 daemon 线程) │  ← 单线程消费，串行处理
                        │   + worker 线程池 (4 线程)    │  ← 异步执行 LLM 调用
                        └─────────────────────────────┘
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                  ▼
            StreamResponseCallback  sessionResponses   SSE/WS push
            (CopyOnWriteArrayList)  (ConcurrentHashMap)
```

**核心设计原则**：消息入站可并发，消息处理是串行的（AgentLoop 单线程消费）。这保证了同一会话的消息不会被并发处理。

---

## 2. 场景1: MessageBus

### 使用的并发原语

| 组件 | 类型 | 作用 |
|------|------|------|
| `inboundQueue` | `ArrayBlockingQueue<InboundMessage>(100)` | 入站消息的阻塞队列 |
| `sessionResponses` | `ConcurrentHashMap<String, Queue<OutboundMessage>>` | 请求-响应匹配 |
| `running` | `AtomicBoolean` | 总线运行状态 |

### 源码位置

`bus/MessageBus.java`

### 并发场景分析

**场景A: 多用户同时发消息（生产者-消费者）**

```
线程1 (用户A的 HTTP 请求): publishInbound(msgA) → inboundQueue.put(msgA)
线程2 (用户B的 HTTP 请求): publishInbound(msgB) → inboundQueue.put(msgB)
线程3 (AgentLoop):         consumeInbound()       → inboundQueue.take() → 取到 msgA

同一时刻: 线程1、线程2 并发写，线程3 读。
```

**为什么选 `ArrayBlockingQueue`？**

```
ArrayBlockingQueue vs LinkedBlockingQueue:

ArrayBlockingQueue:
  ✅ 有界（容量100），内存可控，队列满时自动背压
  ✅ 内部一把锁，put/take 竞争同一把锁，实现简单
  ✅ 预分配数组，无 GC 压力

LinkedBlockingQueue:
  ❌ 无界（或伪有界），生产者过快会 OOM
  ✅ put/take 用不同锁（头尾分离），吞吐更高
  → 但这里只有1个消费者，吞吐不是瓶颈

选 ArrayBlockingQueue 的理由：防止内存溢出 > 极致吞吐
```

**场景B: HTTP 同步请求-响应匹配**

```java
// ChatController（HTTP 线程）:
messageBus.publishInbound(message);                              // ① 发消息
OutboundMessage response = messageBus.waitForSessionResponse(    // ② 阻塞等待
    sessionId, requestId, 120, TimeUnit.SECONDS);

// AgentLoop（消费线程）:
messageBus.publishOutbound(response);                            // ③ 写入响应

// waitForSessionResponse 实现:
while (未超时) {
    Queue<OutboundMessage> queue = sessionResponses.get(sessionId);
    for (Iterator it = queue.iterator(); it.hasNext(); ) {
        if (requestId.equals(msg.getRequestId())) {
            it.remove();  // 取完即删 ← 为什么用 Iterator.remove()？
            return msg;   // 而不是 queue.poll()？
        }
    }
    Thread.sleep(50);  // 轮询间隔
}
```

**为什么用 `ConcurrentHashMap` + `LinkedList` + 迭代器遍历，而不是 `ConcurrentLinkedQueue`？**

```
因为需要"按 requestId 精确匹配"而非"先进先出"。

ConcurrentLinkedQueue 只有 poll()（取头部），无法在中间查找特定 requestId。
而 ConcurrentHashMap<String, Queue> 允许:
  ① 多线程安全地 put/get（CHM 的 segment 锁）
  ② 在 Queue 中遍历（Iterator 不会抛 ConcurrentModificationException）
  ③ it.remove() 原子删除匹配项（LinkedList 的迭代器 remove 是安全的）
```

**关键问题：`it.remove()` 为什么不加锁？**

`waitForSessionResponse()` 只有一个线程会调用（HTTP 请求线程），`publishOutbound()` 只有 AgentLoop 线程会调用，两个线程操作的是不同的 key（`requestId` 唯一），不存在同一元素的并发修改。`it.remove()` 只删除当前元素，不影响其他元素的迭代。

### 面试追问

**Q: 入站队列为什么不用 Disruptor？**

A: Disruptor 适合极低延迟场景（纳秒级，无锁环形缓冲区），但部署复杂（需要预分配、处理序号）。这里的瓶颈是 LLM 调用（秒级），不是消息传递（微秒级）。`ArrayBlockingQueue` 足够且简单。

**Q: 队列满了怎么办？**

A: `put()` 会阻塞调用线程，形成自然的**背压（backpressure）**。用户会感到 HTTP 请求卡住，这比静默丢消息更安全——至少用户知道系统忙。

---

## 3. 场景2: SessionManager

### 使用的并发原语

| 组件 | 类型 | 作用 |
|------|------|------|
| `sessionLocks` | `ConcurrentHashMap<String, Object>` | 每个会话独立的一把锁 |
| `synchronized(lock(sessionKey))` | 内置锁 | 保护同一会话的读写操作 |

### 源码位置

`session/SessionManager.java`

### 并发场景分析

```
用户A 连续发两条消息:
  线程1: saveHistory("session-A", msgs1)  ─┐
  线程2: saveHistory("session-A", msgs2)  ─┤ 同一会话，需要互斥
                                           ─┘ 否则 JSONL 文件可能交错写入

用户A 和 用户B 同时发消息:
  线程1: saveHistory("session-A", msgs1)  ─┐ 不同会话，不需要互斥
  线程2: saveHistory("session-B", msgs2)  ─┘ 两把不同的锁

lock("session-A") → 从 CHM 获取/创建一个锁对象 → synchronized(该对象)
lock("session-B") → 从 CHM 获取/创建另一个锁对象 → synchronized(该对象)
```

### 为什么不用全局一把锁？

```java
// ❌ 全局锁
synchronized (this) {
    saveHistory(sessionKey, messages);
}
// 问题：session-A 和 session-B 的操作互相阻塞，毫无必要。

// ✅ 每会话独立锁
synchronized (lock(sessionKey)) {
    saveHistory(sessionKey, messages);
}
// 好处：不同会话并行，同一会话串行。锁粒度最优。
```

### `lock()` 方法的双重作用：惰性创建 + 去重

```java
private Object lock(String sessionKey) {
    return sessionLocks.computeIfAbsent(sessionKey, k -> new Object());
}
```

`computeIfAbsent` 是原子的：如果 key 不存在，执行 lambda 创建新对象并放入 Map；如果已存在，返回已有对象。保证同一个 sessionKey 永远拿到同一个锁对象。

### 为什么 `new Object()` 而不直接锁 `sessionKey` 字符串？

```java
// ❌ synchronized (sessionKey) 的问题:
// sessionKey 是 String，Java 的字符串驻留（intern）会导致不同来源的相同字符串
// 可能共享同一个锁对象，造成意外的锁竞争。

// ✅ synchronized (new Object()) 的好处:
// 即使 sessionKey 相同，锁对象也是明确独立的，语义清晰。
```

### 面试追问

**Q: 用 `ConcurrentHashMap` 的 `computeIfAbsent` 创建锁对象，有并发问题吗？**

A: 没有。`computeIfAbsent` 保证原子性——即使两个线程同时调用 `lock("session-A")`，也只有一个 `new Object()` 会执行并存入 Map，另一个拿到已存在的对象。这就是 CHM 的核心能力。

**Q: 为什么不用 `ReadWriteLock`？**

A: 场景不适合。会话的读写比例接近 1:1（每次对话读一次历史 + 写一次历史），`ReadWriteLock` 的收益来自读多写少。而且 JSONL 写入是**增量追加**——需要先读到内存再写，这个过程本身读写不可分。

---

## 4. 场景3: AgentLoop

### 使用的并发原语

| 组件 | 类型 | 作用 |
|------|------|------|
| `running` | `AtomicBoolean` | 控制主循环启停 |
| `planMode` | `volatile boolean` | 跨线程的 Plan Mode 状态 |
| `streamResponseCallbacks` | `CopyOnWriteArrayList` | SSE/WS 流式回调列表 |

### 源码位置

`core/AgentLoop.java`

### 场景A: 为什么 `streamResponseCallbacks` 用 `CopyOnWriteArrayList`？

```java
// 注册：HTTP 线程
agentLoop.addStreamResponseCallback(callback);    // writer

// 遍历：LLM 流式响应线程
for (StreamResponseCallback cb : streamResponseCallbacks) {
    cb.onStreamData(sessionId, requestId, content);  // readers (多个!)
}

// 注销：SSE 超时/完成/错误线程
agentLoop.removeStreamResponseCallback(callback);  // writer
```

**为什么不能是普通 `ArrayList`？**

```
如果 readers 在遍历时，writer 同时 add/remove → ConcurrentModificationException
如果用 synchronized (list) { for... }, writer 会被阻塞直到遍历完成
→ 遍历 LLM 响应可能持续几秒到几十秒，writer 等不起
```

**`CopyOnWriteArrayList` 的写时复制机制**：

```
add("C"):
  [A, B]  ──复制──→  [A, B, C]  ──原子替换引用──→ 新数组
                            ↑
  正在遍历的迭代器仍然指向 [A, B]，不受影响

成本: 写操作 O(n)（复制整个数组）
适合: 读极多、写极少 → 这里回调注册/注销频率很低，完美匹配
```

### 场景B: `volatile boolean planMode`

```java
// 写入线程: CommandState（AgentLoop worker 线程）
planMode = true;   // 用户输入 /plan

// 读取线程: BuildState（AgentLoop worker 线程）
if (planModeSupplier.getAsBoolean()) { ... }
```

为什么用 `volatile`？不同 worker 线程处理不同轮次的请求，`volatile` 保证写入立即可见。如果不用，读取线程可能永远看到旧值（CPU 缓存）。

### 场景C: `AtomicBoolean running`

```java
// 启动
running.set(true);

// 主循环
while (running.get()) { ... }

// 停止（shutdown hook 线程调用）
running.set(false);
```

为什么不用 `volatile boolean`？`AtomicBoolean` 提供 `compareAndSet`，可以做 CAS 操作。不过当前代码只用了 `get/set`，用 `volatile` 也一样。选 `AtomicBoolean` 可能是为将来扩展留余地（例如 `running.compareAndSet(false, true)` 防止重复启动）。

---

## 5. 场景4: ChannelServer

### 使用的并发原语

| 组件 | 类型 | 作用 |
|------|------|------|
| `streamResponseHandlers` | `ConcurrentHashMap + synchronized 块` | SSE 响应处理器注册表 |
| `handler` (StreamResponseHandler) | `synchronized(handler) + wait/notify` | 线程挂起等待 LLM 响应完成 |

### 源码位置

`v1/channel/ChannelServer.java`

### 并发场景：HTTP 线程等待 LLM 响应（wait/notify 模式）

```
线程A (HTTP 请求线程):
  ① synchronized (streamResponseHandlers) { handlers.put(key, handler); }
  ② synchronized (handler) { handler.wait(300000); }  ← 挂起，等待 LLM 响应
  ③ synchronized (streamResponseHandlers) { handlers.remove(key); }

线程B (AgentLoop 的 stream callback 线程):
  ④ synchronized (streamResponseHandlers) { handler = handlers.get(key); }
  ⑤ handler.sendChunk(content);     ← 推送增量数据到 SSE 流
  ⑥ handler.completed = true;
  ⑦ synchronized (handler) { handler.notifyAll(); }  ← 唤醒线程A
```

**为什么需要两层锁？**

```
streamResponseHandlers 锁 → 保护 handlers Map 的并发读写
handler 锁             → 实现 wait/notify 线程通信

它们是不同粒度的：
- handlers Map 的注册/注销必须原子
- wait/notify 需要 handler 对象作为监视器
```

**为什么不用 `CountDownLatch`？**

`CountDownLatch` 只能用一次（计数到 0 后不能重置）。而 stream handler 可能需要被多次使用。`wait/notify` 更灵活——handler 可以被多个 callback 反复唤醒。

**`volatile boolean completed` 的作用？**

防止重复通知——`sendChunk()` 在 completed 后不再推送数据。多个 stream callback 线程可能同时调用 `sendChunk()`，`volatile` 保证状态立即可见。

---

## 6. 场景5: TurnContext

### 使用的并发原语

| 字段 | 类型 | 作用 |
|------|------|------|
| `iteration` | `AtomicInteger` | LLM 调用轮次计数器 |
| `response` | `volatile LLMResponse` | 当前轮 LLM 响应 |
| `finalContent` | `volatile String` | 最终回复内容 |
| `cancelled` | `volatile boolean` | 是否被用户取消 |
| `toolCalls` | `volatile List<ToolCallRequest>` | 当前轮工具调用列表 |

### 源码位置

`core/TurnContext.java`

### 为什么 `iteration` 用 `AtomicInteger` 而不是 `int`？

```java
// AgentRunner.runInternal() 递归调用:
runInternal(messages, tools, ctx.getIteration() + 1);  // 读
ctx.incrementIteration();                                // 写

// 如果这些操作跨线程（worker 线程池 + LLM 回调线程），
// AtomicInteger 保证自增的原子性和可见性。
```

虽然当前 AgentLoop 是单线程消费，但 `TurnContext` 作为会话上下文可能在多个 state handler 间传递，`AtomicInteger` 提供了防御性的线程安全保障。

### 为什么多个字段用 `volatile`？

`TurnContext` 在 State 模式的不同 Handler 间传递：

```java
SaveState.execute(ctx)  →  ctx.setFinalContent(result)
RespondState.execute(ctx) →  String content = ctx.getFinalContent()
```

虽然这些通常在同一线程执行，但 `volatile` 确保了如果将来改成异步执行，不会出现可见性问题。这是**防御性设计**。

---

## 7. 场景6: MetricsHook

### 使用的并发原语

| 组件 | 类型 | 作用 |
|------|------|------|
| `sessionMetrics` | `ConcurrentHashMap` | 按 session 隔离指标 |
| `SessionMetrics` 内所有方法 | `synchronized` | 指标累加的原子性 |

### 源码位置

`core/hook/impl/MetricsHook.java`

### 并发场景：多 Agent worker 线程同时更新指标

```
线程1 (处理 session-A):  metrics.addTokens(150, 500);
线程2 (处理 session-A):  metrics.incrementIterations();  ← 同一 session！
线程3 (处理 session-B):  metrics.addToolCalls(2);        ← 不同 session

synchronized void addTokens(int prompt, int completion) {
    this.promptTokens += prompt;     ← 如果不是原子操作，
    this.completionTokens += completion;  ← 多线程累加会丢数据
}
```

**为什么只用 `synchronized` 而不用 `AtomicInteger`？**

两个字段（`promptTokens` 和 `completionTokens`）需要**联合原子更新**。如果用两个 `AtomicInteger`：

```java
// ❌ 非原子：两个字段之间可能被其他线程插入
promptTokens.addAndGet(150);
// ← 线程2 在这里读了不一致的状态
completionTokens.addAndGet(500);

// ✅ synchronized 保证整个方法原子执行
synchronized void addTokens(int prompt, int completion) {
    this.promptTokens += prompt;
    this.completionTokens += completion;
}
```

**`ConcurrentHashMap` 在这里起什么作用？**

```java
sessionMetrics.get(sessionId)  // 获取该 session 的 Metrics 对象（线程安全）
  → 拿到对象后，对该对象加锁（synchronized）
  → 不同 session 拿到不同对象 → 不同锁 → 并行无阻塞
```

两层隔离：CHM 隔离不同 session → `synchronized` 保护同一 session 内的并发更新。

---

## 8. 场景7: MCP 客户端

### 使用的并发原语

| 组件 | 类型 | 作用 |
|------|------|------|
| `pendingRequests` | `ConcurrentHashMap<String, CompletableFuture<MCPResult>>` | JSON-RPC 请求-响应配对 |
| `closed` | `volatile boolean` | 客户端关闭状态 |

### 源码位置

`mcp/StdioMCPClient.java`

### 并发场景：异步请求-响应配对

```
线程1 (Agent worker):  
  Future<MCPResult> future = mcpClient.callTool("git_commit", args);
  → 构建 JSON-RPC 请求 → 写入子进程 stdin
  → 创建 CompletableFuture → 存入 pendingRequests
  → 返回 future

线程2 (stdout 读取线程):
  → 从子进程 stdout 读到 JSON-RPC 响应
  → 根据 id 从 pendingRequests 找到对应的 CompletableFuture
  → future.complete(result)  ← 唤醒线程1
```

**为什么用 `ConcurrentHashMap`？**

两个线程并发访问：Agent worker 线程 `put`，stdout 读取线程 `get + remove`。CHM 的分段锁设计保证高并发下的安全访问。

**为什么用 `CompletableFuture` 做桥接？**

这是最优雅的异步模式。`CHM` 做路由表，`CompletableFuture` 做信号量。线程1 拿到 future 后可以 `await` 或 `thenApply`，线程2 通过 `future.complete()` 传递结果。不需要手动 `wait/notify`。

---

## 9. 场景8: WebSocket

### 使用的并发原语

| 组件 | 类型 | 作用 |
|------|------|------|
| `SESSIONS` | `static ConcurrentHashMap<String, Session>` | WebSocket 连接注册表 |
| `connectionCount` | `static AtomicInteger` | 连接计数器 |

### 源码位置

`v2/websocket/NanobotWebSocketEndpoint.java`

### 为什么是 `static`？

WebSocket 端点由 Jakarta WebSocket 容器管理，每次新连接创建新的 Endpoint 实例。如果 `SESSIONS` 不是 static，每个实例都有自己的连接表，无法实现跨实例的广播/查找。

### 为什么用 `ConcurrentHashMap`？

```
线程1 (Tomcat ws 线程): @OnOpen  → SESSIONS.put(sessionId, session)
线程2 (AgentLoop 回调):  callback → SESSIONS.get(sessionId).sendText(data)
线程3 (Tomcat ws 线程): @OnClose → SESSIONS.remove(sessionId)
```

三个不同线程并发操作同一 Map，必须用线程安全的数据结构。

### `AtomicInteger` 做连接计数器

`connectionCount.incrementAndGet()` 比 `synchronized` + `int` 轻量得多。这里只需要原子自增，不需要与其他字段联合更新，`AtomicInteger` 正合适。

---

## 10. 面试速查表

| 并发原语 | 使用位置 | 场景 | 为什么选它 |
|---------|---------|------|-----------|
| `ArrayBlockingQueue(100)` | MessageBus | 生产者-消费者 | 有界防 OOM，背压机制 |
| `ConcurrentHashMap` | 全局 15+ 处 | 并发读写的 Map | 分段锁，高并发性能 |
| `CopyOnWriteArrayList` | AgentLoop.streamCallbacks | 读极多写极少 | 写时复制，读不加锁 |
| `synchronized(perSessionLock)` | SessionManager | 同一会话读写互斥 | 锁粒度最优（不同会话并行） |
| `synchronized(handler).wait()` | ChannelServer | 线程挂起等待响应 | wait/notify 可复用 |
| `synchronized(method)` | MetricsHook | 多字段联合原子更新 | 联合操作不可分 |
| `AtomicBoolean` | MessageBus, AgentLoop | 启停状态 | CAS + 可见性 |
| `AtomicInteger` | TurnContext, WebSocket | 计数器 | 轻量原子自增 |
| `volatile boolean` | 全局多处 | 状态标志 | 跨线程可见性 |
| `CompletableFuture` | MCP, Tool 系统 | 异步结果传递 | 非阻塞等待，可组合 |
| `computeIfAbsent` | SessionManager.lock() | 惰性创建锁对象 | CHM 原子操作，无重复创建 |

### 设计原则总结

1. **锁粒度最小化** — SessionManager 每会话一把锁，不同会话零竞争
2. **数据结构匹配访问模式** — 读多写少用 COW，并发读写用 CHM，生产者消费者用 BlockingQueue
3. **有界优于无界** — 队列、缓存都要设上限，防止内存泄漏
4. **简单优于复杂** — 能用一个 `volatile` 解决的，不引入锁；能用一个 `synchronized` 解决的，不引入 `ReentrantLock`
5. **单线程消费是核心简化策略** — AgentLoop 的单 daemon 线程消费模型，消除了大部分并发问题
