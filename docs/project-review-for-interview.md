# Nanobot-Java —— 手搓AI Agent项目总结文档

> **项目定位*：从零自研的 Java版AI Agent 运行时基础设施（非 LangChain 套壳，无SpringAI框架），参考港大 Nanobot 架构
> 并融合 Claude Code 设计理念。129 源文件，~27,000 行 Java 代码，通过claudecode+deepseek+vibe-coding 1人独立完成。

---

## 一、项目亮点（What makes this special）

### 1.1 自研 Agent 基础设施，而非 API 封装

市面上大部分 "AI Agent" 项目本质是 LangChain/Spring AI 的配置封装。本项目**手写实现了 Agent 运行时需要的全部基础设施**：

- **消息总线**：异步生产者-消费者模式，解耦 LLM 调用与通道接入
- **工具注册中心**：27 个工具的注册、发现、执行、权限校验统一入口
- **四步安全管道**：Hook → Guards × 3 → RuleEngine → PermissionMode，纵深防御
- **流式引擎**：Fan-Out Pub-Sub，RunState → outboundQueue → Dispatcher 扇出，SSE/WebSocket/CLI 三通道独立消费

### 1.2 异步消息总线 —— 架构级亮点

这是整个项目中最体现系统设计能力的模块。传统同步方案（如 mewcode）是：

```
HTTP 请求 → 阻塞等 LLM → 返回结果
```

问题是：一个通道绑定一个 LLM 实例，加 CLI、加 WebSocket、加 Telegram 都要改核心代码。

我采用了**异步消息总线**架构：

