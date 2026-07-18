---
change: C-006
status: pass
verifier: deploy-verify
date: 2026-06-09
---

# ✅ 部署验证报告: C-006 小红书笔记分析 Agent（主链路版本）

> 阶段 ⑥ deploy-verify · 验证日期 2026-06-09 · **流程裁剪说明见下**

## 流程裁剪声明（重要）

C-006 交付物是**库类制品**：common `XHSNote` 契约 + skills `xhs-note-analysis`（端口 + 聚合/筛选/降级/可观测）+ `XHSAnalysisAgent`（继承 C-005 `BaseAgent` 的 A2A 接入）。本卡**有完整运行时业务面，但无对外活体链路**：

- **Agent 未在 server 装配/注册**：`XHSAnalysisAgent` 的 Spring 装配、Nacos 活体注册、`/api/v1` 端点归 **C-011/C-012**；本卡仅交付 Agent 与 Skill 库（已 grep 确认 server 无 `XHSAnalysisAgent`/`NacosAgentRegistry` 构造点，与本卡非目标一致）。
- **真实来源未在生产接入**：`XHSOpenApiNoteSource` + `LlmXHSNoteParser` 在 C-006 增量 B 作为库类实现并通过 HTTP 桩测试，但**小红书开放平台实际不存在**，`PlanConfig.xhsNoteSource()` 中 OpenAPI 分支已注释（注释原文：「小红书无这些数据开放平台，所以这里通过本地景点知识库来完成」）；生产始终走 `FileBasedXHSNoteSource`（C-012-D 落地）。`XHSNoteCache` 真实 Redis 适配延后 **C-015**。
- **无可执行打包 / 无 Docker**：server pom 未配 `spring-boot-maven-plugin:repackage`（归 C-011）；本机无 Docker（同 C-003/C-005 环境阻塞）。

因此 deploy-verify 标准流程的**系统级冒烟**——5 Agent 注册 / 简单规划端到端 / A2A 往返 / HITL 触发 / 地图降级——在本卡**均不适用（N/A）**，按开发流程规范 §5「特殊场景流程裁剪」下沉至 C-011/C-012。

> 诚实裁剪，非跳过：库类卡「线上可用」的等价定义 = **业务主链路在真实治理链（C-005 `GovernedExternalCaller`/`RateLimiter`/`AgentMetrics`）下端到端真跑可验**，下面据实验证。

## 环境

- 验证方式: **JVM in-process 真跑**——`XHSAnalysisAgent.receive(Msg)` 经真实 `XHSNoteAnalyzer` + 真实 `GovernedExternalCaller`（非 mock 治理）+ 内存桩端口（来源/缓存/目录）端到端执行
- 制品: `huazai-trip-skills-0.1.0.jar` / `huazai-trip-agent-xhs-0.1.0.jar`（`mvn clean verify` BUILD SUCCESS）
- 限频/退避确定性: `InMemoryRateLimitStore`(可注入时钟) + 记录式 `Sleeper`，不真实阻塞

## 健康检查（库装配真跑证据）

| 项 | 方法 | 结果 |
|----|------|------|
| Agent 标识规范化 `agent://xhs-analyst`（AC-1） | `XHSAnalysisAgentTest.agentIdNormalized` | 🟢 PASS |
| 可注册进 `AgentRegistry` 并按标识解析（AC-1） | `registrableIntoRegistry`（`InMemoryAgentRegistry` 真注册真解析） | 🟢 PASS |
| `agent.call.latency` 指标真实上报（AC-7） | `recordsLatencyMetric`（`SimpleMeterRegistry` 实查 timer.count==1） | 🟢 PASS |

## 冒烟测试（业务主链路等价 · 真跑）

