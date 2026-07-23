# 项目总结文档

> **项目定位**：从零自研的 Java 版 AI Agent 运行时基础设施（非 LangChain 套壳，无 SpringAI 框架），参考港大 Nanobot 架构
> 并融合 Claude Code 设计理念。129 源文件，~27,000 行 Java 代码，通过 claude code + deepseek + vibe-coding 1 人独立完成。

---

## 一、项目亮点（What makes this special）

### 1.1 自研 Agent 基础设施，而非 API 封装

市面上大部分 "AI Agent" 项目本质是 LangChain/Spring AI 的配置封装。本项目**手写实现了 Agent 运行时需要的全部基础设施**：

- **消息总线**：三队列异步架构（inboundQueue + outboundQueue + sessionResponses），Fan-Out Pub-Sub 流式分发
- **工具注册中心**：18+ 工具的注册、发现、执行、权限校验统一入口
- **四步安全管道**：Hook → Guards × 3 → RuleEngine → PermissionMode，纵深防御
- **流式引擎**：Fan-Out Pub-Sub，RunState → outboundQueue → Dispatcher 扇出，SSE/WebSocket/CLI 三通道独立消费

### 1.2 异步消息总线 —— 架构级亮点

这是整个项目中最体现系统设计能力的模块。传统同步方案是：

```
HTTP 请求 → 阻塞等 LLM → 返回结果
```

问题是：一个通道绑定一个 LLM 实例，加 CLI、加 WebSocket、加 Telegram 都要改核心代码。

我采用了**异步消息总线 + Fan-Out Pub-Sub**架构：

```
                    ┌──────────────┐
    HTTP sync  ────→│              │
    SSE        ────→│  MessageBus  │──→ AgentLoop（唯一核心，零改动）
    WebSocket  ────→│              │
    CLI        ────→│              │
                    └──────┬───────┘
                           │
                 ┌─────────┴──────────────┐
                 │  Fan-Out Pub-Sub       │
                 │  outboundQueue(1000)   │
                 │  → Dispatcher 扇出      │
                 ├────────────────────────┤
                 │ SSE  → emitter (每请求) │
                 │ WS   → sendText (全局)  │
                 │ CLI  → stdout (全局)    │
                 │ sync → sessionRes Map   │
                 └────────────────────────┘
```

**核心价值**：
- **天然多通道**：核心代码写一次，四通道复用。加 Telegram/Discord 只需 ~60 行适配器
- **生产者-消费者解耦**：HTTP 线程发完消息即刻返回，不阻塞 Tomcat 线程池
- **sessionResponses 精确匹配**：异步模式下多请求并发不乱序，requestId 精确路由响应
- **Fan-Out Pub-Sub**：RunState 只 put 到 outboundQueue，Dispatcher daemon 扇出到各通道独立 subscriberQueue，零数据复制，背压自然限速

这和 Claude Code 的架构理念一致——消息总线是 Agent 框架从"单体"走向"分布式"的关键基础设施。

### 1.3 AgentLoop 8 状态机 —— 对话全生命周期管理

**为什么需要状态机？** LLM 调用不是简单的 `input → output`。一次对话需要：加载历史 → 检查压缩 → 处理命令 → 构建 prompt → 调 LLM → 持久化 → 推送响应。如果全揉在一个方法里，200 行代码难以维护。

**8 状态设计**：

```
RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE
   │         │         │        │       │      │        │
  加载历史  压缩检查  拦截命令  拼装提示  调LLM  存历史   发响应
```

每个状态是独立的 AgentState 实现（State 模式），`processState()` 通过 stateHandlers 分发，`processStates()` 循环执行直到 DONE。

**工程价值**：
- **可测试性**：每个状态独立测试，Mock 上下文即可验证单个状态逻辑
- **可扩展性**：加新状态只需实现 AgentState 接口 + 注册到 stateHandlers，不影响现有流程
- **可观测性**：每个状态执行前后打印日志，问题定位精确到具体状态
- **与消息总线配合**：状态机是处理单条消息的状态流转，消息总线负责多条消息的排队调度

这和 TCP 状态机、JVM 类加载器等经典系统设计思路一致：**将复杂生命周期建模为有限状态机，是复杂系统的通用解**。

### 1.4 设计模式全景 —— 从 SOLID 到企业级模式

本项目不是简单的 CRUD，手写 Agent 运行时过程中自然应用了大量设计模式：

| 模式 | 应用场景 | 实现位置 |
|------|------|------|
| **状态机模式** | 对话生命周期管理 | `AgentLoop` 8 状态 State 模式 |
| **生产者-消费者** | 消息入队/出队解耦 | `MessageBus` + `AgentLoop` |
| **发布-订阅模式** | 流式输出多渠道分发 | `MessageBus` Fan-Out Dispatcher |
| **管道/责任链** | 四步权限检查 | `PermissionManager.check()` |
| **策略模式** | 子Agent分配 / LLM Provider | `AgentCoordinator` / `ProviderFactory` |
| **工厂模式** | ToolResult 创建 / Provider 匹配 | `ToolResult.ok()` / `ProviderFactory.create()` |
| **Builder 模式** | 复杂对象构建 | InboundMessage.builder() / OutboundMessage.builder() |
| **门面模式** | LLM 多 Provider 适配 | `LLMProvider` 接口统一 DeepSeek/OpenAI |
| **适配器模式** | MCP 工具包装 | `MCPToolWrapper` 将 MCP 协议适配为 Tool 接口 |
| **单例/多例** | Spring Bean 管理组件生命周期 | `AgentLoopConfig` + `MessageBusConfig` + `NanobotConfig` |
| **模板方法模式** | 工具执行前后处理 | `ToolRegistry.execute()` 统一参数校验+权限+结果包装 |
| **注册表模式** | 工具发现与注册 | `ToolRegistry` 注册/查找/执行 |

**可扩展性设计**：
- **加新工具**：实现 Tool 接口 or 方法级 @ToolDef 注解 → 自动注册
- **加新通道**：实现 ~60 行适配器 + `messageBus.subscribeToOutbound()` → 零改动核心
- **加新安全策略**：实现 Guard 接口 or 注册 Rule → 管道自动编排
- **加新 LLM Provider**：注册 ProviderStrategy → ProviderFactory 透明切换
- **加新记忆类型**：扩展 Dream → BuildState 自动注入 System Prompt

### 1.5 三维度工程体系化落地

| 工程维度 | 核心产出 | 价值 |
|------|------|------|
| 提示词工程 | 条件注入、首位+近因效应、NANOBOT.md、Skills 渐进式加载 | 证明理解 LLM 行为，不是写死 prompt |
| 上下文工程 | 8 状态机、三层记忆、token 预算管理、自动压缩 | 证明理解 LLM 局限，有工程化方案 |
| Harness 工程 | 异步消息总线、Fan-Out Pub-Sub、Hook/Guard/Rule/Mode 管道、并发工具执行 | 证明有系统设计能力，不是脚本级代码 |

### 1.6 安全和可靠性达到生产级

- 14 条 CommandGuard 默认拒绝规则 + 可扩展配置
- 3 次重试 + 连续失败降级（LLM 无工具回答）+ maxTurns/maxCost 预算控制
- EditFile 唯一性校验（对齐 Claude Code）、大文件自动分页
- 工具结果结构化标记 `[TOOL_OK]`/`[TOOL_ERR]` 帮助 LLM 判断

### 1.7 端到端请求追踪 — 一条消息的完整生命周期

> 面试官："从用户发消息到看到回复，你们的系统经历了什么？"
> 以下是精确到线程级别的完整追踪。假设用户在 Web 前端输入"帮我写一个排序算法"并点击发送。

#### 第一跳：前端 → ChatController

```
浏览器 JS 线程
  fetch('/api/chat/stream', {body: {sessionId, content: "帮我写一个排序算法"}})
    ↓
Tomcat HTTP 线程 (http-nio-8080-exec-N)
```

ChatController 三步走，**顺序至关重要**——必须先订阅再发消息，否则 AgentLoop 快于订阅建立，流式 token 丢失：

```java
// ① 订阅 → 获得专属 subscriberQueue（容量 500）
BlockingQueue<OutboundMessage> subscriberQueue = messageBus.subscribeToOutbound();

// ② 启动 SSE-consumer-{uuid} daemon 线程
//    死循环 poll subscriberQueue(1s) → 按 sessionId+requestId 过滤
//    → emitter.send(SseEmitter.event().data(token)) 推给浏览器

// ③ 构建 InboundMessage → 发到 MessageBus
messageBus.publishInbound(message);

// ④ 立刻 return emitter（Tomcat 线程释放回池，耗时 ~1ms）
```

**设计决策**：Tomcat 线程不等待 LLM。如果每个请求阻塞 30 秒，200 线程池 20 个并发用户就耗尽。现在 Tomcat 线程 ~1ms 即释放。

