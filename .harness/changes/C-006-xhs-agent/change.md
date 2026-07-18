---
id: C-006
slug: xhs-agent
status: done
created: 2026-06-07
owner: Owner Agent
---

# C-006 小红书笔记分析 Agent（XHSAnalysisAgent · 主链路 + 真实来源与解析）

> **修订记录**
> - **R1（2026-06-07）**：主链路桩版本（增量 A）— 委派→检索→解析→候选筛选→TASK_RESULT 端到端打通，检索/缓存/目录端口化、桩实现验证契约。状态 = `done`。
> - **R2（2026-06-10）**：新增**增量 B「真实来源与解析」**——把原「后续来源与解析方向」中已确认的 D-1/D-2/D-3 由「延后拆 C-017」**收编进本卡**：合规笔记来源真实适配（官方授权 API / ReMe 召回，**排除模拟登录**）、`XHSNoteParser` 解析/抽取/情感、`XHSNote` 情感粒度契约变更。增量 B 为新增规格，状态回到 `analyzing`，**待人类审批**后方可进入 ② coding。增量 A 已交付内容不回退。
> - **R3（2026-06-11）**：新增**增量 C「AgentScope 原生化（XHS 切片）」**——核实「项目声明 AgentScope 为铁律却 0 处使用、0 次 LLM 调用」（NIH）后，把 XHS 这条链真正原生化：Agent 入口用 **`HarnessAgent`** + 真实 LLM（**主 deepseek-v4-pro / 降级 qwen3-max，ADR-009**）+ 真实 **Skill（SKILL.md）**，业务硬不变量仍锁在确定性 Java（经 `@Tool` 暴露）。已通过 **Gate 0 spike**（agentscope-harness 解析 + SB4/JDK21 装配 + 上下文启动，实测排雷）。决策落 **ADR-008**/**ADR-009**。C-005 自研栈对其余 Agent 的全量原生化拆独立地基卡，本卡不动 A2A `Msg`/`BaseAgent` 边界。增量 A/B 回归不回退。
> - **R4（2026-06-13）**：新增**增量 D「AgentScope 原生 A2A 适配」**——C-005-B 已交付原生化地基（`MsgAdapter`/`BaseHarnessAgentFactory`/`TripAgentCardFactory`/`GovernanceMiddleware`）并将自建 A2A 栈标记 `@Deprecated(forRemoval=true)`。本增量将 `XHSAnalysisAgent` 从废弃 `BaseAgent` 脱离：移除 `extends BaseAgent`、`AgentId`、`AgentReply` 依赖，内联 `receive(Msg)` 模板方法，用 `TripAgentCardFactory` 替代 `InMemoryAgentRegistry` 做 A2A 服务发现。`receive(Msg)→Msg` 公共契约与双路径行为不变。增量 A/B/C 全部回归不回退。

## 用户故事

作为 SupervisorAgent，我想要委派 XHSAnalysisAgent 检索并分析小红书旅游笔记、产出高质量候选景点（含评分、情感、提及频次），以便后续行程编排基于真实玩法而非凭空生成。

> **增量 B 补充故事**：作为系统所有者，我想要 XHSAnalysisAgent 从**合规真实来源**（官方授权 API 或自建 ReMe 知识库）取得笔记原文，并由独立的 `XHSNoteParser` 完成「原文 → 景点提及 + 逐提及情感」的结构化抽取，以便分析结论建立在真实语料与可治理的 LLM 抽取之上，而非「景点已链接、情感已判定」的输入假设。

## 本次范围

本卡分两个增量交付，**真相同卡**：

### 增量 A — 主链路桩版本（R1，已 `done`，本次不回退）

> 打通 **委派 → 检索 → 解析 → 候选筛选 → TASK_RESULT** 的端到端主链路，复用 C-005 已交付治理能力，**不引入新外部基础设施**。检索源、缓存、目录一律以**端口（接口）+ 可替换实现**形式接入，主链路用内存/桩实现验证契约。

- 做：`XHSNote` 模型补齐、`xhs-note-analysis` 业务 Skill、`XHSAnalysisAgent` A2A 接入、评分≥3.5 与情感筛选、空检索/故障降级（`fallback=true`）。
- 端口化（定义接口 + 桩/内存实现）：小红书检索源 `XHSNoteSource`、笔记缓存 `XHSNoteCache`、景点目录 `AttractionCatalog`（ID→Attraction，提供 rating/名称/位置）。

> **设计偏差登记（coding 阶段，已与人类确认）**: `XHSNote`（数据模型 §2.5）仅含 `mentionedAttractions: List<String>`（景点 ID）与 `sentiment`，**不含 rating**；而 AC-4 需对候选 `Attraction` 按 `rating ≥ 3.5` 筛选。结论：新增第三个端口 `AttractionCatalog`（ID→`Attraction`，承载真实 rating/名称/位置），主链路用内存桩，真实 ReMe `attraction_kb` 召回延后至 C-015。该端口为本卡契约新增项，已登记于「契约影响」。

### 增量 B — 真实来源与解析（R2，本次新增，待审批）

> 目标：把「文本 → 景点链接」与「情感判定」从**输入假设**转为**本卡内可治理的代码产出**，并接入**合规真实来源**。原「后续来源与解析方向」D-1/D-2/D-3 由「延后拆 C-017」**收编进本卡**（C-017 占位作废，详见「拆卡安排」）。端口形态不变、增量演进，增量 A 的桩实现保留为契约基线与测试替身。

- 做（B-1 合规来源真实适配 · D-1）：为 `XHSNoteSource` 增加**合规真实适配实现**，二选一/组合——
  - **官方授权 API 适配（本卡主交付）**：HTTP 客户端经 C-005 `GovernedExternalCaller` 治理（`xhs ≤10/min`、超时 30s、重试 3 次、降级），密钥走环境变量/配置中心（禁硬编码）。
  - **ReMe `attraction_kb` 召回适配（端口就位 + 适配壳，真实实现依赖 C-015）**：从已合规沉淀语料检索，绕开实时抓取的合规与稳定性风险；真实向量召回在 C-015 落地，本卡只保证端口可插拔与契约对齐。
  - **明确排除**：模拟登录 / 登录态抓取 / 任何规避官方接口的方式（合规红线，见非目标）。
- 做（B-2 解析独立成端口 · D-2）：新增 `XHSNoteParser` 端口——「原始笔记 → 结构化（景点提及 + 逐提及情感）」，实现为 LLM 实体抽取 + 链接到 `AttractionCatalog` + 情感判定，经 `GovernedExternalCaller` 统一治理（LLM 超时/重试/降级）。`XHSNoteSource` 退化为**只取原文**，解析独立成段；现有 `XHSNoteAnalyzer` 聚合/筛选链原样复用。
- 做（B-3 情感粒度契约变更 · D-3）：`XHSNote` 情感由**整篇单值**下沉到**每个提及**——新增 `AttractionMention{attractionId, sentiment}`，以 `mentions: List<AttractionMention>` 取代 `mentionedAttractions: List<String>` + `sentiment: SentimentType`。属 `huazai-trip-common` 字段级契约变更，`XHSNoteAnalyzer` 改为按 mention 情感聚合，既有桩/测试随迁。
- 仍延后（不在增量 B Gate）：真实 Redis 缓存写入实现、ReMe 向量库的真实召回/写入实现 → 归 **C-015**；本卡仅保留端口与适配壳。

### 增量 C — AgentScope 原生化（XHS 切片，R3，本次新增）

> 目标：把 XHS 这条链从「声明 AgentScope 却 0 使用、0 LLM 调用」转为**真正原生运行**——Agent 入口用 `HarnessAgent` + 真实 LLM（**主 deepseek-v4-pro / 降级 qwen3-max，ADR-009**）+ 真实 Skill（`SKILL.md`），同时把硬业务不变量留在确定性 Java。决策依据见 **ADR-008**/**ADR-009**；范围与风险见审批计划。