| 链路 | 验证点 | 结果 |
|------|--------|------|
| Case-1 正常分析（AC-2/AC-3） | `XHS_ANALYSIS(成都,[美食,小众])` + 桩源 3 篇 → `TASK_RESULT` 候选含评分≥3.5/情感/频次，`fallback=false`，traceId 透传 | 🟢 `XHSAnalysisAgentTest$HappyPath.returnsCandidates` PASS |
| Case-4 评分阈值（AC-4） | 评分 3.4 景点不入推荐池 | 🟢 `XHSNoteAnalyzerTest$Filtering.belowRatingThresholdExcluded` PASS |
| 情感剔除（AC-5） | 负面占多数景点剔除（即便评分达标） | 🟢 `negativeSentimentMajorityExcluded` PASS |
| Case-2 超时降级（AC-6） | 检索持续超时 → 重试耗尽（4 次尝试）→ 走缓存 → `fallback=true` | 🟢 `sourceFailureFallsBackToCache` PASS |
| 限频 ≤10/min 真实边界（AC-6/边界#2） | 真实 10/min 额度：前 10 次实时检索，第 11 次命中限频走缓存，检索源调用恒=10（不超频） | 🟢 `RateLimitBoundary.tenthSucceedsEleventhRateLimited` PASS |
| Case-3 空检索降级 | 检索为空 → 空候选 + 说明 + `fallback=true`，无裸异常 | 🟢 `Degradation.emptySearchReturnsFallbackTrue` PASS |
| 缓存命中回归 | 取自缓存的笔记仍受评分/情感不变量约束（防降级旁路规则漂移） | 🟢 `CacheFallbackRegression` PASS |
| Case-5 错误任务类型 | `taskType=ROUTE_PLANNING` → 走 BaseAgent 失败路径返回 `TASK_ERROR(INVALID_REQUEST)` | 🟢 `DegradeAndError.wrongTaskTypeReturnsError` PASS |
| 限频不退化为错误（回归） | 命中限频 → `TASK_RESULT(fallback=true)` 而非 `TASK_ERROR` | 🟢 `rateLimitedReturnsFallbackResultNotError` PASS |

> 系统级冒烟（5 Agent 注册 / 端到端规划 / A2A 往返 / HITL / 地图降级）= N/A，下沉 C-011/C-012（见裁剪声明）。

## 可观测性核验

- `api.rate_limit.hit{api=xhs}`、`memory.cache.hit{cache,result}`、`agent.call.latency{agent,taskType}` 三类指标经 `XHSAnalysisResult.Telemetry` 由 Agent 真实派发，`XHSAnalysisAgentTest$Observability` 实查 `SimpleMeterRegistry` 计数验证（命中/未命中/未咨询三态区分正确）。
- `traceId` 透传：`TASK_RESULT` 回 traceId 经 `returnsCandidates` 断言；`fallback` 标记显式不静默移除。
- 命中率（§6 告警 <80%）由 `hit/(hit+miss)` 在 Prometheus 侧聚合，活体暴露（`/actuator/prometheus`）随 C-011 装配；埋点已就位。
- 安全：无硬编码密钥（⑤ 扫描 0 命中）；`SensitiveContentScreen` 默认过滤敏感笔记；用户数据不落盘（缓存为端口，真实 Redis 延后 C-015）。

## 回滚预案

- 上一稳定版本: 本卡前一 commit（C-005 收口点之后、C-006 之前）。
- 回滚命令: `git revert <C-006 commit 范围>`（或回退上一稳定 tag）。
- 触发条件: 下游模块因 `XHSNote`(common) 契约或 `xhs-note-analysis` Skill API 不兼容导致编译/测试失败。
- 残留风险: **无**。本卡纯新增（common 契约 + skills 能力 + xhs Agent），无数据库迁移；用户数据不落盘（ADR-006），缓存为端口未接真实 Redis，回滚无数据残留。

## 给后续变更的交接

