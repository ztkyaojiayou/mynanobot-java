---
id: C-005
slug: a2a-foundation
status: done
created: 2026-06-07
owner: Owner Agent
---

# C-005 A2A 协同基座与统一治理层

> **修订记录**
> - **R1（2026-06-07）**：增量 A — 自建 A2A 基座（不可变 `Msg`、`BaseAgent`、`AgentRegistry`、统一治理、`OutputQualityGate`、可观测贯穿）。7 条 AC 全部交付，状态 = `done`。
> - **R2（2026-06-12）**：新增**增量 B「AgentScope 原生化地基」**——核实项目声明 AgentScope 2.0.0-RC1 为铁律依赖却 **0 处 import、0 次 LLM 调用**（全栈 NIH），ADR-008 已决策「走原生 HarnessAgent，C-006 增量 C XHS 切片先行验证可行」。本增量为 ADR-008 明确的**独立地基卡**：提供 MsgAdapter 桥接、GovernanceMiddleware 原生包装、BaseHarnessAgentFactory 通用工厂、A2A 标准服务端基础设施、OTel 可观测替换、GracefulShutdown 集成、自研栈废弃标记，使后续 Agent 卡（C-007~C-010）可直接基于原生基础设施迁移。增量 A 已交付内容不回退，状态回到 `analyzing`，**待人类审批**。

## 用户故事

作为五个 Agent 的开发者，我想要一套统一的 A2A 通信基座（不可变 `Msg`、Nacos 注册发现、超时/重试/降级治理、`OutputQualityGate`、可观测字段贯穿），以便各 Agent 只需聚焦自身业务，而协同、治理与全局一致性由公共基座保证。

> **增量 B 补充故事**：作为系统架构师，我想要把 C-005 增量 A 自建的 A2A 运行时（`BaseAgent`/`AgentRegistry`/`TraceContext`）迁移到 **AgentScope 2.0.0-RC1 原生**（`HarnessAgent`/`AgentScopeA2aServer`/`OtelTracingMiddleware`），并保留已验证的治理能力（`GovernedExternalCaller`/`RateLimiter`）以 `MiddlewareBase` 形态接入框架中间件链，以便项目**真正用上 AgentScope 的原生能力**（ReAct 推理、中间件链、原生 A2A、OTel 追踪、优雅关停），而非继续背着铁律依赖的重量却拿不到它的任何能力。

## 本次范围

本卡分两个增量交付，**真相同卡**：

### 增量 A — 自建 A2A 基座（R1，已 `done`，本次不回退）

> 打通 A2A 协同基座全栈：不可变 `Msg`、`BaseAgent` 抽象、`AgentRegistry` 注册发现、`GovernedExternalCaller` 统一治理、`OutputQualityGate` 全局不变量复核、`TraceContext` + `AgentMetrics` 可观测贯穿。7 条 AC 全绿，`mvn clean verify` 通过。

### 增量 B — AgentScope 原生化地基（R2，本次新增，待审批）

> 目标：把 C-005 增量 A 自建的运行时基座迁移到 AgentScope 原生，为后续 Agent（route/itinerary/budget/supervisor）的全量原生化提供**可复用的基础设施层**。C-006 增量 C 已证明 HarnessAgent + 真实 LLM + Skill 在 XHS 单切片可行（Gate 0 spike 全过），本增量将该模式泛化为所有 Agent 可用的通用地基。