- 做（Gate 0 · 阻塞门禁，已过）：spike 实测 `agentscope-harness:2.0.0-RC1` 解析 + `HarnessAgent` 在 JDK21 + SB4 BOM 下编译/类加载/装配 + SB4 上下文启动 + enforcer 无 SNAPSHOT。失败即止转 ADR；已全过。
- 做（C-1 工具门面）：`XHSAnalysisTools` 把确定性 `XHSNoteAnalyzer` 暴露为 `@Tool analyze_xhs_notes`，**评分≥3.5/负面剔除/降级 fallback 留 Java**，`lastResult()` 捕获权威结果。
- 做（C-2 真实 Skill）：`resources/skills/xhs-note-analysis/SKILL.md`（frontmatter `name=xhs_note_analysis`）经 `ClasspathSkillRepository` 加载——AgentScope 原生渐进式披露机制（非废弃 `SkillBox`）。
- 做（C-3 原生 Agent 工厂）：`XHSHarnessAgentFactory implements XHSAgentRunner`，组装 LLM（**主 deepseek-v4-pro / 降级 qwen3-max，见 ADR-009**，env key）+ `Toolkit` + Skill + `HarnessAgent`（无状态最小配置，不接 sandbox/Redis/subagent），`run()` 权威候选取自 `lastResult()`。
- 做（C-4 Agent 接缝）：`XHSAnalysisAgent` 新增注入 `XHSAgentRunner` 的构造器，`onTaskAssign` 先走原生路、失败/无权威产物**降级回确定性直路**（`fallback=true`），不抛裸异常；旧构造器与增量 A/B 行为不动。
- **明确不做（拆走）**：`BaseAgent`/`Msg`/`AgentRegistry`/`GovernedExternalCaller` 对其余 Agent 的全量原生化、Nacos 原生注册、ReMe/Redis 真实存储 → 独立地基卡（落 ADR-008 的并存期收口）。

### 增量 D — AgentScope 原生 A2A 适配（R4，本次新增）

> 目标：将 `XHSAnalysisAgent` 从废弃自建 A2A 栈（`BaseAgent`/`AgentId`/`AgentReply`）彻底脱离，适配 C-005-B 已交付的 AgentScope 原生基础设施（`TripAgentCardFactory`/`MsgAdapter`/框架 `AgentRegistry`）。不创建新基类——`BaseAgent` 的模板方法直接内联到 `XHSAnalysisAgent.receive()`，用 `Msg.taskResult()`/`Msg.taskError()` 替代 `AgentReply`，用常量 `AGENT_URI` 替代 `AgentId`。双路径架构（native HarnessAgent + deterministic fallback）保持不变。

- 做（D-1 移除 BaseAgent 继承）：`XHSAnalysisAgent` 去掉 `extends BaseAgent`，内联 `receive(Msg)` 模板方法（TASK_ASSIGN 验证、lifecycle status 管理、exception→TASK_ERROR 包装、traceId 透传）。
- 做（D-2 消除 AgentId/AgentReply）：用 `AGENT_URI = "agent://xhs-analyst"` 常量替代 `AgentId.of(AGENT_ID).uri()`；`onTaskAssign` 返回的 `AgentReply.ok/fallback` 改为直接 `Msg.taskResult(AGENT_URI, ...)`。
- 做（D-3 适配 TripAgentCardFactory）：测试层用 `TripAgentCardFactory.buildCard()` 替代 `InMemoryAgentRegistry` + `AgentRegistration`，证明 agent 元数据可接入框架 A2A 服务发现。
- 做（D-4 构造器 null guard + lifecycle 可测）：显式校验 `analyzer`/`metrics` 非 null（原由 BaseAgent 校验 `agentId`）；`status()` 方法和 `AgentStatus` lifecycle 内联到本类。

## 非目标（Out of Scope）

- 不做小红书内容发布/评论/爬取规避（合规：遵守 robots.txt 与 API 条款）。
- **不采用模拟登录/登录态抓取等规避官方接口的方式获取笔记**（合规红线，增量 B 真实来源同样受约束）——真实来源仅限**官方授权 API** 或**自建 ReMe 知识库召回**（详见增量 B / 「后续来源与解析方向」）。
- 不做行程编排、路线规划、预算（归 C-007~C-009）。
- 不实现 A2A 基座/统一治理/QualityGate（复用 C-005 既有交付）。
- 不实现**真实 Redis 缓存写入**与 **ReMe 向量库的真实召回/写入** —— 本卡（含增量 B）仅定义端口与适配壳，真实接入归 **C-015**（ReMe 记忆/RAG）。增量 B 的 `XHSNoteSource` 合规真实适配以**官方授权 API** 为主交付，ReMe 召回适配壳的真实向量实现亦归 C-015。
- 不接入 **Nacos** 注册发现 —— 主链路用 C-005 既有内存 `AgentRegistry`，Nacos 归后续基础设施卡。
- ~~不实现真实小红书 HTTP 客户端~~ —— **（R2 调整）** 官方授权 API 真实适配已收编进增量 B；仅模拟登录/登录态抓取仍为红线。

## 验收标准（AC）