- **C-011/C-012**: 在 server 装配 `XHSAnalysisAgent`（注入真实 `XHSNoteAnalyzer` + 治理 Bean），接通 Nacos 活体注册与 `/api/v1` 端点，届时执行系统级冒烟（5 Agent 注册 / 端到端规划 / A2A 往返）并回填运行时 verify。
- **C-015**: 落地 `XHSNoteCache` 真实 Redis 适配（键 `trip:xhs:note:{noteId}` 24h TTL）+ `AttractionCatalog` 的 ReMe `attraction_kb` 召回。
- **（C-017 已取消）**: `XHSOpenApiNoteSource` + `LlmXHSNoteParser` + 情感粒度契约变更（`AttractionMention`）已在 C-006 **增量 B** 作为库类实现，C-017 不再独立。**⚠️ 生产未使用**：小红书开放平台实际不可用，`PlanConfig` 中 OpenAPI 分支已注释，生产笔记来源改由 **C-012-D** 的 `FileBasedXHSNoteSource` 承接。

## 结论（增量 A 主链路）

✅ **业务主链路等价验证全部通过**：检索→聚合→候选筛选(≥3.5/情感)→降级(超时/限频≤10min/空检索)→可观测 在真实治理链下端到端真跑可验，50 测试 0 失败。系统级活体冒烟按裁剪声明诚实下沉至 C-011/C-012，未虚假断言。C-006 可交付，状态 `verifying → done`。

---

# ✅ 部署验证报告: C-006 增量 C — AgentScope 原生化（XHS 切片）

> 阶段 ⑥ deploy-verify · 验证日期 2026-06-11

## 裁剪声明

增量 C 在增量 A 基座上新增 **AgentScope 原生 Agent 入口**（`HarnessAgent` + 真实 LLM + `@Tool` + `SKILL.md`），与增量 A 共享裁剪前提（无 server 装配/无 Nacos 活体/无 Docker）。新增裁剪项：**真实 LLM 端到端需 `DEEPSEEK_API_KEY`**——当前环境无该 key，env-gated 集成测试 `XHSHarnessAgentFactoryIT` 整类 skip（`@EnabledIfEnvironmentVariable`），诚实记录，不伪装通过。

## 环境

- 验证方式: JVM in-process 真跑 + env-gated IT（真实 LLM 链路）
- 制品: `huazai-trip-skills-0.1.0.jar` / `huazai-trip-agent-xhs-0.1.0.jar`（`mvn clean verify` BUILD SUCCESS）
- `DEEPSEEK_API_KEY`: **未设置** → `XHSHarnessAgentFactoryIT` 自动 skip
- `DASHSCOPE_API_KEY`: **未设置** → `HarnessAgentSpikeTest.harnessAgentLiveCall` 自动 skip

## 冒烟测试（增量 C 原生化验证 · 确定性层）

| 链路 | 验证点 | 结果 |
|------|--------|------|
| Gate 0 离线装配（AC-13） | `HarnessAgent` 在 JDK21+SB4 BOM+agentscope-RC1 下构造无冲突 | 🟢 `HarnessAgentSpikeTest.harnessAgentAssemblesOffline` PASS |
| 不变量留 Java（AC-14） | `@Tool` 工具 lastResult == 确定性分析器产物（评分阈值/负面剔除/降级语义不被 LLM 绕过） | 🟢 `XHSAnalysisToolsTest`（6 项全 PASS） |
| 原生路成功透传（AC-16） | runner 返回确定性结果 → Agent 透传 TASK_RESULT，fallback=false | 🟢 `XHSAnalysisAgentNativeTest.nativePathSuccess` PASS |
| 原生路异常降级（AC-16） | runner 抛 TimeoutException → 降级回确定性直路，fallback=true，不抛裸异常 | 🟢 `nativePathFailureFallsBackToDeterministic` PASS |
| 原生路 null 降级（AC-16） | runner 返回 null → 降级回确定性直路，fallback=true | 🟢 `nativePathNullFallsBack` PASS |
| SB4 上下文启动（AC-13） | Spring Boot 4.0 上下文 `TripPlanServerApplication` 正常启动 | 🟢 `ServerApplicationContextTest` PASS |
| 增量 A/B 回归不回退（AC-16） | 全部 Case-1~5 + 降级/限频/缓存语义 + 情感粒度 + 场景测试 | 🟢 全部 PASS（125 测试 0 失败） |

