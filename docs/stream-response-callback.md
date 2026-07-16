# StreamResponseCallback 流式响应机制

> 本文档拆解从用户输入到屏幕逐字输出的完整数据流。

---

## 完整数据流

```
用户输入 "帮我写代码"
     │
     ▼
┌──────────────────────────────────────────────────────────────┐
│ 第1步: 入队                                                   │
│ CliChannel.sendMessage() → messageBus.publishInbound()       │
│ AgentLoop.messageExecutor.submit(processMessage)             │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ 第2步: doRun() 准备 onDelta 回调                             │
│                                                              │
│ 检查 streamResponseCallbacks 列表: 有 CLI 的 callback 吗? 有!│
│                                                              │
│ onDelta = delta -> {                                         │
│     for (StreamResponseCallback cb : activeCallbacks) {      │
│         cb.onStreamData(sessionId, requestId, delta);        │
│     }                                                         │
│ };                                                            │
│                                                              │
│ runner.run(context, messages, onDelta)                       │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ 第3步: DeepSeek 逐字返回 → onDelta 逐字触发                  │
│                                                              │
│ provider.chatStream(messages, tools, onDelta)                │
│                                                              │
│ DeepSeek 流式 chunk: "今天"  → onDelta.accept("今天")        │
│ DeepSeek 流式 chunk: "天气"  → onDelta.accept("天气")        │
│ DeepSeek 流式 chunk: "晴"    → onDelta.accept("晴")          │
│                                                              │
│ 每次 accept → forEach(callbacks) → cb.onStreamData()        │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ 第4步: 各通道回调执行（谁注册谁收到，互不干扰）              │
│                                                              │
│ CLI 回调 (CliChannel.java:60)                                │
│   onStreamData → System.out.print(renderMarkdown(content))   │
│   onStreamComplete → println() + currentRequestId = null     │
│                                                              │
│ SSE 回调 (ChatController.java:150)                           │
│   onStreamData → emitter.send(SseEmitter.event().data(...))  │
│   onStreamComplete → emitter.send("[DONE]") + emitter.complete()│
│                                                              │
│ WS 回调 (NanobotWebSocketEndpoint.java:77)                   │
│   onStreamData → session.sendText(JSON)                      │
│   onStreamComplete → session.sendText({"type":"done"})       │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ 第5步: 流结束，runner.run() 返回                             │
│                                                              │
│ doRun() 中:                                                  │
│   for (StreamResponseCallback cb : activeCallbacks) {        │
│       cb.onStreamComplete(sessionId, requestId);             │
│   }                                                           │
│                                                              │
│ → TurnState.SAVE → TurnState.RESPOND → TurnState.DONE        │
└──────────────────────────────────────────────────────────────┘
```

---

## 关键设计决策

### 为什么用回调列表而非直接返回 String？

```
同步:                           流式:
                                
DeepSeek API                     DeepSeek API
     │                                │
     ▼ 等5秒                          ▼ 立即
"今天天气晴..."                    chunk "今天"
     │                                │
     ▼ 返回完整字符串                  ▼ 推给 onDelta
display("今天天气晴...")            display("今")
                                       │
                                       ▼ 再推
                                    chunk "天" 
                                       │
                                       ▼
                                    display("天")
                                       ...
```

流式是边生成边推送，用户感知延迟大幅降低。

### 为什么是回调列表（CopyOnWriteArrayList）而不是单个回调？

多个通道可能同时需要流式数据。例如用户开了 CLI 又连了 WebSocket，两个通道各注册一个回调，doRun() 依次通知所有人。

### 为什么 sessionId/requestId 需要匹配？

多会话并发时，doRun() 可能同时处理两条消息（不同 CLi 用户）。回调只处理 match 到自己那轮的：

```java
if (chatId.equals(sid) && currentRequestId != null && currentRequestId.equals(reqId)) {
    System.out.print(content);  // 只打印自己这轮的
}
```

### 为什么先 addCallback 再 publishInbound？

```java
agentLoop.addStreamResponseCallback(callback);  // ← 先注册
messageBus.publishInbound(message);             // ← 再发消息
```

倒过来会导致 doRun() 检查回调列表时还是空的，LLM 响应的第一个 chunk 就丢了。

---

## 核心标识符

消息中携带四个 ID，各有用途：

| 字段 | 含义 | 例子 | 必填 |
|------|------|------|:--:|
| `channel` | 从**哪个渠道**进来的 | `cli` / `api` / `websocket` | ✅ |
| `senderId` | **谁**发的（用户标识） | Telegram 用户 ID、WS session ID | ✅ |
| `sessionId` | 发到**哪个对话** | `web-1234567890`、`cli-1234567890` | ✅ |
| `connectionId` | 从**哪个连接**进来的 | WS 连接 ID | ❌ |
| `requestId` | **哪一轮**对话 | UUID（如 `f12b0f2a-...`） | ✅ |

**`requestId` 的关键作用** — 精确匹配响应，防止串话。

同一 `sessionId` 下有多轮对话，每轮有独立 `requestId`。AgentLoop 异步处理后，回调只推给匹配的那轮：

```
sessionId: "web-1234567890"
  ├── 第1轮: requestId="req-001"  → 流式 → 匹配 req-001 → 推给前端
  ├── 第2轮: requestId="req-002"  → 流式 → 匹配 req-002 → 推给前端
  └── 第3轮: requestId="req-003"  → 流式 → 匹配 req-003 → 推给前端
```

```
同步 HTTP:     waitForSessionResponse(sessionId, requestId, 120s)  ← 按 requestId 从 queue 取
SSE 回调:      cb.onStreamData(sid, rid, content) → rid == requestId? → 匹配才发
WS 回调:       同上
CLI 回调:      currentRequestId.equals(reqId) → 匹配才 print
```

没有 requestId 时，同步 HTTP 只能靠 sessionId 粗略匹配（先到先得），多轮并发就会串话。

当前单用户模式下，`senderId` 通常等于 `sessionId`（自己 = 自己的对话）。多用户时才有区分意义。

```
多用户（未来 Telegram/Discord）:
  senderId:  "@zhangsan"          ← 不同用户
  sessionId: "group-888"          ← 同一个群聊

单用户（当前 CLI）:
  senderId: = sessionId            ← 自己 = 自己的会话
  sessionId: "cli-1784100000000"

WebSocket:
  connectionId: "ws-abc123"        ← 用于回调时路由回正确的 WS 连接
```

`sessionKey`（`"channel:sessionId"`）是 SessionManager 的存储 key，不直接在消息中传输。

---

## 涉及文件

| 文件 | 行号 | 角色 |
|------|:--:|------|
| `AgentLoop.java` | ~650 | 定义 `StreamResponseCallback` 接口，检查回调列表，创建 onDelta |
| `AgentRunner.java` | ~220 | 将 onDelta 传给 `provider.chatStream()` |
| `DeepSeekProvider.java` | ~120 | SSE 逐行读取，每次 `data:` 行触发 `onDelta.accept()` |
| `CliChannel.java` | 60 | CLI 回调: `System.out.print(render(content))` |
| `ChatController.java` | 150 | SSE 回调: `emitter.send(event)` |
| `NanobotWebSocketEndpoint.java` | 77 | WS 回调: `session.sendText()` |