- AC-1: `XHSAnalysisAgent` 继承 C-005 `BaseAgent`，标识 `agent://xhs-analyst`，可注册进 `AgentRegistry`；`receive` 仅受理 `TASK_ASSIGN(taskType=XHS_ANALYSIS, context={destination, preferences?, headcount?, style?})`，非该类型/非该 taskType 走 BaseAgent 既有快速失败/`TASK_ERROR` 路径。
- AC-2: 正常路径返回 `TASK_RESULT`，`payload.result` 为候选 `Attraction` 列表（透传 `traceId`），`fallback=false`。
- AC-3: 实现 `xhs-note-analysis` 业务 Skill：经 `XHSNoteSource` 检索 `XHSNote`（标题/正文/标签/点赞/提及景点/情感）→ 聚合为候选 `Attraction`（含提及频次驱动的排序），字段对齐 `数据模型.md` §2.4/§2.5。
- AC-4: 候选筛选满足业务不变量（`业务模型.md` §4）：仅 `rating ≥ 3.5` 入选；低于阈值的景点不进入推荐池。
- AC-5: 情感筛选：负面情感（`SentimentType.NEGATIVE`）占比高的提及对景点降权，达剔除条件的不进入推荐池；候选携带情感聚合信息。
- AC-6: 外部检索经 C-005 `GovernedExternalCaller` 统一治理：限频 `xhs ≤10/min`、超时 30s、重试 3 次；检索失败/超时/限频耗尽 → 走 `XHSNoteCache` 缓存或预置默认候选并 `fallback=true`，绝不抛裸异常、不静默挂起。
- AC-7: 可观测：经 C-005 `AgentMetrics`/`TraceContext` 发射 `traceId/planId?/agentId=xhs-analyst/taskType=XHS_ANALYSIS/fallback` 及 `agent.call.latency`、`api.rate_limit.hit`、`memory.cache.hit.rate`。

### 增量 B 验收标准（R2，待审批）

- AC-8（合规真实来源 · B-1）: 提供 `XHSNoteSource` 的**官方授权 API 真实适配实现**，经 C-005 `GovernedExternalCaller` 治理（`xhs ≤10/min` 不超频、超时 30s、重试 3 次、失败/限频耗尽降级），API Key 从环境变量/配置中心读取（**禁硬编码**，CI 密钥扫描 0 命中）；适配实现失败必降级、绝不抛裸异常、不静默挂起。**不得**出现模拟登录/登录态抓取代码或依赖。
- AC-9（ReMe 召回适配壳 · B-1）: `XHSNoteSource` 的 ReMe `attraction_kb` 召回适配以可插拔实现接入，**真实向量召回依赖 C-015**；本卡保证端口契约对齐与可替换（无 C-015 时以桩/降级占位），不阻塞增量 B Gate。
- AC-10（解析独立端口 · B-2）: 新增 `XHSNoteParser` 端口，`parse(rawNote) → 结构化 XHSNote`（LLM 实体抽取 + 链接到 `AttractionCatalog` + 逐提及情感判定），经 `GovernedExternalCaller` 治理（LLM 超时 30s/重试 3 次/降级默认值）；`XHSNoteSource` 仅负责取原文。解析失败 → 该笔记降级为「无结构化提及」并跳过，不污染候选、不抛裸异常。
- AC-11（情感粒度契约变更 · B-3）: `XHSNote` 以 `mentions: List<AttractionMention{attractionId, sentiment}>` 取代 `mentionedAttractions + 整篇 sentiment`；新增不可变 `AttractionMention`（common）；`XHSNoteAnalyzer` 改为**按 mention 情感**聚合（「一篇夸 A 吐槽 B」可正确分别计入），AC-5 情感降权/剔除语义在新契约下保持等价或更精确。
- AC-12（向后兼容与回归不回退）: 契约变更后，增量 A 既有 AC-1~AC-7、Case-1~5、降级/限频/缓存语义全部回归通过（桩/测试随契约迁移）；`mvn clean verify` 全绿、核心覆盖率仍 ≥80%；流水线 `XHSAnalysisQuery → 原始笔记 → Parser → 结构化 XHSNote → 聚合/筛选链` 串联可跑通，`fallback` 语义不变。

### 增量 C 验收标准（R3）

- AC-13（Gate 0 装配门禁）: `agentscope-harness:2.0.0-RC1` 经 BOM 解析（不改版本铁律）；`HarnessAgent` + `DashScopeChatModel(qwen3-max)` 在 JDK21 + Spring Boot 4.0 BOM 下编译/类加载/装配 0 冲突，SB4 上下文正常启动，enforcer 无 SNAPSHOT 通过。**门禁不过即止，转 ADR**。
- AC-14（不变量留 Java）: XHS 候选的评分≥3.5、负面情感剔除、降级 `fallback`、限频≤10/min 由确定性 `XHSNoteAnalyzer`（经 `@Tool` 暴露）强制，**LLM 不得伪造/放宽/新增候选**；`XHSAnalysisTools` 单测验证工具产物 == 确定性分析产物。
- AC-15（真实原生链路 + Skill）: 提供 `XHSHarnessAgentFactory`，经 `HarnessAgent` + 真实 LLM（**主 deepseek-v4-pro / 降级 qwen3-max，ADR-009**）+ `ClasspathSkillRepository` 加载 `SKILL.md`（原生渐进式披露，非废弃 `SkillBox`）跑通 XHS 分析；API Key 走环境变量（`DEEPSEEK_API_KEY`/`DASHSCOPE_API_KEY`，禁硬编码，CI 密钥扫描 0 命中）；真实链路经 env-gated 集成测试验证（无 `DEEPSEEK_API_KEY` 自动 skip，不伪装通过）。
- AC-16（降级与回归不回退）: `XHSAnalysisAgent` 注入 `XHSAgentRunner` 后，原生路失败/无权威产物 → 降级回确定性直路并 `fallback=true`、绝不抛裸异常；未注入 runner 时为纯确定性直路。增量 A/B 全部回归通过，`mvn clean verify` 全绿、覆盖率 ≥80%（HarnessAgent 工厂因真实 LLM/网络不可离线覆盖，JaCoCo 排除并登记）。

### 增量 D 验收标准（R4）

- AC-17（零废弃依赖）: `huazai-trip-agent-xhs` 模块零 `import` 自建废弃 A2A 类型（`BaseAgent`/`AgentId`/`AgentReply`/`InMemoryAgentRegistry`/`AgentRegistration`）。`XHSAnalysisAgent` 不 `extends BaseAgent`。
- AC-18（行为等价）: `receive(Msg)→Msg` 公共契约与双路径行为不变——增量 A/B/C 全部现有测试（`XHSAnalysisAgentTest`/`XHSAnalysisAgentNativeTest`/`XHSAnalysisYunnanScenarioTest`）不回退，`mvn clean verify` 全绿、覆盖率 ≥80%。
- AC-19（A2A 服务发现）: agent 元数据可经 `TripAgentCardFactory.buildCard()` 构建 `ConfigurableAgentCard`（name=xhs-analyst, skills=[xhs_note_analysis]），用于框架 A2A 服务发现。
- AC-20（构造器 null guard + lifecycle）: 构造器显式校验 `analyzer`/`metrics` 非 null（快速失败）；`status()` 返回 `AgentStatus`（IDLE→THINKING→ACTING→DONE lifecycle）。