```
                    ┌──────────────┐
    HTTP sync  ────→│              │
    SSE        ────→│  MessageBus  │──→ AgentLoop（唯一核心，零改动）
    WebSocket  ────→│              │
    CLI        ────→│              │
                    └──────┬───────┘
                           │
                 ┌─────────┴─────────┐
                 │  Fan-Out Pub-Sub  │
                 │  (Dispatcher扇出)  │
                 ├───────────────────┤
                 │ SSE  → emitter    │
                 │ WS   → sendText   │
                 │ CLI  → stdout     │
                 │ sync → sessionRes │
                 └───────────────────┘
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

每个状态是独立的 `doXxx()` 方法，`processState()` 通过 switch 分发，`processStates()` 循环执行直到 DONE。

**工程价值**：
- **可测试性**：每个状态独立测试，Mock 上下文即可验证单个状态逻辑
- **可扩展性**：加新状态只需在枚举加值 + 写 `doXxx()` 方法，不影响现有流程
- **可观测性**：每个状态执行前后打印日志，问题定位精确到具体状态
- **与消息总线配合**：状态机是处理单条消息的状态流转，消息总线负责多条消息的排队调度

这和 TCP 状态机、JVM 类加载器等经典系统设计思路一致：**将复杂生命周期建模为有限状态机，是复杂系统的通用解**。

### 1.4 设计模式全景 —— 从 SOLID 到企业级模式

本项目不是简单的 CRUD，手写 Agent 运行时过程中自然应用了大量设计模式：

| 模式 | 应用场景 | 实现位置 |
|------|------|------|
| **状态机模式** | 对话生命周期管理 | `AgentLoop` 8 状态枚举 |
| **生产者-消费者** | 消息入队/出队解耦 | `MessageBus` + `AgentLoop` |
| **发布-订阅模式** | 流式输出多渠道分发 | `MessageBus` Fan-Out Dispatcher |
| **管道/责任链** | 四步权限检查 | `PermissionManager.check()` |
| **策略模式** | 子Agent分配策略 | `AgentCoordinator` DIRECT/CAPABILITY/ROUND_ROBIN/PARALLEL |
| **工厂模式** | ToolResult 创建 | `ToolResult.ok()` / `ToolResult.err()` / `ToolResult.wrap()` |
| **Builder 模式** | 复杂对象构建 | InboundMessage.builder() / OutboundMessage.builder() |
| **门面模式** | LLM 多 Provider 适配 | `LLMProvider` 接口统一 Anthropic/OpenAI/DeepSeek |
| **适配器模式** | MCP 工具包装 | `MCPToolWrapper` 将 MCP 协议适配为 Tool 接口 |
| **单例/多例** | Spring Bean 管理组件生命周期 | `NanobotConfig` @Bean + @Scope |
| **模板方法模式** | 工具执行前后处理 | `ToolRegistry.execute()` 统一参数校验+权限+结果包装 |
| **注册表模式** | 工具发现与注册 | `ToolRegistry` 注册/查找/执行 |

**可扩展性设计**：
- **加新工具**：实现 Tool 接口 or 方法级 @ToolDef 注解 → 自动注册
- **加新通道**：实现 ~60 行适配器 + `messageBus.subscribeToOutbound()` → 零改动核心
- **加新安全策略**：实现 Guard 接口 or 注册 Rule → 管道自动编排
- **加新 LLM Provider**：实现 LLMProvider 接口 → AgentRunner 透明切换
- **加新记忆类型**：扩展 Dream/MemoryLoader → 系统提示词自动注入

### 1.2 三维度工程体系化落地

| 工程维度       | 核心产出                                  | 价值                      |
| ---------- | ------------------------------------- | ----------------------- |
| 提示词工程      | 条件注入、首位+近因效应、NANOBOT.md 项目记忆          | 证明理解 LLM 行为，不是写死 prompt |
| 上下文工程      | 8 状态机、三层记忆、token 预算管理、自动压缩            | 证明理解 LLM 局限，有工程化方案      |
| Harness 工程 | 异步消息总线、Hook/Guard/Rule/Mode 管道、并发工具执行 | 证明有系统设计能力，不是脚本级代码       |

### 1.3 安全和可靠性达到生产级

- 14 条 CommandGuard 默认拒绝规则 + 可扩展配置
- 3 次重试 + 连续失败降级（LLM 无工具回答）+ maxTurns/maxCost 预算控制
- EditFile 唯一性校验（对齐 Claude Code）、大文件自动分页
- 工具结果结构化标记 `[TOOL_OK]`/`[TOOL_ERR]` 帮助 LLM 判断

---

## 二、技术难点（Real engineering challenges）

### 难点 1：异步消息总线下多通道并发 —— 4 层根因 + 5 项修复

**背景**：采用异步消息总线架构（HTTP 线程发消息 → MessageBus → AgentLoop 异步消费 → callback 返回）。上线后发现第 2 轮对话起前端无响应。

**问题链**（4 层根因逐层剥离）：
1. Spring MVC 默认 30 秒异步超时 → SSE 连接被 Spring 截断 → `AsyncRequestTimeoutException`
2. `onTimeout` 没清理 callback → 僵尸 callback 堆积在列表中 → 新请求遍历到已关闭的 emitter → `IllegalStateException`
3. AgentLoop 单线程串行阻塞 → 上一条 LLM 卡住 → 新消息无法消费 → `dataCount=0`
4. Spring Bean 和 NanobotRunner 分别创建 AgentLoop → **共享同一个 MessageBus 竞争消费** → 消息被错误实例抢走 → callback 不匹配

**修复过程**（5 项改动，逐层解决）：
- `spring.mvc.async.request-timeout=300000` → 解决 30s 截断
- `onTimeout`/`onCompletion` 中 `unsubscribeFromOutbound()` → 解决僵尸 subscriberQueue
- `messageExecutor` 线程池异步处理 → 解决主循环阻塞
- 消除双 AgentLoop，`@Autowired` 统一注入 → 解决竞争消费
- Fan-Out Pub-Sub（Dispatcher + CopyOnWriteArrayList subscriberQueues）→ 解决 SSE/WS 互相覆盖

**工程亮点**：`StreamCallbackLifecycleTest` 全面改写为 Pub-Sub 模式，模拟双订阅者并发 + unsubscribe 清理验证修复。

### 难点 2：四步权限管道设计 —— 可扩展的安全模型

**挑战**：不能写死 if-else。需要支持新增守卫、新增规则、新增模式、外部 hook 脚本，且执行顺序固定。新手开发者加一个安全策略不能影响现有逻辑。

**设计权衡**：
- 守卫层 vs 规则层的边界：守卫是安全底线（永远执行），规则是用户偏好（可配置）
- deny 语义设计：Claude Code 采用 deny 优先（deny 不可被 allow 覆写），我这边也采用 fail-closed 策略
- Hook 层优先级：外部脚本必须在所有规则前执行（deny 可拦截一切），但 allow 不能跳过 guard

**最终方案**：
```
PreToolUse Hook → Guards(PathGuard/CommandGuard/NetworkGuard) → RuleEngine(deny>ask>allow) → PermissionMode(4种)
```
- 管道模式，每层职责清晰，新增守卫只需实现接口 + 注册
- 规则引擎支持正则匹配工具名+参数值，deny 不可被 allow 覆盖
- Hook 支持 deny/allow/modify/passthrough 四种语义
- 所有检查统一在 `ToolRegistry.execute()` 执行，零绕过风险

### 难点 3：流式输出的多渠道分发 —— 一份数据四通道零重复

**挑战**：SSE、WebSocket、CLI 三种通道对 LLM 流式输出的消费方式完全不同——SSE 写入 HttpServletResponse 的 OutputStream、WebSocket 发送 JSON 帧、CLI 直接 System.out。核心 Agent 逻辑不能为每种通道写一份。

**错误方案对比**：
- ❌ 每种通道在 doRun() 里写一个 if-else → 加通道改核心代码
- ❌ 把流式数据写入 MessageBus 出站队列 → 早高峰消息堆积
- ✅ 回调列表模式 → 通道注册回调，核心只遍历通知

**最终方案**：Fan-Out Pub-Sub 架构。RunState 通过 `publishToOutboundQueue()` 发布每个流式 token 到 `outboundQueue(1000)`，Dispatcher daemon 线程扇出到 `CopyOnWriteArrayList` 管理的各 subscriberQueue。SSE/CLI/WS 各通道独立 poll 自己的队列，按 sessionId+requestId 过滤。零数据复制（同一份 msg 引用），背压逐层传递（subscriberQueue → outboundQueue → TCP）。加新通道只需 3 行 subscribe。

### 难点 4：工具失败降级 —— 防止 LLM 无限重试

**挑战**：工具执行失败不是异常退出——结果是 "Error: ..." 字符串返回给 LLM。LLM 看到错误会换方式再试，最多循环 100 次（maxToolIterations 默认值），每次调 DeepSeek API 都是真金白银。

**设计决策**：在哪层计数？重试几次？降级策略是什么？

**方案**：
- 在 AgentRunner 递归调用中增加 `consecutiveToolFailures` 计数器
- 本轮工具全部返回 `[TOOL_ERR]` → counter+1，任一成功 → 清零
- 连续 3 次全部失败 → `callLLMWithoutTools()` → 不传 tools 参数，强制 LLM 用自身知识回答
- 单工具重试（超时/连接错误 3 次）和连续失败降级（3 轮）是两个独立机制

### 难点 5：CLI 模式的零日志输出 —— 6 次失败尝试

**挑战**：CLI 交互式对话要求控制台只显示对话内容和 `>` 提示符，零条日志。但系统有三重日志配置源：
- `logback.xml` 硬编码 `<logger name="com.nanobot" level="DEBUG"/>`
- `application.yml` `logging.level.com.nanobot: DEBUG`
- Spring Boot 的 `LoggingSystem` 在启动时重新应用配置

**6 次失败尝试**：
1. `System.setProperty("logging.level.root", "WARN")` → logback.xml 的 per-logger 配置优先级更高
2. `logback.configurationFile` 系统属性 → Spring Boot 不走这个属性
3. `SpringApplication.setDefaultProperties()` → 默认属性优先级低于 YAML
4. `--logging.level.root=WARN` CLI args → YAML 中 `com.nanobot: DEBUG` 是 per-logger，不被 root 覆盖
5. `LoggerContext.reset()` + 程序化设 Level → 初始生效，但 Spring LoggingSystem 重新读取 logback.xml 覆盖
6. **终局方案**：`--logging.config=classpath:logback-cli.xml` → **从源头替换配置文件**，CLI 专用 logback 只有 root WARN，毫无残留

**教训**：日志级别是配置源优先级问题，不是 API 调用时序问题。用程序化 API 对抗声明式配置永远是输——正确做法是从源头替换。

### 难点 6：DeepSeek 模型的身份偏差 —— 系统提示词对抗训练数据

**挑战**：DeepSeek-chat 模型在训练数据中自称 "Claude by Anthropic"。即使系统提示词说"你是 my-nanobot"，模型仍回答 "我是 Claude"。这是训练数据偏差，不是 prompt 写错了。

**尝试过程**：
- 第一次：在系统提示词中间写"你不是 Claude"→ 被模型忽略
- 第二次：放到系统提示词开头（首位效应）→ 略有改善，但仍偶尔自称 Claude
- 第三次：首位 + 近因效应双重声明 + 加 `get_current_time` 工具让模型主动获取真实日期 → 基本解决
- 最终：系统提示词开头和结尾分别声明，CLI 模式在提示词中额外注入 "当前真实日期"，对抗训练数据中的旧日期

**工程价值**：深刻理解了"LLM 不是执行精确指令的计算机，而是基于概率分布生成文本"这一本质。提示词工程的核心是理解模型的注意力机制和训练数据偏差，而非单纯堆砌指令。

### 难点 7：工具接口设计 —— 平衡简洁性与可扩展性

**挑战**：工具系统需要同时满足三个矛盾需求：
1. 核心工具接口足够简单，新人 5 分钟能上手写一个工具
2. 支持自动发现和注册（@ToolDef 注解扫描）
3. 工具参数需要 JSON Schema 自动生成（给 LLM 的 function calling 用）

**设计演变**：
- 初版：所有工具手动 `new XxxTool()` 后 `registry.register()` → 加工具要改注册代码
- 二版：方法级 `@ToolDef` 注解 + ToolScanner 类路径扫描 → 普通 POJO 方法自动注册，但参数类型需手动映射
- 最终：双模式共存 —— `implements Tool`（复杂工具）和 `@ToolDef`（简单工具）互不干扰

**关键决策**：
- 为什么不用 Spring 的 `@Component` 扫描？因为 V1 版本无 Spring，需保持独立性
- 为什么 Tool 接口的 `execute()` 返回 `CompletableFuture<Object>` 而非 `String`？支持长时间异步工具（网络请求）不阻塞线程池
- 为什么参数用 `Map<String, Object>` 而非强类型 DTO？LLM 生成的参数是动态 JSON，用 Map 避免反序列化失败

**成果**：27 个工具中，9 个用 `@ToolDef` 方法级注解（BuiltinTools），7 个实现 Tool 接口。两者共存，互不干扰。

### 难点 8：三版本架构设计 —— 一套核心支持 3 个入口

**挑战**：项目需要同时支持 3 种启动方式，核心代码不能重复，但各版本的依赖和配置完全不同：
- V1：纯 Java，无 Spring Boot，自建 HTTP 服务器
- V2：Spring Boot，Tomcat + MVC + WebSocket，完整 Web 服务
- V3：Spring Boot（仅 DI），无 Web 服务器，CLI 终端交互

**设计约束**：
- Spring 的 `@SpringBootApplication` 会自动扫描同包及子包的 `@Configuration` 类
- 如果 V2 和 V3 的 `@Configuration` 互相可见，会互相创建对方的 Bean → V2 启动时可能误启动 CLI
- 共享的 `NanobotRunner` 需要同时被 V2 和 V1 使用，但不能在 V3 中重复创建 AgentLoop

**方案**：
- 包结构：`v1/` `v2/` `v3/` 各自独立，公共模块在父包
- `scanBasePackages = "com.nanobot"` 统一扫描，但用 `@Profile("cli")` 隔离 V3 的 Bean
- V3 设置 `spring.main.web-application-type=none` 禁止 Web 服务器启动
- V1 完全独立，不依赖任何 Spring 注解

**成果**：三版本文件数 5+8+3=16，核心模块 101 个文件零版本耦合。

### 难点 9：记忆系统三层架构设计 —— 从手动维护到全自动闭环

**挑战**：对话历史、token 预算、长期记忆是三个不同时间尺度的需求，如果混在一起会互相干扰——压缩太激进丢失上下文，太保守撑爆窗口。

**架构演进**：
```
v1 (已废弃):
  短期 (history.jsonl)    → 对话级别，JSONL 增量追加
  中期 (Consolidator)     → usage>90%budget → LLM 总结 → system 替换
  长期 (MemoryStore)      → 手动维护 MEMORY.md/SOUL.md/USER.md，从未接入AgentLoop
  问题: MemoryStore 是死代码(420行)，Dream 从不解析LLM返回的JSON(只返回空列表占位)

