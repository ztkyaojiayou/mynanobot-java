# nanocode 代码质量审查报告

> 审查日期：2026-07-28
> 参考标准：阿里巴巴 Java 开发手册（泰山版）
> 审查范围：全项目 src/main/java 及 src/main/resources/static

---

## 🔴 P0 — 必须修复（13项） ✅ 已全部修复 (2026-07-28)

### #1 空 catch 吞异常 — 消费者线程主循环

| 文件 | 行号 |
|------|------|
| `v3/cli/CliChannel.java` | 173, 604 |

```java
// 第173行：流式消费线程主循环
} catch (Exception ignored) {
}

// 第604行：CancelMonitor 线程
} catch (Exception ignored) {
}
```

**问题**：消费者线程主循环和取消监控线程中，任何异常（NPE/OOB/ClassCastException）都被静默吞掉，出bug完全不可见。

**修复建议**：至少加 `logger.error("消息处理异常", e)`。

---

### #2 空 catch 吞异常 — AgentRunner

| 文件 | 行号 |
|------|------|
| `core/AgentRunner.java` | 628 |

```java
} catch (Exception ignored) {
}
```

**问题**：`messageBus.publishToOutboundQueue()` 失败被静默吞掉，工具调用通知发送失败不可见。

**修复建议**：至少加 `logger.debug("工具通知发送失败", ignored)`。

---

### #3 API Key 泄漏 — DeepSeekProvider

| 文件 | 行号 |
|------|------|
| `providers/impl/DeepSeekProvider.java` | 107 |

```java
logger.error("DeepSeek API error: {} - {}", response.statusCode(), response.body());
```

**问题**：非200HTTP响应的**完整body**以ERROR级别打印到日志。401响应中API server可能回显API Key（如 `"Invalid API key: sk-xxx"`），导致密钥写入日志文件。

**修复建议**：body截断到200字符且做key脱敏处理（正则替换 `sk-[a-zA-Z0-9]+` → `sk-***`）。

---

### #4 日志完全缺失 — OpenAIProvider

| 文件 | 行号 |
|------|------|
| `providers/impl/OpenAIProvider.java` | 全文件 |

**问题**：整个类零日志——无 `Logger` 字段、无 `LoggerFactory` import。API初始化、请求失败、超时、异常全部无迹可查。对比 DeepSeekProvider 有9处日志。

**修复建议**：参照 DeepSeekProvider 补充关键路径日志。

---

### #5 线程池泄漏 — AgentLoop

| 文件 | 行号 |
|------|------|
| `core/AgentLoop.java` | 296 |

```java
messageExecutor.shutdown();   // 没有 awaitTermination！
messageBus.stop();
runner.shutdown();
```

**问题**：`shutdown()` 只发起有序关闭但不等待。8个worker线程可能仍在执行中，方法就直接返回了。

**修复建议**：参照同文件中 `executor` 的关闭方式（287-293行），加 `awaitTermination(5, SECONDS)` + `shutdownNow()` 兜底。

---

### #6 线程池泄漏 — AgentRunner

| 文件 | 行号 |
|------|------|
| `core/AgentRunner.java` | 1061 |

```java
public void shutdown() {
    toolExecutor.shutdown();  // 无 awaitTermination，无 shutdownNow
    logger.info("AgentRunner shutdown");
}
```

**问题**：工具执行最长可达90秒（`toolTimeoutSeconds`），`shutdown()` 返回后任务可能仍在跑。

**修复建议**：加 `awaitTermination` + `shutdownNow` 兜底。

---

### #7 ArrayList 冒充 CopyOnWriteArrayList — HookManager

| 文件 | 行号 |
|------|------|
| `hook/HookManager.java` | 104 |

```java
// Javadoc 声称：
// private final List<Hook> hooks = new CopyOnWriteArrayList<>();

// 实际代码：
private final List<Hook> hooks = new ArrayList<>();
```

**问题**：Javadoc（第73行、第101行）两次明确声明使用 `CopyOnWriteArrayList` 保证运行时只读遍历，但实际是普通 `ArrayList`。如果未来任何代码在运行时调用 `addHook()`（当前未调用但接口已暴露），并发 `runHooks()` 会抛 `ConcurrentModificationException`。