## 边界情况（≥3）

- 当目的地检索结果为空（无任何可用笔记）时，返回空候选列表 + 可读说明 + `fallback=true`，不抛裸异常。
- 当小红书检索触发限频（≤10/min）时，由 `GovernedExternalCaller` 短路 `RATE_LIMITED`，经降级走缓存/默认候选并 `fallback=true`，绝不超频调用。
- 当全部候选评分均 `<3.5` 时，返回空推荐池 + 「质量不足」说明（`fallback=false`，质量门由下游/QualityGate 决定是否 HITL）。
- 当笔记含敏感/不安全信息或 `mentionedAttractions` 为空时，过滤该笔记/提及，不污染候选。
- 当 `context` 含未知/新增字段时，忽略而非失败（向后兼容，对齐 C-005 BaseAgent 约定）。

增量 B 新增边界：

- 当官方 API 返回授权失败/401/403 或密钥缺失时，按检索故障走降级（缓存/默认候选 + `fallback=true`），并发射 `api.rate_limit.hit` 之外的可识别失败信号，绝不降级为模拟登录。
- 当 `XHSNoteParser` LLM 调用超时/重试耗尽时，该笔记降级为「无结构化提及」跳过；整体若无任何结构化提及则等同空检索（空候选 + `fallback=true`）。
- 当 LLM 抽取出的景点无法链接到 `AttractionCatalog`（未知 ID/名称）时，丢弃该提及而非伪造候选，不污染推荐池。
- 当一篇笔记同时含正面与负面提及（「夸 A 吐槽 B」）时，按 `AttractionMention` 逐提及情感分别计入 A/B，互不串扰（契约变更后的核心正确性边界）。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 单次分析（检索+解析，主链路桩源）P95 < 3s；真实源接入后目标 P95 < 10s、命中缓存 < 1s |
| 可靠性 | 检索故障降级缓存/默认候选并 `fallback=true`，不阻塞主链路；失败必返回可识别 `TASK_ERROR`，禁静默超时 |
| 安全 | 遵守小红书条款；API Key 走环境变量/配置中心（禁硬编码）；笔记缓存 24h；不落盘；输出脱敏 |
| 可观测 | `memory.cache.hit.rate`（目标 ≥80%）、`api.rate_limit.hit`、`agent.call.latency`、`fallback` 标记 |
| 质量 | Agent/Skill 核心逻辑（筛选/情感/降级）单测覆盖率 ≥80% |

## 设计约束

- `XHSAnalysisAgent` 落在 `huazai-trip-agent-xhs`；可复用的检索/解析/筛选能力下沉 `huazai-trip-skills` 的 `xhs-note-analysis` 包（跨 Agent 复用，**禁依赖其他 Agent 模块**，遵守工程结构依赖方向）。
- `XHSNote` 作为字段级数据契约补入 `huazai-trip-common`（与已存在的 `SentimentType`、`Attraction` 同层；C-004 仅遗漏，本卡补齐并登记契约影响）。
- 必须复用 C-005 既有交付：`BaseAgent`/`AgentReply`/`AgentId`/`Msg`/`GovernedExternalCaller`/`RateLimiter`/`AgentMetrics`/`TraceContext`/`AgentRegistry`；**不得复制治理逻辑**。
- 检索源、缓存、景点目录以端口（接口）声明：`XHSNoteSource`（检索）、`XHSNoteCache`（缓存读/写，键用 `CacheKeys.xhsNoteKey`）、`AttractionCatalog`（ID→`Attraction`，提供 rating）；桩实现保留为契约基线与测试替身。
- 评分阈值（≥3.5）与情感筛选属 Agent/Skill 内部职责；候选全局一致性（去重等）由下游 `OutputQualityGate` 复核，本卡只保证候选质量。
- **（增量 B）解析与取原文职责分离**：`XHSNoteSource` 仅返回原始笔记，结构化（抽取/链接/情感）下沉新端口 `XHSNoteParser`；二者均经 `GovernedExternalCaller` 统一治理，**禁自建治理**。`XHSNoteParser` 落 `huazai-trip-skills/xhs-note-analysis`，对 LLM 的依赖经端口隔离，便于桩测。
- **（增量 B）来源合规约束**：真实 `XHSNoteSource` 适配仅限官方授权 API 或 ReMe 召回；**禁出现**模拟登录/登录态/绕过官方接口的代码与依赖（ArchUnit/评审/CI 关注点）。
- **（增量 B）契约变更最小爆炸半径**：`XHSNote` 情感粒度变更集中在 `huazai-trip-common`（新增 `AttractionMention`、改 `XHSNote` 字段），下游 `XHSNoteAnalyzer` 聚合改造 + 桩/测试随迁，A2A `TASK_RESULT`（`result=List<Attraction>`）信封不变。

## 契约影响