- 做（B-1 消息桥接）：`MsgAdapter`——桥接自定义任务信封 `com.nanobot.common.a2a.Msg`（`msgId/from/to/type/payload/timeoutMs/traceId`）↔ 框架 LLM 消息 `io.agentscope.core.message.Msg`（`role/ContentBlocks`）。两者本质不同（任务调度信封 vs LLM 对话消息），适配器实现双向无损转换，使 Agent 内部可用框架消息而 A2A 边界仍走自定义信封，支持渐进迁移。
- 做（B-2 治理中间件）：`GovernanceMiddleware implements MiddlewareBase`——把已验证的 `GovernedExternalCaller` 治理逻辑（限频→重试 1/2/4s→降级 `fallback=true`）包装为框架 `onModelCall` 钩子，使 `HarnessAgent` 经中间件链自动获得 C-005 AC-4 全部治理保证，无需显式传入 `GovernedExternalCaller`。复用现有 `RetryPolicy`/`RateLimiter`/`FallbackHandler`，零逻辑复制。
- 做（B-3 通用 Agent 工厂）：`BaseHarnessAgentFactory`——从 `XHSHarnessAgentFactory`（C-006 增量 C）提取可复用模式：双模型组装（ADR-009 deepseek-v4-pro 主 + qwen3-max 降级）、`ClasspathSkillRepository` 加载、workspace 管理、环境变量密钥解析。各 Agent 工厂继承并仅提供 agent-specific 的 `@Tool` 与 `SKILL.md`。
- 做（B-4 A2A 标准服务端）：接入框架 `AgentScopeA2aServer` + `ConfigurableAgentCard` + 框架 `AgentRegistry`（`io.agentscope.core.a2a.server.registry`）。每个 Agent 经 `AgentCard` 暴露 name/description/capabilities/skills，走标准 A2A 协议（Google `io.a2a` JSON-RPC）的服务发现。Server 层 `A2aConfig` 改写为装配框架 A2A 服务端。自建 `InMemoryAgentRegistry`/`ResilientAgentRegistry` 废弃。
- 做（B-5 OTel 可观测替换）：`OtelTracingMiddleware`（框架内置）接入 `HarnessAgent` 中间件链，自动产出 `invoke_agent <name>`/`chat <model>`/`execute_tool <name>` OTel spans。自定义 `TraceContext` 6 字段（`traceId/planId/agentId/taskType/msgId/fallback`）映射为 OTel span attributes，保留全链路可追踪语义。`AgentMetrics`（Micrometer）保留为互补指标层——OTel 管分布式追踪，Micrometer 管聚合指标。
- 做（B-6 优雅关停）：`GracefulShutdownMiddleware` + `GracefulShutdownConfig` 接入 Spring 生命周期。进行中的 `HarnessAgent.call()` 在进程退出前完成；`ShutdownStateSaver` 持久化部分推理状态供恢复；超时信号确保不无限等待。
- 做（B-7 废弃标记与回归）：`BaseAgent`、`AgentId`、`AgentReply`、`InMemoryAgentRegistry`、`ResilientAgentRegistry`、`TraceContext` 标记 `@Deprecated(since="C-005-B", forRemoval=true)` 并在 javadoc 注明迁移到的框架等价类。增量 A 全部测试保持绿色。`XHSAnalysisAgent`（C-006）双路径（原生+确定性直路）不破。

## 非目标（Out of Scope）

- 不迁移具体 Agent 业务逻辑（XHS/Route/Itinerary/Budget/Supervisor 的原生化归各自变更卡 C-006~C-010）。
- 不实现 PlanNotebook 集成（SupervisorAgent 的自主任务分解归 C-010）。
- 不实现 Memory/RAG/ReMe 真实存储（归 C-015）。
- 不实现具体 Nacos `NamingService` 传输适配（框架 `AgentScopeA2aServer` 支持多种 `TransportWrapper`，Nacos 适配作为 transport 配置归部署阶段，非本卡阻塞项）。
- 不删除增量 A 自建代码（仅标记 `@Deprecated`，留并存窗口供各 Agent 逐卡迁移；最终清理归独立技术债卡）。
- 不改变 REST API（归 C-011）。
- 不改变 `OutputQualityGate`（增量 A 已交付，无变动）。
- 不改变自定义 `Msg` 信封契约（仅新增 `MsgAdapter` 适配层，`Msg` record 本身不动）。

## 验收标准（AC）

### 增量 A 验收标准（R1，已交付）

- AC-1: 实现不可变 A2A `Msg`，字段对齐 `接口协议.md` §2：`msgId/from/to/type/payload/timeoutMs/traceId`，`type ∈ {TASK_ASSIGN,TASK_RESULT,TASK_ERROR,HEARTBEAT,BROADCAST}`；构造后不可变。
- AC-2: 提供 `BaseAgent` 抽象（无状态原则、生命周期、A2A 收发、Agent 标识对齐 `工程结构.md` §2.2，如 `agent://route-planner`），子 Agent 仅实现业务回调。
- AC-3: Nacos 注册发现可用：Agent 启动注册、`agent://<id>` 可被解析定位；`/api/v1/admin/agent/status` 数据源就位。
- AC-4: 统一外部调用治理：超时 30s + 重试 3 次（指数退避 1/2/4s）+ 调用链深度 ≤5 + 失败降级返回默认值并标记 `fallback=true`；治理对所有外部调用（小红书/地图/LLM）通用。
- AC-5: `TASK_RESULT` 在降级路径必须显式 `fallback=true`；子 Agent 失败必须返回 `TASK_ERROR`（可识别错误），禁止静默超时。
- AC-6: 实现 `OutputQualityGate`：对装配后的 TripPlan 统一复核全局不变量（天数一致、景点不重复、每日含午晚餐、预算红线、评分阈值），违规标记 `requiresHITL` 并给出原因；本变更先提供框架 + 可被各 Agent/Server 复用的校验入口。
- AC-7: 可观测字段 `traceId/planId/agentId/taskType/msgId/fallback` 贯穿日志与指标（Micrometer），并暴露 `接口协议.md` §6 关键指标埋点位。