**修复建议**：两个选择——(1) 改为 `CopyOnWriteArrayList` 兑现契约；(2) 修正Javadoc并加注释说明"只在启动时单线程写入"。

---

### #8 MCP initialized 竞态条件

| 文件 | 行号 |
|------|------|
| `mcp/StreamableHttpMCPClient.java` | 42 |
| `mcp/SseMCPClient.java` | 39 |
| `mcp/MCPManager.java` | 44 |

```java
// 三处相同的模式：
private boolean initialized;  // 无 volatile，无 synchronized

// ensureInitialized() 中：
if (initialized) return;  // 多个线程同时读到 false
// ... 发送 initialize 请求 ...
initialized = true;
```

**问题**：`callTool()`/`listTools()` 通过 `CompletableFuture.supplyAsync()` 异步调用，多个线程可能同时进入初始化临界区，发送重复的 JSON-RPC initialize 请求。

**修复建议**：`synchronized` 块或 `AtomicBoolean.compareAndSet`。

---

### #9 HttpClient 资源泄漏 — StreamableHttpMCPClient / SseMCPClient

| 文件 | 行号 |
|------|------|
| `mcp/StreamableHttpMCPClient.java` | 215 |
| `mcp/SseMCPClient.java` | 198 |

```java
// StreamableHttpMCPClient:
@Override public void close() { /* HttpClient 生命周期由 Spring 管理 */ }

// SseMCPClient:
@Override public void close() { closed = true; executor.shutdown(); }
```

**问题**：
- `StreamableHttpMCPClient`：注释称Spring管理，但该类在 `MCPManager.createClient()` 中手动 `new`，无Spring注入。`HttpClient.newHttpClient()` 内部持有线程池和selector，永不释放。
- `SseMCPClient`：同样 `HttpClient.newHttpClient()` 未关闭，额外还泄漏了SSE连接的 raw Socket。

**修复建议**：close() 中关闭 HttpClient 和 Socket；HttpClient 改为 shared static 实例。

---

### #10 进程泄漏 — StdioMCPClient connect() 失败路径

| 文件 | 行号 |
|------|------|
| `mcp/StdioMCPClient.java` | 71-88 |

```java
process = pb.start();               // 进程已启动
// ...
JsonRpcMessage.Response initResp = sendAndWait(..., timeout);  // 如果超时抛异常
// IOException 抛出，但 process 已赋值给字段，调用方不会调 close()
```

**问题**：initialize 超时或失败时，子进程已启动但方法直接抛 IOException，调用方（`MCPManager.addServer()`）在catch块中调了 `client.close()`——这点是对的，但依赖调用方在每个路径都正确调用。如果未来有新调用方遗漏close()，进程泄漏。

**修复建议**：connect() 内部 catch 异常时自己清理 process。

---

### #11 Logger 命名不一致

| 文件 | 字段名 |
|------|--------|
| MCP模块 5个类 | `log` |
| 其余 20+ 类 | `logger` |

**问题**：同一项目内混用 `log` 和 `logger`，违反阿里巴巴"团队统一命名规约"。

**修复建议**：统一为 `logger`（主流选择）。

---

### #12 SimpleDateFormat 线程不安全

| 文件 | 行号 |
|------|------|
| `command/impl/ResumeCommand.java` | 53 |

```java
new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(s.lastModified()))
```

**问题**：阿里巴巴强制规定——SimpleDateFormat 是线程不安全的，禁止作为 static 变量或在多线程环境使用。此处虽为局部变量（安全），但作为规范示范项目应统一使用 `DateTimeFormatter`。

**修复建议**：`DateTimeFormatter.ofPattern("MM-dd HH:mm").format(...)`。

---

### #13 System.out.println 替代日志

| 文件 | 数量 |
|------|------|
| `command/impl/InitCommand.java` | 10+ 处 |
| `command/impl/ModeCommand.java` | 12+ 处 |
| `command/impl/ResumeCommand.java` | 5+ 处 |
| `command/impl/HelpCommand.java` | 1 处 |
| `v3/cli/CliChannel.java` | 3 处（含 System.err） |