- REST: 无（经 Supervisor 编排）。
- A2A: 约定 `taskType=XHS_ANALYSIS` 的 `TASK_ASSIGN` payload（`context={destination, preferences?, headcount?, style?}`）与 `TASK_RESULT`（`result=List<Attraction>`, `fallback`），复用 `Msg` 既有信封，向后兼容演进。**增量 B 不改 A2A 信封**。
- 数据模型（增量 A）: **新增 `XHSNote`（common，对齐 `数据模型.md` §2.5）**；复用 `Attraction`、`SentimentType`（C-004 已交付）。
- 数据模型（增量 B · D-3 字段级契约变更，**破坏性**）: **新增不可变 `AttractionMention{attractionId: String, sentiment: SentimentType}`（common）**；`XHSNote` 以 **`mentions: List<AttractionMention>`** 取代 `mentionedAttractions: List<String>` + 整篇 `sentiment: SentimentType`。需同步更新 `数据模型.md` §2.5（移除「整篇单值」「顺延 C-017」表述，改为逐提及情感）。影响 `XHSNoteAnalyzer` 聚合逻辑、所有构造 `XHSNote` 的桩与测试。
- Skill 端口（skills 模块）: 增量 A — `XHSNoteSource`、`XHSNoteCache`、`AttractionCatalog`；**增量 B 新增 `XHSNoteParser`**（`parse(rawNote)→结构化 XHSNote`，LLM 抽取/链接/情感，经治理）。
- 真实来源适配（增量 B 新增）: `XHSNoteSource` 官方授权 API 适配实现（HTTP + 治理 + 环境变量密钥）；ReMe `attraction_kb` 召回适配壳（真实向量召回依赖 C-015）。
- 可观测（AC-7，增量 A）: `AgentMetrics` 新增 `memory.cache.hit{cache,result}` 计数（命中率 = hit/(hit+miss)，复用既有 `api.rate_limit.hit{api}`）；`XHSAnalysisResult` 新增不可变 `Telemetry(rateLimited, cacheOutcome)` 载荷（`CacheOutcome ∈ {NOT_CONSULTED,HIT,MISS}`），由 `XHSAnalysisAgent` 派发指标，实时成功不污染命中率分母。增量 B 新增 LLM 解析调用的 `agent.call.latency`/重试/降级可观测（沿用既有指标口径，不新增指标名）。
- 安全过滤（边界 #4，增量 A）: skills 新增 `SensitiveContentScreen implements Predicate<XHSNote>`（默认黑名单，大小写不敏感），作为 `XHSNoteAnalyzer` 缺省准入断言，可经构造注入覆盖。
- Redis / ReMe: 仅定义端口 `XHSNoteCache`（键 `trip:xhs:note:{noteId}`，24h TTL，`CacheKeys.xhsNoteKey` 已就绪）；真实 Redis/ReMe 及 `AttractionCatalog`、ReMe `attraction_kb` 召回的**真实实现**延后至 C-015（增量 B 仅就位端口与适配壳）。

## 影响面

- 模块 / Agent / Skill: `huazai-trip-agent-xhs`（Agent）、`huazai-trip-skills/xhs-note-analysis`（Skill + 端口，增量 B 新增 `XHSNoteParser` + 官方 API/ReMe 适配）、`huazai-trip-common`（增量 A 新增 `XHSNote`；增量 B 新增 `AttractionMention` + 改 `XHSNote` 字段）。
- 外部 API: 小红书检索（≤10/min；增量 A 桩化，**增量 B 官方授权 API 真实适配**）、LLM（解析/情感；增量 A 可桩化，**增量 B 经 `XHSNoteParser` 真实接入并治理**）。
- wiki: 对齐 `业务模型.md`(§4 不变量 / §7 映射)、`数据模型.md`(§2.4 Attraction / **§2.5 XHSNote — 增量 B 需改情感粒度并清除「顺延 C-017」**)、`接口协议.md`(§3 payload / §4 限频降级)。

## 规则归属

- 业务不变量归属: 评分≥3.5、情感筛选 → XHS Agent/Skill 内部；候选全局一致性 → 下游 `OutputQualityGate`。
- 外部调用治理归属: 小红书/LLM 调用统一走 C-005 `GovernedExternalCaller`（限频 + 重试 + 降级），不自建治理。
- 可观测性要求: `traceId/planId?/agentId=xhs-analyst/taskType=XHS_ANALYSIS/fallback`；缓存命中率、限频命中、调用时延指标。

## 测试策略

- 先写失败测试（Red）: 评分阈值筛选、情感降权/剔除、限频不超频、检索故障降级缓存/默认（`fallback=true`）、空检索 → 先红。
- Happy Path: 给定目的地+偏好，桩源返回若干笔记 → 返回 ≥1 个评分≥3.5 的候选 `Attraction`，`fallback=false`，`traceId` 透传。
- 边界测试: 空检索、检索源返回 `null`、全部<3.5、限频触发、敏感/空提及笔记过滤、`context` 含未知字段。
- 降级测试: `XHSNoteSource` 超时/限频耗尽 → 走 `XHSNoteCache`/默认候选并标记 `fallback=true`；不抛裸异常。
- 回归测试: 候选筛选与情感规则纳入回归，防阈值/规则漂移；与 C-005 BaseAgent 收发契约联测。
- 强化补充（T-6 阶段③）:
  - **限频 ≤10/min 真实边界**（`RateLimitBoundary`）: `RateLimiter.withDefaults`（xhs=10/min）下前 10 次实时检索成功、第 11 次命中限频走缓存，断言检索源调用次数恒 =10（不超频）。
  - **空结果**: 检索源返回 `null` 按空处理且不触发重试；故障且缓存为空列表视为 `MISS`（非 `HIT`）。
  - **超时**: 瞬时超时 2 次后恢复 → `fallback=false`、退避序列 `[1s,2s]`、缓存回填（区别于持续超时降级）。
  - **缓存命中回归**（`CacheFallbackRegression`）: 取自缓存的笔记仍受 `rating≥3.5` 与负面情感不变量约束（防降级旁路规则漂移）；达标候选在 `fallback=true` 下仍有效产出。
  - **Agent 层降级语义**: 命中限频返回 `TASK_RESULT(fallback=true)`，绝不退化为 `TASK_ERROR`。
- 增量 B 新增（R2，先红）:
  - **解析端口（`XHSNoteParser`）**: 给定原始笔记 → 抽取景点提及 + 逐提及情感 → 链接到 `AttractionCatalog`；无法链接的提及被丢弃；LLM 超时/重试耗尽 → 该笔记降级跳过（不抛裸异常）。
  - **情感粒度契约（`AttractionMention`）**: 「一篇夸 A 吐槽 B」→ A 正面、B 负面分别计入，互不串扰；B 负面占比超阈值被剔除而 A 保留（契约变更后 AC-5 等价/更精确）。
  - **官方 API 真实适配**: 限频 `≤10/min` 不超频（沿用 `RateLimitBoundary` 口径）；授权失败/密钥缺失 → 降级 `fallback=true`；**禁模拟登录**（无登录态/Cookie 抓取代码，评审 + 静态检查关注）。
  - **契约迁移回归（AC-12）**: 增量 A 全部 Case-1~5 与降级/限频/缓存语义在新 `XHSNote` 契约下回归通过；`mvn clean verify` 全绿、覆盖率 ≥80%。
  - **串联链路**: `Query → Source(取原文) → Parser(结构化) → Analyzer(聚合/筛选)` 端到端，`fallback` 语义不变。

## 验收用例

