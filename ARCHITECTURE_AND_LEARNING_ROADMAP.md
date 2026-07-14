# Nanobot-Java 架构说明与新手学习路线

---

## 一、项目概述

Nanobot-Java 是基于香港大学开源的 Nanobot（mini 版 OpenClaw）项目进行的 Java 重写。这是一个轻量级的 AI Agent 框架，遵循 **"Core stays small; extend at the edges"**（核心保持精简，通过边缘扩展）的设计理念。

**项目位置**：`d:\IdeaProjects\个人项目\ai-vibe-coding\nanobot-java`

---

## 二、架构说明

### 2.1 整体架构分层

```
┌─────────────────────────────────────────────────────────────────────┐
│                      用户层 (Channels)                             │
│   Telegram | Discord | WeChat | WebSocket | CLI | ...              │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    消息总线层 (MessageBus)                          │
│           Inbound Queue              Outbound Queue                 │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     核心引擎层 (AgentLoop)                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐               │
│  │AgentLoop │ │AgentRunner│ │TurnContext│ │  Hooks   │               │
│  │ (状态机) │ │ (LLM循环) │ │ (上下文)  │ │ (扩展点) │               │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        ▼                      ▼                      ▼
┌───────────────┐    ┌───────────────┐    ┌───────────────┐
│   工具层      │    │   提供商层    │    │   内存层      │
│  ToolRegistry │    │  LLMProvider  │    │ MemoryStore   │
│  + Tools      │    │  + Providers  │    │ + Sessions    │
└───────┬───────┘    └───────────────┘    └───────────────┘
        │
        │  ╔══════════════════════════════╗
        └──╣  安全层 (Security) ★新增    ║
           ║  Hook→Guards→Rules→Mode     ║
           ║  PathGuard / CommandGuard   ║
           ║  NetworkGuard / RuleEngine  ║
           ╚══════════════════════════════╝
```

### 2.2 核心组件职责

| 组件 | 职责 | 文件位置 |
|------|------|----------|
| **Nanobot** | 应用入口，组件初始化和生命周期管理 | `Nanobot.java` |
| **AgentLoop** | 状态机引擎，管理消息处理流程 | `core/AgentLoop.java` |
| **AgentRunner** | LLM 调用循环，处理工具调用 | `core/AgentRunner.java` |
| **TurnContext** | 会话上下文，存储消息和状态 | `core/TurnContext.java` |
| **TurnState** | 状态枚举，定义状态机节点 | `core/TurnState.java` |
| **MessageBus** | 消息总线，异步队列通信 | `bus/MessageBus.java` |
| **ToolRegistry** | 工具注册中心 | `tools/ToolRegistry.java` |
| **Tool** | 工具接口，定义工具契约 | `tools/Tool.java` |
| **LLMProvider** | LLM 提供商接口 | `providers/LLMProvider.java` |
| **SessionManager** | 会话管理器 | `session/SessionManager.java` |
| **MemoryStore** | 内存持久化存储 | `memory/MemoryStore.java` |
| **Config / ConfigLoader** | 配置加载和管理 | `config/` |
| **AgentHook / CompositeHook** | 钩子系统，生命周期扩展 | `core/hook/` |
| **MCPManager** | MCP 服务器管理和工具注册 | `mcp/MCPManager.java` |
| **MCPClient / StdioMCPClient / HttpMCPClient** | MCP 客户端实现 | `mcp/` |
| **MCPToolWrapper** | MCP 工具包装器 | `mcp/MCPToolWrapper.java` |
| **CronScheduler** | 定时任务调度器 | `cron/CronScheduler.java` |
| **Consolidator** | 记忆压缩器 | `memory/Consolidator.java` |
| **Dream** | 长期记忆系统 | `memory/Dream.java` |
| **ChannelServer** | 多通道接入服务器 | `channels/ChannelServer.java` |
| **PermissionManager** | 权限编排器，Hook→Guards→Rules→Mode 管道 | `security/PermissionManager.java` |
| **PathGuard** | 文件路径守卫，工作区隔离 | `security/guard/PathGuard.java` |
| **CommandGuard** | Shell命令守卫，黑/白名单过滤 | `security/guard/CommandGuard.java` |
| **NetworkGuard** | 网络/SSRF守卫，IP范围过滤 | `security/guard/NetworkGuard.java` |
| **RuleEngine** | 规则引擎，deny→ask→allow 优先级链 | `security/rule/RuleEngine.java` |
| **PreToolUseHookManager** | Hook链管理器，工具执行前拦截 | `security/hook/PreToolUseHookManager.java` |

> 📖 完整安全模块文档: [docs/features.md](docs/features.md)

### 2.3 定时任务系统 (CronScheduler)

**功能说明**：基于 cron 表达式的定时任务调度器，支持在指定时间执行任务。

**支持的 cron 表达式格式**：
```
┌───────────── 分钟 (0 - 59)
│ ┌───────────── 小时 (0 - 23)
│ │ ┌───────────── 日期 (1 - 31)
│ │ │ ┌───────────── 月份 (1 - 12)
│ │ │ │ ┌───────────── 星期 (0 - 6) (周日=0)
│ │ │ │ │
* * * * *
```

**使用示例**：
```java
CronScheduler scheduler = new CronScheduler(messageBus);
scheduler.schedule("0 * * * *", () -> {
    messageBus.publish(OutboundMessage.builder()
        .channel("system")
        .content("每分钟执行一次")
        .build());
});
```

**支持的特殊字符**：
- `*` : 匹配任何值
- `?` : 不指定值（用于日期和星期）
- `,` : 列出多个值
- `-` : 指定范围
- `/` : 步长

### 2.4 记忆压缩系统 (Consolidator)

**功能说明**：当对话历史超过 token 预算时，自动压缩历史消息，保留关键信息。

**压缩策略**：
1. 计算当前对话的 token 使用量
2. 如果超过预算，选择最不重要的消息进行压缩
3. 使用 LLM 对选中的消息进行总结
4. 用总结替换原始消息

**优先级规则**：
- 系统消息：最高优先级，不压缩
- 工具调用结果：高优先级，保留关键结果
- 用户消息：中等优先级，可适当压缩
- 助手消息：低优先级，优先压缩

**核心方法**：
```java
// 压缩消息列表
CompletableFuture<List<Map<String, Object>>> consolidate(List<Map<String, Object>> messages)

// 检查是否需要压缩
boolean needsConsolidation(List<Map<String, Object>> messages)

// 获取当前 token 使用量
int getCurrentUsage(List<Map<String, Object>> messages)
```

### 2.5 长期记忆系统 (Dream)

**功能说明**：实现 AI Agent 的长期记忆功能，允许 Agent 存储和检索长期信息。

**核心功能**：
1. **记忆存储** - 将对话中的关键信息保存到长期记忆
2. **记忆检索** - 根据当前上下文检索相关记忆
3. **记忆整合** - 将新信息与现有记忆融合
4. **记忆清理** - 移除过时或重复的记忆