v2 (当前):
  短期 (history.jsonl)    → 不变
  中期 (Consolidator + /compact 命令) → 自动压缩 + 手动触发
  长期 (Dream + MemoryLoader) → SAVE状态自动触发提取 → 增量节流(5000字符/1500token阈值) → Jackson解析JSON → 自动持久化MEMORY.md → BUILD状态注入System Prompt
  手动入口: /remember 跳过节流立即提取, /compact 跳过阈值立即压缩
```

**关键设计决策**：
- **删除 MemoryStore（420 行）**：与 Dream 职责重叠，且从未接入 AgentLoop。记忆管理统一由 Dream + MemoryLoader 负责
- **增量节流**：`EXTRACTION_MIN_NEW_CHARS = 5000`（≈1500 tokens），新增字符不足阈值时跳过 LLM 调用。首次提取(lastChars=0)不跳过
- **真正的 JSON 解析**：旧 Dream.parseMemoryResponse() 只返回空列表（注释写着"实际应用中应解析JSON"）。现在用 Jackson ObjectMapper 解析 LLM 返回的 JSON 数组
- **全自动闭环**：SaveState → Dream.extractAndStore() → BuildState.appendMemories()，无需人工干预
- **首次启动体验**：MemoryLoader/IdentityLoader 自动生成模板文件，不再静默返回空列表

### 难点 10：MCP 协议集成 —— 外部工具的发现、生命周期与故障隔离

**挑战**：MCP（Model Context Protocol）是 Cursor 提出的标准化外部工具协议，允许 Agent 动态连接外部工具服务器。集成难点在于：
1. 外部 MCP 服务器的进程生命周期管理（启动、心跳、重启、超时清理）
2. 动态工具发现：服务器启动后需查询其工具列表并注册到 ToolRegistry
3. 故障隔离：一个 MCP 服务器崩溃不应影响其他工具和 Agent 主流程

**设计决策**：
- 为什么支持 3 种传输？stdio（子进程，最常用）、sse（HTTP SSE，远程）、streamableHttp（HTTP 流式）—— 覆盖本地、远程、云三种部署场景
- MCPToolWrapper 模式：MCP 工具包装为 Tool 接口，对 ToolRegistry 透明——PermissionManager 的检查管道自动覆盖 MCP 工具，无需额外安全代码
- 工具命名规范：`mcp_{serverName}_{toolName}` 防止与内置工具冲突，同时支持 RuleEngine 中 `mcp_.*` 正则批量管控

**成果**：8 个文件实现完整 MCP 客户端协议栈，包括 JSON-RPC 消息编解码、stdio 进程管理、HTTP 客户端，对接外部工具的安全检查与内置工具完全一致。

### 难点 11：LLM 交互层的可靠性设计 —— API 适配、重试策略、循环控制

**挑战**：Agent 的"大脑"是外部 LLM API，天然不可靠——超时、限流、返回格式错误、tool_calls 不完整。交互层需要在不可靠的外部依赖之上构建可靠的 Agent 行为。

**11.1 多 Provider 适配的接口抽象**

DeepSeek 和 OpenAI 的 API 99% 兼容，但细节差异足以让 Agent 崩溃：
- tool_calls 中 `arguments` 字段：OpenAI 是 JSON 字符串，DeepSeek 有时返回已解析的 JSON 对象
- 流式结束标记：OpenAI 发 `[DONE]`，DeepSeek 发空 data
- 系统消息处理：DeepSeek 要求 system 消息必须在 messages[0]，且不能连续出现
- tool 结果消息格式：DeepSeek 不要求 `name` 字段，OpenAI 可选

**方案**：`LLMProvider` 接口 + `Message` 统一数据模型。每个 Provider 实现负责把内部格式转换为 API 格式，差异封装在 `buildRequestBody()` 中。上层代码只操作 `Message` 对象，零厂商耦合。

**11.2 工具调用的循环控制 —— 何时停，何时继续**

Agent 的核心循环是 `LLM → tool_calls → 执行 → 结果回注 → LLM → ...`。循环必须在正确的时机终止，否则就是烧钱的无底洞：

- **正常终止**：LLM 返回纯文本（`content != null && tool_calls == null`）
- **最大轮次终止**：`iteration >= maxTurns`，返回 "已达到最大轮次限制"
- **费用终止**：`cumulativeCost >= maxCost`，返回 "已超出费用预算"
- **失败降级终止**：连续 3 次工具全部返回 `[TOOL_ERR]` → 强制 `callLLMWithoutTools()`

**关键设计**：降级终止是"隐藏功能"——LLM 不知道系统在保护它。LLM 看到工具失败会换方式再试，系统在后台计数，到阈值直接切到无工具模式。这对用户体验是透明的，避免 "你已超出限制" 的冷冰冰提示。

**11.3 重试策略的粒度设计**

工具执行失败发生在两个层级，需要不同的重试策略：

| 层级 | 失败场景 | 重试策略 | 原因 |
|------|------|------|------|
| 单工具重试 | 超时、连接错误 | 3次，间隔1s | 网络抖动，重试大概率恢复 |
| 连续失败降级 | 所有工具返回 `[TOOL_ERR]` | 连续3轮→降级 | 不是网络问题，是工具逻辑问题（文件不存在、权限拒绝）|

**为什么不所有错误都重试？** 文件不存在的错误重试 3 次也不会突然存在，只会浪费 API 费用。区分 transient error（网络）和 logic error（业务）是关键设计。

**11.4 流式调用中的 onDelta 与循环的配合**

流式调用时，`provider.chatStream()` 接收一个 `onDelta` 回调，每收到一个 SSE chunk 就调用一次。但这个回调在不同 provider 中的触发时机不同：
- DeepSeek：模型每生成一个 token 触发一次（高频小块）
- OpenAI：类似，但 chunk 大小取决于网络 buffer

**潜在问题**：如果在 `onDelta` 中做重操作（如写文件、调外部 API），会阻塞流式接收，用户看到卡顿。

**方案**：`onDelta` 只做轻量操作——调用 `messageBus.publishToOutboundQueue()` 发到队列（publish端只管 put，剩下的 Dispatcher 异步扇出）。所有重操作（权限检查、文件写入）在 `runner.run()` 返回后统一处理。

### 难点 12：Skills 渐进式加载 —— 对标 Claude Code 的 use_skill 元工具

**挑战**：Skills 系统需要让 LLM 自主发现和调用技能，但不能每个 Skill 注册一个独立 tool——tools 数组会膨胀，LLM 工具选择准确率暴跌。

**方案对比**：

| | 方案A (每Skill独立tool) | 方案B (use_skill元工具) |
|------|------|------|
| tools 数组大小 | N个技能 = N个额外tool | 始终只多1个 |
| LLM 选择准确率 | 技能>10时暴跌 | 不受技能数量影响 |
| Skill 全文加载 | 自动注入（无法控制时机） | LLM 自主决定何时调用 |

**最终方案B — 两层渐进式加载**：

```
第一层: BuildState 注入技能目录（仅名称+描述，~50 token）
  "## 可用技能（Skills）
   - code-review — 代码审查：检查安全漏洞、代码质量
   - refactor — 结构化重构：识别代码异味
   - doc-generator — 文档生成：为Java类生成Javadoc
   - test-writer — 单元测试编写：JUnit5+Mockito+AssertJ
   - dependency-check — 依赖分析：检查pom.xml冲突和漏洞"

