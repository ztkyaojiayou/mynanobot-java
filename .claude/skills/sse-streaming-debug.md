# SSE Streaming Debug & Verify

> 诊断和修复 Spring Boot SseEmitter + DeepSeek 流式对话问题的标准化流程。
> 适用的典型症状：非流式 `/api/chat` 正常，流式 `/api/chat/stream` 无响应/卡死/只显示部分内容。

---

## 诊断清单（按概率排序）

### 1. SSE 格式是否被双重封装

**症状**：前端 EventSource 完全不触发 onmessage，或收到 `data:data:...` 格式数据。

**检查**：搜索 `emitter.send(` 调用，确认是否手动拼接了 `"data: "` 前缀：
```java
// ❌ 错误：SseEmitter.send() 会自动加 data: 前缀 + \n\n
emitter.send("data: " + content + "\n\n");

// ✅ 正确：让 SseEmitter 处理 SSE 格式
emitter.send(SseEmitter.event().data(content));
```

### 2. streamMode 是否正确传递

**症状**：流式端点返回 SSE 连接但无任何数据，最终超时。

**检查链路**：
```
Controller → metadata.put("streamMode", ...)
    → AgentLoop.doRun() 检查 streamMode
        → 创建 onDelta 回调
            → 传给 AgentRunner.run()
                → 传给 LLMProvider.chatStream()
```

每个环节确认：
- Controller 中 `metadata.put("streamMode", true)` — `/chat/stream` 端点应**固定 true**，不依赖前端请求参数
- `doRun()` 中 `streamMode` 取自 `context.getMessage().getMetadata().get("streamMode")` — 确认 key 一致

### 3. 流式条件是否绑定无关配置

**症状**：即使 streamMode=true，也不创建 onDelta 回调。

**检查 `AgentLoop.doRun()` 中的条件**：
```java
// ❌ 可能被 WebSocket 配置意外屏蔽
if (streamMode && config.getChannels().isSendProgress()) { ... }

// ✅ 增加 SSE 回调检测，与 WebSocket 配置解耦
boolean hasStreamCallback = streamResponseCallback != null && requestId != null;
if (streamMode && (config.getChannels().isSendProgress() || hasStreamCallback)) { ... }
```

### 4. 回调中的阻塞操作是否卡住管道

**症状**：前端收到几个 token 后卡死不动。

**根因**：`onDelta` 回调在 `chatStream()` 的同一线程中执行。如果回调中有阻塞操作（如往满队列 `put()`），会阻塞 SSE 读取线程 → 管道冻结。

**检查 `onDelta` 内的所有调用**：
```java
// ❌ 阻塞操作在 onDelta 中
publishProgress(builder.build());  // 内部 outboundQueue.put() — 队列满则永久阻塞

// ✅ 非阻塞操作
messageBus.offerOutbound(progress);  // offer() 队列满时静默丢弃
```

### 5. HTTP chunk 边界是否截断 SSE 行

**症状**：浏览器控制台能收到数据但页面渲染不完整（只显示首个 token）。

**根因**：`ReadableStream` 的 chunk 边界不一定对齐 SSE 消息边界。`data:hello\n\n` 可能被切成 `chunk1: "data:he"` + `chunk2: "llo\n\n"`。

**检查前端 SSE 解析**：
```javascript
// ❌ 每个 chunk 独立 split，截断行被丢弃
const lines = decoder.decode(value).split('\n');

// ✅ 跨 chunk 行缓冲
let streamBuffer = '';
const text = streamBuffer + decoder.decode(value, {stream: true});
streamBuffer = '';
const lines = text.split('\n');
if (text.length > 0 && text[text.length - 1] !== '\n') {
    streamBuffer = lines.pop();  // 不完整行留到下次
}
```

### 6. 消息队列是否有消费者

**症状**：出站队列持续增长，最终阻塞生产者。

**检查**：
- `MessageBus` 的 `outboundQueue` 是否有对应的消费者线程
- `publishOutbound()` 使用 `put()`（阻塞）还是 `offer()`（非阻塞）
- 进度/流式增量消息应使用非阻塞 `offerOutbound()`

---

## 验证流程

### 前置条件
- JDK 17+ 可用（不修改环境变量，命令行前缀覆盖）
- 端口 8080 可用

### Step 1: 杀旧进程 + 启动
```bash
# 查端口占用
netstat -ano | grep ":8080 "
# 杀进程
taskkill //F //PID <pid>

# 启动（JAVA_HOME 仅此行生效）
cd nanobot-java
JAVA_HOME=/path/to/jdk17 mvn package -DskipTests -q
JAVA_HOME=/path/to/jdk17 java -jar target/nanobot-java-*.jar &
```

### Step 2: 等就绪
```bash
for i in $(seq 1 15); do
  sleep 1
  curl -s http://localhost:8080/api/health | grep -q '"ok"' && break
done
```

### Step 3: 非流式测试
```bash
curl -s --max-time 30 -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"chatId":"test","content":"say ok","channel":"api","useSearch":false,"streamMode":false}'
# 预期: {"status":"success","content":"ok"}
```

### Step 4: 流式测试
```bash
curl -s -N --max-time 25 -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  --data-raw '{"chatId":"test2","content":"say ok","channel":"api","useSearch":false,"streamMode":true}' \
  > /tmp/sse.txt

cat /tmp/sse.txt
# 预期格式:
#   data:
#   data:ok
#   data:
#   data:[DONE]
```

### Step 5: 检查关键日志
```
# 确认 streamMode 传递正确
grep "doRun: streamMode=true" logs

# 确认 onDelta 被创建
grep "Created onDelta callback" logs

# 确认 SSE 数据被推送
grep "Calling streamResponseCallback.onStreamData" logs

# 确认完成
grep "SSE stream completed" logs
```

---

## 涉及的关键文件

| 文件 | 作用 | 常见问题 |
|------|------|----------|
| `ChatController.java` | SSE emitter 创建 + 回调注册 | SSE 格式双重封装；streamMode 未强制 true |
| `AgentLoop.java:doRun()` | 创建 onDelta 回调 | 流式条件绑定 isSendProgress；publishProgress 阻塞 |
| `AgentRunner.java:runInternal()` | 选择 chat() vs chatStream() | onDelta=null 时走非流式 |
| `DeepSeekProvider.java:chatStream()` | SSE 解析 + onDelta 调用 | onDelta 在解析线程中被阻塞 |
| `MessageBus.java` | 出站队列管理 | publishOutbound 用 put() 阻塞 |
| `index.html` | 前端 SSE 解析 | chunk 边界截断；markdownToHtml 性能 |

---

## 复盘总结

本次 nanobot-java `/chat/stream` 问题共定位并修复 5 个缺陷：

| # | 层级 | 缺陷 | 修复 |
|---|------|------|------|
| 1 | Controller | `emitter.send("data: "+content+"\n\n")` 双重封装 SSE | `emitter.send(SseEmitter.event().data(content))` |
| 2 | Controller | streamMode 依赖前端传参 | `/chat/stream` 固定为 true |
| 3 | AgentLoop | 流式条件绑定 WebSocket `isSendProgress` | 增加 `hasStreamCallback` 条件 |
| 4 | AgentLoop | `publishProgress` 中 `put()` 阻塞管道 | 新增 `offerOutbound()` 非阻塞方法 |
| 5 | index.html | SSE chunk 截断未做行缓冲 | 增加 `streamBuffer` 跨 chunk 拼接 |

验证结果：非流式 `{"status":"success","content":"ok"}` 正常；流式 `data:ok → [DONE]` 完整推送。