## 真实 LLM 端到端验证（env-gated）

| 链路 | 环境要求 | 当前结果 |
|------|----------|----------|
| `XHSHarnessAgentFactoryIT` — 真实 HarnessAgent（主 deepseek-v4-pro/降级 qwen3-max）跑通 XHS 分析，候选均 rating≥3.5（网红 3.2 被剔除） | `DEEPSEEK_API_KEY` | ⏭ **SKIP**（环境无 key，`@EnabledIfEnvironmentVariable` 守卫，Tests run: 0） |
| `HarnessAgentSpikeTest.harnessAgentLiveCall` — 真实 qwen3-max 调用 | `DASHSCOPE_API_KEY` | ⏭ **SKIP**（环境无 key，同上） |

> **诚实声明**: 当前验证环境无 LLM API 密钥，真实 deepseek-v4-pro / qwen3-max 端到端**未跑通**。确定性层（不变量留 Java、降级兜底、回归不回退）已全部验证通过。真实 LLM 链路待首次配置 `DEEPSEEK_API_KEY` 后手动触发 `mvn test -pl huazai-trip-skills -Dtest="XHSHarnessAgentFactoryIT"` 验证。

## 密钥安全核验

| 检查项 | 结果 |
|--------|------|
| 代码中密钥来源 | ✅ 全部 `System.getenv()` — `XHSHarnessAgentFactory.fromEnvironment()` |
| `.env.example` 占位符 | ✅ 修后 `your-deepseek-api-key`（原 `sk-26b97e5ca9dc4332801c0f5a08f545e7` 已修复） |
| 仓库密钥扫描 `grep -rP 'sk-[a-f0-9]{32}'` | ✅ 0 命中 |
| `EnvironmentValidator.REQUIRED_KEYS` 含 `DEEPSEEK_API_KEY` | ✅ 启动期 fail-fast |
| enforcer 无 SNAPSHOT | ✅ `RequireReleaseDeps passed`（全模块） |

## CI 门禁汇总

| 门禁项 | 结果 |
|--------|------|
| 编译 | ✅ 全 10 模块 BUILD SUCCESS |
| 测试 | ✅ 125 测试 0 失败 1 skip（env-gated IT） |
| 覆盖率 | ✅ `All coverage checks have been met`（skills/agent-xhs/server 均 ≥80%） |
| ArchUnit | ✅ 6/6（R1~R6 全绿，含 R2 skills 不依赖 agents 验证） |
| Enforcer 无 SNAPSHOT | ✅ 全模块 `RequireReleaseDeps passed` |
| 密钥扫描 | ✅ 0 命中 |

## 回滚预案

- 同增量 A 声明：`git revert` C-006 增量 C commit 范围。
- 增量 C 为纯新增（`XHSAnalysisTools`/`XHSAgentRunner`/`XHSHarnessAgentFactory`/`SKILL.md` + 测试 + `XHSAnalysisAgent` 接缝），回滚后退化为纯确定性直路（增量 A/B 行为），无数据残留。

## 结论（增量 C）

✅ **增量 C 确定性层验证全部通过**：Gate 0 装配（AC-13）+ 不变量留 Java（AC-14，6 项单测）+ 原生路成功/异常降级/null 降级（AC-16，3 项单测）+ 增量 A/B 回归不回退（125 测试 0 失败）+ 密钥禁硬编码（扫描 0 命中）。

⏭ **真实 LLM 端到端**: 当前环境无 `DEEPSEEK_API_KEY`/`DASHSCOPE_API_KEY`，`XHSHarnessAgentFactoryIT` 诚实 skip。**待用户配置密钥后手动验证一次真实 deepseek-v4-pro（+ qwen3-max 降级）端到端**。

增量 C 确定性面可交付；真实 LLM 链路 skip 已如实记录。

---

# ✅ 部署验证报告: C-006 增量 B — 真实来源与解析