- Case-1: `XHS_ANALYSIS(destination=成都, preferences=["美食","小众"])` + 桩源返回 3 篇笔记 → `TASK_RESULT` 候选含评分≥3.5、情感字段、提及频次，`fallback=false`。
- Case-2: `XHSNoteSource` 连续超时（重试耗尽）→ 走 `XHSNoteCache` → `TASK_RESULT(fallback=true)`。
- Case-3: 检索为空 → 返回空候选 + 说明 + `fallback=true`，无异常。
- Case-4: 候选中评分 3.4 的景点 → 不出现在推荐池。
- Case-5: 向 Agent 投递 `taskType=ROUTE_PLANNING` 的 `TASK_ASSIGN` → 走 BaseAgent 失败路径返回 `TASK_ERROR`（不误处理）。
- Case-6（增量 B · 真实来源）: 官方 API 适配返回若干原始笔记 → 经 `XHSNoteParser` 结构化 → 候选含逐提及情感，`fallback=false`；密钥缺失/授权失败 → 降级 `fallback=true`，无模拟登录路径。
- Case-7（增量 B · 解析降级）: `XHSNoteParser` LLM 连续超时（重试耗尽）→ 受影响笔记跳过；若全部跳过 → 空候选 + `fallback=true`，无裸异常。
- Case-8（增量 B · 情感粒度）: 一篇笔记「夸 A 吐槽 B」→ A 入选（正面）、B 因负面占比超阈值剔除；验证 `AttractionMention` 逐提及情感互不串扰。

## 后续来源与解析方向（D-1/D-2/D-3 已于 R2 收编进增量 B；本节保留为决策溯源）

> **R2 更新**：本节原为「评审登记的演进方向，拆 C-017」。R2 起 D-1（合规来源）/D-2（解析端口）/D-3（情感粒度契约）**收编进本卡增量 B**（见「本次范围 · 增量 B」「增量 B 验收标准 AC-8~AC-12」与任务 T-8~T-10），**不再拆 C-017**。D-4（真实存储）仍归 C-015。以下保留决策背景以备溯源。
>
> 背景：评审时确认增量 A 主链路的「检索/提取/情感分析」**靠输入假设成立**——`XHSNoteSource` 仅有内存桩，`XHSNote.mentionedAttractions` 已是景点 ID、`sentiment` 已是整篇单值，意味着「文本→景点链接」与「情感判定」发生在 Agent 之外、尚无代码产出。增量 B 即把这一步转为本卡内可治理的代码产出。

### D-1 笔记来源策略（已决策：合规来源，排除模拟登录）— **R2 收编进增量 B（B-1 / AC-8、AC-9）**
- **不走模拟登录/登录态抓取**（与非目标合规红线一致）。
- 真实来源二选一/组合，留待来源卡评审：
  - **官方授权 API**：受 `xhs ≤10/min` 限频治理（C-005 `GovernedExternalCaller`），需密钥走环境变量/配置中心。
  - **自建知识库（首选）**：ReMe `attraction_kb` 向量召回（归 C-015），从已合规沉淀的语料检索，绕开实时抓取的合规与稳定性风险。
- `XHSNoteSource` 端口形态不变，仅新增真实适配实现；增量 A 桩实现保留为契约基线与测试替身。

### D-2 解析/抽取应独立成端口 — **R2 收编进增量 B（B-2 / AC-10）**
- 现状把「原始笔记 → 结构化（景点提及 + 情感）」这一**最难的分析步**前置成了输入假设。方向：新增 `XHSNoteParser` 端口（LLM 实体抽取 + 链接到自有 `AttractionCatalog` + 情感判定），让 `XHSNoteSource` 只负责取原文、解析独立成段并经统一治理（LLM 超时/重试/降级）。
- 影响：`XHSAnalysisQuery → 原始笔记 → Parser → 结构化 XHSNote → 现有聚合/筛选链` 串联；现有 `XHSNoteAnalyzer` 聚合/筛选逻辑可原样复用。

### D-3 情感粒度契约变更（影响 common 模型）— **R2 决策：收编进增量 B（B-3 / AC-11），随解析一并落地**
- 现状 `XHSNote.sentiment` 为**整篇单值**，但 `analyzer` 按景点聚合情感——「一篇夸 A 吐槽 B」无法表达，AC-5 前提不严谨。
- 方向（R2 采纳）：将情感下沉到**每个提及**，以 `mentions: List<AttractionMention{attractionId, sentiment}>` 取代 `mentionedAttractions + sentiment`。属 `XHSNote`（common）字段级契约变更，越晚改动越大（牵动 common + 既有测试），故与解析（B-2）一并落地、避免二次破坏。
- 决策点（已拍板）：随增量 B 一并落地；需同步 `数据模型.md` §2.5 与既有桩/测试迁移（AC-12 回归守护）。

### D-4 存储方向
- 写入与召回统一归 **C-015**（ReMe 记忆/RAG + 真实 Redis 缓存）：原始/结构化笔记落知识库，`XHSNoteCache` 真实 Redis 适配（键 `trip:xhs:note:{noteId}`，24h TTL）。本卡仅保留端口与桩。

### 拆卡安排（R2 更新）
- ~~**C-017（拟）**：合规笔记来源真实适配 + `XHSNoteParser` 解析 + `XHSNote` 情感粒度契约变更~~ → **作废**：D-1/D-2/D-3 已于 R2 **收编进本卡增量 B**（T-8~T-10），不再新开 C-017。
- 仍延后至 **C-015**：`XHSNoteCache` 真实 Redis 适配、ReMe `attraction_kb`/`AttractionCatalog` 的真实向量召回与写入（增量 B 仅就位端口与适配壳，见 D-4）。
- 增量 B 的 ReMe 召回适配壳真实化依赖 C-015；官方授权 API 真实适配在本卡内即可完成，不阻塞于 C-015。

## 任务拆解（≤1 天/项，DAG 无环）

- [x] T-1: 补 `XHSNote` 模型（common，对齐数据模型 §2.5）+ 往返/字段失败测试先行 · P0 · 依赖 C-004 · 模块 common
- [x] T-2: `xhs-note-analysis` Skill 端口（`XHSNoteSource`/`XHSNoteCache`/`AttractionCatalog`）+ 笔记→候选聚合解析（先红）· P0 · 依赖 T-1,C-005 · 模块 skills
- [x] T-3: 候选筛选（评分≥3.5）+ 情感降权/剔除 + 阈值/情感测试 · P0 · 依赖 T-2 · 模块 skills
- [x] T-4: 降级路径（检索故障/限频/空 → 缓存或默认候选 + `fallback=true`）+ 降级测试 · P0 · 依赖 T-2,C-005 · 模块 skills
- [x] T-5: `XHSAnalysisAgent` A2A 接入（继承 BaseAgent、注册 `agent://xhs-analyst`、收发 + 可观测）+ 收发契约测试 · P0 · 依赖 T-3,T-4,C-005 · 模块 xhs
- [x] T-6: 边界/回归用例固化（默认 `SensitiveContentScreen` 敏感过滤、Case-1~5 全量）+ AC-7 可观测补齐（`api.rate_limit.hit`/`memory.cache.hit` 经 `XHSAnalysisResult.Telemetry` 派发）+ 记录类覆盖补齐 · P0（阶段③）· 依赖 T-5 · 模块 xhs/skills
### 增量 A（已交付）