### 增量 B 验收标准（R2，待审批）

- AC-8（消息桥接 · B-1）: `MsgAdapter` 实现自定义 `Msg`（任务信封）↔ 框架 `io.agentscope.core.message.Msg`（LLM 对话消息）的双向转换。`toFrameworkMsg` 将 `TASK_ASSIGN` 的 payload 序列化为 `UserMessage` 的 `TextContent`，携带 `traceId/timeoutMs/from/to` 等元数据作为 message metadata；`fromFrameworkMsg` 从框架 `AssistantMessage` 提取结果并重建 `TASK_RESULT`。转换无损：自定义 `Msg` → 框架 `Msg` → 自定义 `Msg` 的往返中，`msgId/from/to/type/traceId/timeoutMs/payload(fallback)` 全部保留，单测覆盖 5 种 `MessageType` 往返。
- AC-9（治理中间件 · B-2）: `GovernanceMiddleware implements MiddlewareBase` 在 `onModelCall` 钩子中施加 C-005 AC-4 治理链（限频→重试 1/2/4s→降级）。`HarnessAgent.builder().middleware(new GovernanceMiddleware(...))` 构造的 Agent 自动获得限频/重试/降级保证，模型调用失败（超时/限频/异常）走 `FallbackHandler` 返回降级响应并标记 `fallback=true`。复用现有 `RetryPolicy`/`RateLimiter`/`FallbackHandler`/`Sleeper`，零逻辑复制（Checkstyle CPD 0 新增重复）。单测验证退避序列 1/2/4s、限频阻断、降级标记，与增量 A `GovernedExternalCaller` 行为等价。
- AC-10（通用工厂 · B-3）: `BaseHarnessAgentFactory` 封装双模型组装（ADR-009：主 deepseek-v4-pro + 降级 qwen3-max，env key 解析，缺主 key 降级为 qwen 单模型，两者皆缺 fail-fast）、`ClasspathSkillRepository("skills")` 加载、workspace 目录管理、`GovernanceMiddleware` + `OtelTracingMiddleware` + `GracefulShutdownMiddleware` 默认中间件链。`XHSHarnessAgentFactory` 重构为继承 `BaseHarnessAgentFactory`，C-006 增量 C 全部回归不回退。API Key 走环境变量（禁硬编码，CI 密钥扫描 0 命中）。
- AC-11（A2A 标准服务端 · B-4）: `AgentScopeA2aServer` 经 Spring `A2aConfig` 装配，每个注册 Agent 经 `ConfigurableAgentCard.Builder` 暴露 name/description/capabilities/skills 元数据。框架 `AgentRegistry`（`io.agentscope.core.a2a.server.registry`）替代自建 `AgentRegistry`（`com.nanobot.skills.registry`）作为服务发现数据源。`/api/v1/admin/agent/status` 数据源切换到框架 `AgentRegistry`。`AgentRegistryService` 提供自建→框架注册表的桥接（并存期），`@ConditionalOnMissingBean` 保留扩展缝。
- AC-12（OTel 可观测 · B-5）: `OtelTracingMiddleware`（框架内置）加入 `BaseHarnessAgentFactory` 默认中间件链，自动产出 `invoke_agent`/`chat`/`execute_tool` 三级 OTel spans。自定义 `TraceContext` 6 字段（`traceId/planId/agentId/taskType/msgId/fallback`）映射为 OTel span attributes（经自定义 `TripPlanSpanEnricher` 在 `onAgent` 钩子中注入）。无 OTel SDK 配置时 `OtelTracingMiddleware` 走 no-op 路径，零额外开销（框架保证）。`AgentMetrics`（Micrometer）保留为互补指标层，不废弃。
- AC-13（优雅关停 · B-6）: `GracefulShutdownMiddleware` 加入默认中间件链。Spring `@PreDestroy` 触发 `GracefulShutdownManager.shutdown()`：拒绝新请求、等待进行中 `HarnessAgent.call()` 完成（configurable timeout，默认 30s）、`ShutdownStateSaver` 持久化部分推理状态到 workspace。超时后强制停止，不无限等待。单测验证：关停期间新请求抛 `AgentShuttingDownException`、进行中请求正常完成。
- AC-14（废弃与回归 · B-7）: `BaseAgent`/`AgentId`/`AgentReply`/`InMemoryAgentRegistry`/`ResilientAgentRegistry`/`TraceContext` 标记 `@Deprecated(since="C-005-B", forRemoval=true)`，javadoc 注明迁移目标（`BaseAgent` → `HarnessAgent`，`AgentRegistry` → 框架 `AgentRegistry`，`TraceContext` → `OtelTracingMiddleware`）。增量 A 全部 AC-1~AC-7 测试保持绿色。`XHSAnalysisAgent`（C-006 增量 C 双路径）行为不变。`mvn clean verify` 全绿、覆盖率 ≥80%（真实 LLM 链路 env-gated，JaCoCo 排除并登记）。