#### 第二跳：MessageBus.inboundQueue → AgentLoop

```
Tomcat HTTP 线程
  messageBus.publishInbound(message)
    → inboundQueue.put(message)    ← ArrayBlockingQueue(100)，有界背压
    ↓
AgentLoop daemon 线程（单线程死循环）
  msg = inboundQueue.poll(1s)       ← 超时返回 null，不浪费 CPU
  messageExecutor.submit(() -> processMessage(msg))  ← 提交到 worker 池
    ↓
AgentLoop-worker 线程（4 线程池）
  processMessage(msg)               ← 开始 8 状态机
```

**为什么分两层？** 主循环只管取消息（<1ms），worker 管处理（5-30s）。如果同线程，一条慢 LLM 调用阻塞整个系统的消息消费。

#### 第三跳：8 状态机流转（AgentLoop-worker 线程中）

```
RESTORE → SessionManager.loadHistory()
  从 .nanobot/sessions/{key}/history.jsonl 恢复历史，首次对话返回空

COMPACT → Consolidator.consolidate()
  token > contextWindowTokens × 90%? → LLM 总结 → system 替换 : 跳过

COMMAND → CommandState.execute()
  content.startsWith("/")? → 匹配内置命令(/stop/clear等)或技能 : 继续

BUILD → BuildState.execute()      ★ 构建 System Prompt
  8 来源按序拼接：
    ① 身份+日期（首位效应）   ② SOUL+IDENTITY+USER
    ③ 联网搜索开关（条件注入） ④ Dream 长期记忆检索（关键词匹配 top 5）
    ⑤ NANOBOT.md 项目记忆     ⑥ Skills 技能目录（渐进式加载第一层，~50 token）
    ⑦ Rules 规则注入           ⑧ 工具格式说明（近因效应）
  插入到 messages[0] 作为 system 消息

RUN → RunState.execute()        ★ 核心（下一跳详解）

SAVE → SaveState.execute()
  sessionManager.saveHistory() → JSONL 增量追加
  dream.extractAndStore() → fire-and-forget，不阻塞响应

RESPOND → RespondState.execute()
  publishOutbound(response) → sessionResponses Map（sync /api/chat 专用）
```

#### 第四跳：RunState → LLM 调用 + 流式 Fan-Out（跨 4 个线程）

```
AgentLoop-worker 线程
  │
  ├─ buildOnDelta() 闭包:
  │    delta → { publishToOutboundQueue(OutboundMessage with _stream_delta: true) }
  │
  ├─ runner.run(ctx, messages, onDelta).join()   ← 阻塞等待 LLM 完成
  │
  └─ sendStreamEnd() → OutboundMessage(_stream_end: true)

  ════════════════════ 跨线程边界 ════════════════════

DeepSeekProvider (ForkJoinPool 线程)
  CompletableFuture.supplyAsync(HTTP POST DeepSeek API)
  每收到 SSE chunk → onDelta.accept(token)
    → publishToOutboundQueue(msg)
      → outboundQueue.put(msg)          ← ArrayBlockingQueue(1000)，背压
        ↓
MessageBus-dispatcher daemon 线程
  msg = outboundQueue.poll(1s)          ← 消息到达微秒唤醒
  for (sq : subscriberQueues) {         ← CopyOnWriteArrayList
      sq.offer(msg, 100ms)              ← 非阻塞，队列满丢弃该订阅者
  }                                     ← 同一份 msg 引用，零拷贝
        ↓
SSE-consumer-{uuid} daemon 线程
  msg = subscriberQueue.poll(1s)
  if (sessionId+requestId 匹配) {
      emitter.send(SseEmitter.event().data(msg.content))
  }
        ↓
浏览器 JS EventSource 线程
  event.data → 累积 currentStreamingContent → md2html() → innerHTML
  用户看到逐字输出 "帮\n你\n写\n一\n个\n排\n序\n算\n法\n..."
```

#### 第五跳：流结束 → 清理

```
AgentLoop-worker
  sendStreamEnd() → Dispatcher → SSE-consumer
    → emitter.send(data: "[DONE]")
    → emitter.complete()
    → consumerThread 终止 + unsubscribeFromOutbound()  ← 防泄漏

浏览器
  收到 "[DONE]" → currentStreamingEl = null
  下一次 addMsg 创建新 bubble

RespondState
  sessionManager.saveHistory() → 用户消息+AI回复写入 history.jsonl
  Dream.extractAndStore() → fire-and-forget 异步提取长期记忆
```

#### 线程边界全景图

```
浏览器(JS) ──fetch──▶ Tomcat(http-nio-8080-exec-N) ──publishInbound──▶ inboundQueue(100)
                                                                          │
                                          AgentLoop daemon ◀── poll(1s) ──┘
                                                │ submit()
                                          AgentLoop-worker (1 of 4)
                                                │ RunState.runner.run().join()
                          ┌─────────────────────┼─────────────────────┐
                          ▼                     ▼                     ▼
              DeepSeekProvider            onDelta 回调              工具执行
              ForkJoinPool               HTTP IO 线程              ToolExecutor
                    │                       │                       │
                    │  SSE chunk             │  publishToOutbound    │  CompletableFuture
                    │                       ▼                       │
                    │              outboundQueue(1000)               │
                    │                       │                       │
                    │          Dispatcher daemon ◀── poll(1s)        │
                    │            fan-out (同一份引用扇出)              │
                    │     ┌──────────┼──────────┐                   │
                    │     ▼          ▼          ▼                   │
                    │  SSE-cons   CLI-cons   WS-cons                │
                    │     │          │          │                   │
                    ▼     ▼          ▼          ▼                   ▼
              用户看到输出   终端渲染   WebSocket   结果回注 → 递归 LLM
```

**从发送到第一个 token 到达前端，至少经过 4 个线程边界。每一跳都是解耦点，都有明确的工程目的**——不是随意跨线程。

---

### 1.8 项目架构 vs Claude Code 对比

> 面试常见追问："你这个和 Claude Code 有什么区别？"