**记忆结构**：
| 字段 | 说明 |
|------|------|
| id | 唯一标识 |
| content | 记忆内容 |
| keywords | 关键词标签 |
| timestamp | 创建时间 |
| importance | 重要性分数 (0-1) |
| source | 来源会话 ID |

**核心方法**：
```java
// 从对话中提取并存储记忆
CompletableFuture<List<MemoryEntry>> extractAndStore(String sessionId, List<Map<String, Object>> messages)

// 检索相关记忆
CompletableFuture<List<MemoryEntry>> retrieve(String query, int limit)

// 整合新信息到现有记忆
CompletableFuture<MemoryEntry> consolidate(MemoryEntry newMemory)

// 清理过时记忆
void cleanup()
```

### 2.6 多通道接入系统 (ChannelServer)

**功能说明**：提供 HTTP 和 WebSocket 通道的统一管理，允许客户端通过多种方式与 Agent 交互。

**支持的通道类型**：

| 通道类型 | 说明 | 端点 |
|---------|------|------|
| HTTP REST | 同步请求/响应模式 | `/api/chat` |
| WebSocket | 异步双向通信 | `/ws` |

**HTTP API 端点**：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/chat` | POST | 发送消息 |
| `/api/sessions` | GET | 获取会话列表 |
| `/api/sessions/{id}` | DELETE | 删除会话 |

**消息参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `content` | String | 消息内容（必填） |
| `chat_id` | String | 会话 ID（可选） |
| `useSearch` | Boolean | 是否启用联网搜索（可选，默认 true） |

**useSearch 参数说明**：
- `true`（默认）：允许 LLM 调用 web_search 工具进行联网搜索
- `false`：禁用所有工具调用，LLM 将直接回答问题，不进行联网搜索

**使用示例**：
```json
POST /api/chat
{
  "content": "今天天气怎么样？",
  "useSearch": true
}
```

**使用示例**：
```java
ChannelServer server = new ChannelServer(messageBus, 8080);
server.start();
```

### 2.7 状态机流程

`AgentLoop` 采用状态机模式管理消息处理：

```
RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE
    │          │         │        │       │       │         │
    ▼          ▼         ▼        ▼       ▼       ▼         ▼
 恢复会话   压缩历史   命令分发  构建上下文 LLM调用 保存状态 发送响应
```

**状态转换表**：

| 当前状态 | 事件 | 下一状态 | 说明 |
|---------|------|---------|------|
| RESTORE | ok | COMPACT | 加载会话历史 |
| COMPACT | ok | COMMAND | 压缩历史消息 |
| COMMAND | dispatch | BUILD | 分发普通消息 |
| COMMAND | shortcut | DONE | 处理快捷命令（如 `/stop`） |
| BUILD | ok | RUN | 构建 LLM 上下文 |
| RUN | ok | SAVE | 执行 LLM 调用和工具 |
| SAVE | ok | RESPOND | 保存会话状态 |
| RESPOND | ok | DONE | 发送响应 |

### 2.8 核心消息处理全链路详解

> **本节目标**：完整梳理一条用户消息从进入系统到生成响应的全链路，理解每一层的职责、数据流转和关键代码路径。

#### 2.8.1 端到端流程图

```
                        【消息入口层】—— 三种渠道
┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐
│ POST /api/   │  │  WebSocket   │  │ POST /api/chat       │
│ chat (HTTP)  │  │ /ws (原生)    │  │ /api/chat/stream     │
│ ChannelServer│  │ ChannelServer│  │ ChatController(Spring)│
└──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘
       │                  │                      │
       │    构建 InboundMessage(channel, chatId, content, metadata...)
       │                  │                      │
       └──────────────────┼──────────────────────┘
                          │
                          ▼   publishInbound()
              ┌───────────────────────┐
              │     MessageBus        │  ◄── 双队列生产者-消费者模式
              │  inboundQueue.put()   │      ArrayBlockingQueue
              └───────────┬───────────┘
                          │
                          ▼   consumeInbound() — AgentLoop 单线程阻塞消费
              ┌───────────────────────────────────────────────────────┐
              │                   AgentLoop                           │
              │                七状态状态机引擎                         │
              │                                                       │
              │  RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE
              └───────────────────────────────────────────────────────┘
                          │                        ▲
                          │   RUN 状态              │ 递归循环（工具调用）
                          ▼                        │
              ┌───────────────────────────────────────────────────────┐
              │                   AgentRunner                         │
              │               LLM 调用 + 工具执行循环                   │
              │                                                       │
              │  ① 清理孤立的 tool 结果 (dropOrphanToolResults)        │
              │  ② 转换消息格式 → LLMProvider.Message                 │
              │  ③ 调用 LLM API (chat / chatStream)                   │
              │  ④ 解析响应                                           │
              │  ⑤ 如果有 tool_calls → 并行执行工具 → 结果追加 → 回到② │
              │  ⑥ 否则 → 返回最终文本内容                             │
              └─────────────┬─────────────────────────────────────────┘
                            │
                            ▼ 最终响应
              ┌───────────────────────┐
              │     MessageBus        │
              │  outboundQueue.put()  │  ← 出站队列
              └───────────┬───────────┘
                          │
       ┌──────────────────┼──────────────────────┐
       │                  │                      │
       ▼                  ▼                      ▼
  HTTP 同步响应      SSE 流式推送           WebSocket 推送
  waitForSessionResp StreamRespCallback   session.sendText()
```

#### 2.8.2 阶段一：消息入口 —— 三种渠道

系统支持三种渠道接收用户消息，最终都构建 `InboundMessage` 发布到 `MessageBus`。

**渠道 A：Spring Boot REST API**（`controller/ChatController.java`）

| 端点 | 模式 | 机制 |
|------|------|------|
| `POST /api/chat` | 同步 | 发布消息后 `waitForSessionResponse()` 阻塞等待（默认 120s），按 `requestId` 精确匹配 |
| `POST /api/chat/stream` | 流式 SSE | 注册 `StreamResponseCallback` 到 AgentLoop，通过 `SseEmitter` 实时推送每个 token |

**渠道 B：Jakarta WebSocket**（`websocket/NanobotWebSocketEndpoint.java`）

- 端点：`ws://host:port/ws`
- `@OnMessage` 解析 JSON → type 为 `"chat"` → 构建 InboundMessage
- WebSocket 默认启用流式模式（`streamMode: true`）
- 流式响应通过 `StreamResponseCallback` → `session.getBasicRemote().sendText()`

**渠道 C：内嵌 HTTP 服务器**（`channels/ChannelServer.java`）

- 独立模式下的内嵌 HTTP 服务器（`com.sun.net.httpserver.HttpServer`）
- `ChatHandler` 处理 `POST /api/chat`，支持流式 SSE 和同步两种模式
- `WebSocketHandler` 处理 `/ws` 的 WebSocket 升级握手，`WebSocketConnection` 管理帧读写

**InboundMessage 核心字段**：