## 边界情况（≥3）

### 增量 A 边界（已验证）

- 当被调 Agent 超时时，调用方应在重试耗尽后降级并标记 `fallback=true`，绝不静默挂起。
- 当调用链深度达到 5 时，应阻断更深委派并返回可识别错误（防死锁/死循环）。
- 当 Nacos 不可达时，A2A 应进入降级（本地直连/缓存路由或显式失败），健康检查反映异常。
- 当收到含未知新增字段的 `payload` 时，子 Agent 应忽略而非失败（向后兼容，除非声明必填）。
- 当 `OutputQualityGate` 收到天数不一致的 TripPlan 时，必须置 `requiresHITL=true` 并记录违规项，而非抛弃数据。

### 增量 B 新增边界

- 当 `MsgAdapter.toFrameworkMsg` 收到 `HEARTBEAT`/`BROADCAST` 类型（非任务调度消息）时，应转为框架 `SystemMessage` 或返回 `Optional.empty()`，不抛异常；`fromFrameworkMsg` 对无法识别的框架消息类型同样返回 `Optional.empty()` 并记录 warn 日志。
- 当 `GovernanceMiddleware.onModelCall` 遭遇 `RateLimitedException` 时，应直接传播（不重试、不降级），与增量 A `GovernedExternalCaller` 限频行为保持一致；调用方（`HarnessAgent`）在中间件链异常时走 `fallbackModel` 原生降级。
- 当 `BaseHarnessAgentFactory` 启动时 `DEEPSEEK_API_KEY` 和 `DASHSCOPE_API_KEY` 均缺失时，`fromEnvironment()` 抛 `IllegalStateException` fail-fast，禁止无 LLM 的"空壳"Agent 启动（与 C-006 增量 C 行为一致）。
- 当 `AgentScopeA2aServer` 收到非标准 JSON-RPC 请求时，框架 `JsonRpcTransportWrapper` 返回标准 JSON-RPC error response（-32600 Invalid Request），不崩溃、不静默丢弃。
- 当进程收到 SIGTERM/SIGINT 触发 `GracefulShutdownManager` 时，进行中的 `HarnessAgent.call()` 应在 30s timeout 内完成；超时后 `ShutdownStateSaver` 保存部分推理状态，进程退出。新请求在关停期间收到 `AgentShuttingDownException`，不排队等待。
- 当 OTel SDK 未配置（仅 no-op provider）时，`OtelTracingMiddleware` 的所有钩子应直接 delegate 到 `next`，无额外开销（框架保证）；`TripPlanSpanEnricher` 在 `Span.current() == Span.getInvalid()` 时不写 attributes，不抛异常。
- 当自建 `AgentRegistry`（废弃路径）与框架 `AgentRegistry` 并存期间，`AgentRegistryService` 桥接层应优先查询框架注册表，fallback 到自建注册表；两边注册的同名 Agent 以框架侧为准，不重复。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | `GovernanceMiddleware` 中间件链额外开销 < 1ms（仅逻辑分发）；`OtelTracingMiddleware` no-op 路径 < 0.1ms |
| 可靠性 | 任一外部依赖故障均有降级路径（`GovernanceMiddleware` fallback + `HarnessAgent` fallbackModel 双层）；`GracefulShutdown` 保证进行中请求不丢 |
| 安全 | API Key 走环境变量/配置中心（禁硬编码）；OTel span attributes 不含敏感字段（手机号等脱敏，同增量 A） |
| 可观测 | OTel spans 覆盖 agent/model/tool 三级；Micrometer 指标保留 §6 全部埋点位；两层互补 |
| 质量 | 基座核心逻辑（适配器/中间件/工厂/桥接）单测覆盖率 ≥80%；真实 LLM 链路 env-gated 集成测试 |

## 设计约束

