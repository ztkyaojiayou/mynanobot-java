# Nanobot-Java 并发控制与锁机制 — 面试深度解析

> 基于项目最新代码（2026-07-21），逐场景分析所有并发控制的使用。
> 每个场景回答三个面试问题：**为什么需要锁、不用会怎样、为什么选这个方案**。

---

## 目录

1. [并发架构总览](#1-并发架构总览)
2. [MessageBus — 生产者-消费者 + 请求-响应匹配](#2-messagebus)
3. [SessionManager — 每会话独立锁（最小粒度）](#3-sessionmanager)
4. [SessionStore — 故意不用 ConcurrentHashMap](#4-sessionstore)
5. [AgentLoop — 生命周期 + 流式回调安全迭代](#5-agentloop)
6. [AgentRunner — CompletableFuture 链式异步](#6-agentrunner)
7. [TurnContext — volatile 保证跨状态可见性](#7-turncontext)
8. [ToolRegistry — volatile 缓存 + 并行执行器](#8-toolregistry)
9. [Dream — 双 CHM：记忆存储 + 增量节流](#9-dream)
10. [PermissionManager — volatile 模式切换](#10-permissionmanager)
11. [CliChannel — volatile 中断标志 + Esc 检测](#11-clichannel)
12. [ChannelServer — synchronized + wait/notify 线程通信](#12-channelserver)
13. [MCP 客户端 — CHM + CompletableFuture 异步桥接](#13-mcp-客户端)
14. [MetricsHook — synchronized 多字段联合原子更新](#14-metricshook)
15. [WebSocket — static CHM 连接注册表](#15-websocket)
16. [全项目并发原语速查表](#16-全项目并发原语速查表)
17. [防御性设计模式总结](#17-防御性设计模式总结)

---

## 1. 并发架构总览

```
                              ┌─────────────────────────────┐
                              │    HTTP 请求线程池 (~200)     │  Spring Boot 默认
                              │    WebSocket 线程             │
                              │    CLI 主线程                 │
                              └─────────────┬───────────────┘
                                            │ publishInbound()
                                            ▼
                              ┌─────────────────────────────┐
                              │   ArrayBlockingQueue(100)   │  有界阻塞 (防 OOM)
                              │        入站消息队列          │  生产者阻塞=背压
                              └─────────────┬───────────────┘
                                            │ consumeInbound()
                                            ▼
                              ┌─────────────────────────────┐
                              │  AgentLoop 主循环            │  单 daemon 线程消费
                              │  (1 线程, 串行处理)          │  消除大部分并发问题
                              └─────────────┬───────────────┘
                                            │
                              ┌─────────────▼───────────────┐
                              │  messageExecutor (4 线程)    │  异步处理每条消息
                              │  RUN state → LLM 调用        │
                              └─────────────┬───────────────┘
                                            │
               ┌────────────────────────────┼────────────────────────────┐
               ▼                            ▼                            ▼
    StreamResponseCallback       sessionResponses Map          SSE / WS push
    CopyOnWriteArrayList         ConcurrentHashMap             (直推，不走队列)
    (SSE/WS 流式输出)             (HTTP sync 匹配)
```

**核心简化策略**：单线程消费入站队列 → 同一会话不会并发处理 → 消除了锁的最复杂场景。

---

## 2. MessageBus

**文件**: `bus/MessageBus.java`

### 2.1 使用的并发原语

| 字段 | 类型 | 作用 |
|------|------|------|
| `running` | `AtomicBoolean` | 启停状态，shutdown hook 线程写，消费者线程读 |
| `inboundQueue` | `ArrayBlockingQueue<InboundMessage>(100)` | 所有入口 → AgentLoop 的消息通道 |
| `sessionResponses` | `ConcurrentHashMap<String, Queue<OutboundMessage>>` | sync HTTP 请求-响应配对 |

### 2.2 场景A：生产者-消费者

```
生产者（多线程）：HTTP handler、WebSocket、CLI
    → publishInbound(msg)
    → inboundQueue.put(msg)      ← 队列满时阻塞（背压）

消费者（单线程）：AgentLoop daemon 线程
    → consumeInbound(timeout)
    → inboundQueue.poll(1s)     ← 超时返回 null
```

**为什么用 `ArrayBlockingQueue` 而不是 `LinkedBlockingQueue`？**

| | ArrayBlockingQueue | LinkedBlockingQueue |
|------|------|------|
| 容量 | 必须指定（这里是 100） | 可无界（默认 Integer.MAX_VALUE） |
| 内存 | 预分配数组，无 GC 压力 | 节点动态分配，有 GC |
| 锁 | 一把锁（put 和 take 共享） | 两把锁（putLock + takeLock） |
| 背压 | 队列满自动阻塞生产者 | 无界时永不阻塞 → OOM 风险 |

**选择理由**：有界优于无界 → 防 OOM。只有 1 个消费者，LinkedBlockingQueue 的双锁优势体现不出来。

**Q: 队列满了怎么办？**

`put()` 阻塞调用线程，形成自然的**背压（backpressure）**。HTTP 请求会卡住，这比静默丢消息更安全——至少用户知道系统忙。

### 2.3 场景B：sync HTTP 请求-响应匹配

```
ChatController (HTTP 线程):
  ① messageBus.publishInbound(message)
  ② OutboundMessage resp = messageBus.waitForSessionResponse(
         sessionId, requestId, 120, SECONDS)   ← 轮询阻塞等待
  ③ return ResponseEntity.ok(resp)

AgentLoop (消费线程):
  ④ messageBus.publishOutbound(response)
     → sessionResponses.computeIfAbsent(sessionId, k -> new LinkedList<>())
                         .offer(response)       ← 写入 CHM 中的 Queue

waitForSessionResponse 内部:
  while (未超时) {
      Queue<OutboundMessage> queue = sessionResponses.get(sessionId);
      for (Iterator it = queue.iterator(); it.hasNext(); ) {
          if (requestId.equals(msg.getRequestId())) {
              it.remove();   // 取完即删
              return msg;
          }
      }
      Thread.sleep(50);      // 50ms 轮询间隔
  }
```

**为什么不用 `BlockingQueue` 做响应匹配？**

响应需要按 `requestId` 精确匹配，而非 FIFO。同一个 session 可能先后发出多个请求，响应的到达顺序不一定等于发送顺序（虽然 AgentLoop 串行处理，但防御性设计）。

**`computeIfAbsent` 的线程安全性？**

`ConcurrentHashMap.computeIfAbsent` 是原子的：如果 key 不存在，执行 lambda 创建 LinkedList 并放入；如果已存在，返回已有对象。两个线程同时为同一 sessionId 写响应时，不会创建两个 Queue。

**Q: `waitForSessionResponse` 的轮询有什么问题？**

用 `Thread.sleep(50)` 轮询，理论上可以用 `CountDownLatch` 或 `CompletableFuture` 替代以避免忙等。但当前设计简单直观，且 50ms 的等待对用户体验无影响（LLM 响应本身需要秒级）。

---

## 3. SessionManager

**文件**: `session/SessionManager.java`

### 3.1 使用的并发原语

| 字段 | 类型 | 作用 |
|------|------|------|
| `sessionLocks` | `ConcurrentHashMap<String, Object>` | 每会话独立的锁对象池 |
| `saveHistory()` / `loadHistory()` | `synchronized(lock(sessionKey))` | 保护同一会话的文件读写 |

### 3.2 为什么需要锁？

```
场景：用户快速连续发两条消息（或双设备同一 session）:

时间线:
  T1: 线程A → saveHistory("s-A", [msg1, msg2, msg3])
  T2: 线程B → saveHistory("s-A", [msg1, msg2, msg3, msg4])  ← 同一 session!

无锁情况:
  线程A 读取 history.jsonl(3条) → 准备写回
  线程B 读取 history.jsonl(4条) → 准备写回
  线程A 写入 → 3条 (msg4 丢失!)  ← 线程B 的更新被覆盖
  线程B 写入 → 4条 (msg3 的更新状态丢失)

有锁情况:
  线程A 获取锁 → 读(3条) → 写(3条) → 释放锁
  线程B 获取锁 → 读(3条,含A的更新) → 追加 → 写(4条) → 释放锁
```

### 3.3 为什么每会话独立锁，而不是全局一把锁？

```java
// ❌ 全局锁 — 不同会话互相阻塞
synchronized (this) {
    saveHistory(sessionKey, messages);
}
// session-A 和 session-B 的操作毫无关系，却要互相等待

// ✅ 每会话独立锁 — 锁粒度最优
synchronized (lock(sessionKey)) {
    saveHistory(sessionKey, messages);
}
// session-A 和 session-B 各自用不同的锁，完全并行
```

### 3.4 `lock()` 方法设计

```java
private Object lock(String sessionKey) {
    return sessionLocks.computeIfAbsent(sessionKey, k -> new Object());
}
```

**为什么返回 `new Object()` 而不是直接锁 `sessionKey` 字符串？**

String 的 `intern()` 驻留机制意味着不同来源的相同字符串可能指向 JVM 内的同一个 String 对象——如果直接 `synchronized(sessionKey)`，可能和完全不相关的代码共享一把锁。

**`computeIfAbsent` 的原子性**：即使两个线程同时调用 `lock("same-key")`，CHM 保证只有一个 `new Object()` 被执行，另一个拿到同一个对象。这是 CHM 的核心能力。

---

## 4. SessionStore

**文件**: `session/SessionStore.java`

### 4.1 一个"故意不用锁"的例子

```java
// SessionStore 中的 dirCache 使用了普通 HashMap，而不是 ConcurrentHashMap
private final Map<String, Path> dirCache = new HashMap<>();

Path getSessionDir(String sessionKey) {
    return dirCache.computeIfAbsent(sessionKey, key -> {
        String safeKey = key.replaceAll("[^a-zA-Z0-9_-]", "_");
        Path dir = baseDir.resolve(safeKey);
        Files.createDirectories(dir);
        return dir;
    });
}
```

**为什么这里敢用 `HashMap` 而不是 `ConcurrentHashMap`？**

SessionStore 的所有 public 方法（`saveHistory`, `loadHistory`, `deleteSession`）都**只在 `SessionManager` 的 `synchronized(lock(sessionKey))` 块内被调用**。

```
SessionManager.saveHistory(sessionKey, msgs)
  → synchronized (lock(sessionKey)) {     ← 锁在这里！
        store.saveHistory(sessionKey, msgs);  ← 单线程访问，无需额外同步
    }
```

**设计原则**：锁放在业务层（SessionManager），存储层（SessionStore）只管 I/O。这比在 SessionStore 里到处加锁更清晰——**调用方负责并发控制，被调用方保持简单**。

### 4.2 面试价值

这是一个很好的例子说明：**不是所有共享数据都需要自己加锁。如果调用方已经保证了单线程访问，被调用方不需要重复保护。** 过度同步会降低性能且让代码更难理解。

---

## 5. AgentLoop

**文件**: `core/AgentLoop.java`

### 5.1 使用的并发原语

| 字段 | 类型 | 作用 |
|------|------|------|
| `running` | `AtomicBoolean` | 主循环启停 |
| `planMode` | `volatile boolean` | Plan Mode 状态，worker 线程写，不同 worker 线程读 |
| `streamResponseCallbacks` | `CopyOnWriteArrayList<StreamResponseCallback>` | 流式回调列表 |
| `messageExecutor` | `ExecutorService` (4 fixed threads) | 异步处理每条消息 |

### 5.2 CopyOnWriteArrayList 的写时复制

```java
// 注册 (HTTP/WS 线程)
addStreamResponseCallback(callback)  →  list.add(callback)   ← writer

// 遍历 (LLM 流式响应线程)
for (StreamResponseCallback cb : streamResponseCallbacks) {
    cb.onStreamData(sessionId, requestId, content);          ← readers
}

// 注销 (SSE 超时/完成/错误线程)
removeStreamResponseCallback(callback) → list.remove(cb)     ← writer
```

**为什么 CopyOnWriteArrayList？**

```
                   读操作               写操作              适合场景
ArrayList:         无锁(ConcurrentModification风险) 无锁       × 并发不行
同步ArrayList:    读阻塞写、写阻塞读                     × 读多时性能差
COWArrayList:     无锁(快照迭代)       Arrays.copyOf(全量复制) ✅ 读极多+写极少
```

LLM 流式响应时，每收到一个 token 就要遍历一次回调列表。一轮对话可能有几百上千个 token → 几百上千次读。而回调的注册/注销只在连接建立/断开时发生 → 极少写。这正是 COW 的最佳场景。

**写时复制的代价**：每次 add/remove 都会复制整个数组（O(n)），所以只适合**写极少**的场景。如果写频繁，应该用 `ReadWriteLock`。

### 5.3 `volatile boolean planMode`

```java
// 写入: CommandState (worker 线程池中的线程A)
planMode = true;   // 用户输入 /plan

// 读取: BuildState (worker 线程池中的线程B)
if (planModeSupplier.getAsBoolean()) { ... }
```

不同 worker 线程处理不同轮次的消息。`volatile` 保证线程A 的写入对线程B 立即可见。如果不用 `volatile`，线程B 可能永远看到 CPU 缓存中的旧值 `false`。

### 5.4 `AtomicBoolean running` vs `volatile boolean`

当前代码只用 `get()/set()`，没有用到 CAS。从功能讲，`volatile boolean` 够了。选 `AtomicBoolean` 的原因可能是：
1. 语义更明确——"这是一个原子操作的状态标志"
2. 为将来扩展留余地——比如 `running.compareAndSet(false, true)` 防止重复启动

---

## 6. AgentRunner

**文件**: `core/AgentRunner.java`

### 6.1 CompletableFuture 链式异步

AgentRunner 的核心并发模式是 `CompletableFuture.thenCompose()`：

```java
public CompletableFuture<String> run(TurnContext context,
        List<Map<String, Object>> messages, Consumer<String> onDelta) {
    return runInternal(context, messages, onDelta, 0, 0);
}

private CompletableFuture<String> runInternal(...) {
    // ... 各种终止条件检查 ...

    CompletableFuture<LLMResponse> llmFuture;
    if (useStreaming) {
        llmFuture = provider.chatStream(messages, tools, onDelta);
    } else {
        llmFuture = provider.chat(messages, tools);
    }

    return llmFuture.thenCompose(response -> {
        if (response.shouldExecuteTools()) {
            // 并行执行工具 → 结果注入 messages → 递归
            return executeTools(...).thenCompose(results -> {
                messages.addAll(results);
                return runInternal(context, messages, onDelta,
                    iteration + 1, newConsecutiveFailures);
            });
        }
        // LLM 返回纯文本 → 循环结束
        return CompletableFuture.completedFuture(response.getContent());
    });
}
```

**为什么用 CompletableFuture 而不是同步阻塞？**

```
同步方式:
  LLMResponse resp = provider.chat(messages, tools);  // 阻塞 5-30 秒
  if (resp.hasToolCalls()) {
      results = executeTools(toolCalls);               // 阻塞 1-5 秒
      return runInternal(...);                         // 再阻塞...
  }

CompletableFuture:
  provider.chatStream(messages, tools, onDelta)  ← 非阻塞返回 Future
      .thenCompose(response → ...)               ← 响应到达后自动执行下一阶段
      .thenCompose(results → runInternal(...))    ← 链式递归

优势:
  ① 不阻塞调用线程（AgentLoop worker 可以处理其他消息）
  ② 流式推送不受影响（onDelta 回调在 HTTP 响应到达时实时触发）
  ③ thenCompose 天然表达"异步递归"语义
```

### 6.2 并发面试要点

**Q: `onDelta` 回调和 `thenCompose` 回调分别在哪个线程执行？**

- `onDelta`：HTTP 响应读取线程（OkHttp/HttpClient 的 IO 线程）
- `thenCompose`：`CompletableFuture` 的默认线程池（ForkJoinPool.commonPool()）

这意味着 `onDelta` 中的操作要尽量轻量（只是推送 SSE/WS 数据），不要在 IO 线程中做重计算。

---

## 7. TurnContext

**文件**: `core/TurnContext.java`

### 7.1 volatile 字段一览

| 字段 | 访问线程 | 为什么 volatile |
|------|---------|----------------|
| `response` | RestoreState 写, RunState 读 | 不同 StateHandler 可能在不同 worker 线程执行 |
| `finalContent` | SaveState 写, RespondState 读 | 同上 |
| `cancelled` | Esc 中断线程写, RunState 读 | 跨线程中断信号 |
| `error` | AgentRunner 写, RespondState 读 | 错误传播 |
| `iteration` | AtomicInteger | 需要原子自增 |

### 7.2 `AtomicInteger iteration` vs `int`

```java
// AgentRunner 递归中:
ctx.incrementIteration();                           // ++ 操作
runInternal(ctx, ..., ctx.getIteration() + 1);      // 读

// AtomicInteger.incrementAndGet() 保证：
// 1. 自增原子性（不会被其他线程打断）
// 2. 可见性（其他线程立即看到新值）
```

### 7.3 防御性拷贝

```java
// TurnContext 中的 getter 都返回防御性拷贝
public List<Map<String, Object>> getMessages() {
    return new ArrayList<>(messages);     // 外部修改不影响内部状态
}
public List<ToolCallRequest> getToolCalls() {
    return new ArrayList<>(toolCalls);
}
```

**为什么getter要拷贝而不是直接返回引用？**

外部代码（Hook、StateHandler）拿到 List 后可能修改它。如果不拷贝，外部代码的 `list.add()` 会直接污染 TurnContext 内部状态，而且这个修改发生在哪、什么时候发生，完全不可追踪。防御性拷贝把 TurnContext 变成一个"值对象"——你只能通过 `setXxx()` 方法修改它，不能通过返回的引用偷偷改。

### 7.4 设计洞察

TurnContext 的 volatile 字段本质是**防御性设计**——虽然当前 State 模式各状态通常在同一个 worker 线程中顺序执行，但 volatile 确保如果将来某个 StateHandler 被异步化（如 SaveState 的 Dream 提取是异步的），不会出现可见性问题。

---

## 8. ToolRegistry

**文件**: `tools/ToolRegistry.java`

### 8.1 并发原语

| 组件 | 类型 | 作用 |
|------|------|------|
| `tools` | `ConcurrentHashMap<String, Tool>` | 工具注册表 |
| `cachedDefinitions` | `volatile List<JsonNode>` | 工具定义缓存 |
| `executor` | `ExecutorService` | 工具并行执行线程池 |

### 8.2 `volatile` 缓存模式

```java
private volatile List<JsonNode> cachedDefinitions = null;

public List<JsonNode> getDefinitions(boolean readOnly) {
    if (cachedDefinitions != null) {
        return filterByReadOnly(cachedDefinitions, readOnly);
    }
    // 缓存失效 → 重新序列化所有工具定义
    List<JsonNode> defs = tools.values().stream()
        .map(this::toDefinition).toList();
    cachedDefinitions = defs;          // volatile 写
    return filterByReadOnly(defs, readOnly);
}
```

**为什么用 volatile 而不是锁？**

工具列表变更极少（只在启动时注册），读取极其频繁（每轮 LLM 调用都要获取）。`volatile` 保证：
- 写操作后，所有读线程立即看到新缓存
- 读操作不加锁，性能最高

**潜在问题**：如果有两个线程同时发现 `cachedDefinitions == null`，会重复计算缓存。但这只是浪费一点 CPU，不影响正确性（两次计算结果相同）。

### 8.3 工具并行执行器

```java
private static final int MAX_CONCURRENT_TOOLS = 10;
private final ExecutorService executor;
```

LLM 可能一次返回多个 `tool_calls`（如同时调用 `read_file` + `web_search`），这些工具互不依赖，应该并行执行：

```java
List<CompletableFuture<Object>> futures = toolCalls.stream()
    .map(tc -> CompletableFuture.supplyAsync(
        () -> executeTool(tc), executor))
    .toList();
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

---

## 9. Dream

**文件**: `memory/Dream.java`

### 9.1 双 CHM 模式

```java
private final Map<String, MemoryEntry> memories = new ConcurrentHashMap<>();
private final Map<String, Integer> lastExtractionCharCount = new ConcurrentHashMap<>();
```

| Map | 写入线程 | 读取线程 | 并发场景 |
|-----|---------|---------|---------|
| `memories` | SaveState (worker) + /remember (worker) | BuildState (worker) | 多 session 同时读写 |
| `lastExtractionCharCount` | SaveState (worker) | SaveState (worker) | 同一 session 的多次 extractAndStore |

### 9.2 为什么需要两个 CHM？

```
memories:         跨会话共享的长期记忆库
                  线程A: extractAndStore("session-A", ...)  →  put 新记忆
                  线程B: retrieve(query, 5)                  →  get 相关记忆
                  冲突: 同时 put/get 同一 key → CHM 的分段锁解决

lastExtractionCharCount:  每个 session 的提取进度
                  线程A: extractAndStore("session-A", msgs1)  →  put("s-A", 5000)
                  线程A: extractAndStore("session-A", msgs2)  →  get("s-A") + put("s-A", 10000)
                  冲突: 同一 session 多次对话的字符计数累加 → CHM 原子操作
```

### 9.3 增量节流的并发考虑

```java
int currentChars = countTotalChars(messages);
int lastChars = lastExtractionCharCount.getOrDefault(sessionId, 0);
int newChars = currentChars - lastChars;

if (newChars < EXTRACTION_MIN_NEW_CHARS && lastChars > 0) {
    return CompletableFuture.completedFuture(emptyList);  // 跳过提取
}

lastExtractionCharCount.put(sessionId, currentChars);  // 更新进度
```

**问题**：`getOrDefault` + `put` 之间不是原子的。如果两个请求同时到达，可能都算出 `newChars = 100`（都小于阈值 5000），都跳过提取。但这是**可接受的不精确**——多跳过一次提取比重复提取的代价小得多。

---

## 10. PermissionManager

**文件**: `security/PermissionManager.java`

### 10.1 volatile 字段

```java
private volatile PermissionMode mode;
private volatile InteractivePermissionHandler interactiveHandler;
```

| 字段 | 写入场景 | 读取场景 |
|------|---------|---------|
| `mode` | CommandState 处理 `/mode plan` 等命令 | 每次工具调用前的 `check()` |
| `interactiveHandler` | CLI/WS 通道初始化时注册 | 需要用户确认的工具调用 |

### 10.2 为什么 mode 必须是 volatile？

```
用户: /mode bypass
  → CommandState 执行: permissionManager.setMode(BYPASS)  // worker 线程A

下一轮 LLM 调工具:
  → RunState → AgentRunner → PermissionManager.check(tool, params)
    → 读取 mode  // worker 线程B (可能是同线程，也可能不是)

如果 mode 不是 volatile:
  线程B 可能看到旧的 DEFAULT 模式 → 不该弹确认的弹了确认
```

---

## 11. CliChannel

**文件**: `v3/cli/CliChannel.java`

### 11.1 流式中断的并发模型

```
主线程 (CLI 交互):
  用户输入 → publishInbound → 等待流式响应

JLine 读取线程:
  用户按 Esc → Terminal.read() 返回 Esc 字节
  → cancelled = true
  → agentLoop.cancel(callback)

AgentLoop worker:
  流式回调: onStreamData(sessionId, requestId, token)
  → 检查 if (cancelled) return;  ← volatile 读
```

```java
private volatile boolean cancelled;    // Esc 中断标志
private volatile String currentRequestId; // 当前请求 ID
```

### 11.2 为什么这两个字段必须是 volatile？

| 字段 | 写线程 | 读线程 | 不用 volatile 的后果 |
|------|-------|-------|-------------------|
| `cancelled` | JLine 读取线程 | AgentLoop worker + 流式回调线程 | 用户按了 Esc 但 LLM 继续输出 |
| `currentRequestId` | 主线程 | 流式回调线程 | 回调匹配到错误的 request |

---

## 12. ChannelServer

**文件**: `v1/channel/ChannelServer.java`

### 12.1 synchronized + wait/notify

```java
// 线程A (HTTP handler) — 发布 SSE 流式响应
synchronized (streamResponseHandlers) {
    streamResponseHandlers.put(key, handler);   // 注册 handler
}
synchronized (handler) {
    handler.wait(300000);                       // 挂起，等待 LLM 响应
}
synchronized (streamResponseHandlers) {
    streamResponseHandlers.remove(key);         // 清理
}

// 线程B (AgentLoop stream callback) — 推送增量数据
synchronized (streamResponseHandlers) {
    handler = streamResponseHandlers.get(key);
}
handler.sendChunk(content);                     // 写 SSE data
handler.completed = true;
synchronized (handler) {
    handler.notifyAll();                        // 唤醒线程A
}
```

### 12.2 为什么两层锁？

```
streamResponseHandlers 锁 — 保护 handlers Map 的注册/注销/查找
handler 锁             — 实现 wait/notify 线程通信

不同粒度、不同目的，不能合并。
```

### 12.3 为什么不用 CountDownLatch？

`CountDownLatch` 是一次性的（计数到 0 后不能重置）。而这里 handler 需要支持多次推送（每次 callback 都可能唤醒 handler）。`wait/notify` 可以反复使用。

**注意**：这个类是 V1 的遗留代码，V2 已经迁移到 Spring SSE + StreamResponseCallback，不再需要 wait/notify。保留它体现了项目从"手动线程通信"到"框架抽象"的演进。

### 12.4 设计批判：CHM + synchronized(CHM) 有意义吗？

```java
private final Map<String, StreamResponseHandler> streamResponseHandlers
    = new ConcurrentHashMap<>();

// 所有访问都包裹在 synchronized(streamResponseHandlers) 中
synchronized (streamResponseHandlers) {
    streamResponseHandlers.put(key, handler);
}
```

**这里 CHM 的多余的**。因为所有对 `streamResponseHandlers` 的访问都已经被 `synchronized` 块串行化了，CHM 的内部分段锁完全没被用到。用普通的 `HashMap` 效果一样。

这是 V1 快速迭代留下的**过度保护**——写的时候顺手用了 CHM"以防万一"，后来又加了 `synchronized` 做复合操作保护，但没意识到两者叠加是冗余的。

面试时如果能指出这类问题，说明你有代码嗅觉，不是只会背书。

---

## 13. MCP 客户端

**文件**: `mcp/StdioMCPClient.java`

### 13.1 CHM + CompletableFuture 桥接

```java
private final Map<String, CompletableFuture<MCPResult>> pendingRequests
    = new ConcurrentHashMap<>();

// 线程A (Agent worker): 发起 MCP 调用
public CompletableFuture<MCPResult> callTool(String toolName, Map args) {
    String requestId = UUID.randomUUID().toString();
    CompletableFuture<MCPResult> future = new CompletableFuture<>();
    pendingRequests.put(requestId, future);          // 注册 pending
    sendJsonRpcRequest(requestId, toolName, args);   // 写入子进程 stdin
    return future;                                   // 返回 Future
}

// 线程B (stdout reader): 收到 MCP 响应
private void handleResponse(String json) {
    String requestId = parseRequestId(json);
    CompletableFuture<MCPResult> future = pendingRequests.remove(requestId);
    if (future != null) {
        future.complete(parseResult(json));          // 传递结果 → 唤醒线程A
    }
}
```

**为什么这个模式优雅？**

- `ConcurrentHashMap` 做路由表（按 requestId 匹配请求和响应）
- `CompletableFuture` 做信号量（线程间传递结果）
- 不需要手动 `wait/notify`
- 线程A 拿到 `CompletableFuture` 后可以 `thenApply` 继续链式处理

**关键细节：为什么用 `remove()` 而不是 `get() + remove()`？**

```java
// ✅ 正确 — remove() 是原子的
CompletableFuture<MCPResult> future = pendingRequests.remove(requestId);

// ❌ 错误 — get() 和 remove() 之间可能被插入
CompletableFuture<MCPResult> future = pendingRequests.get(requestId);
// ← 超时线程在这里执行了 remove + complete，future 已被完成
pendingRequests.remove(requestId);    // ← 这里 remove 返回 null（已被删）
future.complete(result);              // ← 重复 complete 抛异常！
```

场景：响应线程收到响应、超时线程同时触发。两个线程都试图完成同一个 Future。`remove()` 的原子性保证只有一个线程能拿到非 null 的 Future 并完成它——另一个线程的 `remove()` 返回 null，直接跳过。

**Q: 如果子进程挂了，pending request 怎么办？**

`closed` (volatile) 标志配合 shutdown 逻辑：关闭时遍历 `pendingRequests`，对每个 Future 调用 `future.completeExceptionally()` 释放等待线程。

---

## 14. MetricsHook

**文件**: `core/hook/impl/MetricsHook.java`

### 14.1 synchronized 多字段联合原子更新

```java
private final ConcurrentHashMap<String, SessionMetrics> sessionMetrics
    = new ConcurrentHashMap<>();

private static class SessionMetrics {
    private int promptTokens;
    private int completionTokens;
    private int iterations;
    private int toolCalls;
    private int errors;
    private long totalDurationMs;

    synchronized void addTokens(int prompt, int completion) {
        this.promptTokens += prompt;       // 这两个字段必须一起更新
        this.completionTokens += completion; // 不能被其他线程插入
    }

    synchronized void incrementIterations() { ... }
    synchronized void incrementErrors()    { ... }
}
```

### 14.2 为什么用 synchronized 而不用 AtomicInteger × N？

```java
// ❌ 用多个 AtomicInteger — 两个字段之间不原子
AtomicInteger promptTokens = new AtomicInteger();
AtomicInteger completionTokens = new AtomicInteger();

void addTokens(int p, int c) {
    promptTokens.addAndGet(p);
    // ← 线程B 在这读到不一致的状态！promptTokens 已更新但 completionTokens 未更新
    completionTokens.addAndGet(c);
}

// ✅ synchronized — 整个方法原子执行
synchronized void addTokens(int p, int c) {
    this.promptTokens += p;
    this.completionTokens += c;  // 两个字段在锁保护下一起更新
}
```

**关键认知**：`AtomicInteger` 只保证**单个变量**的原子性。当多个变量需要**联合原子更新**时，必须用锁。

### 14.3 两层隔离

```
ConcurrentHashMap  →  隔离不同 session（session-A 和 session-B 用不同 SessionMetrics 对象）
synchronized       →  保护同一 session 内的并发更新
```

和 SessionManager 的设计思路一致：外层用 CHM 隔离，内层用锁保护。

---

## 15. WebSocket

**文件**: `v2/websocket/NanobotWebSocketEndpoint.java`

### 15.1 static CHM 连接注册表

```java
private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
private static final AtomicInteger connectionCount = new AtomicInteger(0);
```

**为什么必须是 `static`？**

Jakarta WebSocket 容器为每个新连接创建新的 Endpoint 实例。如果 `SESSIONS` 不是 static，每个实例有自己的连接表，无法实现跨连接的查找（例如"给所有连接的客户端广播"）。

**为什么用 `ConcurrentHashMap`？**

三个线程并发操作：
- Tomcat WS 线程：`@OnOpen` → `SESSIONS.put()`
- AgentLoop 回调线程：`SESSIONS.get()` → `sendText()`
- Tomcat WS 线程：`@OnClose` → `SESSIONS.remove()`

**`AtomicInteger` 做连接计数**：`incrementAndGet()` 比 `synchronized + int` 轻量。只做自增，不需要和其他字段联动。

---

## 16. 全项目并发原语速查表

| 并发原语 | 使用位置 | 场景 | 为什么选它 |
|---------|---------|------|-----------|
| `ArrayBlockingQueue(100)` | MessageBus | 生产者-消费者 | 有界防 OOM + 自然背压 |
| `ConcurrentHashMap` | 全局 15+ 处 | 并发读写 Map | 分段锁，高并发 |
| `CopyOnWriteArrayList` | AgentLoop.streamCallbacks | 读极多写极少 | 写时复制，读无锁 |
| `synchronized(perKeyLock)` | SessionManager | 同一会话互斥 | 锁粒度最优（不同会话并行） |
| `synchronized(method)` | MetricsHook.SessionMetrics | 多字段联合原子更新 | 联合操作不可拆分 |
| `synchronized(handler).wait()` | ChannelServer | 线程挂起等响应 | wait/notify 可复用 |
| `CompletableFuture.thenCompose()` | AgentRunner | 异步递归链 | 不阻塞，流式友好 |
| `CompletableFuture + CHM` | StdioMCPClient | 异步请求-响应配对 | CHM 路由 + Future 信号量 |
| `AtomicBoolean` | MessageBus, AgentLoop | 启停状态 | CAS + 可见性 |
| `AtomicInteger` | TurnContext, WebSocket | 计数器 | 轻量原子自增 |
| `volatile boolean` | 多类 (7+ 处) | 状态标志 | 跨线程可见性，零成本 |
| `volatile 引用` | ToolRegistry, PermissionManager | 缓存/模式切换 | 读多写极少 |
| `ExecutorService` | AgentLoop(4t), ToolRegistry(10t) | 受限线程池 | 控制并发度 |
| `HashMap` (非线程安全) | SessionStore.dirCache | 调用方已加锁 | 无需重复保护 |

---

## 17. 防御性设计模式总结

### 17.1 锁粒度最小化

```
❌ 全局锁: synchronized(this) { 操作所有会话 }
✅ 每会话锁: synchronized(lock(sessionKey)) { 操作一个会话 }
✅ 每指标锁: synchronized(metricsInstance) { 操作一个 session 的指标 }
```

### 17.2 有界优于无界

```
❌ LinkedBlockingQueue()           → 无界，可 OOM
✅ ArrayBlockingQueue(100)         → 有界，背压保护
✅ ExecutorService(固定线程数)      → 有界线程池
```

### 17.3 简单优于复杂

```
能用一个 volatile 解决的 → 不加锁
能用一个 synchronized 解决的 → 不用 ReentrantLock
能用 CHM 解决的 → 不自建锁方案
```

### 17.4 调用方负责并发控制

```
SessionManager (业务层) → synchronized 加锁
SessionStore   (存储层) → 普通 HashMap，信任调用方

这种分层比"每层都自己加锁"更清晰——锁的职责集中在一处。
```

### 17.5 单线程消费是最大的简化

AgentLoop 的单 daemon 线程消费入站消息，是整个系统最关键的并发简化策略。它保证**同一会话不会被并发处理**，消除了最复杂的并发场景。在此基础上，其他并发控制只需要处理跨会话的共享状态（如 Memory、Metrics、连接表）。