> 阶段 ⑥ deploy-verify · 验证日期 2026-06-11

## 裁剪声明

增量 B 在增量 A 基座上新增 **真实来源适配**（`XHSOpenApiNoteSource` + `ReMeXHSNoteSource`）+ **LLM 解析**（`LlmXHSNoteParser`）+ **情感粒度契约变更**（`AttractionMention`），与增量 A/C 共享裁剪前提（无 server 装配/无 Nacos 活体/无 Docker）。增量 B 的核心交付均为**库类制品 + 确定性单元测试覆盖**，业务主链路在真实治理链（C-005 `GovernedExternalCaller`/`RateLimiter`）下端到端 JVM in-process 真跑可验。

**诚实裁剪**：`XHSOpenApiNoteSource` 的真实 HTTP 调用需有效小红书官方 API Key（当前环境未配置），故官方 API 链路以 HTTP 桩验证治理行为；`ReMeXHSNoteSource` 的真实向量召回依赖 C-015，以适配壳交付。系统级活体冒烟同增量 A/C 下沉 C-011/C-012。

## 环境

- 验证方式: JVM in-process 真跑 — `XHSNoteAnalyzer.analyze()` 经真实 `GovernedExternalCaller` + `RateLimiter`（非 mock 治理）+ 内存桩端口（来源/缓存/目录/LLM/HTTP）端到端执行
- 制品: `huazai-trip-skills-0.1.0.jar` / `huazai-trip-agent-xhs-0.1.0.jar`（`mvn clean verify` BUILD SUCCESS）
- 限频/退避确定性: `InMemoryRateLimitStore`(可注入时钟) + 记录式 `Sleeper`，不真实阻塞

## 冒烟测试（增量 B · 业务主链路等价 · 真跑）

| 链路 | 验证点 | 结果 |
|------|--------|------|
| **Case-6 完整链路**（AC-8/AC-10） | `XHSOpenApiNoteSource → LlmXHSNoteParser → Analyzer → 候选产出`：官方 API 返回 2 篇原始笔记（mentions 空）→ Parser 抽取逐提及情感（宽窄巷子 POSITIVE + 锦里 NEGATIVE）→ Analyzer 聚合筛选（A-001 入选、A-002 负面 100% 剔除）→ `fallback=false` | 🟢 `case6FullChainOfficialApiToCandidates` PASS |
| **Case-6 密钥缺失**（AC-8） | `XHSOpenApiNoteSource` 构造时密钥 null → `IllegalArgumentException` 快速失败 | 🟢 `case6MissingApiKeyFailsFast` PASS |
| **Case-7 LLM 限频全部降级**（AC-10） | LlmXHSNoteParser × LLM 限频 ≤2/min 预先耗尽 → 全部 2 篇笔记 `parse()` 抛 `RateLimitedException` → Analyzer 跳过全部 → 空候选 + `fallback=true`，无裸异常 | 🟢 `case7LlmRateLimitExhaustedAllNotesSkipped` PASS |
| **Case-7 部分 LLM 限频**（AC-10） | LLM 限频 ≤1/min：第 1 篇正常解析、第 2 篇限频跳过 → 仅 N-OK 产出候选 | 🟢 `case7PartialLlmRateLimit` PASS |
| **Case-8 情感粒度互不串扰**（AC-11） | 3 篇笔记「夸 A 吐槽 B」→ A（宽窄巷子）2 次正面 / 0 次负面入选（mentionCount=2, negativeRatio=0.0）；B（锦里）0 次正面 / 2 次负面全负面剔除。**逐提及情感互不串扰** | 🟢 `case8PerMentionSentimentIndependence` PASS |
| **LLM 限频边界**（AC-10 边界） | `LlmXHSNoteParser` × LLM ≤60/min：额度内正常解析、耗尽抛 `RateLimitedException`（由 Analyzer 降级）、耗尽后原文标识可读 | 🟢 `LlmXHSNoteParserTest$LlmRateLimitBoundary`（3 项）PASS |
| **xhs ≤10/min 治理集成**（AC-8 边界） | `XHSOpenApiNoteSource` 经 `GovernedExternalCaller(API_XHS)`：前 10 次实时检索成功、第 11 次不超频（HTTP 调用 = 10）、授权失败重试耗尽降级、瞬时超时恢复退避 [1s,2s]、限频耗尽走缓存降级 | 🟢 `XHSOpenApiNoteSourceTest$GovernedIntegration`（5 项）PASS |
| **官方 API 正常/空/密钥/授权失败**（AC-8） | 正常 JSON 解析为原始笔记（mentions 空）、空数组 → 空列表、密钥 null/blank 快速失败、HTTP 401/403 抛 IOException 驱动降级 | 🟢 `XHSOpenApiNoteSourceTest`（NormalSearch 2 + ApiKeyAndAuth 3）PASS |
| **合规红线无模拟登录**（AC-8 红线） | HTTP 请求不含 Cookie/登录态头 | 🟢 `Compliance.noCookieOrLoginHeaders` PASS |
| **解析链路**（AC-10/AC-12） | Parser 正常抽取（逐提及情感）+ 链接失败丢弃 + LLM 超时/非法 JSON 降级空提及 + null parser 直通兼容 + Parser 单笔记失败跳过 + Parser 全部失败等同空检索 | 🟢 `LlmXHSNoteParserTest`（11 项）+ `SourceParserAnalyzerChain`（10 项）PASS |
| **增量 A/C 回归不回退**（AC-12/AC-16） | Case-1~5 + 降级/限频/缓存语义 + `XHSAnalysisAgentTest` + `XHSAnalysisAgentNativeTest` + `XHSAnalysisYunnanScenarioTest` + 其余全模块 | 🟢 全部 PASS（516 测试 0 失败 2 skip） |