| 字段 | 说明 |
|------|------|
| `channel` | 来源通道：`"api"`, `"websocket"`, `"http"` 等 |
| `senderId` | 发送者 ID |
| `chatId` | 会话标识，用于会话隔离 |
| `content` | 用户文本内容 |
| `metadata` | 元数据 Map，包含 `requestId`, `streamMode`, `useSearch`, `connectionId` 等 |
| `sessionKey` | 会话键，格式 `"{channel}:{chatId}"`，支持 `sessionKeyOverride` 覆盖 |

#### 2.8.3 阶段二：MessageBus —— 异步消息总线

文件：`bus/MessageBus.java`

MessageBus 是系统的**中枢神经**，采用双队列 + 生产者-消费者模式实现模块解耦：

```
Channel Adapters ──(publishInbound)──→ inboundQueue  ──(consumeInbound)──→ AgentLoop
AgentLoop ────────(publishOutbound)──→ outboundQueue ──(consumeOutbound)──→ Channel Adapters
                                      sessionResponses (ConcurrentHashMap, 按 requestId 匹配)
```

| 方法 | 说明 |
|------|------|
| `publishInbound(msg)` | 阻塞写入入站队列（`put`），队列满时阻塞等待 |
| `consumeInbound(timeout)` | 带超时的阻塞消费，AgentLoop 主循环使用 |
| `publishOutbound(msg)` | 阻塞写入出站队列 + 同步存入 `sessionResponses` Map |
| `offerOutbound(msg)` | 非阻塞写入出站队列（`offer`），用于流式进度消息，队列满时静默丢弃 |
| `waitForSessionResponse(sessionId, requestId, timeout)` | HTTP 同步模式专用：轮询 `sessionResponses` Map，按 `requestId` 精确匹配 |

**设计要点**：
- 使用 `ArrayBlockingQueue`（有界队列），默认容量 100，防止内存溢出
- 入站单线程消费（AgentLoop 的 daemon 线程），保证消息串行处理
- `sessionResponses` 用 `ConcurrentHashMap<String, Queue<OutboundMessage>>` 存储，支持按 sessionId + requestId 精确匹配响应

#### 2.8.4 阶段三：AgentLoop —— 七状态状态机引擎

文件：`core/AgentLoop.java`

AgentLoop 是整个系统的**核心引擎**，在单 daemon 线程中运行主循环：

```java
// AgentLoop.runLoop() — 主循环
while (running.get()) {
    InboundMessage message = messageBus.consumeInbound(1, TimeUnit.SECONDS);
    if (message != null) {
        processMessage(message);  // 创建 TurnContext → 驱动状态机 → 发送响应
    }
}
```

**状态机七阶段详解**：

| # | 状态 | 职责 | 核心操作 | 下一状态 |
|---|------|------|---------|---------|
| 1 | **RESTORE** | 恢复会话 | `sessionManager.loadHistory(sessionKey)` → 从 JSONL 文件加载历史消息；将当前用户消息追加到消息列表 | COMPACT |
| 2 | **COMPACT** | 压缩历史 | 检查 token 预算，超限时调用 LLM 生成摘要压缩旧消息（当前为 TODO，直接跳过） | COMMAND |
| 3 | **COMMAND** | 命令分发 | 解析 `/` 前缀：匹配 Skills（`skillManager.parseSlashCommand()`）、`/stop`、`/clear`、`/skills`、`/rules`；命中命令则直接返回，否则继续 | BUILD 或 DONE |
| 4 | **BUILD** | 构建上下文 | 组装 System Prompt：注入身份信息（`IdentityManager.getSystemPrompt()`）+ 规则（`RuleManager.getRulesPrompt()`）+ 工具指令；根据 `useSearch` 元数据决定是否启用工具 | RUN |
| 5 | **RUN** | 运行 LLM | 设置流式回调 `onDelta` → 调用 `AgentRunner.run(context, messages, onDelta)` → 等待结果 → 发送流式结束标记 | SAVE |
| 6 | **SAVE** | 保存状态 | 将助手响应追加到消息历史 → `sessionManager.saveHistory(sessionKey, messages)` → 写入 JSONL 文件 | RESPOND |
| 7 | **RESPOND** | 发送响应 | 构建 `OutboundMessage` → `messageBus.publishOutbound(response)` → 发布到出站队列 | DONE |

**流式回调机制**（在 `doRun()` 中设置）：

```java
onDelta = delta -> {
    // 路径1：发布进度消息到 MessageBus（供 ChannelServer WebSocket 消费）
    publishProgress(OutboundMessage.progress(channel, chatId, delta));

    // 路径2：调用 StreamResponseCallback（供 SSE/WebSocket 直接推送）
    if (streamResponseCallback != null) {
        streamResponseCallback.onStreamData(sessionId, requestId, delta);
    }
};
```

**System Prompt 构建过程**（`doBuild()`）：

```
System Prompt =
    IdentityManager.getSystemPrompt(currentDate)    // SOUL.md + IDENTITY.md + USER.md
    + (useSearch ? 工具调用指令 : "请直接回答，不要调用工具")
    + RuleManager.getRulesPrompt()                   // CLAUDE.md + .nanobot/rules/*.md
```

#### 2.8.5 阶段四：AgentRunner —— LLM 调用 + 工具执行循环

文件：`core/AgentRunner.java`

AgentRunner 是**最核心的执行引擎**，采用递归模式实现"LLM 调用 → 工具执行 → 再调用"的多轮循环。

**递归循环流程**：

```
runInternal(context, messages, onDelta, iteration=0)
    │
    ├─ 1. 终止条件检查
    │     - iteration >= maxIterations (默认100) → 返回 "达到最大次数限制"
    │     - context.isCancelled() → 返回 "处理已取消"
    │
    ├─ 2. dropOrphanToolResults(messages)
    │     清理没有对应 tool_calls 的孤立 tool 结果消息
    │
    ├─ 3. convertToLLMMessages(messages)
    │     Map<String,Object> → LLMProvider.Message
    │
    ├─ 4. 调用 LLM API
    │     if (streamMode && provider.supportsStreaming())
    │         provider.chatStream(messages, toolDefs, onDelta)
    │     else
    │         provider.chat(messages, toolDefs)
    │
    ├─ 5. 解析 LLMResponse
    │     ├─ isError() → 返回错误信息
    │     │
    │     ├─ shouldExecuteTools() → 有工具调用
    │     │   ├─ 检查 useSearch 过滤（禁用联网时过滤掉非 ask_user 的工具）
    │     │   ├─ 创建 assistant 消息（含 tool_calls）追加到 messages
    │     │   ├─ executeTools() — 并行执行所有工具（CompletableFuture.allOf）
    │     │   │   └─ 每个工具：executeToolWithRetry() 最多重试3次
    │     │   │       结果截断至 16000 字符
    │     │   │       追加 tool 角色消息到 messages
    │     │   └─ return runInternal(context, messages, onDelta, iteration+1)  ← 递归
    │     │
    │     └─ 最终文本响应 → 追加 assistant 消息 → 返回 content
    │
    └─ 6. 返回 CompletableFuture<String>
```

**工具执行细节**：