第二层: LLM判断需要某技能 → tool_calls=[{use_skill, name="code-review"}]
  → UseSkillTool.execute() 从 SkillRegistry 查到 SKILL.md 全文
  → 技能全文作为 tool 结果注入 → LLM 按指令执行
```

**关键设计决策**：
- **为什么用 function calling 而不是新协议？** 复用现有机制，零额外 API 依赖，DeepSeek/OpenAI 全支持
- **为什么 System Prompt 只放目录不放全文？** 5个技能全文 ~2000 token，目录 ~50 token。省下的 token 留给真正有用的上下文
- **手动 / 命令入口保留**：`/code-review` 在 CommandState 中拦截（内置命令优先级高于技能匹配），直接返回技能全文，不进 LLM
- **auto-trigger 开关**：每个 Skill 的 frontmatter 中有 `auto-trigger: true/false`，控制是否允许 LLM 自主匹配。dependency-check 设 false（涉及 pom.xml 变更，需用户明确确认）


---

## 三、核心功能清单

### 3.1 Agent 运行时

| 功能       | 实现                                                           |
| -------- | ------------------------------------------------------------ |
| 异步消息总线   | MessageBus（ArrayBlockingQueue + ConcurrentHashMap），生产者/消费者解耦 |
| 8 状态状态机  | RESTORE→COMPACT→COMMAND→BUILD→RUN→SAVE→RESPOND→DONE          |
| LLM 调用循环 | AgentRunner.runInternal() 递归，支持 tool_calls 自动迭代              |
| 流式输出     | DeepSeek SSE → onDelta → outboundQueue → Dispatcher 扇出 → SSE/CLI/WS 三通道 |
| 并行工具执行   | 只读工具线程池并发，写工具保持顺序，结果数组保证有序                                   |

### 3.2 工具系统（27 个）

| 类别    | 工具                                                    |
| ----- | ----------------------------------------------------- |
| 文件    | read_file(分页), write_file, edit_file(唯一性校验), list_dir |
| 搜索    | grep(regex+include过滤), glob(跳过.git/node_modules)      |
| Shell | exec(stderr分离, 默认120s/最大600s)                         |
| 网络    | web_search(4 providers), web_fetch(Jsoup)             |
| Agent | spawn(同步+异步inbox), spawn_check, ask_user(交互确认)        |
| 技能    | use_skill(元工具，一个工具管所有Skill，渐进式加载)                |
| 任务    | task_create/list/update(JSON持久化)                      |
| 内置    | BuiltinTools 10个(@ToolDef注解: base64编解码、四则运算、随机数、文本统计、日期时间) |
| 其他    | get_current_time                                           |

### 3.3 安全体系

| 组件                 | 说明                                     |
| ------------------ | -------------------------------------- |
| PathGuard          | 工作区隔离，toRealPath() 防止 `../` 绕过         |
| CommandGuard       | allowPatterns > denyPatterns，14 条默认拒绝  |
| NetworkGuard       | SSRF 防护，12 个 CIDR 范围                   |
| RuleEngine         | deny > ask > allow 优先级链，正则匹配           |
| PermissionMode     | PLAN / DEFAULT / ACCEPT_EDITS / BYPASS |
| InteractiveHandler | CLI 交互确认 `[y/N]`                       |
| PreToolUse Hook    | 外部脚本钩子，所有规则前执行                         |

### 3.4 通道

| 通道        | 技术                                                         |
| --------- | ---------------------------------------------------------- |
| HTTP REST | POST /api/chat, sessionResponses + requestId 精确匹配          |
| SSE 流式    | POST /api/chat/stream, SseEmitter + subscriberQueue poll |
| WebSocket | @ServerEndpoint /ws, onMessage → publishInbound → callback |
| CLI       | 类 Claude Code，Markdown 彩色渲染，当前目录即工作区                       |

### 3.5 上下文与记忆管理

| 功能               | 说明                                        |
| ---------------- | ----------------------------------------- |
| 会话持久化            | history.jsonl，JSONL 格式，按 sessionKey 隔离    |
| Token 压缩         | Consolidator 自动触发 + `/compact` 手动触发        |
| 长期记忆提取       | Dream 自动提取(5000字符节流) + `/remember` 手动触发 |
| NANOBOT.md       | `/init` 生成，BuildState 自动注入 system prompt   |
| 技能目录注入       | BuildState 注入技能名称+描述，LLM 自主匹配调用       |
| maxTurns/maxCost | 预算控制，超限自动停止                               |

---

## 四、STAR 法则项目陈述

### S — Situation（背景）

作为后端工程师，我发现市面上大多数 "AI Agent" 框架（LangChain、Spring AI）本质是 API 封装，
开发者只需配置 prompt + 调 API 即可获得回复——但无法理解 Agent 内部机制，
也无法对 LLM 运行时有更深度的理解。

于是决定使用自己擅长的Java语言从零实现一个生产级 Agent 运行时基础设施，参考港大开源项目 Nanobot 的核心架构
（消息总线 + 状态机 + 工具系统），并融合 Claude Code 的安全模型和编程工具设计。

### T — Task（任务）

设计并实现一个完整的 AI Agent 运行时框架，核心要求：

1. **不是 API 封装**：手写消息总线、状态机、工具注册中心、权限管道
2. **多通道支持**：同一套核心逻辑驱动 HTTP/SSE/WebSocket/CLI 四种交互方式
3. **安全纵深防御**：4 层权限检查（Hook→Guard→Rule→Mode），不可绕过
4. **上下文管理**：自动会话持久化、token 预算管理、历史压缩、长期记忆
5. **对标生产级 Agent**：27 个工具、流式输出、并发工具执行、子 Agent、MCP 协议

### A — Action（行动）

**架构设计阶段**：

- 采用异步消息总线（生产者-消费者模式）作为核心通信层，解耦 Agent 引擎和通道适配器
- 设计 8 状态状态机（RESTORE→COMPACT→COMMAND→BUILD→RUN→SAVE→RESPOND→DONE）管理对话生命周期
- 设计 Hook→Guard→Rule→Mode 四步权限管道，确保每个工具调用必经安全检查

**核心实现阶段**：

- 实现 AgentRunner LLM 递归调用循环（工具调用 → 执行 → 结果回注 → 递归直到纯文本）
- 实现 Fan-Out Pub-Sub 流式分发，RunState → outboundQueue → Dispatcher 扇出，SSE/WS/CLI 三通道独立消费
- 实现 Consolidator 上下文压缩器（token 超 90% 预算 → LLM 自动总结旧消息）
- 实现 27 个工具，包括 EditFile 唯一性校验（对齐 Claude Code）、Exec stderr 分离、ReadFile 分页

**踩坑与修复**：

- 双 AgentLoop 竞争消费问题 → 统一注入解决
- SSE 连接被 Spring MVC 30s 超时截断 → 配置 300s + AsyncRequestTimeoutException 处理
- AgentLoop 单线程阻塞 → 线程池异步消息处理
- 日志配置被 YAML 和 logback.xml 多重覆盖 → 用 `--logging.config` 从源头替换配置文件

**持续迭代**：

- 日志全面改为 Lombok，减少 ~800 行样板代码
- 引入 @ToolDef 注解实现方法级工具自动注册
- 实现 SpawnTool 子 Agent + FileInbox 异步通信
- 对标 Claude Code 优化所有工具描述
- 实现 NANOBOT.md 项目记忆（`/init` 生成，后续对话自动注入）
- **记忆系统重构**：删除 MemoryStore 死代码(420行)，Dream 实现全自动闭环（提取→节流→JSON解析→持久化→检索→注入）
- **新增 /compact、/remember 命令**：手动压缩上下文 + 手动记忆提取
- **实现 use_skill 元工具**：对标 Claude Code 渐进式加载，一个工具管所有 Skill，tools 数组不膨胀
- **目录结构扁平化**：去掉 .nanobot/workspace/ 冗余层级，首次启动自动生成模板文件

### R — Result（成果）

**工程指标**：

- 129 个源文件，~27,000 行 Java 代码
- 18+ 内置工具 + @ToolDef 注解扫描 + MCP 动态工具，4 种权限模式，4 种交互通道
- 异步消息总线 Fan-Out Pub-Sub 支持 HTTP/SSE/WebSocket/CLI 四种通道，核心代码零重复

**技术成果**：

- 自研 Agent 运行时基础设施，非任何框架封装
- 安全模型达到生产级：4 步管道、纵深防御、交互确认
- 编程工具细节对齐 Claude Code（EditFile 唯一性校验、Exec stderr 分离、ReadFile 分页）
- 三维度工程（提示词/上下文/Harness）完整落地

**个人成长**：

- 深入理解了 LLM 的 tool calling 机制、流式处理和上下文窗口管理
- 实践了消息总线、状态机、管道模式等架构模式在 AI 场景的应用
- 积累了从零搭建复杂系统、踩坑修复、持续迭代的完整经验

---

## 五、性能优化设计

> 从吞吐量、延迟、内存、可靠性四个维度梳理项目中的性能优化点。

### 5.1 吞吐量优化

| 优化点 | 实现 | 效果 |
|------|------|------|
| **并行工具执行** | 只读工具通过 `toolExecutor` 线程池并发执行，`CompletableFuture.allOf()` 等待全部完成 | 多工具场景 2-3x 加速 |
| **异步消息处理** | `messageExecutor` 线程池异步处理消息，主循环只负责消费入队 | LLM 调用不阻塞后续消息消费 |
| **批量编译** | Maven 编译一次性处理所有源文件 | 避免逐文件编译开销 |
| **Provider HTTP 连接池** | `HttpClient.newBuilder()` 默认复用 TCP 连接 | 减少 TLS 握手开销 |

### 5.2 延迟优化

| 优化点 | 实现 | 效果 |
|------|------|------|
| **流式输出** | SSE chunk → `onDelta` 回调 → 逐字推送给用户 | 用户感知延迟从 5-30s 降至 < 500ms |
| **轻量 onDelta** | 回调内只做 `System.out.print()` / `emitter.send()` / `session.sendText()` | 避免流式接收阻塞 |
| **工具定义缓存** | `cachedDefinitions` 避免每次 LLM 调用重新序列化工具 schema | 减少 JSON 序列化 CPU 开销 |
| **惰性编译** | `nanobot` 脚本首次运行时自动编译，后续跳过 | 避免重复 Maven 启动开销 |

### 5.3 内存优化

| 优化点 | 实现 | 效果 |
|------|------|------|
| **大文件分页** | `ReadFileTool` 默认 2000 行，`offset`+`limit` 分页 | 防止大文件全部读入 OOM |
| **工具结果截断** | `maxToolResultChars = 16_000`，超出部分截断 | 防止 web_fetch 大页面撑爆上下文 |
| **历史增量追加** | `history.jsonl` 逐行追加而非全量重写 | 避免大文件 I/O |
| **Session 隔离** | 每个会话独立的 JSONL 文件，按需加载 | 不把所有会话全量加载到内存 |
| **Glob 自动跳过** | 自动跳过 `.git`/`node_modules`/`target` 等 12 个大型目录 | 减少文件系统遍历开销 |

### 5.4 可靠性保障

| 保障点 | 实现 | 效果 |
|------|------|------|
| **工具重试** | 超时/连接错误重试 3 次，间隔 1s | 网络抖动自动恢复 |
| **连续失败降级** | 3 次全失败 → 强制 LLM 无工具回答 | 防止烧钱死循环 |
| **预算控制** | `maxTurns` + `maxCost` 双重上限 | 防止失控消耗 |
| **上下文压缩** | `usage > budget × 90%` → LLM 总结旧消息 | 防止上下文溢出导致 LLM 幻觉 |
| **Guard 安全底线** | 守卫层永远执行不可跳过 | 无论什么模式，危险命令绝不放行 |
| **Graceful Shutdown** | `appContext.close()` 优雅关闭，Bean 按 destroyMethod 清理 | 避免资源泄漏 |

### 5.5 设计取舍（Trade-offs）

| 取舍 | 选择 | 原因 |
|------|------|------|
| Token 估算精度 vs 依赖复杂度 | char/4 估算（~20%误差） | 避免引入 tiktoken + 模型级 tokenizer 复杂度 |
| 同步 vs 异步子 Agent | 默认同步，大任务异步 | 简单场景开销小，复杂场景不阻塞 |
| 全量搜索 vs 索引 | 全量 Grep（ripgrep 语义） | 项目规模 < 10K 文件时全量搜索足够快 |
| 内存压缩 vs 文件缓存 | 内存压缩（LLM 总结） | 避免引入 Redis/磁盘缓存复杂性 |
| 单点工具注册 vs 分布式 | 单进程 ToolRegistry | 个人使用场景，分布式无收益 |

> 以下从提示词工程、上下文工程、Harness 工程三个维度，对项目进行系统级技术梳理。

## 六、三维度工程深度拆解

> 以下从提示词工程、上下文工程、Harness 工程三个维度，对项目进行系统级技术梳理。

### 6.1 提示词工程（Prompt Engineering）

**定义**：如何构造和组织 System Prompt，使得 LLM 准确理解自身角色、能力边界、行为约束。

#### System Prompt 组装流水线

```
doBuild() 构造 system prompt（按优先级）:
│
├── ❶ 身份 + 日期指令（首位效应）
│    IdentityManager.getSystemPrompt(currentDate)
│    "你的名字是 my-nanobot，绝对不是 Claude..."
│    "今天是 2026-07-21，这是真实日期..."
│
├── ❷ SOUL + IDENTITY + USER 身份详情
│    IdentityManager.getCombinedPrompt()
│
├── ❸ 联网搜索模式指令（条件注入）
│    useSearch=false → "不要使用 web_search/web_fetch"
│    useSearch=true → 跳过此行（LLM 自行决定）
│
├── ❹ 长期记忆检索注入（Dream）
│    Dream.retrieve(userMessage, 5) → 关键词匹配+重要性排序
│    "【长期记忆 — 从过往对话中自动提取】..."
│
├── ❺ NANOBOT.md 项目上下文 ★ 对标 CLAUDE.md
│    if (Files.exists("NANOBOT.md")) → 注入全部内容
│
├── ❻ Skills 技能目录 ★ 对标 Claude Code 渐进式加载
│    SkillRegistry.getAllSkills() → 仅注入名称+描述
│    "可用技能: code-review(代码审查), refactor(重构)..."
│
├── ❼ Rules 规则注入
│    RuleManager.getRulesPrompt()
│    "Java代码风格规范 / 安全编码规范 / 响应格式规范"
│
└── ❽ 工具结果格式说明（近因效应）
     "[TOOL_OK] 表示成功，[TOOL_ERR] 表示失败"