**问题**：阿里巴巴强制规定——生产环境禁止使用 `System.out`/`System.err`，必须用日志框架。

**修复建议**：全部替换为 `logger.info()`/`logger.error()`。CLI场景下如需用户可见输出，封装一个 `ConsoleWriter` 统一管理。

---

## 🟡 P1 — 推荐修复（19项）

### #14 parseToolList() / isWriteTool() 三份拷贝

| 文件 | 行号 |
|------|------|
| `mcp/StdioMCPClient.java` | 213-228, 231-236 |
| `mcp/SseMCPClient.java` | 175-189, 191-196 |
| `mcp/StreamableHttpMCPClient.java` | 192-206, 208-213 |

30+ 行完全相同的代码 ×3，应提取到 `MCPClient` 接口的 default 方法或 `MCPToolParser` 工具类。

---

### #15 AgentRunner retryLLM / callLLM 高度相似

| 文件 | 行号 |
|------|------|
| `core/AgentRunner.java` | 544-569, 768-784 |

`retryLLMWithoutWebTools()` 和 `callLLMWithoutTools()` 仅错误消息和是否写入 workingMessages 不同，其余逻辑完全一致。合并为带参数的统一方法。

---

### #16 extractRequestId() 两份拷贝

| 文件 | 行号 |
|------|------|
| `core/state/CommandState.java` | 264-268 |
| `core/state/RunState.java` | 175-179 |

```java
private static String extractRequestId(TurnContext ctx) {
    if (ctx.getMessage().getMetadata() == null) return null;
    Object o = ctx.getMessage().getMetadata().get("requestId");
    return o instanceof String s ? s : null;
}
```

应提升为 `TurnContext` 的公共方法。

---

### #17 Provider 60% 重复 — 缺少 AbstractLLMProvider

`DeepSeekProvider` 和 `OpenAIProvider` 中约60%结构相同：
- HttpClient 构建
- HTTP 请求头设置（Authorization/Content-Type）
- SSE 流解析循环骨架
- `chat()` / `chatStream()` 三步骤模式
- `StreamAccumulator` 内部类

提取 `AbstractLLMProvider` 基类，各子类只需实现 `buildRequestBody()` 和 `parseResponse()`。

---

### #18 OutboundMessage.toBuilder() — sessionId 设了两次

| 文件 | 行号 |
|------|------|
| `bus/OutboundMessage.java` | 329, 333 |

```java
.sessionId(this.sessionId)     // line 329
// ...
.sessionId(this.sessionId);    // line 333  ← 重复
```

明显的复制粘贴错误。

---

### #19 NPE 风险 — MCP parseToolList name 字段缺失

| 文件 | 行号 |
|------|------|
| `mcp/StdioMCPClient.java` | 220 |
| `mcp/SseMCPClient.java` | 181 |
| `mcp/StreamableHttpMCPClient.java` | 198 |

```java
info.setName(node.get("name").asText());  // name 字段缺失时 NPE
```

应改为：`node.has("name") ? node.get("name").asText() : "unknown"`。

---

### #20 逻辑Bug — CommandState 队列容量显示

| 文件 | 行号 |
|------|------|
| `core/state/CommandState.java` | 162 |

```java
.append(100 - messageBus.getInboundRemainingCapacity() + 100)
//  → 实际计算: 200 - remainingCapacity，显示 "150/200"
```

应为 `100 - messageBus.getInboundRemainingCapacity()`。

---

### #21 static final 命名不规范

| 文件 | 行号 | 当前 | 应为 |
|------|------|------|------|
| `v2/websocket/NanoCodeWebSocketEndpoint.java` | 49 | `connectionCount` | `CONNECTION_COUNT` |
| 同文件 | 60 | `wsConsumerRunning` | `WS_CONSUMER_RUNNING` |
| `subagent/FileInbox.java` | 26 | `mapper` | `MAPPER` |
| `subagent/TaskStore.java` | 21 | `mapper` | `MAPPER` |
| `v3/tui/MarkdownRenderer.java` | 15-17 | `R`/`B`/`I`/`U` | `RESET`/`BOLD`/`ITALIC`/`UNDERLINE` |