```java
// executeTools() — 并行工具执行
for (ToolCallRequest call : toolCalls) {
    CompletableFuture.runAsync(() -> {
        Object result = executeToolWithRetry(toolName, params, callId);
        // 截断过长结果 (>16000字符)
        // 追加 tool 角色消息: {role: "tool", tool_call_id: id, content: result}
    }, toolExecutor);
}
return CompletableFuture.allOf(futures);  // 等待全部完成

// executeToolWithRetry() — 带重试机制
// - 最大重试3次，重试间隔1秒
// - 仅对网络错误 (ConnectException, IOException) 和超时重试
// - 其他异常直接返回错误信息
// - 每次执行有30秒超时保护
```

#### 2.8.6 阶段五：LLM Provider 调用

接口：`providers/LLMProvider.java`
实现：`providers/impl/DeepSeekProvider.java`、`providers/impl/OpenAIProvider.java`

**DeepSeekProvider 调用流程**（当前主要使用的 provider）：

```
chatStream(messages, tools, onDelta)
    │
    ├─ buildRequestBody(messages, tools, stream=true)
    │     POST https://api.deepseek.com/chat/completions
    │     Body: {model, messages, tools, stream: true, max_tokens, temperature}
    │
    ├─ 发送 HTTP 请求 (Java HttpClient, 超时300s)
    │
    ├─ 逐行解析 SSE 响应流
    │     data: {"choices":[{"delta":{"content":"..."}}]}
    │     data: {"choices":[{"delta":{"tool_calls":[...]}}]}
    │     data: [DONE]
    │
    ├─ 每收到 content delta → onDelta.accept(content) → 流式推送给用户
    │
    └─ 返回 LLMResponse
          ├─ 有 tool_calls → LLMResponse.toolCalls(...)
          └─ 纯文本     → LLMResponse.success(content, finishReason)
```

**LLMResponse 三种类型**：

| 类型 | `shouldExecuteTools()` | `isError()` | 含义 |
|------|----------------------|-------------|------|
| `success(content, ...)` | false | false | 最终文本响应 |
| `toolCalls(list, model)` | true | false | LLM 请求执行工具 |
| `error(msg, kind)` | false | true | API 调用失败 |

#### 2.8.7 阶段六：响应返回

响应通过 `OutboundMessage` 封装，按请求渠道和模式分三种路径返回：

| 模式 | 机制 | 代码路径 | 适用场景 |
|------|------|---------|---------|
| **HTTP 同步** | `MessageBus.waitForSessionResponse(sessionId, requestId, 120s)` 轮询匹配 OutboundMessage | ChatController.chat() / ChannelServer.ChatHandler | `POST /api/chat` |
| **SSE 流式** | `AgentLoop.StreamResponseCallback.onStreamData()` → `SseEmitter.send()` | ChatController.streamChat() → AgentLoop.doRun() → onDelta | `POST /api/chat/stream` |
| **WebSocket** | `StreamResponseCallback.onStreamData()` → `Session.getBasicRemote().sendText()` | NanobotWebSocketEndpoint.onMessage() → AgentLoop.doRun() → onDelta | `ws://host:port/ws` |

**OutboundMessage 特殊标记**（metadata 中以 `_` 开头的系统标记）：

| 标记 | 含义 |
|------|------|
| `_progress` | 流式输出的进度消息（中间结果） |
| `_stream_delta` | 流式消息片段 |
| `_stream_end` | 流式消息结束标记 |
| `_streamed` | 消息已完整流式发送 |
| `_tool_hint` | 工具调用提示 |
| `_wants_stream` | 请求启用流式输出 |

#### 2.8.8 完整数据流示例

以用户发送 `"帮我搜索最新AI新闻"` 为例：

```
用户输入 "帮我搜索最新AI新闻"
    │
    ▼
[ChatController.streamChat()] 
    构建 InboundMessage {channel:"api", chatId:"xxx", content:"帮我搜索最新AI新闻",
                         metadata:{requestId:"uuid", streamMode:true, useSearch:true}}
    │
    ▼
[MessageBus.publishInbound()] → inboundQueue.put(msg)
    │
    ▼
[AgentLoop.consumeInbound()] → processMessage(msg)
    │
    ├─ [RESTORE] SessionManager.loadHistory() → 加载历史消息 (JSONL)
    │     messages = [{role:"system",...}, {role:"user",...}, ...]
    │
    ├─ [COMPACT] (skip)
    │
    ├─ [COMMAND] 不是 / 命令 → 继续
    │
    ├─ [BUILD] 构建 System Prompt:
    │     IdentityManager.getSystemPrompt()   ← SOUL.md: "你是 my-nanobot..."
    │     + "你可以调用工具...包括网页搜索"       ← useSearch=true
    │     + RuleManager.getRulesPrompt()       ← 项目规则
    │
    ├─ [RUN] AgentRunner.run(context, messages, onDelta)
    │     │
    │     ├─ 第1轮 LLM 调用 (iteration=0)
    │     │   POST DeepSeek API: messages + tools[{web_search,...}]
    │     │   LLM 返回: tool_calls:[{name:"web_search", args:{query:"最新AI新闻"}}]
    │     │
    │     ├─ executeTools() 并行执行
    │     │   WebSearchTool.execute({query:"最新AI新闻"})
    │     │   → HTTP 请求搜索引擎 → 返回 [{title:"...", url:"...", snippet:"..."}, ...]
    │     │
    │     ├─ 第2轮 LLM 调用 (iteration=1) — 递归
    │     │   messages 追加了 tool_calls assistant消息 + tool结果消息
    │     │   LLM 基于搜索结果生成回答:
    │     │   "根据最新搜索，以下是近期AI领域的重大新闻：1. ... 2. ..."
    │     │   → 纯文本响应，无 tool_calls
    │     │
    │     └─ 返回最终内容
    │
    ├─ [SAVE] SessionManager.saveHistory() → 写入 JSONL
    │
    └─ [RESPOND] OutboundMessage → MessageBus.outboundQueue
                    │
                    ▼
              SSE 流式推送给客户端 (实时展示)
```

#### 2.8.9 两种运行模式对比

| 维度 | 独立模式 (Nanobot) | Spring Boot 模式 (NanobotApplication) |
|------|-------------------|--------------------------------------|
| 入口类 | `Nanobot.java` | `NanobotApplication.java` |
| 初始化 | `Nanobot.initialize()` 手动组装 | `NanobotRunner.run()` + Spring DI 自动注入 |
| HTTP 服务器 | `ChannelServer`（内嵌 `com.sun.net.httpserver`） | Spring MVC + 内嵌 Tomcat |
| WebSocket | `ChannelServer.WebSocketHandler`（手动帧解析） | `NanobotWebSocketEndpoint`（Jakarta WebSocket 注解） |
| REST API | `ChannelServer.ChatHandler` | `ChatController`（`@RestController`） |
| 配置管理 | `ConfigLoader.load()` | Spring `@ConfigurationProperties` + `application.yml` |
| 组件获取 | 直接引用字段 | `NanobotRunner.getXxx()` 静态方法 / Spring `@Autowired` |
| 核心流程 | **完全一致** — AgentLoop + AgentRunner + MessageBus | **完全一致** — AgentLoop + AgentRunner + MessageBus |