```

#### 关键技术点

| 技术             | 实现                                      | 原理                            |
| -------------- | --------------------------------------- | ----------------------------- |
| **首位效应**       | 身份+日期放 prompt 最开头                       | 模型对早期 token 注意力权重最高           |
| **近因效应**       | 工具格式说明放 prompt 最末尾                      | 模型对最近 token 记忆最强              |
| **对抗训练偏差**     | 日期+身份双重显式声明                             | DeepSeek 训练数据自称 Claude，需强硬覆盖  |
| **条件注入**       | useSearch → 动态选择工具指令                    | 减少不必要的 token 消耗               |
| **渐进式技能加载**   | System Prompt仅注入技能目录, LLM按需调use_skill | 对标Claude Code, tools数组不膨胀       |
| **长期记忆检索**     | Dream.retrieve(query,5) → 关键词+重要性排序     | 跨会话上下文注入，<1ms延迟，零LLM成本     |
| **项目上下文注入**    | NANOBOT.md → system prompt              | 对标 Claude Code 的 CLAUDE.md 机制 |
| **Rules 分层管理** | 内置 coding-style/security/response-style | 项目级 .nanobot/rules > 用户级 > 默认 |

---

### 6.2 上下文工程（Context Engineering）

**定义**：如何管理对话历史、长期记忆、上下文窗口，使 Agent 在多轮对话中保持连贯且不超 token 预算。

#### 8 状态状态机

```
RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE
   │         │         │        │       │      │        │
  加载历史  压缩检查  命令拦截  构建提示  调LLM   持久化   推送