- 必须对齐 **ADR-008**（原生化方向、HarnessAgent 入口、C-005 自研栈地基卡收口）和 **ADR-009**（双 LLM deepseek-v4-pro + qwen3-max）。
- `GovernanceMiddleware` 必须复用增量 A 的 `GovernedExternalCaller`/`RetryPolicy`/`RateLimiter`/`FallbackHandler` 核心逻辑，禁止复制治理代码（编码规范 §10 / PMD CPD）。
- `BaseHarnessAgentFactory` 必须兼容 `XHSHarnessAgentFactory`（C-006 增量 C）：重构为继承关系后，C-006 全部回归不回退。
- A2A 服务端走框架标准 `AgentScopeA2aServer`，不自建 JSON-RPC 传输层。
- `MsgAdapter` 放 `huazai-trip-common`（与 `Msg` 同层），中间件/工厂/A2A 服务端放 `huazai-trip-skills`，Spring 装配放 `huazai-trip-server`——遵守依赖方向 R2（common ← skills ← server）。
- 废弃标记仅在本卡做 `@Deprecated` 注解 + javadoc 迁移指引；**不删除**自建代码，保留并存窗口供 C-007~C-010 逐卡迁移。最终清理登记技术债。
- 框架 `agentscope-extensions-a2a-server` 和 `agentscope-extensions-a2a-client` 经 `agentscope-bom:2.0.0-RC1` 管理（已在 parent pom），新增 dependency 需确认 BOM 已覆盖、enforcer 无 SNAPSHOT。

## 契约影响

- REST: 无变化（`/api/v1/admin/agent/status` 数据源从自建 `AgentRegistry.list()` 切换到框架 `AgentRegistry`，外部 API 不变）
- A2A: 自定义 `Msg` 信封契约不变；新增 `MsgAdapter` 适配层；框架标准 A2A（Google `io.a2a` JSON-RPC）作为新增传输通道并存
- 数据模型: 无变化（`OutputQualityGate`/Redis key schema 不动）
- 中间件链: 新增 `GovernanceMiddleware`/`OtelTracingMiddleware`/`GracefulShutdownMiddleware` 三个默认中间件（框架 `MiddlewareBase` 契约）

## 影响面

- 模块 / Agent / Skill:
  - `huazai-trip-common`：新增 `MsgAdapter`
  - `huazai-trip-skills`：新增 `GovernanceMiddleware`、`BaseHarnessAgentFactory`、`TripPlanSpanEnricher`；重构 `XHSHarnessAgentFactory` 继承关系；废弃 `BaseAgent`/`AgentId`/`AgentReply`/`InMemoryAgentRegistry`/`ResilientAgentRegistry`/`TraceContext`
  - `huazai-trip-server`：`A2aConfig` 改写（装配 `AgentScopeA2aServer` + 框架 `AgentRegistry`），`AgentStatusService` 适配框架数据源
  - `huazai-trip-agent-xhs`：`XHSAnalysisAgent` 不变（C-006 增量 C 双路径保持）
  - `huazai-trip-tests`：ArchUnit 新增/调整规则覆盖框架依赖方向
- 外部 API: 无新增外部依赖；新增 Maven artifact `agentscope-extensions-a2a-server`、`agentscope-extensions-a2a-client`（BOM 管理）
- wiki: `接口协议.md` §2 补充框架标准 A2A 并存说明；`架构决策.md` ADR-008 状态不变（本卡为其落地）

## 决策记录（增量 A 沿用 + 增量 B 新增）

### 增量 A 决策（D-1~D-7，已审批，不回退）

- **D-1 ~ D-7**: 见 R1 原文（自建 `Msg` 信封选型、common 模块落位、校验策略、`fallback` 落位 payload、标识符生成、集合 null 安全、AC-3 收口口径）。增量 B 不推翻这些决策，`Msg` 信封保持自建 record，仅新增 `MsgAdapter` 桥接到框架消息。

### 增量 B 决策