#### 2.8.10 关键设计决策与要点

| 设计决策 | 说明 |
|---------|------|
| **单线程消费入站消息** | AgentLoop 在单 daemon 线程运行，保证同一会话消息串行处理，避免并发修改会话历史 |
| **有界阻塞队列** | `ArrayBlockingQueue`（默认 100），防止生产者过快导致 OOM |
| **递归而非循环** | AgentRunner 用递归实现多轮工具调用，每轮自然携带更新后的 messages |
| **工具并行执行** | 同一轮次的多个 tool_calls 并行执行（`CompletableFuture.allOf`），减少等待时间 |
| **流式回调双路径** | `onDelta` 同时走 MessageBus（WebSocket 广播）和 StreamResponseCallback（SSE/直接 WebSocket），覆盖两种推送场景 |
| **会话级锁** | SessionManager 用 `ConcurrentHashMap<String, Object>` 实现会话级 synchronized，确保读写历史文件串行 |
| **增量保存历史** | `saveHistory()` 只追加新增消息（对比已有行数），而非全量覆写 |

---

### 2.9 模块结构

```
nanobot-java/
├── src/main/java/com/nanobot/
│   ├── Nanobot.java           # 主入口
│   ├── bus/                   # 消息总线
│   │   ├── MessageBus.java
│   │   ├── InboundMessage.java
│   │   └── OutboundMessage.java
│   ├── config/                # 配置系统
│   │   ├── Config.java
│   │   └── ConfigLoader.java
│   ├── core/                  # 核心引擎
│   │   ├── AgentLoop.java
│   │   ├── AgentRunner.java
│   │   ├── TurnContext.java
│   │   ├── TurnState.java
│   │   └── hook/              # 钩子系统
│   ├── hook/
│   │   │   ├── AgentHook.java
│   │   │   ├── AgentHookContext.java
│   │   │   ├── CompositeHook.java
│   │   │   ├── HookLoader.java
│   │   │   └── impl/
│   │   │       ├── MetricsHook.java    # 指标收集钩子
│   │   │       ├── TracingHook.java    # 链路追踪钩子
│   │   │       └── ValidationHook.java # 内容验证钩子
│   │   └── subagent/
│   │       ├── Subagent.java                    # 子 Agent 接口
│   │       ├── SubagentContext.java             # 子 Agent 上下文
│   │       ├── SubagentCommunication.java       # 子 Agent 通信管理器
│   │       ├── AgentCoordinator.java            # Agent 协调器
│   │       └── impl/
│   │           └── SimpleSubagent.java          # 简单子 Agent 实现
│   ├── channels/              # 多通道接入
│   │   └── ChannelServer.java
│   ├── cron/                   # 定时任务系统
│   │   └── CronScheduler.java
│   ├── memory/                # 内存存储
│   │   ├── MemoryStore.java
│   │   ├── Consolidator.java   # 记忆压缩器
│   │   └── Dream.java          # 长期记忆系统
│   ├── mcp/                   # MCP (Model Context Protocol)
│   │   ├── MCPClient.java
│   │   ├── StdioMCPClient.java
│   │   ├── HttpMCPClient.java
│   │   ├── MCPManager.java
│   │   ├── MCPToolWrapper.java
│   │   ├── MCPToolInfo.java
│   │   ├── MCPResult.java
│   │   └── MCPMessage.java
│   ├── providers/             # LLM提供商
│   │   ├── LLMProvider.java
│   │   ├── LLMResponse.java
│   │   └── impl/
│   │       └── OpenAIProvider.java
│   ├── session/               # 会话管理
│   │   └── SessionManager.java
│   └── tools/                 # 工具系统
│       ├── Tool.java
│       ├── ToolRegistry.java
│       ├── Schema.java
│       └── impl/
│           ├── ReadFileTool.java
│           ├── WriteFileTool.java
│           ├── EditFileTool.java
│           ├── ListDirTool.java
│       │   │   ├── GlobTool.java
│   │   ├── GrepTool.java
│   │   ├── ExecTool.java
│   │   ├── WebSearchTool.java    # 网页搜索工具（支持百度、Brave、Bing）
│   │   └── WebFetchTool.java     # 网页内容抓取工具（已禁用）
├── src/main/resources/
│   ├── config/
│   │   └── config.yaml
│   └── logback.xml
└── pom.xml
```

### 2.5 核心接口设计

#### Tool 接口

工具是 Agent 与外部世界交互的核心方式：

```java
public interface Tool {
    String getName();                    // 工具名称
    String getDescription();             // 工具描述
    JsonNode getParameters();            // 参数 JSON Schema
    boolean isReadOnly();                // 是否只读
    boolean isExclusive();               // 是否独占执行
    CompletableFuture<Object> execute(Map<String, Object> params);  // 执行方法
}
```

#### LLMProvider 接口

统一的 LLM API 调用接口：

```java
public interface LLMProvider {
    String getName();
    CompletableFuture<LLMResponse> chat(List<Message> messages, List<JsonNode> tools);
    CompletableFuture<LLMResponse> chatStream(List<Message> messages, 
                                               List<JsonNode> tools, 
                                               Consumer<String> onDelta);
}
```

#### AgentHook 接口

生命周期钩子扩展点：

```java
public interface AgentHook {
    void onMessageReceived(TurnContext context);
    void onMessageProcessed(TurnContext context);
    void onToolCalled(TurnContext context, String toolName, Map<String, Object> params);
    void onError(TurnContext context, Throwable error);
}
```

#### MCP 相关接口

**MCPClient 接口** - MCP 客户端接口：

```java
public interface MCPClient {
    CompletableFuture<MCPResult> callTool(String toolName, Map<String, Object> arguments);
    CompletableFuture<List<MCPToolInfo>> listTools();
    CompletableFuture<MCPResult> readResource(String uri);
    CompletableFuture<MCPResult> getPrompt(String promptName, Map<String, Object> arguments);
    void close();
    boolean isConnected();
    String getServerName();
}
```

**MCPToolWrapper 类** - 将 MCP 工具包装为 Nanobot Tool：

```java
public class MCPToolWrapper implements Tool {
    private final MCPClient client;
    private final MCPToolInfo toolInfo;
    private final String qualifiedName;  // mcp_<server>_<tool>
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return client.callTool(toolInfo.getName(), params)
            .thenApply(result -> result.toString());
    }
}
```

---

## 二、架构说明（续）

### 2.6 MCP (Model Context Protocol) 系统

**MCP**（Model Context Protocol）是由 Cursor 编辑器提出的标准化协议，用于连接 AI Agent 与外部工具/服务。Nanobot 通过 MCP 支持，可以动态加载和使用第三方工具，而无需修改核心代码。

#### MCP 架构