```

#### 记忆三层架构

```
┌──────────────────────────────────────────────────┐
│ 第一层: 短期记忆（对话上下文）                    │
│   history.jsonl → doRestore() 加载 → messages[]   │
│   每次对话 doSave() 自动持久化，按 sessionKey 隔离  │
│   JSONL 格式，逐行追加，原子写入                   │
├──────────────────────────────────────────────────┤
│ 第二层: 中期压缩（Token 预算管理）                 │
│   Consolidator.needsConsolidation()               │
│   触发条件: token > contextWindowTokens × 90%     │
│   压缩对象: 旧的 assistant + tool 消息             │
│   压缩方式: LLM 总结 → system 消息替换             │
│   替换为 "[对话历史总结] ..."                      │
├──────────────────────────────────────────────────┤
│ 第三层: 长期记忆（跨会话）                        │
│   Dream: 自动提取+增量节流(5000字符阈值)            │
│   MemoryLoader: MEMORY.md读写+自动模板生成          │
│   NANOBOT.md: /init 生成的永久项目记忆            │
│   TaskStore: JSON 持久化的任务追踪                │
│   /compact + /remember: 手动压缩和记忆提取命令     │
└──────────────────────────────────────────────────┘
```

#### Token 管理与预算控制

| 机制       | 说明                                               |
| -------- | ------------------------------------------------ |
| Token 估算 | char_count / 4 ≈ token 数                         |
| 压缩触发     | usage > contextWindowTokens × 90%                |
| 降级兜底     | 连续 3 次工具全部失败 → 强制 LLM 无工具回答                      |
| maxTurns | 对话轮次上限（0=不限），启动参数配置                              |
| maxCost  | 费用上限（基于 DeepSeek $0.14/$0.28 per 1M tokens 实时累计） |

#### 会话生命周期

| 阶段  | 操作                                           |
| --- | -------------------------------------------- |
| 创建  | `"cli-" + timestamp` 或 `--resume <key>` 恢复   |
| 加载  | `doRestore()` → SessionManager.loadHistory() |
| 运行  | 多轮对话，messages[] 在内存中增长                       |
| 压缩  | `doCompact()` → Consolidator 检查 → LLM 总结     |
| 保存  | `doSave()` → 增量追加 history.jsonl              |
| 恢复  | `--resume <key>` / `/resume` 命令              |

---

### 6.3 Harness 工程（Runtime Infrastructure）

**定义**：运行时基础设施——如何调度 LLM、执行工具、管理权限、处理错误，把 LLM 从"对话模型"变成"可行动的 Agent"。

#### Agent 循环引擎

```
用户消息 → InboundMessage → MessageBus.inboundQueue
                                    │
                    AgentLoop.messageExecutor（线程池）
                                    │
                    processStates() → 8 状态状态机
                                    │
                              doRun() → AgentRunner.runInternal()
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
              provider.chat()  → LLM 响应  →  tool_calls?
                                              │
                                   ┌─────────┴─────────┐
                                   ▼                   ▼
                             executeTools()       return content
                                   │
                         ┌────────┴────────┐
                         ▼                 ▼
                  只读工具(并行)     写工具(顺序)
                         │                 │
                         └────────┬────────┘
                                  ▼
                         结果回注 messages[]
                                  │
                         递归 runInternal(iteration+1)
```

#### 工具执行管道（核心安全边界）

```
ToolRegistry.execute()  ← 唯一切入点，所有工具必经
  │
  ├── 1. PreToolUse Hook 链
  │     deny → 立即拒绝 | allow → 跳过后续检查 | modify → 修改参数 | passthrough → 继续
  │
  ├── 2. Guards 守卫层（永远执行，不可跳过）
  │     PathGuard.resolvePath() → 工作区隔离 + 符号链接解析
  │     CommandGuard.guard()     → allowPatterns > denyPatterns
  │     NetworkGuard.validateUrl() → CIDR 范围匹配
  │
  ├── 3. RuleEngine 规则引擎
  │     deny → ask → allow 优先级链
  │     deny 不可被 allow 覆盖（fail-closed）
  │     ASK + InteractiveHandler → CLI [y/N] 确认
  │
  ├── 4. PermissionMode 模式判定
  │     PLAN: 只读 | DEFAULT: 读放行写拒绝 | ACCEPT_EDITS: 文件编辑 | BYPASS: 全放行
  │
  └── 5. tool.execute() → [TOOL_OK] / [TOOL_ERR] 结果包装
```

#### 流式输出架构

```
DeepSeek SSE → provider.chatStream(messages, tools, onDelta)
     │
     ▼
  onDelta.accept(delta) → RunState.publishToOutboundQueue(msg)
     │                       → outboundQueue.put(msg)  [背压: 队列满阻塞]
     ▼
  MessageBus-dispatcher daemon 线程
     │  poll outboundQueue(1s) → 遍历 subscriberQueues (CopyOnWriteArrayList)
     │  offer(msg, 100ms) → 队列满时丢弃该订阅者，不影响其他通道
     │
     ├── SSE-consumer:  poll subscriberQueue → emitter.send(delta)
     ├── CLI-consumer:  poll subscriberQueue → System.out.print(delta)
     └── WS-consumer:   poll subscriberQueue → session.sendText(json)
```

#### 核心扩展点

| 扩展点         | 机制                                        | 说明                           |
| ----------- | ----------------------------------------- | ---------------------------- |
| 子 Agent     | SpawnTool → AgentCoordinator              | 4 种能力匹配，同步+异步(inbox)两模式      |
| 任务追踪        | TaskCreate/List/Update → TaskStore        | JSON 文件持久化                   |
| MCP 协议      | MCPManager → StdioMCPClient/HttpMCPClient | stdio/sse/streamableHttp 全支持 |
| 技能系统        | SkillManager + use_skill 元工具             | .nanobot/skills/ 目录加载 + 渐进式激活 |
| 定时任务        | CronScheduler                             | cron 表达式解析 + 调度              |
| @ToolDef 注解 | ToolScanner 类路径扫描                         | 方法级注解自动注册                    |
| LLM 双后端     | LLMProvider 接口                            | DeepSeek + OpenAI 统一适配       |
| Channel 适配  | MessageBus Fan-Out Pub-Sub                | HTTP/SSE/WS/CLI 零重复代码        |


## 七、三维度总览图

```
              ┌─────────────────────────────────────────┐
              │    提示词工程（Prompt Engineering）      │
              │                                         │
              │  System Prompt 组装流水线                │
              │  首位+近因效应 → 对抗训练偏差             │
              │  条件注入(useSearch) → 减少 token 消耗   │
              │  NANOBOT.md 项目记忆自动注入              │
              │  Rules 分层管理(项目/用户/内置)           │
              └──────────────────┬──────────────────────┘
                                 │
                                 ▼
              ┌─────────────────────────────────────────┐
              │    上下文工程（Context Engineering）       │
              │                                         │
              │  8 状态状态机 → 对话生命周期管理           │
              │  三层记忆: 短期 → 中期压缩 → 长期         │
              │  Consolidator: token>90%预算 → LLM 总结  │
              │  maxTurns / maxCost → 双重预算控制       │
              │  --resume → 任意历史会话恢复             │
              └──────────────────┬──────────────────────┘
                                 │
                                 ▼
              ┌─────────────────────────────────────────┐
              │    Harness 工程（Runtime Infrastructure）  │
              │                                         │
              │  异步消息总线 → 生产者/消费者解耦          │
              │  工具管道: Hook→Guard→Rule→Mode→Execute  │
              │  流式引擎: 一份 onDelta 多渠道订阅         │
              │  工具执行: 只读并行 + 重试 + 降级          │
              │  通信协议: MCP / SSE / WebSocket          │
              └─────────────────────────────────────────┘