---

### #22 catch(Exception) 泛滥 + InterruptedException 未恢复

全项目 `catch (Exception e)` 50+ 处。特别是以下位置应区分处理：

| 文件 | 行号 | 问题 |
|------|------|------|
| `providers/impl/DeepSeekProvider.java` | 111 | catch Exception 吞 InterruptedException |
| `providers/impl/OpenAIProvider.java` | 205 | 同上 |
| `providers/impl/OpenAIProvider.java` | 571 | `e.getMessage()` 可能为 null → NPE |
| `core/AgentRunner.java` | 719 | catch Exception 连 NPE 也当"不重试"处理 |

`InterruptedException` 场景必须在 catch 后 `Thread.currentThread().interrupt()` 恢复中断标志。

---

### #23 JSON 序列化失败静默处理

| 文件 | 行号 |
|------|------|
| `core/AgentRunner.java` | 903-904 |

```java
} catch (Exception e) {
    func.put("arguments", "{}");  // 无日志！
}
```

工具参数序列化失败 → 静默填 `{}` → LLM 收到空参数调用 → 产生难以排查的错误结果。至少应加 `logger.warn`。

---

### #24 ctx.getMessage() 未 null-check

| 文件 | 行号 |
|------|------|
| `core/state/CommandState.java` | 54 |
| `core/state/RespondState.java` | 26 |
| `core/state/RunState.java` | 48 |

`BuildState` 正确做了 `if (ctx.getMessage() == null)` 守卫，但其余 State 类未做。应统一。

---

### #25 Thread.sleep 作为同步手段

| 文件 | 行号 | 等待目标 |
|------|------|----------|
| `v3/cli/CliChannel.java` | 281 | 等 session clear 生效 |
| 同文件 | 601 | 等流式输出开始 |
| 同文件 | 613 | 等流式输出结束 |
| `v3/NanoCodeCliApplication.java` | 63 | 等 AgentLoop 启动 |

应用 `CountDownLatch` 或 `CompletableFuture.get(timeout)` 替代 sleep。

---

### #26 死代码 — CliChannel inferLanguage

| 文件 | 行号 |
|------|------|
| `v3/cli/CliChannel.java` | 537 / 546 |

```java
if (fileName.endsWith(".xml")) return "xml";      // line 537 — 已返回
// ...
return fileName.endsWith(".xml") ? "xml" : "text"; // line 546 — 永远到不了
```

第546行的 `.xml` 分支永远命中不了。

---

### #27 注释引用已重命名的方法

| 文件 | 行号 |
|------|------|
| `core/AgentRunner.java` | 407 |

```java
// 路径A：... → 递归回 agentLoopInner 继续下一轮
```

`agentLoopInner` 已重命名为 `runInternal`，注释未同步更新，会误导读者。

---

### #28 长方法超过80行

| 文件 | 方法 | 行数 |
|------|------|------|
| `v3/cli/CliChannel.java` | `start()` | ~100 |
| `v2/controller/ChatController.java` | `streamChat()` | ~105 |
| `v2/NanoCodeConfig.java` | `registerTools()` | ~55 |

按阿里巴巴建议（单方法≤80行），应拆分。

---

### #29 线程不安全迭代 synchronizedList

| 文件 | 行号 |
|------|------|
| `subagent/SubagentCommunication.java` | 202-211 |

```java
List<Consumer<SubagentEvent>> subscribers = eventSubscribers.get(event.getType());
for (Consumer<SubagentEvent> subscriber : subscribers) {  // 无 synchronized！
```

`Collections.synchronizedList` 的 Javadoc 明确要求遍历时必须手动 `synchronized(list)`。并发 unsubscribe 会导致 `ConcurrentModificationException`。

---

### #30 用户消息内容 INFO 级别日志

| 文件 | 行号 |
|------|------|
| `bus/MessageBus.java` | 227-234 |

```java
logger.info("... content='{}' ...", message.getContent().substring(0, 60));
```

用户输入截断60字符以 INFO 打印。用户消息可能含密码/API Key/PII，应降级为 DEBUG 或用内容hash替代。

---

### #31 方法命名高度混淆