```
┌─────────────────────────────────────────────────────────────┐
│                      MCP 服务器层                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐      │
│  │ Git MCP  │  │ 天气 MCP │  │  其他 MCP 服务...     │      │
│  └────┬─────┘  └────┬─────┘  └──────────┬───────────┘      │
│       │             │                    │                  │
└───────┼─────────────┼────────────────────┼─────────────────┘
        │             │                    │
        ▼             ▼                    ▼
┌─────────────────────────────────────────────────────────────┐
│                    MCP 客户端层                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │   StdioMCPClient    │    HttpMCPClient               │   │
│  │   (进程间通信)       │    (HTTP/SSE 通信)            │   │
│  └──────────────────────────────────────────────────────┘   │
│                          │                                  │
│                          ▼                                  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                  MCPManager                           │   │
│  │  - 管理多个 MCP 服务器连接                            │   │
│  │  - 自动发现和注册工具                                 │   │
│  │  - 统一生命周期管理                                   │   │
│  └──────────────────────────────────────────────────────┘   │
│                          │                                  │
└──────────────────────────┼──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    ToolRegistry                            │
│        MCP 工具被包装为标准 Tool 注册到此处                  │
└─────────────────────────────────────────────────────────────┘
```

#### 支持的传输方式

| 传输类型 | 说明 | 适用场景 |
|---------|------|---------|
| **stdio** | 通过标准输入输出与进程通信 | 本地 MCP 服务器（如 git-mcp） |
| **sse** | Server-Sent Events | 实时推送场景 |
| **streamableHttp** | HTTP 流式传输 | 远程 MCP 服务 |

#### MCP 工具命名规则

注册后的工具名称格式：`mcp_<server_name>_<tool_name>`

例如：
- 服务器名 `git` + 工具名 `status` → `mcp_git_status`
- 服务器名 `weather` + 工具名 `get` → `mcp_weather_get`

---

### 2.7 Skills 技能系统

**Skills** 是参考 Claude Code 设计的可复用技能系统，允许用户定义可复用的工作流、指令集和领域知识。Skills 可以通过斜杠命令手动调用，也可以根据对话场景自动触发。

#### Skills 架构

```
┌─────────────────────────────────────────────────────────────┐
│                     技能存储层                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ 项目级技能   │  │  用户级技能  │  │  内置技能    │      │
│  │ .nanobot/    │  │ ~/.nanobot/  │  │  (内置)     │      │
│  │ skills/      │  │ skills/      │  │             │      │
│  └────┬────────┘  └────┬────────┘  └────┬─────────┘      │
│       │                 │                │                  │
└───────┼────────────────┼────────────────┼─────────────────┘
        │                 │                │
        ▼                 ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                    SkillLoader                             │
│        加载 SKILL.md 文件，解析元数据和内容                   │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   SkillRegistry                             │
│        管理所有已注册的技能，支持按名称查找和场景匹配          │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   SkillManager                              │
│        技能管理器：加载、匹配、执行技能                       │
└─────────────────────────────────────────────────────────────┘
```

#### 技能目录结构

```
.nanobot/skills/my-skill/
├── SKILL.md          # 必需：技能定义文件
├── templates/        # 可选：模板文件
├── scripts/          # 可选：辅助脚本
└── resources/        # 可选：资源文件
```

#### SKILL.md 格式

```markdown
---
name: code-review
description: 代码审查：检查代码质量、安全性和最佳实践
argument-hint: "[file-path]"
auto-trigger: true
---
# 代码审查指南

## 审查要点

1. **代码风格**
   - 检查命名规范
   - 检查代码格式
   - 检查注释完整性

2. **安全性**
   - SQL 注入风险
   - 敏感信息泄露
   - 输入验证

3. **性能**
   - 不必要的循环
   - 重复计算
   - 资源泄漏
```

#### 技能类型

| 类型 | 说明 | 示例 |
|------|------|------|
| **参考型** | API/Library 使用参考 | 内部库使用说明 |
| **验证型** | 产品验证和测试 | 注册流程验证 |
| **数据型** | 数据获取和分析 | 指标查询 |
| **自动化型** | 业务流程自动化 | 部署流程 |
| **审查型** | 代码审查和安全检查 | 代码审查、安全扫描 |

#### 调用方式

**1. 手动调用（斜杠命令）**
```
/code-review src/main/java/
/doc-generator
/refactor
```

**2. 自动触发**
当对话内容匹配技能描述中的关键词时，技能会自动触发。

**3. 带参数调用**
```
/code-review src/main/java/MyClass.java
```

#### 核心组件

| 组件 | 职责 | 文件位置 |
|------|------|----------|
| **Skill** | 技能接口定义 | `skill/Skill.java` |
| **SkillMetadata** | 技能元数据 | `skill/SkillMetadata.java` |
| **SkillLoader** | 技能加载器 | `skill/SkillLoader.java` |
| **SkillRegistry** | 技能注册中心 | `skill/SkillRegistry.java` |
| **SkillManager** | 技能管理器 | `skill/SkillManager.java` |

#### 使用示例

```java
// 创建技能管理器
SkillManager skillManager = new SkillManager(config);

// 加载技能
skillManager.loadSkills();

// 手动调用技能
String result = skillManager.executeSkill("code-review", context, "src/main/java/");

// 解析斜杠命令
SkillManager.SkillCall call = skillManager.parseSlashCommand("/review src/main/");
if (call != null) {
    String skillResult = skillManager.executeSkill(call.skillName(), context, call.args());
}

// 查找匹配的技能（自动触发）
List<Skill> matches = skillManager.findMatchingSkills("帮我审查代码");
```

---

### 2.8 Rules 规则系统

**Rules** 是参考 Claude Code 的设计理念实现的全局规则系统，通过自然语言指令定义 Agent 的行为规范。规则告诉模型"在这个项目中你应该遵循什么规范"。

#### Rules 架构

```
┌─────────────────────────────────────────────────────────────┐
│                     规则存储层                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   CLAUDE.md  │  │ .nanobot/    │  │ ~/.nanobot/  │      │
│  │ (项目根目录)  │  │ rules/*.md   │  │ rules/*.md   │      │
│  └────┬────────┘  └────┬────────┘  └────┬─────────┘      │
│       │                 │                │                  │
└───────┼────────────────┼────────────────┼─────────────────┘
        │                 │                │
        ▼                 ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                    RuleLoader                              │
│        加载规则文件，解析元数据和内容                        │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   RuleRegistry                             │
│        管理所有已注册的规则，支持按优先级排序和筛选            │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   RuleManager                              │
│        规则管理器：加载、合并、生成提示词                     │
└─────────────────────────────────────────────────────────────┘
```

#### 规则文件位置

| 级别 | 路径 | 优先级 | 说明 |
|------|------|--------|------|
| **项目级** | `CLAUDE.md` | 高 | 项目根目录下的规则文件 |
| **项目级** | `.nanobot/rules/*.md` | 中 | 项目专属规则目录 |
| **用户级** | `~/.nanobot/rules/*.md` | 低 | 当前用户全局规则 |
| **内置** | Built-in | 最低 | 系统默认规则 |

#### 规则文件格式