```

---

## 八、设计模式深度解析

> 面试常见问题："你在这个项目中用了哪些设计模式？"——本节提供系统级回答。

### 8.1 状态机模式 —— 对话全生命周期

LLM调用不是简单的 input→output，一次对话需7+步骤。揉在一个方法里难以维护。8 状态枚举 + processState() switch 分发 + processStates() 循环→DONE。和TCP状态机、订单状态机同构——复杂生命周期的通用解。

### 8.2 管道/责任链 —— 四步权限检查

PreToolUse Hook→Guards→RuleEngine→PermissionMode，每层独立编址，PermissionManager 串联。不同环节有不同短路语义（Hook deny 短路、Guard 抛异常、Rule 优先级匹配），不是简单 if-else 链。

### 8.3 发布-订阅模式 —— 流式多渠道分发

MessageBus Fan-Out Pub-Sub：RunState 发布到 outboundQueue，Dispatcher daemon 扇出到各 subscriberQueue（CopyOnWriteArrayList 管理）。SSE/CLI/WS 各自独立 poll，互不干扰。同一份 msg 引用零数据复制。COW 让 dispatcher 遍历时无锁，offer() 而非 put() 确保慢消费者只丢自己的消息。

### 8.4 策略模式 —— 子Agent分配

AgentCoordinator 支持 4 种分配策略(DIRECT/CAPABILITY_MATCH/ROUND_ROBIN/PARALLEL)。不同策略改变并发模型（ROUND_ROBIN 串行，PARALLEL 并行），体现对策略模式深层理解。

### 8.5 门面模式 —— LLM 多Provider适配

LLMProvider 接口 + Message 统一数据模型。DeepSeek/OpenAI 的 tool_calls 格式、流式结束标记、系统消息处理差异全部封装在 buildRequestBody() 中。和 JDBC 驱动、SLF4J 的设计理念一致。

### 8.6 Builder + 验证 —— 复杂消息对象

InboundMessage.builder() 链式调用。Builder 嵌入 validate() 自动校验必填字段，防御性拷贝 metadata/media。比 Lombok @Builder 生成的裸 Builder 更安全。

### 8.7 适配器 —— MCP 外部工具

MCPToolWrapper implements Tool，将 JSON-RPC over stdio 适配为 Tool.execute()。适配后自动享受全部安全检查基础设施，适配器+管道组合使用。

### 8.8 工厂 + 模板方法 —— 工具执行框架

ToolResult.ok()/err()/wrap() 工厂方法根据结果自动标记 [TOOL_OK]/[TOOL_ERR]。ToolRegistry.execute() 作为模板方法，统一处理参数校验→安全检查→执行→结果包装，子类（各工具）只需实现 execute()。

### 8.9 注册表模式 —— @ToolDef 注解扫描

ToolScanner 类路径扫描 + ToolRegistry.register()，对标 Spring 的 @ComponentScan 理念。支持 implements Tool（复杂工具）和 @ToolDef 方法注解（简单工具）两种注册方式共存。

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

一条用户消息从到达到响应，经过 **4-5 个线程边界**。每个边界都有明确目的（解耦、隔离、缓冲），不是随意跨线程。

### 9.3 MessageBus — 三队列 + Fan-Out Pub-Sub

**并发原语**：`ArrayBlockingQueue(100)` + `ArrayBlockingQueue(1000)` + `ConcurrentHashMap` + `CopyOnWriteArrayList` + `AtomicBoolean`

**为什么用 ArrayBlockingQueue 而不是 LinkedBlockingQueue？**

| | ArrayBlockingQueue | LinkedBlockingQueue |
|------|------|------|
| 容量 | 必须指定（100/1000） | 可无界 → OOM 风险 |
| 内存 | 预分配数组，无 GC 压力 | 节点动态分配 |
| 锁 | 一把锁 | 两把锁（多余，只有 1 消费者） |

选择理由：**有界防 OOM**。队列满时 `put()` 阻塞生产者，形成自然背压。

**Fan-Out Pub-Sub（流式分发核心）**：

```
RunState → publishToOutboundQueue(msg) → outboundQueue.put(msg)
  → Dispatcher daemon poll(1s) → 遍历 subscriberQueues (COW)
    → offer(msg, 100ms)  // 非阻塞，队列满丢弃该订阅者
      → SSE-consumer / CLI-consumer / WS-consumer 各自 poll
```

**为什么 Fan-Out？** `BlockingQueue.take()` 是破坏性消费——一条消息只能一个消费者取走。三通道都需要同一条流式数据，必须各有独立队列。Dispatcher 同一份引用扇出，零数据复制。

**零订阅者守护**：`subscriberQueues.isEmpty()` 时直接 return，防止 outboundQueue 积压后 `put()` 阻塞 RunState → 整个 AgentLoop 卡死。

**`waitForSessionResponse` 的精确匹配**：sync HTTP 模式下，`sessionResponses` (CHM) 中按 `requestId` 轮询匹配。用 `Iterator.remove()` 原子取走，防止并发重复消费。Cleanup daemon 每 2 分钟清理超 5min TTL 的残留条目。

### 9.4 SessionManager — 每会话独立锁

**并发原语**：`ConcurrentHashMap<String, Object>` + `synchronized(lock(sessionKey))`

```java
// 每会话独立锁 — 粒度最优
private Object lock(String sessionKey) {
    return sessionLocks.computeIfAbsent(sessionKey, k -> new Object());
}

public void saveHistory(String sessionKey, List<Map<String, Object>> messages) {
    synchronized (lock(sessionKey)) {
        store.saveHistory(sessionKey, messages);
    }
}
```

**为什么每会话独立锁？** 不同会话的读写完全无关，全局锁会让它们互相阻塞。`synchronized(lock(sessionKey))` 让 session-A 和 session-B 完全并行。

**为什么锁的是 `new Object()` 而不是 sessionKey 字符串？** String 的 `intern()` 机制可能导致不同来源的相同字符串指向同一 JVM 对象——可能和无关代码共享锁。

**`computeIfAbsent` 的原子性**：两线程同时调 `lock("same-key")`，CHM 保证只有一个 `new Object()` 被执行。

### 9.5 SessionStore — 故意不用 ConcurrentHashMap

```java
// SessionStore 用普通 HashMap，不加锁
private final Map<String, Path> dirCache = new HashMap<>();
```

**为什么敢用 HashMap？** SessionStore 所有 public 方法都**只在 SessionManager 的 `synchronized` 块内被调用**。锁在业务层，存储层保持简单。

**设计原则**：不是所有共享数据都需要自己加锁。调用方已保证单线程访问时，被调用方不需要重复保护。过度同步降低性能且让代码更难理解。

### 9.6 AgentLoop — final 不可变 = 天然线程安全

**并发原语**：`AtomicBoolean running` + `volatile boolean planMode`

**为什么 State Handler 用 `final` 字段 + 重建，而不是 setter？**

```java
// setDream/setConsolidator 不直接 set 字段，而是重建整个 State Handler：
public void setDream(Dream d) {
    this.dream = d;
    stateHandlers.put(TurnState.SAVE, new SaveState(sessionManager, d));
    stateHandlers.put(TurnState.BUILD, new BuildState(..., d, ...));
    stateHandlers.put(TurnState.COMMAND, new CommandState(..., d, ...));
}
```

`final` + 不可变 = 天然线程安全。重建的开销极小（只在启动时执行一次），换来了每个 State 内部完全不需要考虑并发。`stateHandlers` (EnumMap) 运行时只读，无并发写。

**`volatile boolean planMode`**：BuildState 通过 `() -> planMode` 闭包访问，不同 worker 线程间切换模式时保证立即可见。

### 9.7 AgentRunner — CompletableFuture 链式异步

```java
provider.chatStream(messages, tools, onDelta)  // 非阻塞返回 Future
    .thenCompose(response -> {
        if (response.hasToolCalls())
            return executeTools(...).thenCompose(results -> runInternal(...));
        return CompletableFuture.completedFuture(response.getContent());
    });