- **D-8 自定义 Msg 保留 + MsgAdapter 桥接**：自定义 `Msg`（任务调度信封，`msgId/from/to/type/payload/timeoutMs/traceId`）与框架 `Msg`（LLM 对话消息，`role/ContentBlocks`）本质不同（一个是协同信封，一个是 LLM 上下文），不能直接替换。保留自定义 `Msg` 作为 A2A 任务调度契约真相源（D-1 已决），新增 `MsgAdapter` 双向桥接。依据：D-1 人类已审批自建 `Msg` 选型；ADR-008 决策范围是 Agent 运行时（`BaseAgent` → `HarnessAgent`），不要求替换任务信封。
- **D-9 GovernanceMiddleware 复用而非重写**：`GovernanceMiddleware` 内部委托 `GovernedExternalCaller.call()` 而非复制其重试/限频/降级逻辑——确保增量 A 治理行为在原生路径上的一致性，且治理逻辑只有一份真相源（编码规范 §10）。`onModelCall` 钩子是正确挂载点（拦截 LLM API 调用，最内层）。
- **D-10 BaseHarnessAgentFactory 继承而非组合**：`XHSHarnessAgentFactory extends BaseHarnessAgentFactory`——基类封装双模型 + skill repo + workspace + 默认中间件链，子类 override `createToolkit()`/`buildPrompt()` 提供 agent-specific 行为。继承优于组合的理由：所有 Agent 工厂共享完全相同的构建流程（模型→中间件→skill→build→call→extract result），变化点仅是 toolkit 和 prompt。依据：C-006 增量 C 的 `XHSHarnessAgentFactory` 已验证此流程。
- **D-11 框架 AgentRegistry 与自建并存**：并存窗口期（本卡 → 各 Agent 卡全部迁移完），`AgentRegistryService` 同时查询框架 `AgentRegistry` 和自建 `AgentRegistry`，框架侧优先。原因：各 Agent 卡（C-007~C-010）逐卡迁移，不可能一次性切换；并存保证迁移期间服务发现不中断。并存期结束后，自建 `AgentRegistry` 连同 `@Deprecated` 代码一并清除。
- **D-12 OTel + Micrometer 双层可观测**：`OtelTracingMiddleware` 管分布式追踪（spans），`AgentMetrics`（Micrometer）管聚合指标（counters/timers/gauges）。两者互补，不互相替代——OTel 看单次请求链路，Micrometer 看宏观趋势。`TraceContext`（自定义 MDC 贯穿）废弃，其 6 字段转为 OTel span attributes（经 `TripPlanSpanEnricher`）。

## 测试策略

### 增量 A 测试（已验证）

- 先写失败测试: 重试退避序列(1/2/4s)、超时降级置 `fallback=true`、调用链深度=5 阻断、QualityGate 各不变量违规 → 先红。
- Happy Path: `TASK_ASSIGN→TASK_RESULT` 正常往返含 `traceId`；QualityGate 对合规 plan 通过。
- 边界测试: 超时/重试耗尽、链深超限、Nacos 不可达、未知字段忽略、天数不一致/超预算/评分过低触发 `requiresHITL`。
- 降级测试: 各外部依赖故障 → 默认值 + `fallback=true`，且 `TASK_RESULT` 显式标记。
- 回归测试: A2A 契约与治理是后续所有 Agent 的协同基线，纳入回归。

### 增量 B 测试

- **先写失败测试（Red）**:
  - `MsgAdapter`: 5 种 `MessageType` 往返无损（`TASK_ASSIGN`/`TASK_RESULT`/`TASK_ERROR`/`HEARTBEAT`/`BROADCAST`）；非任务类型返回 `Optional.empty()`
  - `GovernanceMiddleware`: 退避序列 1/2/4s 在 `onModelCall` 中触发；限频 `RateLimitedException` 直接传播不降级；重试耗尽后 fallback 标记
  - `BaseHarnessAgentFactory`: 双 key 组装、单 key 降级组装、无 key fail-fast 三种启动路径
  - `GracefulShutdown`: 关停期间新请求抛 `AgentShuttingDownException`；进行中请求正常完成
- **Happy Path**:
  - `MsgAdapter.toFrameworkMsg(TASK_ASSIGN)` → `UserMessage` 含 payload + metadata → `fromFrameworkMsg` → 重建 `TASK_RESULT` 含 `traceId`
  - `GovernanceMiddleware` 包装的 `HarnessAgent` 正常模型调用，治理链透明通过
  - `BaseHarnessAgentFactory.fromEnvironment()` 构造 Agent 实例，中间件链完整（Governance + OTel + GracefulShutdown）
  - `AgentScopeA2aServer` 启动，`AgentCard` 注册成功，`AgentStatusService` 返回状态列表
- **边界测试**:
  - `MsgAdapter` 处理 null payload、空 metadata、超大 payload（序列化边界）
  - `GovernanceMiddleware` 与 `HarnessAgent.fallbackModel` 双层降级交互（中间件降级 vs 模型降级）
  - `AgentRegistryService` 并存期查询：框架侧有、自建侧无 → 返回框架侧；两侧皆有 → 框架侧优先
  - `OtelTracingMiddleware` no-op 路径：无 SDK 时 span attributes 不写、不异常