| 维度 | Claude Code | Nanobot-Java | 对比分析 |
|------|-------------|-------------|---------|
| **消息模型** | 单会话单进程，用户输入 → 同步处理 → 输出 | 异步消息总线，多通道共享 AgentLoop | Claude Code 是单用户 CLI 工具，不需要消息队列；Nanobot 作为服务需要多通道并发 |
| **流式输出** | 直接 stdout（单个消费者） | Fan-Out Pub-Sub（outboundQueue → Dispatcher 扇出 → SSE/CLI/WS 三通道） | Claude Code 一个终端就够；Nanobot 需要考虑多通道零拷贝分发 |
| **工具注册** | 内置工具集（Bash/Read/Write/Edit/Glob/Grep/WebFetch/WebSearch/Task/Agent 等） | 18+ Tool 接口 + @ToolDef 注解扫描 + MCP 外部工具 | 接口设计相似，Nanobot 多了注解自动注册和 MCP 协议 |
| **权限模型** | allow/deny 规则（allow 可被 deny 覆盖）+ permissionMode（default/acceptEdits/bypass/plan） | Hook → Guard×3 → RuleEngine → PermissionMode 四步管道 | Claude Code 更简洁（产品化），Nanobot 更多层（展示设计能力）。分层边界清晰，向外展示"如何设计可扩展安全模型" |
| **Skills 加载** | 渐进式：System Prompt 注入目录 → LLM 决定 → 调 use_skill 工具 → 返回 SKILL.md 全文 | 完全对齐：BuildState 注入目录 + UseSkillTool 返回全文 | 100% 对齐，包括内置命令优先级 > 技能匹配 |
| **Rules 机制** | YAML frontmatter + markdown 内容，System Prompt 每轮注入 | 同：.nanobot/rules/*.md 文件加载，BuildState 每轮注入 | 格式相同，但 Nanobot 支持项目级/用户级/内置三层优先级 |
| **记忆系统** | CLAUDE.md 项目记忆 + 长期记忆（自动提取） | NANOBOT.md + Dream 全自动闭环（提取→节流→JSON解析→持久化→检索注入） | 理念一致，Nanobot 多了增量节流和 /remember 手动触发 |
| **System Prompt** | Identity + CLAUDE.md + Rules + Skills catalog + Plan mode | 8 步流水线：身份+日期+SOUL+搜索+记忆+NANOBOT.md+技能目录+Rules+工具格式 | Nanobot 更显式（便于面试展示），Claude Code 更内聚 |
| **子 Agent** | Task 工具 + 异步委派 | SpawnTool + AgentCoordinator（4 分配策略）+ inbox 文件通信 | 相似，Nanobot 多了策略模式和 inbox 异步 |
| **上下文压缩** | /compact 命令 + 自动压缩 | Consolidator 自动触发（>90% budget）+ /compact 手动 | 几乎一样 |
| **配置管理** | JSON/YAML 配置 + 环境变量 | ConfigLoader 合并 config.yaml + secret.yaml | 思路相同 |
| **启动方式** | CLI 单进程 | V1 纯 Java / V2 Spring Boot Web / V3 Spring CLI 三种 | Nanobot 多了服务化能力 |
| **权限确认** | 交互式 ask-user 弹窗 | CLI [1/2/3] 交互确认 + InteractiveHandler 接口 | Nanobot 更灵活（可注入不同交互实现） |

**关键差异总结**：
- Claude Code 是**产品**（单用户 CLI），优化到极致简洁
- Nanobot 是**基础设施**（多用户服务），展示架构设计广度

面试时可以这样表述："我参考了 Claude Code 的设计理念——Skills 渐进式加载、Rules 约束、权限确认——但作为 Java 服务化实现，我额外解决了多通道并发（Fan-Out Pub-Sub）、多版本架构（V1/V2/V3）、MCP 协议集成等 Claude Code 作为 CLI 产品不需要考虑的问题。这不是复制，是**理解和重构**。"

---

## 二、技术难点（Real engineering challenges）

### 难点 1：异步消息总线下多通道并发 —— 4 层根因 + 5 项修复

**背景**：Agent 需要同时支持 HTTP/SSE/WebSocket/CLI 四种交互方式。传统同步方案（一个请求→阻塞等 LLM→返回）简单但要加新通道就得改核心代码。我采用异步消息总线：所有入口发消息到 MessageBus → AgentLoop 异步消费 → 生成响应 → 推回给各通道。

**难点**：异步模式引入了一个复杂的多线程问题。上线后出现诡异现象：第 1 轮对话正常，第 2 轮起前端完全无响应，且不会报任何错误。没有任何异常日志，所有线程看起来都在正常工作，但数据就是到不了前端。

**根因分析**（4 层逐层剥离）：
1. **Spring MVC 30s 超时截断**：Spring Boot 默认 `spring.mvc.async.request-timeout=30000`。SSE 长连接超过 30 秒被 Spring 自动关闭，前端 EventSource 不报错静默断连
2. **僵尸 subscriberQueue 泄漏**：连接断开时 `onTimeout` 没调用 `unsubscribeFromOutbound()`，死 subscriberQueue 残留在 CopyOnWriteArrayList 中。Dispatcher 继续往里面塞消息，新请求的 subscriberQueue 被淹没
3. **AgentLoop 单线程阻塞**：主循环是单 daemon 线程，`processMessage()` 同步等待 LLM 响应（5-30 秒）。这段时间内新消息堆积在 inboundQueue，消费延迟 = 排队消息数 × LLM 响应时间
4. **双 AgentLoop 竞争消费**：这是最隐蔽的。NanobotConfig（Spring @Bean）和 NanobotRunner（ApplicationRunner.run()）各自独立创建了 AgentLoop 实例，两个实例共享同一个 MessageBus。AgentLoop A 处理了 SSE 的请求，但响应被 AgentLoop B 的 callback 匹配走——两个实例竞争消费同一队列，响应路由错乱

**方案**（5 项改动，逐层解决）：
- `spring.mvc.async.request-timeout=300000`（5 分钟）→ 覆盖最长 LLM 调用窗口
- `onTimeout`/`onCompletion`/`onError` 全部调用 `unsubscribeFromOutbound()` → 三路兜底防泄漏
- 引入 `messageExecutor` 4 线程池 → `processMessage()` 异步提交，主循环只负责 `consumeInbound()` + `submit()`，恢复时间 < 1s
- 消除 NanobotRunner 中的重复初始化 → 所有组件由 NanobotConfig 统一 `@Bean` 创建，`@Autowired` 注入，全应用唯一实例
- Fan-Out Pub-Sub 替代 callback 模式 → AgentLoop 零耦合消费者，彻底消灭响应路由错乱

**面试价值**：这不是一个"改了某个配置就好了"的简单 bug，而是需要从现象逐层剥离直到找到根因的系统级问题。4 层根因分布在 Spring 框架配置、资源生命周期管理、线程模型设计、Bean 创建策略四个不同维度，体现了跨层次的系统诊断能力。

### 难点 2：四步权限管道设计 —— 可扩展的安全模型

**背景**：Agent 可以执行 shell 命令、读写文件、发起网络请求——这些操作如果被 LLM 随意执行，后果严重。最简单的方案是 `if (tool.equals("exec")) return false;`，但这样每加一个新工具或新安全策略都要改 if-else。

**难点**：安全模型需要同时满足四个矛盾需求：
1. **硬底线不可绕过**：无论什么模式，危险命令（如 `rm -rf /`）必须拒绝
2. **软偏好可配置**：用户可能想"Shell 命令需要确认，但文件读取直接放行"
3. **外部脚本可介入**：允许用户用外部脚本做自定义安全检查
4. **新手加策略不改核心**：加一个安全策略不应影响已有逻辑

如果写死 if-else，这四个需求会互相缠绕，最终变成无人敢改的"安全面条代码"。

**方案**：四步管道模式，每层独立、短路语义不同：

```
PreToolUse Hook → Guards(PathGuard/CommandGuard/NetworkGuard) → RuleEngine(deny>ask>allow) → PermissionMode(4种)
```

- **Hook 层**（最优先）：外部脚本，支持 deny/allow/modify/passthrough 四种语义。deny 短路跳过所有后续检查
- **Guard 层**（永远执行，不可跳过）：硬底线。PathGuard（工作区隔离+符号链接解析）、CommandGuard（14 条默认拒绝规则）、NetworkGuard（12 个 CIDR 范围 SSRF 防护）。任何一个 Guard 拒绝即抛异常
- **RuleEngine 层**（用户配置）：deny > ask > allow 优先级链，支持正则匹配工具名+参数值。deny 不可被 allow 覆写（fail-closed 策略）
- **Mode 层**（运行时切换）：PLAN 只读 / DEFAULT 读放行写确认 / ACCEPT_EDITS 编辑放行 / BYPASS 全放行

所有检查统一在 `ToolRegistry.execute()` 中执行——这是唯一切入点，零绕过风险。

### 难点 3：流式输出的多渠道分发 —— Fan-Out Pub-Sub 架构演进

**背景**：LLM 流式输出需要实时推送给用户。但不同通道的消费方式完全不同——SSE 写入 HttpServletResponse OutputStream、WebSocket 发送 JSON 帧、CLI 直接 System.out。核心 Agent 逻辑不能为每种通道写一套。

**难点**：三个方案各有致命缺陷：
- 在 RunState 里写 if-else 分通道：每加新通道改核心代码，违背开闭原则
- 每个通道直接注册 callback 到 AgentLoop：AgentLoop 需要知道所有通道的存在，紧耦合。且回调列表的遍历是同步的——一个慢回调拖慢所有通道
- 把流式数据写入 MessageBus 出站队列：可以解耦，但 `BlockingQueue.take()` 是破坏性消费——一条消息只能被一个消费者取走。三个通道需要同一条数据，单队列做不到

**方案**：Fan-Out Pub-Sub 三阶段演进：

```
阶段一（初始）：StreamResponseCallback 接口 + CopyOnWriteArrayList 回调列表
  缺陷：AgentLoop 持有消费者引用，紧耦合；同步遍历

阶段二（改进）：所有通道通过 messageBus.subscribeToOutbound() 获得独立队列
  但仍保留 StreamResponseCallback 接口供向后兼容

阶段三（最终）：彻底移除 StreamResponseCallback，AgentLoop 零耦合消费者
  RunState → publishToOutboundQueue(msg) → outboundQueue(1000)
    → Dispatcher daemon poll(1s) → 遍历 subscriberQueues (COW)
      → offer(msg, 100ms) → 各通道独立 poll
```

零数据复制（同一份 msg 引用）、背压逐层传递（subscriberQueue 满 → offer 丢弃 → outboundQueue 积压 → put 阻塞 → TCP 窗口缩小 → LLM 降速）、加通道只需 `subscribeToOutbound()` + 后台 poll 线程。

### 难点 4：工具失败降级 —— 防止 LLM 无限重试

**背景**：LLM 调用工具可能失败。但工具失败不是抛异常——结果是 `"Error: permission denied"` 字符串返回给 LLM。LLM 看到错误会换方式再试，一次对话最多可循环 100 次（默认 maxToolIterations），每次调 API 都是真金白银。

**难点**：需要区分两类完全不同的失败，在正确的层级做正确的处理：
- 网络抖动（transient error）：重试大概率恢复，应该在单工具层面重试
- 工具逻辑错误（logic error）：文件不存在、权限拒绝、命令语法错误——重试 100 次也不会成功，应该在对话轮次层面降级

如果两类混在一起处理，要么对网络抖动不重试（用户体验差），要么对逻辑错误反复重试（烧钱）。

**方案**：两个独立机制，分层处理：

```
单工具重试（AgentRunner.callAndWaitForResult）：
  超时/连接错误 → 重试 3 次，间隔 1s → 3 次后仍失败 → 返回 [TOOL_ERR]

连续失败降级（AgentRunner.runInternal）：
  本轮所有工具返回 [TOOL_ERR] → consecutiveToolFailures + 1
  任一工具成功 → 清零
  连续 3 轮全部失败 → callLLMWithoutTools() → 强制无工具模式
```

降级终止是"隐藏功能"——LLM 不知道系统在保护它。LLM 看到失败继续尝试，系统后台计数，到阈值直接切换模式。用户看到的是"AI 换了一种方式回答"，而非冷冰冰的"你已超出限制"。

### 难点 5：CLI 模式的零日志输出 —— 6 次失败尝试

**背景**：CLI 交互式对话要求控制台只显示对话内容和 `>` 提示符，零条日志。这不是"把日志级别调成 WARN"那么简单。

**难点**：系统有三重日志配置源，优先级互相覆盖：
- `logback.xml`：硬编码 `<logger name="com.nanobot" level="DEBUG"/>`
- `application.yml`：`logging.level.com.nanobot: DEBUG`
- Spring Boot `LoggingSystem`：在 ApplicationReadyEvent 时重新应用配置

**6 次失败**：
1. `System.setProperty("logging.level.root", "WARN")` → 对 per-logger 配置无效
2. `logback.configurationFile` 系统属性 → Spring Boot 不走这个属性
3. `SpringApplication.setDefaultProperties()` → 默认属性优先级低于 YAML
4. `--logging.level.root=WARN` CLI args → YAML 中 per-logger 配置不被 root 覆盖
5. `LoggerContext.reset()` + 程序化设 Level → LoggingSystem 重新应用 logback.xml 覆盖
6. **终局**：`--logging.config=classpath:logback-cli.xml` → **从源头替换配置文件**

**教训**：日志级别是**配置源优先级问题**，不是 API 调用时序问题。用程序化 API 对抗声明式配置永远是输——正确做法是从源头替换。这同样适用于任何"被多重配置源覆盖"的场景。

### 难点 6：DeepSeek 模型的身份偏差 —— 首位+近因效应对抗

**背景**：DeepSeek-chat 模型训练数据中被标注为 "Claude by Anthropic"。即使系统提示词明确写"你是 my-nanobot"，模型仍回答"我是 Claude"。这不是 prompt 写错了，是训练数据中的偏差——参数中存储的 Claude 身份关联比系统提示词更强。

**难点**：这是一个 LLM 心理模型问题，不是代码问题。需要理解注意力机制——模型对 prompt 不同位置的注意力权重不同（早期 token 和最近 token 权重最高）。

**尝试过程**：
- 第一次：prompt 中间写"你不是 Claude"→ 被模型忽略（中段注意力低）
- 第二次：放到 prompt 开头 → 略有改善（首位效应），但仍偶尔自称 Claude
- 第三次：开头+结尾双声明 + 注入真实日期 → 基本解决（首位+近因效应叠加）
- 最终：`IdentityManager.getSystemPrompt()` 在 prompt 最开头声明"你绝对不是 Claude、DeepSeek、ChatGPT"，最末尾再次强调，中间用 SOUL/IDENTITY/USER 三层文件定义人格。CLI 模式额外用 `get_current_time` 工具让模型主动获取真实日期

**工程价值**：深刻理解了"LLM 不是执行精确指令的计算机，而是基于概率分布生成文本"。提示词工程的核心是理解模型的注意力分布和训练数据偏差，而非堆砌指令。

### 难点 7：工具接口设计 —— 双模式共存

**背景**：工具系统需要让开发者 5 分钟能写一个新工具，同时又需要支持自动发现注册、JSON Schema 生成、异步执行。

**难点**：三个需求天然矛盾：
1. **简单性**：新人 5 分钟上手 → 接口要极简
2. **自动发现**：不需要手动 `registry.register()` → 需要注解或扫描
3. **JSON Schema 生成**：给 LLM function calling 用 → 需要参数元数据

如果只用一个重接口，简单场景太重。如果只用注解，复杂工具（如 ExecTool 需要管理子进程）放不下。

**方案**：双模式共存，`ToolScanner.scanAndRegister()` 自动扫描：

```
implements Tool 模式（复杂工具）：
  getName() / getDescription() / getParameters() / isReadOnly()
  execute(Map<String, Object>) → CompletableFuture<Object>

@ToolDef 注解模式（简单工具）：
  方法级注解，参数用 @ToolParam 标注
  ToolScanner 扫描 → 自动生成 JSON Schema → 包装为 Tool → 注册
```

关键设计决策：
- 返回 `CompletableFuture<Object>` 而非 `String`：支持异步工具（网络请求），不阻塞线程池
- 参数用 `Map<String, Object>` 而非强类型 DTO：LLM 生成动态 JSON，Map 避免反序列化失败
- ToolScanner 类路径扫描而非 Spring @Component：V1 无 Spring 依赖，保持独立性

### 难点 8：三版本架构设计 —— V1/V2/V3 零耦合

**背景**：项目需要同时支持三种启动方式——纯 Java（无 Spring）、Spring Boot Web 服务、Spring Boot CLI。三者的依赖和配置完全不同，但核心代码不能重复。

**难点**：Spring 的 `@SpringBootApplication` 默认扫描同包及子包的所有 `@Configuration`。如果 V2 和 V3 的配置互相可见，V2 启动 Web 服务器时会误创建 CLI 的 Bean，V3 启动 CLI 时会误启动 Tomcat。同时 `NanobotRunner` 要被 V2 和 V1 共享，但 V3 不能重复创建 AgentLoop。

**方案**：包隔离 + Profile + 条件配置：
- `v1/` `v2/` `v3/` 各自独立包，公共模块在父包
- V3 用 `@Profile("cli")` 隔离，`spring.main.web-application-type=none` 禁止 Web 服务器
- V2 的 `AgentLoopConfig`/`MessageBusConfig`/`NanobotConfig` 三配置类独立，各自管理自己的 Bean
- V1 完全不依赖 Spring，自建 HTTP 服务器 + 手动组装组件

**成果**：三版本 16 个文件（5+8+3），核心 100+ 文件零版本耦合。加第四个版本（如 Discord Bot）只需新建包 + 引用核心模块。

### 难点 9：记忆系统三层架构 —— 短期/中期/长期的边界与协作

**背景**：Agent 需要记住三件事：当前对话说了什么（短期）、对话太长怎么压缩（中期）、跨会话的用户偏好和关键事实（长期）。这三个需求时间尺度不同（分钟/小时/永久），如果混在一个系统里会互相干扰——压缩太激进丢上下文，太保守撑爆窗口。

**难点**：
1. 三层各需要不同触发时机：短期是每次对话自动加载/保存，中期是 token 超预算时触发，长期是对话结束后异步提取
2. 压缩对象的优先级：为什么优先压缩 assistant+tool 而非 user 消息？旧回答信息密度低，用户意图需保留
3. 长期记忆的 LLM 成本控制：每次对话都调 LLM 提取记忆太贵，但太久不提会丢失关键信息

**方案**：

```
短期 (history.jsonl) → SessionManager，SaveState 增量追加 JSONL，RestoreState 完整恢复
中期 (Consolidator)  → usage > budget×90% → LLM 总结旧 assistant+tool 消息 → system 替换
长期 (Dream)         → 全自动闭环：SAVE 异步提取 → 5000字符节流 → Jackson JSON 解析
                       → MEMORY.md 持久化 → BUILD 关键词检索 → System Prompt 注入
                       + /remember 手动强制提取 + /compact 手动强制压缩
```

架构演进：V1 的 MemoryStore（420 行死代码，从未接入 AgentLoop）被删除，记忆管理统一由 Dream + MemoryLoader 负责。Dream 内部用 Jackson 真正解析 LLM 返回的 JSON（旧代码只返回空列表占位）。

### 难点 10：MCP 协议集成 —— 外部工具的发现、生命周期与故障隔离

**背景**：MCP（Model Context Protocol）允许 Agent 动态连接外部工具服务器，扩展工具能力。但外部进程不可控——可能启动失败、中途崩溃、响应超时。

**难点**三个：
1. **进程生命周期**：stdio 子进程的启动、心跳、崩溃重启、超时清理需要自动管理
2. **工具发现**：服务器启动后动态查询工具列表 → 注册到 ToolRegistry → 对外部工具零感知
3. **故障隔离**：一个 MCP 服务器崩溃不能影响内置工具和其他 MCP 服务器

**方案**：8 个文件实现完整协议栈：
- 三种传输支持：`StdioMCPClient`（子进程）、`HttpMCPClient`（HTTP SSE 远程）、streamableHttp
- `MCPToolWrapper implements Tool`：外部工具包装为 Tool 接口，对 ToolRegistry 透明
- `MCPManager.initialize()`：启动时连接所有配置的服务器 → `client.listTools()` 获取工具列表 → 注册到 ToolRegistry
- 命名规范 `mcp_{serverName}_{toolName}`：防冲突 + RuleEngine 中 `mcp_.*` 正则批量管控
- 故障隔离：每个 MCP 服务器独立 `ScheduledExecutorService` 线程池，崩溃不传播。PermissionManager 的安全检查管道自动覆盖 MCP 工具

### 难点 11：LLM 交互层的可靠性设计

**背景**：Agent 的"大脑"是外部 LLM API——天然不可靠。超时、限流、格式错误、tool_calls 不完整是常态。交互层需要在不可靠外部依赖之上构建可靠的 Agent 行为。

**难点**：DeepSeek 和 OpenAI 的 API 99% 兼容，但 1% 的差异足以让 Agent 崩溃——`arguments` 字段 OpenAI 是 JSON 字符串、DeepSeek 有时返回已解析对象；流式结束标记 OpenAI 发 `[DONE]`、DeepSeek 发空 data；system 消息 DeepSeek 要求必须在 messages[0] 且不能连续。

**方案**：
- `LLMProvider` 接口 + `Message` 统一数据模型：差异封装在 `buildRequestBody()` 中，上层零厂商耦合
- 四重循环终止：正常终止（纯文本）→ 最大轮次终止 → 费用终止 → 失败降级终止（连续 3 轮 `[TOOL_ERR]` → 无工具模式）
- 双层重试：单工具重试（网络错误 3 次，间隔 1s）+ 连续失败降级（3 轮）——区分 transient 和 logic error
- `onDelta` 只做 `publishToOutboundQueue()`（队列 put），扇出由 Dispatcher 异步完成——IO 线程不阻塞

### 难点 12：Skills 渐进式加载 —— 对标 Claude Code 的 use_skill 元工具

**背景**：Skills 是可复用的专业提示词包（代码审查、重构、测试生成等），LLM 需要自主发现和调用。但如果每个 Skill 注册为一个独立 tool，tools 数组会线性膨胀——5 个技能额外 5 个 tool，LLM 工具选择准确率暴跌。

**难点**：需要在"LLM 知道所有技能"和"tools 数组不膨胀"之间平衡。对标 Claude Code 的做法：System Prompt 只告诉 LLM 有哪些技能可用，LLM 自主决定何时加载哪个技能的全文。

**方案**：两层渐进式加载：

```
第一层（System Prompt）：BuildState.appendSkillCatalog()
  注入技能目录——仅名称+描述，~50 token
  "## 可用技能（Skills）
   - code-review — 代码审查：检查安全漏洞、代码质量
   - refactor — 结构化重构：识别代码异味
   ..."

第二层（Function Calling）：UseSkillTool
  LLM 判断需要某技能 → tool_calls=[{use_skill, name="code-review"}]
  → UseSkillTool.execute() → SkillRegistry 返回 SKILL.md 全文
  → 全文作为 tool 结果注入 → LLM 按指令执行
```

关键设计：
- 一个元工具管所有 Skill，tools 数组始终只多 1 个，不受技能数量影响
- 内置命令（`/stop` `/clear` 等）优先级高于技能匹配——防止用户技能覆盖系统命令
- 每个 Skill YAML frontmatter 中 `auto-trigger: true/false` 控制是否允许 LLM 自主匹配

**对比旧方案**：旧实现只在 CommandState 支持手动 `/skill-name` 斜杠触发，没有 use_skill 工具——LLM 无法自主发现和调用技能。新增 use_skill 后实现了完整的两层渐进式加载。

---

## 三、核心功能清单

### 3.1 Agent 运行时

| 功能 | 实现 |
|------|------|
| 异步消息总线 | MessageBus 三队列（inboundQueue + outboundQueue + sessionResponses）+ Fan-Out Pub-Sub |
| 8 状态状态机 | RESTORE→COMPACT→COMMAND→BUILD→RUN→SAVE→RESPOND→DONE（State 模式） |
| LLM 调用循环 | AgentRunner.runInternal() 递归，CompletableFuture 链式异步 |
| 流式输出 | DeepSeek SSE → onDelta → publishToOutboundQueue → Dispatcher 扇出 → SSE/CLI/WS 三通道 |
| 并行工具执行 | AgentRunner.toolExecutor(4线程) + ToolRegistry.executor(cpu×2)，双层线程池 |

### 3.2 工具系统（18+）

| 类别 | 工具 |
|------|------|
| 文件 | read_file(分页), write_file, edit_file(唯一性校验), list_dir |
| 搜索 | grep(regex+include过滤), glob(跳过.git/node_modules) |
| Shell | exec(stderr分离, 默认120s) |
| 网络 | web_search(多provider), web_fetch(Jsoup) |
| Agent | spawn(同步+异步inbox), spawn_check, ask_user(交互确认) |
| 技能 | use_skill(元工具，渐进式加载，对标Claude Code) |
| 任务 | task_create/list/update(JSON持久化) |
| 其他 | get_current_time, @ToolDef 注解扫描 |

### 3.3 安全体系

| 组件 | 说明 |
|------|------|
| PathGuard | 工作区隔离，toRealPath() 防 `../` 绕过 |
| CommandGuard | allowPatterns > denyPatterns，14 条默认拒绝 |
| NetworkGuard | SSRF 防护，12 个 CIDR 范围 |
| RuleEngine | deny > ask > allow 优先级链，正则匹配 |
| PermissionMode | PLAN / DEFAULT / ACCEPT_EDITS / BYPASS |
| InteractiveHandler | CLI 交互确认 |
| PreToolUse Hook | 外部脚本钩子，所有规则前执行 |

### 3.4 通道

| 通道 | 技术 |
|------|------|
| HTTP REST | POST /api/chat, sessionResponses + requestId 精确匹配 |
| SSE 流式 | POST /api/chat/stream, SseEmitter + subscriberQueue poll |
| WebSocket | @ServerEndpoint /ws, onMessage → publishInbound → subscriberQueue poll |
| CLI | 类 Claude Code，Markdown 彩色渲染，Esc 中断 |

### 3.5 上下文与记忆管理

| 功能 | 说明 |
|------|------|
| 会话持久化 | history.jsonl，JSONL 格式，按 sessionKey 隔离 |
| Token 压缩 | Consolidator 自动触发 + `/compact` 手动触发 |
| 长期记忆 | Dream 自动提取(5000字符节流) + `/remember` 手动触发 |
| NANOBOT.md | `/init` 生成，BuildState 自动注入 system prompt |
| 技能目录注入 | BuildState 注入名称+描述（渐进式加载第一层） |
| maxTurns/maxCost | 预算控制，超限自动停止 |

---

## 四、STAR 法则项目陈述

### S — Situation（背景）

作为后端工程师，我发现市面上大多数 "AI Agent" 框架（LangChain、Spring AI）本质是 API 封装，
开发者只需配置 prompt + 调 API 即可获得回复——但无法理解 Agent 内部机制，
也无法对 LLM 运行时有更深度的理解。

于是决定使用 Java 从零实现一个生产级 Agent 运行时基础设施，参考港大开源项目 Nanobot 的核心架构
（消息总线 + 状态机 + 工具系统），并融合 Claude Code 的安全模型和编程工具设计。

### T — Task（任务）

设计并实现一个完整的 AI Agent 运行时框架，核心要求：

1. **不是 API 封装**：手写消息总线、状态机、工具注册中心、权限管道
2. **多通道支持**：同一套核心逻辑驱动 HTTP/SSE/WebSocket/CLI 四种交互方式
3. **安全纵深防御**：4 层权限检查（Hook→Guard→Rule→Mode），不可绕过
4. **上下文管理**：自动会话持久化、token 预算管理、历史压缩、长期记忆（Dream）
5. **对标生产级 Agent**：18+ 工具、Fan-Out Pub-Sub 流式分发、并发工具执行、子 Agent、MCP 协议

### A — Action（行动）

**架构设计阶段**：

- 采用异步消息总线（三队列 + Fan-Out Pub-Sub）作为核心通信层，解耦 Agent 引擎和通道适配器
- 设计 8 状态 State 模式（RESTORE→COMPACT→COMMAND→BUILD→RUN→SAVE→RESPOND→DONE）管理对话生命周期
- 设计 Hook→Guard→Rule→Mode 四步权限管道，确保每个工具调用必经安全检查

**核心实现阶段**：

- 实现 AgentRunner CompletableFuture 链式递归循环（工具调用 → 执行 → 结果回注 → 递归直到纯文本）
- 实现 Fan-Out Pub-Sub 流式分发：RunState → outboundQueue(1000) → Dispatcher 扇出 → SSE/WS/CLI 独立消费
- 实现 Consolidator 上下文压缩器（token 超 90% 预算 → LLM 自动总结旧消息）+ Dream 长期记忆全自动闭环
- 实现 18+ 工具，包括 EditFile 唯一性校验（对齐 Claude Code）、Exec stderr 分离、ReadFile 分页

**代码重构与架构优化**：

- 消除 NanobotConfig 与 NanobotRunner 的组件重复初始化（Config/LLMProvider/Dream/IdentityManager 各创建两次）
- AgentLoop 提取为独立配置类 `AgentLoopConfig`，与 `MessageBusConfig` 对齐
- ProviderFactory/ToolScanner/HookLoader 消除不必要实例化，改为纯静态工具类
- NanobotConfig.llmProvider() 改用 ProviderFactory 策略匹配，替代硬编码 DeepSeekProvider
- StreamResponseCallback 全面迁移到 Fan-Out Pub-Sub，AgentLoop 零耦合消费者
- `/clear` 命令实现全通道事件通知（SSE/CLI/WS 同步清空）
- sessionResponses 过期清理 daemon 线程，防止断连客户端内存泄漏

**踩坑与修复**：

- 双 AgentLoop 竞争消费问题 → NanobotConfig 统一 `@Bean` 创建
- SSE 连接被 Spring MVC 30s 超时截断 → 配置 300s
- AgentLoop 单线程阻塞 → 4 线程池异步消息处理
- 日志配置被 YAML 和 logback.xml 多重覆盖 → `--logging.config` 从源头替换

### R — Result（成果）

**工程指标**：

- 129 源文件，~27,000 行 Java 代码，11 个测试类（60 个测试用例）
- 18+ 内置工具 + @ToolDef 注解扫描 + MCP 动态工具，4 种权限模式，4 种交互通道
- 异步消息总线 Fan-Out Pub-Sub 支持 HTTP/SSE/WebSocket/CLI 四种通道，核心代码零重复

**技术成果**：

- 自研 Agent 运行时基础设施，非任何框架封装
- 安全模型达到生产级：4 步管道、纵深防御、交互确认
- 编程工具细节对齐 Claude Code（EditFile 唯一性校验、Exec stderr 分离、ReadFile 分页）
- 三维度工程（提示词/上下文/Harness）完整落地
- 19 个并发场景系统化分析，覆盖 14 种并发原语

**个人成长**：

- 深入理解了 LLM 的 tool calling 机制、流式处理和上下文窗口管理
- 实践了消息总线、状态机、管道模式、Fan-Out Pub-Sub 等架构模式在 AI 场景的应用
- 积累了从零搭建复杂系统、持续重构、踩坑修复的完整经验

---

## 五、性能优化设计

### 5.1 吞吐量优化

| 优化点 | 实现 | 效果 |
|------|------|------|
| **并行工具执行** | AgentRunner.toolExecutor(4线程) + ToolRegistry.executor(cpu×2) 双层线程池 | 多工具场景 2-3x 加速 |
| **异步消息处理** | messageExecutor(4线程) 异步处理，主循环只负责消费入队 | LLM 调用不阻塞后续消息 |
| **零拷贝扇出** | Dispatcher 同一份 msg 引用扇出，不复制数据 | 零内存浪费 |
| **Provider HTTP 连接池** | HttpClient 默认复用 TCP 连接 | 减少 TLS 握手开销 |

### 5.2 延迟优化

| 优化点 | 实现 | 效果 |
|------|------|------|
| **流式输出** | SSE chunk → publishToOutboundQueue → Dispatcher → emitter.send() | 用户感知延迟 < 500ms |
| **轻量 onDelta** | 回调内只做 outboundQueue.put()，扇出异步完成 | 避免 IO 线程阻塞 |
| **工具定义缓存** | volatile cachedDefinitions 避免重复序列化 | 减少 JSON 序列化 CPU |

### 5.3 内存优化

| 优化点 | 实现 |
|------|------|
| **有界队列** | inboundQueue(100) + outboundQueue(1000) + subscriberQueue(500) 全有界 |
| **零订阅者守护** | subscriberQueues.isEmpty() → 直接 return，防止 outboundQueue 无穷堆积 |
| **sessionResponses 清理** | cleanup daemon 每 2min 清超过 5min TTL 的残留条目 |
| **大文件分页** | ReadFileTool 默认 2000 行，offset+limit 分页 |
| **历史增量追加** | history.jsonl 逐行追加而非全量重写 |

### 5.4 可靠性保障

| 保障点 | 实现 |
|------|------|
| **工具重试** | 超时/连接错误重试 3 次，间隔 1s |
| **连续失败降级** | 3 次全失败 → callLLMWithoutTools() |
| **预算控制** | maxTurns + maxCost 双重上限 |
| **上下文压缩** | usage > budget × 90% → LLM 总结旧消息 |
| **背压链** | subscriberQueue 满 → offer 丢弃 → outboundQueue 积压 → put 阻塞 → TCP 窗口缩小 → LLM 发送变慢 |
| **Graceful Shutdown** | JVM Shutdown Hook 按序关闭 MCPManager→AgentLoop→MessageBus→ToolRegistry |

### 5.5 设计取舍（Trade-offs）

| 取舍 | 选择 | 原因 |
|------|------|------|
| Token 估算精度 vs 依赖复杂度 | char/4 估算（~20%误差） | 避免引入 tiktoken 复杂度 |
| 同步 vs 异步子 Agent | 默认同步，大任务异步 | 简单场景开销小，复杂场景不阻塞 |
| 全量搜索 vs 索引 | 全量 Grep（ripgrep 语义） | 项目 < 10K 文件时全量搜索足够快 |
| 重建 vs 字段 setter | setDream 时重建 State Handler | final 不可变 = 天然线程安全 |
| 创建 vs 静态 | ProviderFactory/ToolScanner/HookLoader 用静态方法 | 无实例状态，无需 new |

---

## 六、三维度工程深度拆解

### 6.1 提示词工程（Prompt Engineering）

#### System Prompt 组装流水线（BuildState，按优先级）

```
❶ 身份 + 日期指令（首位效应）— IdentityManager.getSystemPrompt(currentDate)
❷ SOUL + IDENTITY + USER 身份详情
❸ 联网搜索模式指令（条件注入：useSearch=false → 禁用 web_search）
❹ 长期记忆检索注入（Dream.retrieve(query, 5) → 关键词匹配+重要性排序）
❺ NANOBOT.md 项目上下文 ★ 对标 CLAUDE.md
❻ Skills 技能目录 ★ 渐进式加载第一层（仅名称+描述，~50 token）
❼ Rules 规则注入（RuleManager.getRulesPrompt()）
❽ 工具结果格式说明（近因效应）— [TOOL_OK]/[TOOL_ERR]
```

#### 关键技术点

| 技术 | 实现 | 原理 |
|------|------|------|
| **首位效应** | 身份+日期放 prompt 最开头 | 模型对早期 token 注意力权重最高 |
| **近因效应** | 工具格式说明放 prompt 最末尾 | 模型对最近 token 记忆最强 |
| **对抗训练偏差** | 日期+身份双重显式声明 | DeepSeek 训练数据自称 Claude |
| **条件注入** | useSearch → 动态选择工具指令 | 减少不必要 token 消耗 |
| **渐进式技能加载** | System Prompt 仅注入目录，LLM 按需调 use_skill | 对标 Claude Code，tools 数组不膨胀 |
| **长期记忆检索** | Dream.retrieve(query,5) → 关键词+重要性排序 | 跨会话上下文注入，<1ms 延迟 |

### 6.2 上下文工程（Context Engineering）

#### 记忆三层架构

```
第一层: 短期记忆 — history.jsonl → RestoreState 加载 → messages[]
第二层: 中期压缩 — Consolidator，token>90%budget → LLM 总结 → system 替换
第三层: 长期记忆 — Dream 全自动闭环：提取→节流(5000字符)→JSON解析→MEMORY.md→检索注入
              + NANOBOT.md 项目记忆 + TaskStore 任务追踪 + /compact + /remember 手动触发
```

### 6.3 Harness 工程（Runtime Infrastructure）

#### Agent 循环引擎

```
用户消息 → InboundMessage → MessageBus.inboundQueue
  → AgentLoop daemon 消费 → messageExecutor(4线程) → 8 状态机
    → RunState → AgentRunner.runInternal()
      → provider.chatStream() → onDelta → publishToOutboundQueue()
        → Dispatcher 扇出 → SSE/CLI/WS consumer poll
      → tool_calls? → executeTools() (CompletableFuture.allOf) → 递归
    → SaveState → Dream.extractAndStore() (fire-and-forget)
  → RespondState → publishOutbound(sessionResponses)
```

#### 流式输出架构（Fan-Out Pub-Sub）

```
DeepSeek SSE → onDelta.accept(delta)
  → RunState.publishToOutboundQueue(msg)
    → outboundQueue.put(msg)  [背压]
      → Dispatcher daemon poll(1s) → 遍历 subscriberQueues (COW)
        → offer(msg, 100ms)  // 非阻塞
          ├── SSE-consumer → emitter.send(delta)
          ├── CLI-consumer → System.out.print(delta)
          └── WS-consumer  → session.sendText(json)
```

#### 核心扩展点

| 扩展点 | 机制 | 说明 |
|------|------|------|
| 子 Agent | SpawnTool → AgentCoordinator | 4 种分配策略，同步+异步(inbox) |
| 任务追踪 | TaskCreate/List/Update → TaskStore | JSON 文件持久化 |
| MCP 协议 | MCPManager → StdioMCPClient/HttpMCPClient | stdio/sse/streamableHttp |
| 技能系统 | SkillManager + use_skill 元工具 | .nanobot/skills/ 目录加载 + 渐进式激活 |
| @ToolDef 注解 | ToolScanner 类路径扫描 | 方法级注解自动注册 |
| LLM 多后端 | LLMProvider 接口 + ProviderFactory | DeepSeek + OpenAI 统一适配 |
| Channel 适配 | MessageBus Fan-Out Pub-Sub | HTTP/SSE/WS/CLI 零重复代码 |

---

## 七、三维度总览图

```
              ┌─────────────────────────────────────────┐
              │    提示词工程（Prompt Engineering）      │
              │  System Prompt 8 步组装流水线            │
              │  首位+近因效应 → 对抗训练偏差             │
              │  Skills 渐进式加载（对标 Claude Code）    │
              │  NANOBOT.md + Dream 记忆自动注入          │
              └──────────────────┬──────────────────────┘
                                 │
              ┌──────────────────▼──────────────────────┐
              │    上下文工程（Context Engineering）       │
              │  8 状态 State 模式 → 对话生命周期         │
              │  三层记忆: 短期 → 中期压缩 → 长期(Dream)  │
              │  Consolidator + Dream 全自动闭环          │
              │  /compact + /remember 手动触发            │
              └──────────────────┬──────────────────────┘
                                 │
              ┌──────────────────▼──────────────────────┐
              │    Harness 工程（Runtime Infrastructure）  │
              │  三队列 MessageBus + Fan-Out Pub-Sub     │
              │  工具管道: Hook→Guard→Rule→Mode→Execute  │
              │  双层线程池并行工具执行 + 背压链          │
              │  MCP / SSE / WebSocket / CLI             │
              └─────────────────────────────────────────┘
```

---

## 八、设计模式深度解析

### 8.1 状态机模式 —— 对话全生命周期

LLM 调用不是 input→output，一次对话需 7+ 步骤。8 状态 State 模式 + processStates() 循环→DONE。和 TCP 状态机、订单状态机同构。

### 8.2 管道/责任链 —— 四步权限检查

PreToolUse Hook→Guards→RuleEngine→PermissionMode，每层独立，不同短路语义。不是简单 if-else 链。

### 8.3 发布-订阅模式 —— 流式多渠道分发

MessageBus Fan-Out Pub-Sub：RunState 发布到 outboundQueue，Dispatcher daemon 扇出到各 subscriberQueue（COW 管理）。SSE/CLI/WS 各自独立 poll，同一份引用零拷贝。COW 无锁遍历，offer() 确保慢消费者不阻塞其他通道。

### 8.4 策略模式 —— 子Agent分配 + LLM Provider 匹配

AgentCoordinator 4 种分配策略(DIRECT/CAPABILITY_MATCH/ROUND_ROBIN/PARALLEL)。ProviderFactory 按模型名前缀匹配 Provider（deepseek*→DeepSeek，gpt-*→OpenAI）。

### 8.5 门面模式 —— LLM 多Provider适配

LLMProvider 接口 + Message 统一数据模型。DeepSeek/OpenAI 的 tool_calls 格式、流式结束标记、系统消息处理差异全部封装在 buildRequestBody() 中。

### 8.6 Builder + 验证 —— 复杂消息对象

InboundMessage.builder() 链式调用。Builder 嵌入 validate() 自动校验必填字段，防御性拷贝 metadata/media。

### 8.7 适配器 —— MCP 外部工具

MCPToolWrapper implements Tool，将 JSON-RPC over stdio 适配为 Tool.execute()。适配后自动享受全部安全检查基础设施。

### 8.8 工厂 + 模板方法 —— 工具执行框架

ToolResult.ok()/err()/wrap() 工厂方法自动标记 [TOOL_OK]/[TOOL_ERR]。ToolRegistry.execute() 作为模板方法，统一参数校验→安全检查→执行→结果包装。

### 8.9 注册表模式 —— @ToolDef 注解扫描

ToolScanner.scanAndRegister()（静态方法）类路径扫描 + ToolRegistry.register()，支持 implements Tool 和 @ToolDef 方法注解两种注册方式共存。

---

## 九、并发控制深度解析（核心问题篇）

> 面试官常追问："你们项目中有哪些并发场景？怎么处理的？为什么这样选？"
> 本章逐场景分析所有并发控制，每个场景回答三个问题：**为什么需要、不用会怎样、为什么选这个方案**。

### 9.1 并发架构全景

```
                          ┌─────────────────────────────┐
                          │    HTTP 线程池 (~200)        │
                          │    WebSocket / CLI 线程      │
                          └─────────────┬───────────────┘
                                        │ publishInbound()
                                        ▼
                          ┌─────────────────────────────┐
                          │   ArrayBlockingQueue(100)   │  有界阻塞 (防 OOM)
                          │         inboundQueue        │  生产者阻塞 = 背压
                          └─────────────┬───────────────┘
                                        │ consumeInbound(1s)
                                        ▼
                          ┌─────────────────────────────┐
                          │  AgentLoop daemon 线程       │  单线程消费
                          │  → submit 到 worker 池       │
                          └─────────────┬───────────────┘
                                        │
                          ┌─────────────▼───────────────┐
                          │  AgentLoop-worker ×4 线程池  │  异步 8 状态机
                          └──┬──────────────────────┬───┘
                             │                      │
              流式 token 发布│                      │最终响应
                             ▼                      ▼
                ┌────────────────────┐   ┌─────────────────────┐
                │  outboundQueue     │   │  sessionResponses   │
                │  ABQ(1000)         │   │  ConcurrentHashMap  │
                └────────┬───────────┘   └─────────────────────┘
                         │
                ┌────────▼───────────┐
                │ Dispatcher daemon  │  Fan-Out：同一份引用扇出
                │ CopyOnWriteArray   │  零数据复制
                │ List subscriberQs  │
                └──┬───────┬──────┬──┘
                   │       │      │
          ┌────────▼─┐ ┌──▼───┐ ┌▼──────────┐
          │SSE-consumer│ │CLI-  │ │WS-consumer│  各通道独立 poll
          └────────────┘ └──────┘ └───────────┘
```

**核心简化策略**：单线程消费入站队列 → 同一会话不会并发处理 → 消除了最复杂的并发场景。

### 9.2 线程清单

| 线程名 | 数量 | 类型 | 职责 |
|--------|------|------|------|
| `AgentLoop` | 1 | 长驻 daemon | poll inboundQueue(1s) → submit worker |
| `AgentLoop-worker` | 4 | 线程池 | 异步处理 8 状态机 |
| `MessageBus-dispatcher` | 1 | 长驻 daemon | poll outboundQueue → 扇出 |
| `MessageBus-cleanup` | 1 | 长驻 daemon | 每 2min 清理过期 sessionResponses |
| `SSE-consumer-{uuid}` | 每连接 | 短命 | poll subscriberQueue → emitter.send() |
| `CLI-consumer` | 1 | 长驻 daemon | poll subscriberQueue(500ms) → stdout |
| `WS-consumer` | 1 | 长驻 daemon | poll subscriberQueue(1s) → sendText() |
| `ToolExecutor` | cpu×2 + 4 | 线程池 | 并行执行工具调用 |
| `Subagent-{id}` | 每 Agent 1 | 长驻 daemon | 子 Agent 独立运行 |

一条用户消息从到达到响应，经过 **4-5 个线程边界**。每个边界都有明确目的（解耦、隔离、缓冲）。

### 9.3 MessageBus — 三队列 + Fan-Out Pub-Sub

**并发原语**：`ArrayBlockingQueue(100)` + `ArrayBlockingQueue(1000)` + `ConcurrentHashMap` + `CopyOnWriteArrayList` + `AtomicBoolean`

**为什么 ArrayBlockingQueue 而不是 LinkedBlockingQueue？** ABQ 有界防 OOM。队列满 `put()` 阻塞生产者 = 自然背压。只有 1 消费者，LinkedBlockingQueue 的双锁优势不存在。

**Fan-Out Pub-Sub 流程**：

```
RunState → publishToOutboundQueue(msg) → outboundQueue.put(msg)
  → Dispatcher daemon poll(1s) → 遍历 subscriberQueues (COW)
    → offer(msg, 100ms)  // 非阻塞，队列满丢弃该订阅者
```

**为什么 Fan-Out？** `BlockingQueue.take()` 是破坏性消费——一条消息只能一个消费者取走。三通道都需要同一条数据，必须独立队列。同一份引用扇出，零数据复制。

**零订阅者守护**：`subscriberQueues.isEmpty()` 时直接 return，防止无消费者时 outboundQueue 积压阻塞 RunState。

**sessionResponses 清理**：cleanup daemon 每 2 分钟清理超 5min TTL 的残留条目，防断连内存泄漏。

### 9.4 SessionManager — 每会话独立锁

**并发原语**：`ConcurrentHashMap<String, Object>` + `synchronized(lock(sessionKey))`

```java
private Object lock(String sessionKey) {
    return sessionLocks.computeIfAbsent(sessionKey, k -> new Object());
}
```

**为什么每会话独立锁？** 不同会话互不相关，全局锁让它们互相阻塞。每会话独立锁让 session-A 和 session-B 完全并行。**为什么锁 Object 而非 sessionKey 字符串？** String intern() 可能导致无关代码共享锁。`computeIfAbsent` 原子性保证两线程同时创建同一 key 的锁对象时只有一个被执行。

### 9.5 SessionStore — 故意不用 ConcurrentHashMap

SessionStore 的 `dirCache` 用普通 `HashMap`。所有 public 方法只在 SessionManager 的 `synchronized` 块内被调用——锁在业务层，存储层保持简单。**不是所有共享数据都需要自己加锁**。

### 9.6 AgentLoop — final 不可变 = 天然线程安全

`setDream()`/`setConsolidator()` 不直接 set 字段，而是**重建**受影响的 State Handler：

```java
public void setDream(Dream d) {
    stateHandlers.put(TurnState.SAVE, new SaveState(sessionManager, d));
    stateHandlers.put(TurnState.BUILD, new BuildState(..., d, ...));
    stateHandlers.put(TurnState.COMMAND, new CommandState(..., d, ...));
}
```

每个 AgentState 的字段是 `final` 的。final + 不可变 = 天然线程安全。重建开销极小（启动时一次），换来了每个 State 内部零并发考虑。运行时 `stateHandlers` (EnumMap) 只读，无并发写。`volatile boolean planMode` 通过闭包访问，保证跨 worker 线程可见。

### 9.7 AgentRunner — CompletableFuture 链式异步 + 并行工具

`provider.chatStream()` 返回 `CompletableFuture` → `.thenCompose()` 链式递归。异步不阻塞 worker 池。`CompletableFuture.allOf(futures)` + toolExecutor(4线程) 并行执行多工具。AgentRunner 和 ToolRegistry 双层线程池限流。

### 9.8 TurnContext — volatile + 防御性拷贝

| 字段 | 类型 | 为什么 |
|------|------|--------|
| `finalContent` | volatile | SaveState 写，RespondState 读（可能不同 worker 线程） |
| `cancelled` | volatile | Esc 中断线程写，RunState 读 |
| `iteration` | AtomicInteger | 需要原子自增 |

`getMessages()` 返回 `new ArrayList<>(messages)` 防御性拷贝——外部修改不影响内部状态。

### 9.9 ToolRegistry — volatile 缓存 + 并行执行器

`volatile List<JsonNode> cachedDefinitions` 缓存工具定义。工具列表启动时注册（极少写），每次 LLM 调用都读取（极频繁读）。volatile 写后立即可见，读无锁。两线程同时 `== null` 重复计算不影响正确性。

### 9.10 Dream — 双 CHM + 可接受的不精确

`memories` (CHM) 跨会话共享，SaveState 写 + BuildState 读。`lastExtractionCharCount` (CHM) 的 `getOrDefault + put` 之间不原子——但跳过一次提取比重复提取代价小得多，这是**可接受的不精确**。

### 9.11 PermissionManager — volatile 模式切换

`volatile PermissionMode mode`：用户 `/mode bypass` → 下一轮工具调用立即可见，不用 volatile 会弹不该弹的确认。

### 9.12 CliChannel — volatile 中断标志

`volatile boolean cancelled`：JLine 线程写 → AgentLoop worker 读。用户按 Esc → 流式输出停止。不用 volatile 则用户按了 Esc 但 LLM 继续输出。

### 9.13 ChannelServer — synchronized + wait/notify (V1 演进)

V1 遗留：HTTP handler `synchronized(handler) { handler.wait(300000); }` 挂起等 LLM，流式回调 `synchronized(handler) { handler.notifyAll(); }` 唤醒。双层锁不同粒度。**设计批判**：CHM + synchronized(CHM) 冗余——CHM 的分段锁完全未使用，普通 HashMap 效果一样。V2 已迁移到 Fan-Out Pub-Sub。

### 9.14 MetricsHook — synchronized 多字段联合原子更新

`synchronized void addTokens(prompt, completion)`：AtomicInteger 只保证单变量原子性，promptTokens 和 completionTokens 必须一起更新。**多个字段联合原子更新必须用锁**。

### 9.15 WebSocket — static CHM + Pub-Sub 消费者

`static ConcurrentHashMap<String, Session> SESSIONS`（WS 容器为每连接创建新实例，非 static 无法跨连接查找）。应用启动时 `subscribeToOutbound()`，全局 WS-consumer 按 msg.sessionId 查表 sendText()。`AtomicBoolean wsConsumerRunning` + `static synchronized initStreamConsumer()` 保证只启动一次。

### 9.16 MCP — remove() 的原子性决胜

```java
// ✅ remove() 原子
CompletableFuture<MCPResult> future = pendingRequests.remove(requestId);
// ❌ get()+remove() 之间响应线程和超时线程竞态 → 重复 complete 异常
```

`remove()` 保证只有一个线程拿到非 null Future。

### 9.17 同步 vs 异步决策速查

| 场景 | 方式 | 决策依据 |
|------|------|---------|
| LLM 调用 | 异步 supplyAsync | 5-30s，不可阻塞 worker |
| 记忆提取 (SAVE) | fire-and-forget | 不能拖慢回复 |
| 记忆检索 (BUILD) | join() 同步等 | 检索结果必须注入 prompt |
| 对话压缩 | join() 同步等 | 压缩结果必须替换消息 |
| 工具执行 | 异步 + 线程池 | 可能并行 5+ 工具 |
| 子 Agent 派发 | fire-and-forget | 不阻塞用户 |
| 流式推送 | daemon poll | 长连接，推送模式 |

### 9.18 全项目并发原语速查表

| 并发原语 | 使用位置 | 为什么选它 |
|---------|---------|-----------|
| `ArrayBlockingQueue` | MessageBus inbound/outbound | 有界防 OOM + 背压 |
| `ConcurrentHashMap` | 15+ 处 | 分段锁，高并发 |
| `CopyOnWriteArrayList` | MessageBus subscriberQueues | 读多写极少，无锁遍历 |
| `synchronized(perKeyLock)` | SessionManager | 锁粒度最优 |
| `synchronized(method)` | MetricsHook.SessionMetrics | 多字段联合原子更新 |
| `CompletableFuture.thenCompose` | AgentRunner | 异步递归，不阻塞线程池 |
| `CompletableFuture + CHM` | MCP | CHM 路由 + Future 信号量 |
| `AtomicBoolean` | MessageBus, AgentLoop, WebSocket | 启停状态 + 可见性 |
| `AtomicInteger` | TurnContext, WebSocket | 轻量原子自增 |
| `volatile boolean` | 7+ 处 | 跨线程标志，零成本 |
| `volatile 引用` | ToolRegistry, PermissionManager | 读多写极少的缓存 |
| `final + 不可变` | AgentLoop stateHandlers | 无需同步的终极方案 |
| `HashMap` (无锁) | SessionStore.dirCache | 调用方已保证单线程 |

### 9.19 防御性设计模式

| 原则 | 实践 |
|------|------|
| **锁粒度最小化** | `synchronized(this)` → `synchronized(lock(sessionKey))` |
| **有界优于无界** | ABQ(100/1000/500) 而非无界 LinkedBlockingQueue |
| **简单优于复杂** | volatile ≥ synchronized ≥ ReentrantLock ≥ 自建锁 |
| **调用方负责并发控制** | SessionManager 加锁 → SessionStore 无锁 |
| **单线程消费是最大简化** | AgentLoop 单 daemon 消费，同一会话不并发处理 |
| **零订阅者守护** | subscriberQueues.isEmpty() → 直接 return，防阻塞 |
| **不可变 > 同步** | final State Handler 字段，重建替代 setter |