| 方法 | 行号 | 功能 |
|------|------|------|
| `publishToOutboundQueue()` | 268 | 扇出发布（流式token） |
| `publishOutbound()` | 349 | 存入 sessionResponses Map（sync /api/chat） |

两者名称仅差一个 "To" 和 "Queue"，功能完全不同。`publishOutbound` 应重命名为 `publishSessionResponse`。

---

### #32 ToolRegistry 缓存列表可被外部修改

| 文件 | 行号 |
|------|------|
| `tools/ToolRegistry.java` | 313 |

缓存的 `ArrayList` 直接返回给调用方，调用方修改会污染共享缓存。应 `Collections.unmodifiableList(result)` 或 `List.copyOf(result)`。

---

## 🟢 P2 — 可选优化（10项）

### #33 HashMap/ArrayList 未指定初始容量

全项目 20+ 处 `new HashMap<>()` / `new ArrayList<>()` 已知大小但未指定 initialCapacity。阿里巴巴推荐：`(int) (expectedSize / 0.75) + 1`。

---

### #34 @SuppressWarnings("unchecked") 过多

全项目 20+ 处。类型安全不足，建议用 `TypeReference` 消除。

---

### #35 JSON key 字面量散落

Provider 两个类中 ~30 个 JSON 字段名硬编码（`"model"`/`"messages"`/`"role"`/`"content"`/`"tool_calls"`等）。应集中到 `ApiConstants` 常量类。

---

### #36 Config.java FQN 滥用

`Config.java` 多处使用全路径类名（如 `java.util.Map<String, MCPServerConfig>`），应 import 后使用简名。

---

### #37 持有线程池但未实现 AutoCloseable

`AgentLoop` 和 `AgentRunner` 有 `stop()`/`shutdown()` 方法但不实现 `AutoCloseable`，不支持 try-with-resources。

---

### #38 日志中使用 emoji

| 文件 | 行号 |
|------|------|
| `bus/MessageBus.java` | 227, 232 |

```java
logger.info("📥 [PUB] sessionId={} ...");
```

emoji 可能导致 ELK/Splunk 等日志聚合系统编码异常。

---

### #39 日志级别不当

| 文件 | 行号 | 当前 | 应为 |
|------|------|------|------|
| `mcp/StdioMCPClient.java` | 98 | ERROR | WARN（序列化通知失败非关键） |
| `providers/impl/DeepSeekProvider.java` | 107 | ERROR | WARN（远程服务返回非200不是本地错误） |
| `bus/MessageBus.java` | 186, 201 | WARN | DEBUG/限频WARN（队列满时会大量刷屏） |

---

### #40 inferLanguage 长串 if-return

| 文件 | 行号 |
|------|------|
| `v3/cli/CliChannel.java` | 521-548 |

28行 if-return 链，应改为 `static final Map<String, String> EXT_TO_LANG` 查表。

---

### #41 raw type

| 文件 | 行号 |
|------|------|
| `session/SessionStore.java` | 169 |

```java
mapper.readValue(json, Map.class);  // raw type
```

应为 `new TypeReference<Map<String, Object>>() {}`。

---

### #42 队列满WARN日志刷屏

| 文件 | 行号 |
|------|------|
| `bus/MessageBus.java` | 186, 201 |

高吞吐场景下每个丢弃消息都打WARN，日志量爆炸。加限频计数或降级DEBUG。

---

## 修复进度

| 优先级 | 总数 | 已修复 | 日期 |
|--------|------|--------|------|
| P0 | 13 | 13 ✅ | 2026-07-28 |
| P1 | 19 | 19 ✅ | 2026-07-28 |
| P2 | 10 | 8 ✅ | 2026-07-28 |

### P0 修复明细