```markdown
---
name: coding-style
description: Java 代码风格规范
priority: 10
enabled: true
tags: [java, coding]
---
# Java 代码风格规范

## 命名规范
- 类名：使用 PascalCase
- 方法名：使用 camelCase
- 变量名：使用 camelCase

## 格式规范
- 每行代码不超过 120 字符
- 使用 4 空格缩进
```

#### 规则类型

| 类型 | 说明 | 示例 |
|------|------|------|
| **代码风格** | 代码命名和格式规范 | 命名规范、缩进规则 |
| **安全规则** | 安全编码规范 | 输入验证、敏感信息保护 |
| **响应规则** | 回复格式规范 | 语言要求、输出格式 |
| **业务规则** | 特定业务规则 | 项目特定规范 |

#### 优先级机制

规则按优先级排序后合并到系统提示词中：
- 数字越小，优先级越高
- 高优先级规则会在提示词中靠前显示
- 内置规则默认优先级为 100-150

#### 核心组件

| 组件 | 职责 | 文件位置 |
|------|------|----------|
| **Rule** | 规则接口定义 | `rules/Rule.java` |
| **RuleLoader** | 规则加载器 | `rules/RuleLoader.java` |
| **RuleRegistry** | 规则注册中心 | `rules/RuleRegistry.java` |
| **RuleManager** | 规则管理器 | `rules/RuleManager.java` |

#### 使用示例

**1. 创建项目级规则文件 CLAUDE.md**：
```markdown
---
name: project-guide
description: 项目指南
priority: 5
---
# 项目指南

## 关于此项目
这是一个 Nanobot-Java AI Agent 项目。

## 开发规范
- 使用 Java 21
- 遵循 Spring Boot 代码风格
- 编写单元测试
```

**2. 创建规则目录**：
```bash
mkdir -p .nanobot/rules
touch .nanobot/rules/coding-style.md
```

**3. 代码中使用**：
```java
RuleManager ruleManager = new RuleManager(config);
ruleManager.loadRules();

// 获取合并后的规则提示词
String rulesPrompt = ruleManager.getRulesPrompt();

// 将规则注入到系统提示词中
String systemPrompt = "你是一个 AI Agent。\n\n" + rulesPrompt;
```

---

## 三、新手学习路线

### 3.1 学习阶段规划

| 阶段 | 目标 | 时间 | 关键知识点 |
|------|------|------|-----------|
| **Phase 1** | 环境搭建与入门 | 1-2 天 | Java 21, Maven, 项目结构 |
| **Phase 2** | 核心组件理解 | 3-5 天 | 状态机、消息总线、工具系统 |
| **Phase 3** | 深入核心机制 | 3-5 天 | LLM 调用、会话管理、钩子系统 |
| **Phase 4** | 扩展开发 | 3-5 天 | 自定义工具、新提供商、通道扩展 |

### 3.2 Phase 1：环境搭建与入门

**目标**：搭建开发环境，了解项目结构，成功运行项目

#### 步骤 1：环境准备

- 安装 JDK 21+
- 安装 Maven 3.9+
- 配置 IDE（IntelliJ IDEA 推荐）

#### 步骤 2：项目结构探索

```bash
cd d:\IdeaProjects\个人项目\ai-vibe-coding\nanobot-java
```

查看目录结构，理解各模块职责：
- `src/main/java/com/nanobot/` - 源代码
- `src/main/resources/` - 配置文件
- `pom.xml` - Maven 依赖管理

#### 步骤 3：配置运行

1. 配置 OpenAI API Key：
   - 创建 `~/.nanobot/config.yaml`
   - 或设置环境变量 `OPENAI_API_KEY`

2. 编译项目：
```bash
mvn clean compile
```

3. 运行项目：
```bash
mvn exec:java -Dexec.mainClass="com.nanobot.Nanobot"
```

### 3.3 Phase 2：核心组件理解

**目标**：理解状态机、消息总线、工具系统的设计

#### 学习路径：

1. **Nanobot.java** - 主入口
   - 组件初始化流程
   - 生命周期管理

2. **AgentLoop.java** - 状态机引擎
   - 状态转换逻辑
   - 消息处理流程

3. **MessageBus.java** - 消息总线
   - 异步队列机制
   - 入站/出站消息模型

4. **Tool.java + ToolRegistry.java** - 工具系统
   - 工具接口设计
   - 工具注册与执行

#### 实践练习：

1. 跟踪一条消息从进入到响应的完整流程
2. 添加一个简单的自定义工具
3. 调试状态机转换过程

### 3.4 Phase 3：深入核心机制

**目标**：理解 LLM 调用、会话管理、钩子系统

#### 学习路径：

1. **AgentRunner.java** - LLM 调用循环
   - 多轮工具调用
   - 上下文治理

2. **LLMProvider.java** - 提供商接口
   - 流式与非流式调用
   - 工具调用处理

3. **SessionManager.java** - 会话管理
   - 历史存储与加载
   - 会话隔离

4. **AgentHook.java** - 钩子系统
   - 生命周期扩展点
   - CompositeHook 组合模式

#### 实践练习：

1. 实现一个自定义 LLMProvider
2. 添加会话压缩逻辑
3. 实现一个监控钩子

### 3.5 Phase 4：扩展开发

**目标**：能够扩展新功能

#### 学习路径：

1. **添加新工具**
   - 实现 Tool 接口
   - 注册到 ToolRegistry

2. **添加新提供商**
   - 实现 LLMProvider 接口
   - 处理不同 API 格式

3. **添加新通道**（未来扩展）
   - 实现通道接口
   - 集成到消息总线

#### 实践练习：

1. 创建一个天气查询工具
2. 实现 Anthropic 提供商
3. 添加 WebSocket 通道

---

## 四、关键技术选型

| 功能 | 技术方案 | 理由 |
|------|---------|------|
| **异步编程** | Virtual Threads + CompletableFuture | Java 21 新特性，高性能并发 |
| **JSON 处理** | Jackson | 成熟稳定，支持 Schema 验证 |
| **HTTP 客户端** | HttpClient (Java 11+) | 内置，支持异步 |
| **配置管理** | Jackson + YAML | 支持复杂嵌套配置 |
| **日志** | SLF4J + Logback | Java 标准日志框架 |
| **定时任务** | ScheduledExecutorService | 内置定时能力 |
| **文件 I/O** | NIO.2 (Files/Paths) | 现代文件 API |

---

## 五、配置说明

### 5.1 配置文件位置

```
~/.nanobot/config.yaml
```

或通过命令行指定：
```bash
java -jar nanobot-java.jar --config /path/to/config.yaml
```

### 5.2 配置结构