## 可观测性核验

- 增量 B 不新增指标名（LLM 解析调用的 `agent.call.latency`/重试/降级沿用 ACL-7 既有口径）。既有的 `api.rate_limit.hit{api=xhs}`、`memory.cache.hit{cache,result}`、`agent.call.latency{agent,taskType}` 指标派发通路不变，经 `XHSAnalysisAgentTest$Observability` 实测验证（`SimpleMeterRegistry` 实查计数）。
- `XHSNoteAnalyzer` 的 `CacheProbe` 三态区分（HIT/MISS/NOT_CONSULTED）在增量 B 串联链路中保持正确。

## 合规红线核查

| 检查项 | 结果 |
|--------|------|
| 模拟登录/登录态代码 | ✅ **0 命中** — `XHSOpenApiNoteSource` 仅 URL 拼接 + HTTP GET，无 Cookie/Session；`ReMeXHSNoteSource` 纯壳无 HTTP；`Compliance` 测试验证请求不含 `cookie` |
| 密钥硬编码 | ✅ **0 命中** — `XHSOpenApiNoteSource` key 从构造参数传入（上层从环境变量读取）；CI 扫描 `grep -rP 'sk-[a-zA-Z0-9]{20,}'` 0 命中 |
| 官方授权 API 合规 | ✅ 仅走 `open.xiaohongshu.com/api/v1` 官方接口，无规避手段 |
| 不变量不交 LLM | ✅ 评分≥3.5/负面剔除/降级 fallback 由确定性 `XHSNoteAnalyzer` 强制；LLM 仅做实体抽取（`LlmClient` 端口隔离），不参与评分裁决 |

## CI 门禁汇总

| 门禁项 | 结果 |
|--------|------|
| 编译 | ✅ 全 10 模块 BUILD SUCCESS |
| 测试 | ✅ **516 测试 0 失败 2 skip**（env-gated IT ×2） |
| 覆盖率 | ✅ `All coverage checks have been met`（skills instruction 94.5% / line 92.9%） |
| ArchUnit | ✅ 6/6（R1~R6 全绿，含 R2 skills 不依赖 agents 验证） |
| Checkstyle | ✅ 0 violations（全模块） |
| PMD | ✅ 净（0 errors） |
| Enforcer 无 SNAPSHOT | ✅ 全模块 `RequireReleaseDeps passed` |
| 密钥扫描 | ✅ 0 命中 |
| 合规红线「无模拟登录」 | ✅ 0 命中 |