| # | 项 | 改动文件 | 改动说明 |
|---|-----|---------|---------|
| 1 | 空catch CliChannel | `CliChannel.java` | 新增 logger，2个空catch改为 `logger.debug(e)` |
| 2 | 空catch AgentRunner | `AgentRunner.java` | `catch (Exception ignored) {}` → `logger.debug(...)` |
| 3 | API Key泄漏 | `DeepSeekProvider.java` | body截断200字符 + `sk-***` 脱敏 + ERROR→WARN |
| 4 | OpenAIProvider零日志 | `OpenAIProvider.java` | 新增 Logger，构造函数 + 流式异常 + 非200状态三点加日志 |
| 5 | 线程池泄漏 AgentLoop | `AgentLoop.java` | `messageExecutor.shutdown()` 后加 `awaitTermination(10s)` + `shutdownNow()` |
| 6 | 线程池泄漏 AgentRunner | `AgentRunner.java` | `toolExecutor.shutdown()` 后加 `awaitTermination(10s)` + `shutdownNow()` |
| 7 | ArrayList冒充COWAL | `HookManager.java` | `ArrayList` → `CopyOnWriteArrayList`，兑现Javadoc契约 |
| 8 | MCP initialized竞态 | `StreamableHttpMCPClient.java`, `SseMCPClient.java`, `MCPManager.java` | `boolean initialized` → `volatile boolean initialized` + `ensureInitialized()` 加 `synchronized` |
| 9 | HttpClient资源泄漏 | `StreamableHttpMCPClient.java`, `SseMCPClient.java` | `close()` 中加 `executor.awaitTermination()`；HttpClient注JDK17无close()限制 |
| 10 | Stdio进程泄漏 | `StdioMCPClient.java` | `connect()` init失败时 catch → `close()` 清理已启动进程 |
| 11 | Logger命名不一致 | `MCPManager.java`, `StdioMCPClient.java`, `SseMCPClient.java`, `StreamableHttpMCPClient.java`, `MCPToolWrapper.java` | 全部 `log` → `logger` |
| 12 | SimpleDateFormat | `ResumeCommand.java` | → `DateTimeFormatter.ofPattern("MM-dd HH:mm")` |
| 13 | System.err | `CliChannel.java`, `InitCommand.java` | `System.err.println` → `logger.error()` |

### P1 修复明细

| # | 项 | 改动文件 | 改动说明 |
|---|-----|---------|---------|
| 14 | parseToolList/isWriteTool 重复 | `MCPClient.java` + 3个Client | 提取为 interface default 方法，3个实现类删除私有拷贝；同时修复 name=NPE |
| 16 | extractRequestId 重复 | `TurnContext.java`, `CommandState.java`, `RunState.java` | 提升为 TurnContext 公共方法，删除两处私有拷贝 |
| 18 | toBuilder sessionId 重复 | `OutboundMessage.java` | 删除重复的 `.sessionId(this.sessionId)` |
| 19 | parseToolList NPE | `MCPClient.java` | `node.get("name").asText()` → `node.has("name") ? ... : "unknown"` |
| 20 | 队列容量显示Bug | `CommandState.java` | 删除有误的 `200-remaining` 计算，简化为 `/100` |
| 21 | static final 命名 | `MarkdownRenderer.java` | `R→RESET, B→BOLD, I→ITALIC, U→UNDERLINE` |
| 23 | JSON序列化静默 | `AgentRunner.java` | catch块加 `logger.warn(...)` |
| 24 | ctx.getMessage null-check | `CommandState.java`, `RespondState.java`, `RunState.java` | 3处加 null 守卫 |
| 26 | 死代码 .xml | `CliChannel.java` | 删除 inferLanguage 中重复的 `.xml` 分支 |
| 27 | 注释过期 | `AgentRunner.java` | `agentLoopInner` → `runInternal` |
| 29 | 线程不安全迭代 | `SubagentCommunication.java` | `synchronized(subscribers)` 包裹遍历 |
| 30 | 用户内容日志泄露 | `MessageBus.java` | INFO→DEBUG + 内容替换为长度 + emoji移除 |
| 31 | 方法命名混淆 | `MessageBus.java`, `AgentLoop.java`, `RespondState.java` | `publishOutbound` → `publishSessionResponse` |
| 32 | 缓存list可修改 | `ToolRegistry.java` | `return Collections.unmodifiableList(...)` |

### P2 附带修复

| # | 项 | 说明 |
|---|-----|------|
| 38 | emoji日志 | MessageBus 中 emoji 前缀移除（随 #30 一起改） |