```yaml
agents:
  defaults:
    model: gpt-4o-mini
    workspace: ~/.nanobot/workspace
    max_tokens: 4096
    temperature: 0.7
    max_tool_iterations: 10

providers:
  openai:
    api_key: ${OPENAI_API_KEY}
    api_base: https://api.openai.com/v1

tools:
  exec:
    enable: false
  
  web:
    enable: true
    search:
      provider: "baidu_web"  # baidu_web: 百度公开接口（国内可访问，无需API Key）
                            # baidu: 百度API（需要API Key）
                            # brave: Brave Search（需要API Key）
                            # bing: Bing Search（需要API Key）
      apiKey: ""            # API Key（当使用 baidu/brave/bing 时需要配置）
      maxResults: 5
      timeout: 30

# MCP 服务器配置
mcp_servers:
  # stdio 模式 - 通过命令启动 MCP 服务器
  git:
    command: "npx"
    args: ["-y", "git-mcp@latest"]
    tool_timeout: 30
    enabled_tools: ["*"]
  
  # HTTP 模式 - 连接远程 MCP 服务器
  weather:
    type: "streamableHttp"
    url: "https://api.example.com/mcp"
    headers:
      Authorization: "Bearer token"

channels:
  send_progress: true
```

### 5.3 环境变量

| 变量名 | 说明 |
|--------|------|
| `OPENAI_API_KEY` | OpenAI API 密钥 |
| `NANOBOT_MODEL` | 默认模型 |
| `NANOBOT_WORKSPACE` | 工作目录 |

---

## 六、编译、启动与部署

### 6.1 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| Java | 21+ | 核心运行环境 |
| Maven | 3.9+ | 构建工具 |
| Git | 2.x | 版本控制（可选） |

### 6.2 编译项目

```bash
# 编译项目
mvn clean compile

# 运行测试
mvn test

# 打包项目（包含依赖）
mvn clean package

# 打包跳过测试
mvn clean package -DskipTests
```

### 6.3 启动运行

#### 开发模式运行

```bash
# 使用 exec 插件运行
mvn exec:java -Dexec.mainClass="com.nanobot.Nanobot"

# 指定配置文件
mvn exec:java -Dexec.mainClass="com.nanobot.Nanobot" -Dexec.args="--config path/to/config.yaml"
```

#### 生产模式运行

```bash
# 打包后运行
java -jar target/nanobot-1.0.0.jar

# 指定配置文件
java -jar target/nanobot-1.0.0.jar --config path/to/config.yaml

# 设置 JVM 参数
java -Xmx2g -Xms512m -jar target/nanobot-1.0.0.jar
```

### 6.4 配置环境变量

```bash
# Linux/Mac
export OPENAI_API_KEY=your-api-key
export NANOBOT_MODEL=gpt-4o-mini
export NANOBOT_WORKSPACE=~/.nanobot/workspace

# Windows PowerShell
$env:OPENAI_API_KEY="your-api-key"
$env:NANOBOT_MODEL="gpt-4o-mini"
$env:NANOBOT_WORKSPACE="~/.nanobot/workspace"
```

### 6.5 Docker 部署

**Dockerfile 示例**：

```dockerfile
FROM openjdk:21-jdk-slim

WORKDIR /app

COPY target/nanobot-1.0.0.jar app.jar

ENV OPENAI_API_KEY=your-api-key
ENV NANOBOT_MODEL=gpt-4o-mini

CMD ["java", "-jar", "app.jar"]
```

**构建和运行**：

```bash
# 构建镜像
docker build -t nanobot .

# 运行容器
docker run -d \
  --name nanobot \
  -e OPENAI_API_KEY=your-api-key \
  -v ~/.nanobot:/root/.nanobot \
  nanobot
```

### 6.6 常见启动问题

| 问题 | 解决方案 |
|------|----------|
| API Key 未设置 | 检查环境变量 `OPENAI_API_KEY` |
| 端口被占用 | 修改配置中的端口或停止占用进程 |
| 依赖下载失败 | 使用 `mvn clean compile -U` 强制更新 |
| 内存不足 | 增加 JVM 堆内存 `-Xmx2g` |

---

## 七、调试与日志

### 7.1 日志配置

日志配置文件：`src/main/resources/logback.xml`

日志级别：
- `DEBUG` - 详细调试信息
- `INFO` - 一般信息
- `WARN` - 警告
- `ERROR` - 错误

### 7.2 调试技巧

1. 设置断点跟踪状态机转换
2. 查看消息队列的入队出队
3. 监控 LLM 调用的请求/响应
4. 使用钩子记录执行时间

---

## 七、扩展建议

### 7.1 添加新工具

```java
public class WeatherTool implements Tool {
    @Override
    public String getName() { return "get_weather"; }
    
    @Override
    public String getDescription() { 
        return "获取指定城市的天气信息"; 
    }
    
    @Override
    public JsonNode getParameters() { /* JSON Schema */ }
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        String city = (String) params.get("city");
        // 调用天气 API
        return CompletableFuture.completedFuture("天气结果...");
    }
}

// 注册
toolRegistry.register(new WeatherTool());
```

### 7.2 添加新提供商

```java
public class AnthropicProvider implements LLMProvider {
    @Override
    public String getName() { return "anthropic"; }
    
    @Override
    public CompletableFuture<LLMResponse> chat(List<Message> messages, List<JsonNode> tools) {
        // 调用 Anthropic API
    }
}
```

### 7.3 配置 MCP 服务器

MCP 允许动态加载第三方工具，无需修改代码：

```yaml
mcp_servers:
  git:
    command: "npx"
    args: ["-y", "git-mcp@latest"]
    tool_timeout: 30
```

### 7.4 添加自定义 MCP 客户端

```java
public class CustomMCPClient implements MCPClient {
    @Override
    public CompletableFuture<MCPResult> callTool(String toolName, Map<String, Object> arguments) {
        // 实现自定义通信逻辑
    }
}
```

---

## 八、常见问题

### Q1：如何配置 API Key？

A：可以通过配置文件或环境变量：
```yaml
providers:
  openai:
    api_key: your-api-key
```
或
```bash
export OPENAI_API_KEY=your-api-key
```

### Q2：如何添加自定义工具？

A：实现 `Tool` 接口，然后在 `Nanobot.initialize()` 中注册。

### Q3：如何扩展支持其他 LLM？

A：实现 `LLMProvider` 接口，处理特定 API 的格式差异。

### Q4：项目依赖哪些库？

A：主要依赖：
- Jackson（JSON 处理）
- SLF4J + Logback（日志）
- OkHttp（HTTP 客户端）

---

## 九、学习资源

1. **官方文档**：`docs/` 目录下的文档
2. **架构分析**：`nanobot_architecture_analysis.md`
3. **测试代码**：参考现有工具和提供商实现
4. **原始项目**：`../nanobot/` 目录下的 Python 源码

---

## 十、总结

Nanobot-Java 的核心价值在于：

1. **简洁的架构设计**：状态机驱动的消息处理
2. **灵活的扩展能力**：插件化的工具和提供商机制
3. **异步优先**：使用 Virtual Threads 实现高性能并发
4. **新手友好**：清晰的模块划分和详细的注释

**建议学习顺序**：
1. 从 `Nanobot.java` 入口开始，理解组件初始化
2. 深入 `AgentLoop.java`，理解状态机流程
3. 学习 `AgentRunner.java`，理解 LLM 调用循环
4. 研究 `Tool.java` 和 `LLMProvider.java`，了解扩展机制

祝你学习愉快！🚀