- **降级测试**:
  - `GovernanceMiddleware` 模型调用持续超时 → 重试 3 次(1/2/4s) → fallback 降级响应
  - `BaseHarnessAgentFactory` 缺 `DEEPSEEK_API_KEY` → qwen 单模型降级
  - `GracefulShutdown` 超时 → 强制停止，`ShutdownStateSaver` 保存部分状态
- **回归测试**:
  - 增量 A 全部 AC-1~AC-7 测试保持绿色
  - C-006 增量 C `XHSHarnessAgentFactory` 重构为继承后全部回归通过
  - `mvn clean verify` 全绿，ArchUnit 依赖方向规则不破

## 验收用例

### 增量 A 验收用例（已通过）

- Case-1: Supervisor→Route `TASK_ASSIGN`（含 traceId/timeoutMs/payload）→ Route 返回 `TASK_RESULT(status=OK)`。
- Case-2: 被调方持续超时 → 调用方重试 3 次(1/2/4s)后降级 → `TASK_RESULT(fallback=true)`。
- Case-3: 构造深度=6 的委派链 → 第 6 跳被阻断，返回可识别错误。
- Case-4: 向 `OutputQualityGate` 传入 days≠request.days 的 plan → `requiresHITL=true` + 违规原因「天数不一致」。

### 增量 B 验收用例

- Case-5: `MsgAdapter.toFrameworkMsg(Msg.taskAssign(...))` → 框架 `Msg(role=USER, content=[TextContent(json)])` → `MsgAdapter.fromFrameworkMsg(...)` → 重建的 `Msg` 与原始 `Msg` 的 `msgId/from/to/traceId/timeoutMs/payload` 全等。
- Case-6: `HarnessAgent.builder().middleware(new GovernanceMiddleware(retryPolicy, sleeper, rateLimiter))` 构造的 Agent 执行模型调用，调用失败 3 次后 → 中间件返回 fallback 降级响应 → Agent 结果含 `fallback=true`。
- Case-7: `BaseHarnessAgentFactory.fromEnvironment()` 在 `DEEPSEEK_API_KEY` + `DASHSCOPE_API_KEY` 均存在时构造双模型 Agent；仅 `DASHSCOPE_API_KEY` 时构造单模型；均缺失时抛 `IllegalStateException`。
- Case-8: `AgentScopeA2aServer` 启动后，经 `ConfigurableAgentCard.Builder` 注册的 Agent 可被 `AgentRegistry.resolve()` 查到；`AgentStatusService` 返回包含该 Agent 的状态列表。
- Case-9: Spring 上下文关停（`@PreDestroy`）→ `GracefulShutdownManager.shutdown()` → 进行中 `HarnessAgent.call()` 正常完成 → 新请求抛 `AgentShuttingDownException` → 进程退出。
- Case-10: `XHSHarnessAgentFactory` 重构为继承 `BaseHarnessAgentFactory` 后，`XHSAnalysisAgentNativeTest` / `XHSHarnessAgentFactoryIT` 全部回归通过。

## 任务拆解（≤1 天/项，DAG 无环）

### 增量 A 任务（已完成）

- [x] T-1: 不可变 `Msg` + 消息类型枚举 + 契约字段校验（先红）· P0 · 依赖 C-004 · 模块 common
- [x] T-2: `BaseAgent` 抽象 + Agent 标识 + 生命周期 · P0 · 依赖 T-1 · 模块 skills
- [x] T-3: Nacos 注册发现接入 + Agent status 数据源 · P0 · 依赖 T-2,C-003 · 模块 skills/server
- [x] T-4: 统一调用治理（Retry/Timeout/RateLimiter/Fallback Interceptor 链）· P0 · 依赖 T-1 · 模块 skills
- [x] T-5: 调用链深度限制（≤5）+ 阻断测试 · P1 · 依赖 T-2 · 模块 skills
- [x] T-6: `OutputQualityGate` 全局不变量复核 + HITL 标记 · P0 · 依赖 C-004,T-1 · 模块 skills
- [x] T-7: 可观测字段贯穿 + Micrometer 指标埋点 · P1 · 依赖 T-2,T-4 · 模块 skills

### 增量 B 任务（待审批后执行）

- [x] T-8: `MsgAdapter` 双向桥接（自定义 Msg ↔ 框架 Msg）· P0 · 无前置依赖 · 模块 common
  - 先红：5 种 `MessageType` 往返无损测试、非任务类型 `Optional.empty()` 测试
  - 实现：`toFrameworkMsg`/`fromFrameworkMsg` 双向转换，payload JSON 序列化，metadata 保留
  - 新增 `agentscope-core` 依赖到 `huazai-trip-common`（BOM 管理，仅用 `Msg`/`MsgRole`/`ContentBlock`，符合 R4 基础库定位）