（T-1~T-6 见上，全部 `[x]`。）

### 增量 B（R2 新增，待审批后按 TDD 先红→实现）

- [x] T-8: `XHSNote` 情感粒度契约变更（B-3 / AC-11）— common 新增 `AttractionMention{attractionId, sentiment}`，`XHSNote` 以 `mentions: List<AttractionMention>` 取代 `mentionedAttractions + sentiment`；往返/字段失败测试先行（`AttractionMentionRoundTripTest` + 重写 `XHSNoteRoundTripTest`，含「夸 A 吐槽 B」逐提及用例）；`XHSNoteAnalyzer.tallyMentions` 改按 mention 情感聚合；既有桩/测试随迁全绿；同步 `数据模型.md` §2.5 · P0 · 依赖 T-1 · 模块 common
- [x] T-9: `XHSNoteParser` 端口 + LLM 实现（B-2 / AC-10）— 端口抽象 + 契约测试（R2 已落）；**新增 `LlmClient` 端口 + `LlmXHSNoteParser`（LLM 实体抽取 + `AttractionCatalog.findByName` 链接 + 逐提及情感 + `GovernedExternalCaller` 治理：超时/重试/降级空提及）+ `LlmXHSNoteParserTest` 8 项确定性用例**（抽取/链接失败/LLM 降级/边界） · P0 · 依赖 T-8 · 模块 skills
- [x] T-10: `XHSNoteAnalyzer` 串联链路（`Source→Parser→Analyzer`）— **新增可选 `XHSNoteParser` 构造参数**，`analyze()` 在 retrieveNotes 后逐笔记 `parser.parse()`（解析失败跳过，全部失败等同空检索 `fallback=true`），null parser 直通（向后兼容增量 A）；**`SourceParserAnalyzerChain` 5 项用例**（链路/单笔记降级/全失败降级/无 parser 兼容/夸A吐槽B） + AC-12 契约迁移回归全绿、覆盖率 ≥80% · P0 · 依赖 T-8,T-9 · 模块 skills/xhs
- [x] T-11: 合规来源真实适配（B-1 / AC-8、AC-9）— **新增 `HttpClient` 端口 + `XHSOpenApiNoteSource`（官方授权 API 适配：环境变量密钥快速失败 + HTTP 检索 + JSON 解析为原始笔记 + mentions 空由 Parser 填充，禁模拟登录/Cookie）+ `XHSOpenApiNoteSourceTest` 6 项用例**（正常/空/密钥缺失/授权失败/合规无 Cookie）；**`ReMeXHSNoteSource` 适配壳**（端口就位，真实向量依赖 C-015）· P0 · 依赖 T-9 · 模块 skills/xhs
- [ ] T-12（延后至 C-015，非增量 B Gate）: `XHSNoteCache` 真实 Redis 适配 + ReMe `attraction_kb` 真实召回/写入 · P1 · 依赖 C-015 · 模块 skills/xhs（详见 D-4）

### 增量 C（R3 新增，AgentScope 原生化 · XHS 切片）

- [x] T-13（Gate 0 spike）: 实测 `agentscope-harness` 解析 + `HarnessAgent` 在 JDK21+SB4 BOM 装配 + SB4 上下文启动 + enforcer 无 SNAPSHOT（`HarnessAgentSpikeTest` + `ServerApplicationContextTest`）· P0 · 阻塞门禁 · 模块 skills/server
- [x] T-14（C-1 工具门面）: `XHSAnalysisTools`（`@Tool analyze_xhs_notes` 包确定性 `XHSNoteAnalyzer`，不变量留 Java，`lastResult()` 捕获权威结果）+ 6 项确定性单测（AC-14）· P0 · 依赖 T-13 · 模块 skills
- [x] T-15（C-2/C-3 真实 Skill + 工厂）: `SKILL.md`（`ClasspathSkillRepository`，原生渐进式披露）+ `XHSHarnessAgentFactory`（**主 deepseek-v4-pro / 降级 qwen3-max + env key，ADR-009**）+ env-gated 集成测试 `XHSHarnessAgentFactoryIT`（AC-15）· P0 · 依赖 T-14 · 模块 skills
- [x] T-16（C-4 Agent 接缝）: `XHSAnalysisAgent` 注入 `XHSAgentRunner`，原生路 + 确定性降级兜底 + `XHSAnalysisAgentNativeTest`（3 例：成功/异常降级/null 降级）；增量 A/B 回归不回退（AC-16）· P0 · 依赖 T-14,T-15 · 模块 xhs

### 增量 D（R4 新增，AgentScope 原生 A2A 适配）

- [x] T-17（TDD-RED 测试先行）: 更新 `XHSAnalysisAgentTest`——移除废弃 import，重构 `agentUriNormalized`/`buildableAsAgentCard`，新增 `constructorRejectsNullAnalyzer`/`constructorRejectsNullMetrics`/`statusLifecycleIdleToDone`（编译失败=红灯） · P0 · 无前置依赖 · 模块 xhs/test
- [x] T-18（TDD-GREEN 重构实现）: `XHSAnalysisAgent` 移除 `extends BaseAgent`，内联 `receive()` 模板，消除 `AgentId`/`AgentReply`，新增 `AGENT_URI`/`status()`/`agentUri()`/构造器 null guard；全部测试绿灯（AC-17/AC-18）· P0 · 依赖 T-17 · 模块 xhs/main
- [x] T-19（验证收口）: `mvn clean verify` 全绿，agent-xhs 模块零废弃 import，ArchUnit/Checkstyle/PMD/JaCoCo ≥80% 全过 · P0 · 依赖 T-18 · 模块 xhs

## 流水线进度

### 增量 A — 主链路桩版本（R1，已交付）

- [x] ① 需求分析（analyzing）
- [x] ② 编码实现（coding）— 主链路 Red→Green 完成，`mvn clean verify` 全绿
- [x] ③ 单测编写（testing，覆盖率 ≥80%）— AC-1~AC-7 + 边界 + 降级 + Case-1~5 全覆盖；`mvn verify` 全绿（JaCoCo「All coverage checks have been met」）
- [x] ④ 专家评审（reviewing，0 严重问题）→ review.md（0 🔴 / 4 🟡 非阻塞，9 维度全过）
- [x] ⑤ CI 门禁（ci，全绿）— 编译/Checkstyle/PMD/ArchUnit6-6/测试50/覆盖率97.4%·92.3%/enforcer/密钥0 全绿
- [x] ⑥ 部署验证（verifying）→ verify.md（业务主链路等价真跑全过；系统级冒烟诚实裁剪下沉 C-011/C-012）
- [x] 交付（done，wiki 已同步：补 XHSNote 落地说明 + §7 映射确认）