## 回滚预案

- 同增量 A 声明：`git revert` C-006 增量 B commit 范围。
- 增量 B 为纯新增（`XHSNoteParser`/`LlmXHSNoteParser`/`HttpClient`/`LlmClient`/`XHSOpenApiNoteSource`/`ReMeXHSNoteSource` + 测试）+ `XHSNote`/`AttractionMention`（common 契约改造，同增量 A 基座）。回滚后退化为增量 A 行为（端口桩 + 整篇情感模型），无数据残留。`AttractionMention` 为 record 无数据库映射，回滚无数据迁移。

## 结论（增量 B）

✅ **增量 B 业务主链路等价验证全部通过**：`Query → Source(取原文) → Parser(结构化) → Analyzer(聚合/筛选)` 端到端在真实治理链（`GovernedExternalCaller` + `RateLimiter`）下 JVM in-process 真跑可验，Case-6/7/8 全覆盖，合规红线「无模拟登录」核查通过，密钥禁硬编码扫描 0 命中，增量 A/C 全部回归通过（516 测试 0 失败）。

C-006 增量 B 确定性面可交付。系统级活体冒烟 + 真实 HTTP/LLM 端到端按裁剪声明诚实下沉（官方 API Key 待配置后手动验证），未虚假断言。

> **⚠️ 生产状态更新（C-012-D 落地后）**：小红书开放平台实际不存在，`PlanConfig.xhsNoteSource()` 中 `XHSOpenApiNoteSource` 分支已全部注释（注释原文：「小红书无这些数据开放平台，所以这里通过本地景点知识库来完成」）。增量 B 的 `XHSOpenApiNoteSource` 实现作为预备代码保留在代码库，当前不在执行路径上；生产笔记来源由 `FileBasedXHSNoteSource` + `knowledge-base.json` 承接。

---

# ✅ 部署验证报告: C-006 增量 D — AgentScope 原生 A2A 适配

> 阶段 ⑥ deploy-verify · 验证日期 2026-06-13

## 裁剪声明

增量 D 为**纯重构**：`XHSAnalysisAgent` 脱离废弃 `BaseAgent` 基类，内联 `receive(Msg)` 模板方法，消除 `AgentId`/`AgentReply` 依赖。无新功能、无新端口、无契约变更、无外部依赖变更。与增量 A/B/C 共享裁剪前提（无 server 装配/无 Nacos 活体/无 Docker）。

> 重构卡「线上可用」的等价定义 = **行为等价 + 全量回归通过 + 零废弃依赖**，下面据实验证。

## 环境

- 验证方式: JVM in-process 真跑 — `XHSAnalysisAgent.receive(Msg)` 经真实 `XHSNoteAnalyzer` + 真实 `GovernedExternalCaller`（非 mock 治理）+ 内存桩端口端到端执行
- 制品: `huazai-trip-agent-xhs-0.1.0.jar`（`mvn clean verify` BUILD SUCCESS）

## 冒烟测试（增量 D · 行为等价验证 · 真跑）