- [x] T-9: `GovernanceMiddleware`（治理逻辑包装为 MiddlewareBase）· P0 · 依赖 T-4 · 模块 skills
  - 先红：`onModelCall` 退避序列 1/2/4s、限频直接传播、重试耗尽 fallback 测试
  - 实现：`GovernanceMiddleware implements MiddlewareBase`，`onModelCall` 委托 `GovernedExternalCaller.call()`
  - 与增量 A `GovernedExternalCaller` 行为等价测试
- [x] T-10: `BaseHarnessAgentFactory` 通用工厂 + `XHSHarnessAgentFactory` 继承重构 · P0 · 依赖 T-9 · 模块 skills
  - 先红：双 key/单 key/无 key 三路径启动测试、默认中间件链包含 Governance+OTel+GracefulShutdown 测试
  - 实现：提取基类，`XHSHarnessAgentFactory extends BaseHarnessAgentFactory`
  - 回归：C-006 增量 C 全部测试不回退
- [x] T-11: A2A 标准服务端基础设施 · P0 · 依赖 T-10 · 模块 skills/server
  - 先红：`AgentScopeA2aServer` 启动注册 Agent、`AgentCard` 元数据查询、`AgentRegistryService` 并存桥接测试
  - 实现：`A2aConfig` 改写，装配 `AgentScopeA2aServer` + `ConfigurableAgentCard` + 框架 `AgentRegistry`
  - `AgentStatusService` 适配框架数据源
  - 新增 `agentscope-extensions-a2a-server` 依赖到 `huazai-trip-skills`（BOM 管理）
- [x] T-12: OTel 可观测替换 + `TripPlanSpanEnricher` · P1 · 依赖 T-10 · 模块 skills
  - 先红：OTel span attributes 包含 6 字段测试、no-op 路径零开销测试
  - 实现：`TripPlanSpanEnricher implements MiddlewareBase`（`onAgent` 钩子注入 span attributes），加入默认中间件链
  - `TraceContext` 标记 `@Deprecated`
- [x] T-13: `GracefulShutdown` 集成 · P1 · 依赖 T-10 · 模块 skills/server
  - 先红：关停期间新请求 `AgentShuttingDownException`、进行中请求完成、超时强制停止测试
  - 实现：`GracefulShutdownMiddleware` + `GracefulShutdownConfig` 接入 Spring 生命周期
- [x] T-14: 废弃标记 + 全量回归 · P0 · 依赖 T-8~T-13 · 模块 common/skills/server/tests
  - 6 个类标记 `@Deprecated(since="C-005-B", forRemoval=true)` + javadoc 迁移指引
  - 增量 A 全部测试绿色
  - C-006 增量 C 回归绿色
  - `mvn clean verify` 全绿，ArchUnit 规则不破

## 流水线进度

### 增量 A（已完成）

- [x] ① 需求分析（analyzing）— 含决策记录 D-1..D-7；人类已审批
- [x] ② 编码实现（coding）— 整卡完成
- [x] ③ 单测编写（testing）— 覆盖率满足
- [x] ④ 专家评审（reviewing）— 0 严重问题
- [x] ⑤ CI 门禁（ci）— 全绿
- [x] ⑥ 部署验证（verifying）— 通过
- [x] 交付（done）

### 增量 B（当前）

- [x] ① 需求分析（analyzing）— 规格卡已完成（R2），含决策记录 D-8..D-12，人类已审批
- [x] ② 编码实现（coding）— T-8~T-14 全部完成，mvn clean verify 全绿
- [x] ③ 单测编写（testing）— 覆盖率 ≥80% 全部达标，MsgAdapter 90.9%/TimeoutPolicy 100%/Sleeper 100%/RateLimitRule 100%/ResilientAgentRegistry 100% branch
- [x] ④ 专家评审（reviewing）— 0 个 🔴，9 维度全过，3 个 🟡 建议（非阻塞）
- [x] ⑤ CI 门禁（ci）— 全绿：编译 0 error, Checkstyle 0 violation, PMD 0 p1/p2, ArchUnit 6/6, 311 tests 0 failed, JaCoCo met, 0 SNAPSHOT, 0 密钥命中
- [x] ⑥ 部署验证（verifying）— 基础设施验证通过，10/10 模块 SUCCESS，制品可构建，回滚预案就位