### 增量 B — 真实来源与解析（R2，本次新增）

- [x] ① 需求分析（analyzing）— AC-8~AC-12、增量 B 边界/测试策略/任务 T-8~T-11 已落档（本次修订）
- [x] ✅ **人类审批通过**：增量 B 规格获批，进入 ② coding
- [x] ② 编码实现（coding）— T-8 情感粒度契约变更 + T-9 `LlmXHSNoteParser`（`LlmClient` 端口 + LLM 抽取/链接/治理 + 8 项用例）+ T-10 串联链路（`Source→Parser→Analyzer` + 5 项用例 + 增量 A 回归不回退）+ T-11 官方 API 适配（`XHSOpenApiNoteSource` + `ReMeXHSNoteSource` 壳 + 6 项用例）；`mvn clean verify` 全绿
- [x] ③ 单测编写（testing，覆盖率 ≥80%）— LlmXHSNoteParser LLM 限频边界（≤60/min 耗尽降级空提及）+ XHSOpenApiNoteSource GovernedExternalCaller 治理集成路径（xhs ≤10/min 耗尽走缓存）+ Case-6/7/8 端到端验证（完整链路 → 解析降级 → 情感粒度互不串扰）；增量 A 全部回归通过，JaCoCo instruction 94.5% / line 92.9%，覆盖率门禁全过
- [x] ④ 专家评审（reviewing，0 严重问题，含合规红线「无模拟登录」核查）→ review.md 增量 B 节（0 🔴 / 3 🟡 非阻塞，9 维度全过，AC-8~AC-12 全落地）
- [x] ⑤ CI 门禁（ci，全绿）— `mvn clean verify` BUILD SUCCESS，516 测试 0 失败 2 skip（env-gated IT），ArchUnit 6/6，覆盖率门禁全过（skills 94.5%/92.9%），Checkstyle 0，PMD 0 errors，Enforcer 无 SNAPSHOT，密钥扫描 0 命中，合规「无模拟登录」0 命中
- [x] ⑥ 部署验证（verifying）→ verify.md 增量 B 节（业务主链路等价真跑全过；Case-6/7/8 全覆盖；系统级活体冒烟诚实裁剪下沉 C-011/C-012；真实 HTTP/LLM 端到端 Key 待配置后手动验证）
- [x] 交付（done，同步 `数据模型.md` §2.5 情感粒度已同步于 T-8）

### 增量 C — AgentScope 原生化（XHS 切片，R3，本次新增）

- [x] ① 需求分析 + 审批（analyzing）— 核实 NIH 现状 → spike-first 计划 → 用户拍板「XHS 垂直切片先原生化」+「Agent 入口用 HarnessAgent」；决策落 ADR-008
- [x] ✅ **Gate 0 阻塞门禁通过**：`agentscope-harness` 解析 + `HarnessAgent` 在 JDK21+SB4 装配 + SB4 上下文启动 + enforcer 无 SNAPSHOT（实测排雷，项目首次真正 import `io.agentscope`）
- [x] ② 编码实现（coding）— T-13~T-16 全落：`XHSAnalysisTools`（@Tool）+ `SKILL.md` + `XHSHarnessAgentFactory` + `XHSAnalysisAgent` 原生接缝（确定性降级兜底）；`mvn verify`（skills/agent-xhs）全绿、覆盖率达标（工厂 JaCoCo 排除已登记）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）— `XHSAnalysisToolsTest`(6) + `XHSAnalysisAgentNativeTest`(3) 确定性覆盖不变量与降级；真实链路 `XHSHarnessAgentFactoryIT` env-gated（无 key 自动 skip）；增量 A/B 回归不回退
- [x] ④ 专家评审（reviewing，0 严重问题，含「密钥禁硬编码」「不变量不交 LLM」核查）— 1 🔴（`.env.example` 疑似真实密钥）当场修复，修后 0 🔴；9 维度全过；2 🟡 非阻塞（review.md 增量 C 节）
- [x] ⑤ CI 门禁（ci，全绿）— `mvn clean verify` BUILD SUCCESS，125 测试 0 失败 1 skip（env-gated IT），ArchUnit 6/6，覆盖率全过，enforcer 无 SNAPSHOT，密钥扫描 0 命中
- [x] ⑥ 部署验证（verifying）— 确定性层全通过（Gate0+不变量+降级+回归 125 测试 0 失败）；真实 LLM 端到端 `XHSHarnessAgentFactoryIT` 因环境无 `DEEPSEEK_API_KEY` 诚实 skip（verify.md 增量 C 节）
- [x] 交付（done，已同步 ADR-008/ADR-009 + review.md/verify.md 增量 C 节；ROADMAP 待同步）

### 增量 D — AgentScope 原生 A2A 适配（R4，已交付）

- [x] ① 需求分析（analyzing）— 增量 D 规格卡已完成（R4），AC-17~AC-20，人类已审批
- [x] ② 编码实现（coding）— T-17 TDD-RED（测试先行编译失败=红灯）+ T-18 TDD-GREEN（移除 `extends BaseAgent`、内联 `receive()`、消除 `AgentId`/`AgentReply`、新增 `AGENT_URI`/`status()`/null guard）；`mvn clean verify` 全绿
- [x] ③ 单测编写（testing，覆盖率 ≥80%）— AC-17~AC-20 全覆盖（`agentUriNormalized`/`buildableAsAgentCard`/`constructorRejectsNullAnalyzer`/`constructorRejectsNullMetrics`/`statusLifecycleIdleToDone`）；增量 A/B/C 全量回归不回退；agent-xhs 19 测试 0 失败，JaCoCo `All coverage checks have been met`
- [x] ④ 专家评审（reviewing，0 严重问题）→ review.md 增量 D 节（0 🔴 / 0 🟡，9 维度全过）
- [x] ⑤ CI 门禁（ci，全绿）— `mvn clean verify` BUILD SUCCESS，全模块 ArchUnit 6/6，覆盖率门禁全过，Checkstyle 0，PMD 净，Enforcer 无 SNAPSHOT，密钥扫描 0 命中，废弃依赖扫描 0 命中
- [x] ⑥ 部署验证（verifying）→ verify.md 增量 D 节（行为等价全量验证通过；零废弃 import；裁剪声明同增量 A/B/C）
- [x] 交付（done）