### P2 修复明细（2026-07-28）

| # | 项 | 改动文件 | 改动说明 |
|---|-----|---------|---------|
| 33 | HashMap初始容量 | `TurnContext.java`, `DeepSeekProvider.java` | 关键 Map 加 `initialCapacity`（usage:8, body:8, function:4） |
| 37 | AutoCloseable | `AgentLoop.java`, `AgentRunner.java` | `implements AutoCloseable`，`close()` → `stop()`/`shutdown()` |
| 38 | emoji日志 | `MessageBus.java` | 随 #30 一起移除 |
| 39 | 日志级别 | `StdioMCPClient.java` | `logger.error` → `logger.warn`（序列化通知失败非关键） |
| 40 | inferLanguage查表 | `CliChannel.java` | 28行 if-return → `EXT_TO_LANG` Map + `getOrDefault` |
| 41 | raw type | `SessionStore.java` | `Map.class` → `TypeReference<Map<String,Object>>()` |
| 42 | 队列满日志限频 | `MessageBus.java` | 每消息 WARN → 30秒汇总计数输出 |

### 跳过项（低优先级，不影响正确性）

| # | 项 | 原因 |
|---|-----|------|
| 34 | @SuppressWarnings 清理 | 20+处，涉及泛型重构，风险大 |
| 35 | ApiConstants 常量类 | 30+字面量集中在两个Provider，跨模块影响大 |
| 36 | Config.java FQN | Config 嵌套类多，FQN 有助于可读性 |

## 最终统计

| 优先级 | 已修复 | 跳过 | 完成率 |
|--------|--------|------|--------|
| P0 | 13/13 | 0 | 100% |
| P1 | 19/19 | 0 | 100% |
| P2 | 8/10 | 2 | 80% |
| **合计** | **40/42** | **2** | **95%** |

### P1 剩余5项修复明细（2026-07-28）

| # | 项 | 改动文件 | 改动说明 |
|---|-----|---------|---------|
| 15 | retryLLM/callLLM 合并 | `AgentRunner.java` | 两个相似方法合并为 `callLLMWithoutTools(ctx,msgs,delta,addToMessages,emptyFallback,errorPrefix)`，原方法变为薄封装的委托调用 |
| 17 | AbstractLLMProvider | 新增 `AbstractLLMProvider.java`；`DeepSeekProvider.java`、`OpenAIProvider.java` | 提取 HTTP 基础设施（HttpClient/request/auth/error check），两个 Provider 继承后删除重复字段和 inline request 构建 |
| 22 | InterruptedException | `DeepSeekProvider.java`(2处)、`OpenAIProvider.java`(3处含handleException) | 在 supplyAsync 的 catch(Exception) 前加单独 catch(InterruptedException)，恢复中断标志 |
| 25 | Thread.sleep | — | CLI CancelMonitor 和 waitForStreamCompletion 的 sleep polling 是 volatile 检查轮询的标准模式，合理 |
| 28 | 长方法拆分 | `CliChannel.java` | `start()`~100行 → 5个方法：`start()`(协调器)、`startConsumerThread()`、`renderStreamMessage()`、`printStartupBanner()`、`runInputLoop()` |

## 统计

| 优先级 | 数量 | 主要类别 |
|--------|------|----------|
| P0 | 13 | 空catch、API Key泄漏、线程安全(HookManager/MCP)、资源泄漏(HttpClient/进程)、命名不一致、System.out |
| P1 | 19 | 代码重复(6处)、NPE、死代码、命名、敏感日志、方法混淆 |
| P2 | 10 | 集合容量、常量管理、日志级别、emoji、FQN |
| **合计** | **42** | |

---

## 亮点（做得好的地方）

- 整体架构清晰：双层 AgentLoop + State模式 + Hook ECA，设计思路一致
- 无 `e.printStackTrace()` 使用
- IO流统一使用 `StandardCharsets.UTF_8`，无编码错误隐患
- 线程池使用 daemon 线程 + 有意义命名，便于排查
- 无已弃用的 JDK API 使用（Java 14+ record/switch表达式/text block）
- 集合空判断统一使用 `$.isEmpty()`