```

**为什么异步？** LLM 调用 5-30 秒。同步阻塞会占满 worker 池（只有 4 线程）。异步让 worker 提交请求后立即释放。

**并行工具执行**：`CompletableFuture.allOf(futures)` 等待多个工具并行执行完成。AgentRunner 和 ToolRegistry 双层线程池隔离，防止工具调用爆炸。

**onDelta 线程安全**：HTTP IO 线程中只做 `publishToOutboundQueue()` (put 到队列)，真正的扇出由 Dispatcher 异步完成。IO 线程不阻塞。

### 9.8 TurnContext — volatile + 防御性拷贝

| 字段 | 类型 | 为什么 |
|------|------|--------|
| `finalContent` | volatile | SaveState 写，RespondState 读（可能不同 worker 线程） |
| `cancelled` | volatile | Esc 中断线程写，RunState 读 |
| `error` | volatile | AgentRunner 写，RespondState 读 |
| `iteration` | AtomicInteger | 需要原子自增（`incrementAndGet()`） |

**防御性拷贝**：`getMessages()` 返回 `new ArrayList<>(messages)`，外部修改不影响内部状态。TurnContext 是"值对象"——只能通过 setter 修改，不能通过返回的引用偷改。

### 9.9 Dream — 双 CHM + 增量节流

```java
private final Map<String, MemoryEntry> memories = new ConcurrentHashMap<>();
private final Map<String, Integer> lastExtractionCharCount = new ConcurrentHashMap<>();
```

- `memories`：跨会话共享的长期记忆库，SaveState 写 + BuildState 读，CHM 分段锁解决并发
- `lastExtractionCharCount`：每 session 的提取进度，`getOrDefault + put` 之间不原子，但**可接受的不精确**——跳过一次提取比重复提取的代价小得多

### 9.10 ToolRegistry — volatile 缓存 + 并行执行器

**volatile 缓存模式**：

```java
private volatile List<JsonNode> cachedDefinitions = null;

public List<JsonNode> getDefinitions(boolean readOnly) {
    if (cachedDefinitions != null) return filterByReadOnly(cachedDefinitions, readOnly);
    List<JsonNode> defs = tools.values().stream().map(this::toDefinition).toList();
    cachedDefinitions = defs;  // volatile 写，所有读线程立即可见
    return filterByReadOnly(defs, readOnly);
}
```

工具列表只在启动时注册（极少写），每次 LLM 调用都要获取（极频繁读）。`volatile` 保证写后立即可见，读不加锁——这是读多写极少的最优解。两个线程同时发现 `== null` 会重复计算，但不影响正确性。

**工具并行执行器**：LLM 可能一次返回 5 个 tool_calls（如同时读 5 个文件），`CompletableFuture.allOf(futures)` 并行执行，`ToolExecutor(cpu*2)` 线程池限流。

### 9.11 PermissionManager — volatile 模式切换

```java
private volatile PermissionMode mode;  // PLAN / DEFAULT / ACCEPT_EDITS / BYPASS
```

用户 `/mode bypass` → CommandState 写入 → 下一轮工具调用时 RunState 读取。如果 mode 不是 volatile，切了 bypass 后 LLM 调用工具仍可能看到旧的 DEFAULT 模式，不该弹确认的弹了确认。

### 9.12 CliChannel — volatile 中断标志 + Esc 检测

```java
private volatile boolean cancelled;        // JLine 线程写，AgentLoop worker 读
private volatile String currentRequestId;  // 主线程写，流式回调线程读
```

用户按 Esc → JLine 线程 `cancelled = true` → AgentLoop worker 检测到后停止流式输出。两个字段都是跨线程读写，不用 volatile 的话用户按了 Esc 但 LLM 继续输出。

### 9.13 ChannelServer — synchronized + wait/notify (V1 演进)

**V1 遗留**：HTTP handler 线程 `synchronized(handler) { handler.wait(300000); }` 挂起等待 LLM 响应，流式回调线程 `synchronized(handler) { handler.notifyAll(); }` 唤醒。双层锁（handlers Map 锁 + handler 对象锁）不同粒度、不同目的。

**设计批判**：`streamResponseHandlers` 用 CHM 但又所有访问包裹 `synchronized(handlersMap)`——CHM 的分段锁完全冗余，普通 HashMap 效果一样。这是 V1 快速迭代留下的**过度保护**，面试指出这类问题说明有代码嗅觉。

**V2 演进**：已迁移到 Spring SSE + Fan-Out Pub-Sub，不再需要 wait/notify。保留它体现了从"手动线程通信"到"框架抽象"的工程成长。

### 9.14 MetricsHook — synchronized 多字段联合原子更新

```java
synchronized void addTokens(int prompt, int completion) {
    this.promptTokens += prompt;
    this.completionTokens += completion;  // 两个字段必须一起更新
}
```

**为什么不用 AtomicInteger × 2？** `AtomicInteger` 只保证**单个变量**原子。两线程同时调 `addTokens(10,20)` 和 `addTokens(30,40)`，中间可能读到 promptTokens 已 +10 但 completionTokens 未 +20 的不一致状态。**多个字段需要联合原子更新时必须用锁**。

两层隔离：外层 CHM 隔离不同 session 的 Metrics 对象，内层 synchronized 保护同一 session 内的并发更新。和 SessionManager 思路一致。

### 9.15 WebSocket — static CHM + Pub-Sub 消费者

```java
private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
private static final AtomicBoolean wsConsumerRunning = new AtomicBoolean(false);
```

**为什么 static？** WS 容器为每个连接创建新 Endpoint 实例，非 static 则每个实例有独立连接表，无法跨连接查找。

**Pub-Sub 消费**：应用启动时一次性 `subscribeToOutbound()`，全局 WS-consumer daemon 线程 poll subscriberQueue，按 `msg.sessionId` 查 `SESSIONS` 表找到对应连接 `sendText()`。`AtomicBoolean wsConsumerRunning` + `static synchronized initStreamConsumer()` 保证只启动一次。

### 9.16 同步 vs 异步决策速查

```java
// ✅ remove() 是原子的
CompletableFuture<MCPResult> future = pendingRequests.remove(requestId);

// ❌ get() + remove() — 之间有竞态窗口
CompletableFuture<MCPResult> future = pendingRequests.get(requestId);
// ← 超时线程在这里 remove + complete
pendingRequests.remove(requestId);  // 返回 null
future.complete(result);            // 重复 complete → 异常！
```

响应线程和超时线程同时竞争同一个 Future。`remove()` 保证只有一个胜出——拿到非 null Future 并完成它，另一个 `remove()` 返回 null 直接跳过。

---

### 9.17 全项目并发原语速查表

| 并发原语 | 使用位置 | 为什么选它 |
|---------|---------|-----------|
| `ArrayBlockingQueue` | MessageBus inbound/outbound | 有界防 OOM + 背压 |
| `ConcurrentHashMap` | 15+ 处 | 分段锁，高并发读写 |
| `CopyOnWriteArrayList` | MessageBus subscriberQueues | 读多写极少，无锁遍历 |
| `synchronized(perKeyLock)` | SessionManager | 锁粒度最优 |
| `synchronized(method)` | MetricsHook.SessionMetrics | 多字段联合原子更新 |
| `CompletableFuture.thenCompose` | AgentRunner | 异步递归，不阻塞 worker |
| `CompletableFuture + CHM` | StdioMCPClient | CHM 路由 + Future 信号量 |
| `AtomicBoolean` | MessageBus, AgentLoop | 启停状态，CAS + 可见性 |
| `AtomicInteger` | TurnContext, WebSocket | 轻量原子自增 |
| `volatile boolean` | 7+ 处 | 跨线程状态标志，零成本 |
| `volatile 引用` | ToolRegistry, PermissionManager | 读多写极少的缓存 |
| `final + 不可变` | AgentLoop stateHandlers | 无需同步的终极方案 |
| `HashMap` (无锁) | SessionStore.dirCache | 调用方已保证单线程 |

### 9.18 防御性设计模式

**锁粒度最小化**：`synchronized(this)` 全局锁 → `synchronized(lock(sessionKey))` 每会话锁

**有界优于无界**：`ArrayBlockingQueue(100)` 而非 `LinkedBlockingQueue()`（无界可 OOM）

**简单优于复杂**：能 volatile 解决的不加锁，能 synchronized 解决的不上 ReentrantLock，能 CHM 解决的不自建锁

**调用方负责并发控制**：SessionManager 加锁 → SessionStore 用普通 HashMap，锁的职责集中一处

**单线程消费是最大简化**：AgentLoop 单 daemon 线程消费入站消息，保证同一会话不并发处理