| 链路 | 验证点 | 结果 |
|------|--------|------|
| AC-17 零废弃 import | `grep BaseAgent/AgentId/AgentReply/InMemoryAgentRegistry/AgentRegistration` → 0 代码依赖（仅 Javadoc `{@code}` 迁移说明） | 🟢 PASS |
| AC-19 A2A 服务发现 | `TripAgentCardFactory.buildCard(AGENT_ID, ..., List.of("xhs_note_analysis"))` → `ConfigurableAgentCard`(name=xhs-analyst, skills=[xhs_note_analysis]) | 🟢 `buildableAsAgentCard` PASS |
| AC-20 null guard | `new XHSAnalysisAgent(null, metrics)` → `InvalidRequestException`；`new XHSAnalysisAgent(analyzer, null)` → `InvalidRequestException` | 🟢 `constructorRejectsNullAnalyzer` + `constructorRejectsNullMetrics` PASS |
| AC-20 lifecycle | receive 前 `status()=IDLE`，receive 后 `status()=DONE` | 🟢 `statusLifecycleIdleToDone` PASS |
| AC-17 URI 规范化 | `agentUri()` == `"agent://xhs-analyst"` | 🟢 `agentUriNormalized` PASS |
| Case-1 正常分析 | `XHS_ANALYSIS(成都,[美食,小众])` → `TASK_RESULT` 候选含评分≥3.5/情感/频次，`fallback=false` | 🟢 `returnsCandidates` PASS |
| Case-2 超时降级 | 检索持续超时 → 走缓存 → `fallback=true` | 🟢 `sourceFailureFallsBackToCache` PASS |
| Case-3 空检索降级 | 空候选 + `fallback=true`，无裸异常 | 🟢 `fallbackPropagated` PASS |
| Case-5 错误任务类型 | `ROUTE_PLANNING` → `TASK_ERROR(INVALID_REQUEST)` | 🟢 `wrongTaskTypeReturnsError` PASS |
| 原生路成功/降级/null | runner 成功透传 / 异常降级 / null 降级 | 🟢 `XHSAnalysisAgentNativeTest` 3 项 PASS |
| 云南场景端到端 | 频次排序 + 评分≥3.5 + 负面剔除 | 🟢 `yunnanCoupleScenario` PASS |
| 限频不退化为错误 | 限频 → `TASK_RESULT(fallback=true)` 非 `TASK_ERROR` | 🟢 `rateLimitedReturnsFallbackResultNotError` PASS |
| 可观测指标 | `agent.call.latency`/`api.rate_limit.hit`/`memory.cache.hit` 三类指标派发正确 | 🟢 `Observability` 3 项 PASS |

## CI 门禁汇总

| 门禁项 | 结果 |
|--------|------|
| 编译 | ✅ 全 10 模块 BUILD SUCCESS |
| 测试 | ✅ agent-xhs 19 测试 0 失败 0 skip；全模块 BUILD SUCCESS |
| 覆盖率 | ✅ `All coverage checks have been met`（agent-xhs ≥80%） |
| ArchUnit | ✅ 6/6（R1~R6 全绿） |
| Checkstyle | ✅ 0 violations |
| PMD | ✅ 净 |
| Enforcer 无 SNAPSHOT | ✅ 全模块通过 |
| 密钥扫描 | ✅ 0 命中 |
| 废弃依赖扫描 | ✅ agent-xhs 模块零 `import` BaseAgent/AgentId/AgentReply/InMemoryAgentRegistry/AgentRegistration |

## 回滚预案

- 回滚命令: `git revert` C-006 增量 D commit 范围。
- 增量 D 为纯重构（内联基类方法），回滚后 `XHSAnalysisAgent` 恢复 `extends BaseAgent`，行为不变。无数据残留、无契约变更、无新外部依赖。
- 触发条件: 其余 Agent 若在 BaseAgent 废弃窗口期仍依赖 `BaseAgent.receive()` 的多态分发，回滚不影响（XHS 自行内联，不改 BaseAgent 本身）。

## 结论（增量 D）

✅ **增量 D 行为等价验证全部通过**：`XHSAnalysisAgent` 脱离废弃 `BaseAgent`，内联 `receive(Msg)` 模板，消除 `AgentId`/`AgentReply`，构造器 null guard + lifecycle 内联，`TripAgentCardFactory` A2A 服务发现验证通过。agent-xhs 模块零废弃 import。增量 A/B/C 全量回归不回退（19 agent-xhs 测试 + 全模块 ArchUnit 6/6 全绿）。

C-006 增量 D 可交付，状态 `verifying → done`。
