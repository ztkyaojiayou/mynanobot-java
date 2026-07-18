---
change: C-006
status: pass
reviewer: expert-reviewer
scope: 整卡 / 小红书笔记分析 Agent 主链路（T-1..T-6 / AC-1..AC-7）
---

# 📋 评审报告: C-006 小红书笔记分析 Agent（主链路版本）

> **评审范围声明**：对 C-006 **主链路整卡**复审——`XHSNote`（common）+ `xhs-note-analysis` Skill（端口 + 聚合/筛选/降级/可观测）+ `XHSAnalysisAgent`（A2A 接入）。真实来源/解析/存储按 change.md「后续来源与解析方向」D-1~D-4 顺延 C-015/C-017，**不在本卡范围**，故不作为本卡放行阻塞项。

## 总览

- 审查范围: common `model/XHSNote` · skills `xhs`(`XHSNoteSource`/`XHSNoteCache`/`AttractionCatalog`/`XHSNoteAnalyzer`/`AttractionCandidate`/`XHSAnalysisQuery`/`XHSAnalysisResult`/`CacheOutcome`/`SensitiveContentScreen`) + `observability/AgentMetrics`(扩展) · xhs `XHSAnalysisAgent`
- 测试规模: XHSNoteAnalyzerTest **23** + XHSAnalysisAgentTest **12** + SensitiveContentScreenTest **7** + AgentMetricsTest **4** + XHSNoteRoundTripTest **4** = **50 测试 0 失败**；全模块 `mvn clean verify` BUILD SUCCESS，ArchUnit 6/6
- 覆盖率: `skills.xhs` 行覆盖 **97.4%**、`agent-xhs` 行覆盖 **92.3%**（均 ≥80% 门禁；JaCoCo「All coverage checks have been met」）
- 🔴 严重问题: **0**
- 🟡 建议改进: 4（均非阻塞）
- 🟢 通过项: 9 维度全过

---

## 🔴 严重问题

**无。** 已实现代码无功能异常、无安全漏洞、无架构腐化、无 SDD/TDD 形式化迹象。

---

## 🟡 建议改进

### 1. 「提取/情感分析」目前为输入假设，非本卡产出（已登记 D-2/D-3，顺延 C-017）
- **现状**: `XHSNote.mentionedAttractions` 已是景点 ID、`sentiment` 为整篇单值；`XHSNoteAnalyzer` 实际只做按 ID 聚合 + 评分/情感筛选 + 频次排序。卡名的「提取」「情感分析」由上游假设完成，无代码产出实体抽取/链接与情感判定。
- **判定**: 此为评审期已识别并经人类确认的演进方向（change.md D-2「`XHSNoteParser` 独立端口」、D-3「情感粒度下沉 `AttractionMention`」），**明确拆 C-017、不在本卡 scope**。本卡以端口 + 桩契约交付主链路，AC-3 的可验证面（检索→聚合→候选）完整。**非 🔴，不阻塞。**
- **前瞻提醒**: D-3 属 `XHSNote`(common) 字段级契约变更，越晚改成本越高，建议 C-017 评审优先决议。

### 2. AC-5 情感聚合在整篇单值模型下语义偏弱
- **文件**: `XHSNoteAnalyzer.java:147-161`（`tallyMentions`）
- **现状**: 「一篇笔记夸 A 吐槽 B」时，整篇 `sentiment` 被同等记到该篇**每个**提及景点上，负面占比判定因此不精确。
- **理由**: 受限于 D-3 未落地的数据契约，当前实现已是该契约下的正确做法（负面占比 > 0.5 剔除、候选携带情感聚合），**逻辑无误**，仅模型表达力不足。随 D-3 落地自然消解。非阻塞。

### 3. `SensitiveContentScreen` 默认黑名单为最小内置集
- **文件**: `SensitiveContentScreen.java:21-22`（`DEFAULT_BLOCKLIST`）
- **现状**: 9 词最小集，子串匹配。满足边界 #4「敏感/不安全笔记不污染候选」的契约面，但非生产级词库。
- **建议**: 真实词库/合规策略经构造注入或随 C-015 外部词表接入（类已留 `SensitiveContentScreen(List<String>)` 注入缝）。本卡作为缺省防线已达标，非阻塞。

### 4. `memory.cache.hit.rate` 为派生指标，依赖下游 PromQL 聚合
- **文件**: `AgentMetrics.java`（`recordCacheAccess` → `memory.cache.hit{cache,result}` 计数）
- **现状**: 本卡发射 hit/miss 计数，命中率（§6 告警 <80%）由 `hit/(hit+miss)` 在 Prometheus 侧聚合，Agent 内不预聚合。
- **理由**: 与 §6「Micrometer→Prometheus」一致，计数器是正确的指标原语；命中率告警规则归 C-016 可观测加固。设计合理，仅作记录。

---

## 🟢 审查通过项

- [x] **维度1 功能完整性**: AC-1~AC-7 全覆盖。AC-1 `XHSAnalysisAgent extends BaseAgent`、`agent://xhs-analyst`、仅受理 `TASK_ASSIGN(taskType=XHS_ANALYSIS)`、非法类型走 BaseAgent→TASK_ERROR；AC-2 `TASK_RESULT` 候选列表 + traceId 透传 + fallback=false；AC-3 检索→聚合→候选链经 `XHSNoteAnalyzer`；AC-4 `rating≥3.5` 不变量（`MIN_RECOMMEND_RATING`）；AC-5 负面占比 >0.5 剔除 + 候选携带情感聚合；AC-6 经 `GovernedExternalCaller` 限频/超时/重试/降级 `fallback=true` 不裸抛；AC-7 `agent.call.latency`/`api.rate_limit.hit`/`memory.cache.hit` 经 `XHSAnalysisResult.Telemetry` 派发。边界 #1~#5 与 Case-1~5 均有对应测试。
- [x] **维度2 架构合规**: ArchUnit `ArchitectureConstraints` 6/6 绿。Skill 落 `huazai-trip-skills` 零 Agent 依赖（R2）；`XHSAnalysisAgent` 仅经 `Msg`/`BaseAgent` 不直依其它 Agent（R1）；`XHSNote` 入 common 仅依基础库（R4）；端口-适配器为真实来源/缓存/目录预留干净缝（`XHSNoteSource`/`XHSNoteCache`/`AttractionCatalog`）。复用 C-005 治理/可观测，未复制治理逻辑。
- [x] **维度3 编码规范**: 全部 public API 含 Javadoc（`@param/@throws`）；阈值/键/指标名抽常量（`MIN_RECOMMEND_RATING`/`MAX_NEGATIVE_RATIO`/`METRIC_*`/`API_XHS`）无魔法值；`XHSAnalysisQuery`/Agent `parseQuery` 防御式 fail-fast 抛 `InvalidRequestException`；降级路径不吞异常（`GovernedExternalCaller` fallback 收根因）；无硬编码密钥。Checkstyle 全模块 0 违规。
- [x] **维度4 代码质量**: 方法 ≤50 行、圈复杂度低；候选聚合/筛选/排序职责清晰拆分（`tallyMentions`/`aggregate`/`byFrequencyThenQuality`）；null 安全集中 `CollectionUtils.safeCopy`；`CacheProbe` 精确区分 HIT/MISS/NOT_CONSULTED 避免命中率分母污染；无重复块、无未用 import（PMD 净）。
- [x] **维度5 安全**: `context` 未知字段忽略不失败（向后兼容）；目的地空白 fail-fast；`SensitiveContentScreen` 默认过滤敏感笔记；用户数据不落盘（缓存为端口，真实 Redis 延后）；非目标明确排除模拟登录/登录态抓取（合规红线）。
- [x] **维度6 测试质量**: happy（Case-1 频次排序）+ 边界（空检索、null 返回、全部<3.5、未知 ID、空提及、敏感过滤、未知 context 字段）+ 降级（持续超时→缓存、瞬时超时恢复退避 [1s,2s]、限频≤10/min 真实边界 10 过 11 限、空缓存列表判 MISS）+ 回归（缓存命中仍受评分/情感不变量约束、限频→TASK_RESULT 不退化 TASK_ERROR）。桩源/Sleeper 注入避免真实阻塞；断言具体非走形式；不 Mock 自写业务类（用内存桩端口）。
- [x] **维度7 流程合规**: 工作树仅含 XHS 相关新包 + 对应测试 + change.md/review.md，无夹带无关变更。修正了 change.md 早前「拟新立 C-016」与既有 C-016（可观测/可靠性）的撞号 → 顺延 C-017。
- [x] **维度8 SDD 合规**: change.md 含完整 AC/边界/非功能/设计约束/契约影响/设计偏差登记（`AttractionCatalog` 新增、Telemetry、SensitiveContentScreen）+ D-1~D-4 演进方向；实现严格对齐且未越界（真实 HTTP/Redis/RAG/解析确未碰，与非目标一致）。
- [x] **维度9 TDD 合规**: change.md 流水线记录 ② Red→Green；③ 补齐边界/降级/回归共 50 测试全绿；本轮强化的限频边界/超时恢复/缓存命中回归均先写失败测试再实现，分层清晰，无「代码先行测试补形式」迹象。

---

## (B) 整卡完整性判定

| AC | 状态 | 归属 task |
|----|------|-----------|
| AC-1 Agent 标识 + 仅受理 XHS_ANALYSIS + 非法走 TASK_ERROR | ✅ 完成 | T-5 |
| AC-2 TASK_RESULT 候选列表 + traceId 透传 + fallback=false | ✅ 完成 | T-5 |
| AC-3 检索→解析→候选聚合（频次驱动排序） | ✅ 完成（解析为输入假设，D-2 顺延 C-017） | T-2 |
| AC-4 候选筛选 rating≥3.5 不变量 | ✅ 完成 | T-3 |
| AC-5 负面情感降权/剔除 + 候选携带情感聚合 | ✅ 完成（粒度受 D-3 约束，🟡-2） | T-3 |
| AC-6 统一治理限频/超时/重试/降级 fallback=true | ✅ 完成 | T-4 |
| AC-7 可观测：latency/rate_limit.hit/cache.hit + Telemetry | ✅ 完成 | T-6 |

**判定**: 7 条 AC 全部落地（AC-3 解析步、AC-5 情感粒度按已登记 D-2/D-3 口径，顺延 C-017）。整卡具备进入 ⑤ CI 门禁与 ⑥ 部署验证的条件。

---

## 结论

- **C-006 整卡**: **放行** ✅（`pass`）。0 个 🔴，9 维度全过，4 条 🟡 均为非阻塞建议或已登记的范围顺延（D-2/D-3/D-4 → C-015/C-017）。
- **后续门禁**: ⑤ CI 已绿（`mvn clean verify` BUILD SUCCESS，50 测试 0 失败，覆盖率 skills.xhs 97.4%/agent-xhs 92.3%，Checkstyle 0，ArchUnit 6/6）。可进入 ⑥ 部署验证。

---

# 📋 评审报告: C-006 增量 C — AgentScope 原生化（XHS 切片）

> **评审范围**：增量 C 全量——`XHSAnalysisTools`（@Tool 门面）+ `SKILL.md`（原生渐进式披露）+ `XHSHarnessAgentFactory`（双模型工厂 + env key）+ `XHSAgentRunner`（接缝接口）+ `XHSAnalysisAgent` 原生路接缝 + `HarnessAgentSpikeTest`（Gate 0 spike）+ env-gated `XHSHarnessAgentFactoryIT`。
> **重点核查**：① 密钥禁硬编码（编码规范 §7 / AC-15）；② 不变量不交 LLM（AC-14）。

## 总览

- 审查文件: 8 个（含 3 个新增 + 5 个修改）
- 测试规模: `XHSAnalysisToolsTest` **6** + `XHSAnalysisAgentNativeTest` **3** + `HarnessAgentSpikeTest` **2** + `XHSHarnessAgentFactoryIT` **1**（env-gated，无 key skip）= **12 新增测试**；全模块 `mvn clean verify` BUILD SUCCESS（97+16+12=125 测试 0 失败 1 skip），ArchUnit 6/6
- 覆盖率: skills `All coverage checks have been met`（`XHSHarnessAgentFactory` JaCoCo 排除已登记）；agent-xhs `All coverage checks have been met`
- 🔴 严重问题: **1（已当场修复 → 修后 0 🔴）**
- 🟡 建议改进: 2（均非阻塞）
- 🟢 通过项: 9 维度全过

---

## 🔴 严重问题

### 1. `.env.example` 包含疑似真实 DeepSeek API Key（**已修复**）
- **文件**: `.env.example:7`
- **问题**: `DEEPSEEK_API_KEY=sk-26b97e5ca9dc4332801c0f5a08f545e7` — `sk-` 前缀 + 32 位十六进制，高度疑似真实密钥泄入版本控制。即便 `.env.example` 是样例文件，公开仓库/协作者可见。
- **违反**: 编码规范 §7 安全红线「API Key/Token → 环境变量，禁止硬编码」；AC-15「CI 密钥扫描 0 命中」。
- **修复**: 评审当场替换为 `your-deepseek-api-key`（与 `DASHSCOPE_API_KEY`/`BAIDU_MAP_AK` 占位风格统一）。修后密钥扫描 `grep -rP 'sk-[a-f0-9]{32}'` → 0 命中。
- **建议**: 若该 key 曾为真实密钥，请在 DeepSeek 控制台 **立即轮换**（revoke + 重新生成）。

> **修后判定**: 0 🔴 遗留。

---

## 🟡 建议改进

### 1. `HarnessAgentSpikeTest` 离线装配用 `"offline-assembly-placeholder-not-a-real-key"` 字面量
- **文件**: `HarnessAgentSpikeTest.java:46`
- **现状**: RC1 的 `DashScopeChatModel.builder().build()` 强校验 apiKey 非空，故离线装配测试用明显假占位绕过。字面量内容明确标注 `not-a-real-key`，不会被密钥扫描误报。
- **理由**: 这是对框架约束的合理 workaround（离线测试不触网），且字面量 ≠ 真实密钥。保留合理，仅建议加行内注释说明框架约束原因（当前已有 `// 构造不触网...` 注释，已足够）。**非阻塞。**

### 2. `XHSHarnessAgentFactory` JaCoCo 排除需持续跟踪
- **文件**: `huazai-trip-skills/pom.xml:76`（`<exclude>**/XHSHarnessAgentFactory.class</exclude>`）
- **现状**: 工厂涉及真实 LLM/网络不可离线覆盖，排除有据可查。确定性不变量由 `XHSAnalysisToolsTest`(6) 覆盖，真实链路由 env-gated IT 验证。
- **建议**: 随后续 Agent 原生化，若排除类增多应集中管理排除策略，防止排除清单膨胀侵蚀覆盖率门禁。已登记于 change.md，本卡不阻塞。

---

## 🟢 审查通过项

### 维度 1: 功能完整性（AC-13~AC-16）
- [x] **AC-13（Gate 0）**: `HarnessAgentSpikeTest` 二级断言——①离线装配（JDK21+SB4 BOM+agentscope-RC1 无冲突）；②真实 qwen3-max 调用（`@EnabledIfEnvironmentVariable`，无 key skip 不伪装）。`agentscope-harness` 经 BOM 解析无 SNAPSHOT。`ServerApplicationContextTest` SB4 上下文启动通过。
- [x] **AC-14（不变量留 Java）**: `XHSAnalysisTools` 把确定性 `XHSNoteAnalyzer` 暴露为 `@Tool analyze_xhs_notes`；`lastResult()` 用 `AtomicReference` 捕获权威结果；`@Tool` description 明确写 "禁止在工具外伪造、放宽或新增候选"。`XHSAnalysisToolsTest` 6 项确定性单测：null 拒绝、happy path（lastResult == 分析器产物）、评分 <3.5 排除、负面多数剔除、空检索 fallback=true、preferences 解析健壮。**LLM 无法绕过工具强制的不变量。**
- [x] **AC-15（真实原生链路 + Skill）**: `XHSHarnessAgentFactory` 双模型（`OpenAIChatModel` deepseek-v4-pro + `DashScopeChatModel` qwen3-max）；`fromEnvironment()` 从 `System.getenv()` 读取密钥（禁硬编码）；降级策略：有 deepseek → 主+降级，无 deepseek 有 dashscope → 单模型，两者皆缺 → 抛异常。`ClasspathSkillRepository("skills")` 加载 `resources/skills/xhs-note-analysis/SKILL.md`（原生渐进式披露，非废弃 `SkillBox`）。`SKILL.md` frontmatter 正确（`name: xhs_note_analysis`），硬约束明确"禁止在工具之外伪造景点"。env-gated `XHSHarnessAgentFactoryIT` 验证真实 HarnessAgent 跑通且候选均 rating≥3.5（网红 3.2 被剔除）。
- [x] **AC-16（降级与回归不回退）**: `XHSAnalysisAgent` 新增 `(XHSAgentRunner, XHSNoteAnalyzer, AgentMetrics)` 三参构造器；`resolve()` 先走 runner→成功透传、异常/null→降级回确定性直路（`fallback=true`），catch 中 `incrementCallError` 不吞不裸抛。旧双参构造器保留（runner=null 退化为纯确定性直路）。`XHSAnalysisAgentNativeTest` 3 例覆盖成功/异常降级/null 降级。增量 A/B 全部回归通过（`XHSAnalysisAgentTest` 12 + `XHSAnalysisYunnanScenarioTest` 1 + `XHSNoteAnalyzerTest` 23 + 其余全绿）。

### 维度 2: 架构合规
- [x] `XHSAnalysisTools`/`XHSHarnessAgentFactory`/`XHSAgentRunner` 落 `huazai-trip-skills`（R2 无 Agent 依赖）；`XHSAnalysisAgent`（agent-xhs）依赖 skills 接口（正向：agents→skills→common）。ArchUnit 6/6 全绿。R2 注释已澄清 `io.agentscope.harness.agent` 非本项目 Agent（框架包名碰巧含 `agent`）。`agentscope-harness` 在 `huazai-trip-skills/pom.xml` 声明由 BOM 管理无版本号。ADR-008/ADR-009 已落。

### 维度 3: 编码规范
- [x] 全部 public 类/方法含 Javadoc。`XHSAnalysisTools` Javadoc 明确"不变量留在 Java"设计意图。常量化：`DEFAULT_PRIMARY_MODEL`/`DEFAULT_FALLBACK_MODEL`/`ENV_DEEPSEEK_KEY`/`ENV_DASHSCOPE_KEY`。`fromEnvironment` 防御式（key null/blank → null Model → 策略降级）。无硬编码密钥（修后 0 命中）。

### 维度 4: 代码质量
- [x] `XHSAnalysisTools` 104 行、`XHSHarnessAgentFactory` 181 行、`XHSAnalysisAgent` 190 行，均 ≤500。方法均 ≤50 行。圈复杂度低。`run()` 每次请求独立 Toolkit+工具实例（避免并发 `lastResult` 串扰）。`HarnessAgent` 经 try-with-resources 正确关闭。

### 维度 5: 安全
- [x] 密钥来源：`System.getenv(ENV_DEEPSEEK_KEY)`/`System.getenv(ENV_DASHSCOPE_KEY)`，代码中无明文密钥（spike 占位 `not-a-real-key` 明确标注非真实）。`.env.example` 已修复为占位符。`EnvironmentValidator.REQUIRED_KEYS` 含 `DEEPSEEK_API_KEY` fail-fast。`SKILL.md` 明确"输出脱敏，不泄露原始笔记敏感信息"。

### 维度 6: 测试质量
- [x] `XHSAnalysisToolsTest`(6): 覆盖不变量（评分阈值、负面剔除）+ 降级 + preferences 解析 + null 拒绝 + 权威产物一致性。`XHSAnalysisAgentNativeTest`(3): 覆盖原生成功/异常降级/null 降级。`HarnessAgentSpikeTest`(2): 离线装配 + 真实调用（env-gated）。`XHSHarnessAgentFactoryIT`(1): 端到端 env-gated（无 key skip，`@EnabledIfEnvironmentVariable` 诚实）。Mock 合理——用桩 `XHSAgentRunner`（函数式接口 lambda）而非 Mock 自写类。

### 维度 7: 流程合规
- [x] 变更范围与 change.md 增量 C 一致。新增文件：`XHSAnalysisTools`/`XHSAgentRunner`/`XHSHarnessAgentFactory`/`SKILL.md`/对应测试。修改文件：`XHSAnalysisAgent`（注入 runner 接缝）/`pom.xml`（agentscope-harness 依赖 + JaCoCo 排除）。无夹带无关变更。

### 维度 8: SDD 合规
- [x] change.md 增量 C 含完整 AC-13~AC-16、边界、设计约束、测试策略、任务 T-13~T-16、ADR-008/ADR-009 决策溯源。实现严格对齐：不动 C-005 `Msg`/`BaseAgent` 边界、不接 Nacos/Redis/sandbox（明确不做已尊重）。

### 维度 9: TDD 合规
- [x] T-14 `XHSAnalysisToolsTest` 先写 6 项失败测试（评分阈值/负面剔除/降级/preferences/null），再 `XHSAnalysisTools` 最小实现。T-16 `XHSAnalysisAgentNativeTest` 先写 3 例（成功/异常/null），再改 `XHSAnalysisAgent` 注入 runner。TDD 痕迹清晰，无"先实现后补测试"迹象。

---

## AC 完整性判定（增量 C）

| AC | 状态 | 归属 task |
|----|------|-----------|
| AC-13 Gate 0 装配门禁 | ✅ 通过 | T-13 |
| AC-14 不变量留 Java | ✅ 通过 | T-14 |
| AC-15 真实原生链路 + Skill + 密钥禁硬编码 | ✅ 通过（`.env.example` 密钥已修复） | T-15 |
| AC-16 降级与回归不回退 | ✅ 通过 | T-16 |

---

## 结论

- **C-006 增量 C**: **放行** ✅ — 1 🔴 当场修复（`.env.example` 疑似真实密钥 → 已替换占位符），修后 0 🔴 遗留。9 维度全过。2 条 🟡 均为非阻塞建议。
- **密钥禁硬编码**: ✅ 代码中全部密钥来自 `System.getenv()`；`.env.example` 已修复；`grep` 密钥扫描 0 命中。
- **不变量不交 LLM**: ✅ 评分≥3.5/负面剔除/降级 fallback 由确定性 `XHSNoteAnalyzer`（经 `@Tool` 暴露）强制；`SKILL.md` 明确禁止 LLM 伪造/放宽；`lastResult()` 捕获权威产物不解析 LLM 自由文本。6 项确定性单测验证不变量 == 确定性分析产物。
- **后续门禁**: ⑤ CI 已绿（`mvn clean verify` BUILD SUCCESS，全模块 125 测试 0 失败 1 skip，ArchUnit 6/6，覆盖率门禁全过，密钥扫描 0 命中）。可进入 ⑥ 部署验证。

---

# 📋 评审报告: C-006 增量 B — 真实来源与解析

> **评审范围**：增量 B 全量——`XHSNote` 情感粒度契约变更（`AttractionMention` 新增、`XHSNote` 字段级改造）+ `LlmXHSNoteParser`（LLM 抽取/链接/治理端口实现）+ `XHSOpenApiNoteSource`（官方 API 适配）+ `ReMeXHSNoteSource`（召回适配壳）+ `XHSNoteAnalyzer` 串联链路（`Source→Parser→Analyzer`）+ `HttpClient`/`LlmClient` 端口 + 阶段③ 强化边界（LLM ≤60/min 限频、xhs ≤10/min 治理集成、Case-6/7/8 E2E）。
> **重点核查**：① 合规「无模拟登录」红线（AC-8）；② 密钥禁硬编码；③ `AttractionMention` 情感粒度契约变更回归（AC-11/AC-12）；④ 解析降级不污染候选（AC-10）。

## 总览

- 审查文件: 17 个（含 8 个新增 + 9 个修改）
- 测试规模: `LlmXHSNoteParserTest` **11** + `XHSOpenApiNoteSourceTest` **11** + `XHSNoteAnalyzerTest`（`SourceParserAnalyzerChain` 升至 **10** + 其余回归）= **129 测试 0 失败 1 skip**（skills）；全模块 `mvn clean verify` BUILD SUCCESS、**516 测试 0 失败 2 skip**
- 覆盖率: skills 指令 **94.5%** / 行 **92.9%**（均 ≥80% 门禁；JaCoCo「All coverage checks have been met」）；agent-xhs 覆盖率门禁全过
- 🔴 严重问题: **0**
- 🟡 建议改进: 3（均非阻塞）
- 🟢 通过项: 9 维度全过

---

## 🔴 严重问题

**无。** 已实现代码无功能异常、无安全漏洞、无架构腐化、无合规红线触碰（无模拟登录/Cookie 代码）、无 SDD/TDD 形式化迹象。

---

## 🟡 建议改进

### 1. `ReMeXHSNoteSource` 为适配壳，搜索方法恒返回 `List.of()`

- **文件**: `ReMeXHSNoteSource.java`
- **现状**: `search()` 恒返回空列表，类上 Javadoc 明确「真实向量召回依赖 C-015」、`search` 方法上注明「真实实现依赖 C-015 `attraction_kb` 向量召回」。JaCoCo 覆盖率低（0 行覆盖）但属有意壳实现，不阻塞。
- **判定**: 端口契约已对齐（`implements XHSNoteSource`），可插拔无歧义；真实实现归 C-015（change.md D-4 / T-12）。**非阻塞，仅记录。**

### 2. `XHSOpenApiNoteSource` 使用 HTTP GET 传 `api_key` 为查询参数

- **文件**: `XHSOpenApiNoteSource.java:54`
- **现状**: `api_key` 经 `URLEncoder.encode` 拼接在查询字符串中。标准的 API Key 承载方式（Bearer/Header）更安全（不进入访问日志），但 `api_key` 查询参数在小红书开放 API 场景可能是其约定。
- **建议**: 若官方 API 支持 `Authorization: Bearer` 头，建议改为 Header 传递。当前实现满足「禁硬编码」（key 从构造参数传入、上层从环境变量读取）、满足「无模拟登录」，合规面不阻塞。**非阻塞。**

### 3. `LlmXHSNoteParser` 的 PROMPT_TEMPLATE 为内置字面量

- **文件**: `LlmXHSNoteParser.java:34-38`
- **现状**: 提示词模板硬编码在 Java 类中，中文固定模板。类已预留 `LlmClient` 端口注入，提示词策略后续可经构造参数注入（当前单参构造器为最小实现）。
- **理由**: 符合增量 B 的「薄 Agent + 厚 Skills」最小实现原则；提示词后续可随 `LlmClient` 实现或模板引擎注入扩展。**非阻塞。**

---

## 🟢 审查通过项

### 维度 1: 功能完整性（AC-8~AC-12）

- [x] **AC-8（合规真实来源）**: `XHSOpenApiNoteSource` — 经 `HttpClient` 端口调用官方 API；API Key 从构造参数传入（上层从环境变量读取，禁硬编码）；响应 JSON 解析为原始 `XHSNote`（`mentions` 空，退化为只取原文）；密钥 null/blank → `IllegalArgumentException` 快速失败；401/403 抛 `IOException` 驱动统一治理降级。**无模拟登录/登录态/Cookie 代码**（`Compliance` 测试验证请求不含 `cookie` 关键字）。经 `GovernedExternalCaller` 治理集成测试（`GovernedIntegration` 5 项）：额度内正常检索、xhs ≤10/min 前 10 次成功第 11 次限频不超频、授权失败重试耗尽降级、瞬时超时恢复退避 [1s,2s]、限频耗尽走缓存降级。
- [x] **AC-9（ReMe 召回适配壳）**: `ReMeXHSNoteSource implements XHSNoteSource`，端口契约对齐；`search()` 恒返回空列表（壳实现），Javadoc 明确「真实向量召回依赖 C-015」。壳不阻塞增量 B Gate（🟡-1 记录）。
- [x] **AC-10（解析端口 + LLM 实现）**: `XHSNoteParser` 函数式端口（`parse(rawNote) → 结构化 XHSNote`）；`LlmXHSNoteParser` 经 `LlmClient` 端口 + `AttractionCatalog.findByName` 实现「提示词模板 → LLM JSON 响应解析 → 名称链接 → 逐提及情感回填」。经 `GovernedExternalCaller(RateLimiter.API_LLM)` 统一治理：LLM 超时 → 重试耗尽 → 降级空提及（`LlmDegradation`）；LLM 非法 JSON → 容错降级空提及。边界覆盖：空响应/空提及/未知景点名丢弃/sentiment 缺失默认 NEUTRAL/LLM 限频 ≤60/min 耗尽降级空提及。解析失败由 `XHSNoteAnalyzer.parseNotes()` catch → 笔记降级跳过、不污染候选。
- [x] **AC-11（情感粒度契约变更）**: common 新增不可变 `AttractionMention{attractionId, sentiment}`；`XHSNote` 以 `mentions: List<AttractionMention>` 取代 `mentionedAttractions + sentiment`。`XHSNoteAnalyzer.tallyMentions()` 按逐提及情感聚合——一篇笔记「夸 A 吐槽 B」正/负面提及独立计入 A/B，互不串扰（`praisABlameB` + `case8PerMentionSentimentIndependence` 双测试验证）。
- [x] **AC-12（向后兼容与回归）**: `XHSNoteAnalyzer` 新增可选 `XHSNoteParser` 三参构造器，`parser=null` 时直通向后兼容增量 A 行为（`noParserUsesNotesAsIs` 测试）。增量 A 全部 Case-1~5 + 降级/限频/缓存语义 + `XHSAnalysisAgentTest` 12 项 + `XHSAnalysisAgentNativeTest` 3 项 + `XHSAnalysisYunnanScenarioTest` 1 项全部回归通过。全模块 516 测试 0 失败。

### 维度 2: 架构合规

- [x] 新增类型落位正确：`XHSNoteParser`/`LlmXHSNoteParser`/`HttpClient`/`LlmClient`/`XHSOpenApiNoteSource`/`ReMeXHSNoteSource` → `huazai-trip-skills/xhs`（R2：skills 禁依赖 Agent 模块）；`AttractionMention` → `huazai-trip-common/model`（与既有 `Attraction`/`XHSNote` 同层）。端口-适配器清晰：`XHSNoteParser`(端口)/`LlmXHSNoteParser`(适配器)、`HttpClient`(端口)/桩(测试替身)、`LlmClient`(端口)/桩(测试替身)。`XHSOpenApiNoteSource` 对 `HttpClient` 的依赖经端口隔离（桩测离线覆盖）。ArchUnit 6/6 全绿。
- [x] 复用未复制：`LlmXHSNoteParser` 经 `GovernedExternalCaller`（非自建治理）；`XHSNoteAnalyzer.retrieveNotes()` 沿用既有 `GovernedExternalCaller` 包装 `XHSNoteSource.search()`。限频规则复用 `RateLimiter.API_XHS`/`API_LLM`（未新增 API 标识）。A2A 信封 `TASK_RESULT`（`result=List<Attraction>`）不变。

### 维度 3: 编码规范

- [x] 全部 public 类/方法含 Javadoc（`@param`/`@throws`/`@implNote`）；端口接口（`XHSNoteParser`/`HttpClient`/`LlmClient`）含 `@FunctionalInterface` 明示契约意图。
- [x] 安全红线：`XHSOpenApiNoteSource` 密钥从构造参数传入（`IllegalArgumentException` 快速失败 blank key）、Javadoc 明确「禁硬编码」。`LlmXHSNoteParser` 无密钥依赖（LLM 调用经 `LlmClient` 端口，密钥由真实 `LlmClient` 实现管理）。密钥扫描 `grep -rP 'sk-[a-zA-Z0-9]{20,}'` → 0 命中。
- [x] 常量化：`PROMPT_TEMPLATE`/`BASE_URL` 抽常量；`RateLimiter.API_XHS`/`API_LLM` 标识复用。
- [x] 异常处理：`LlmXHSNoteParser.parseAndLink()` → 非法 JSON catch 返回空提及；`XHSNoteAnalyzer.parseNotes()` → 解析异常 catch 跳过笔记；`XHSOpenApiNoteSource.parseResponse()` → 非法 JSON 返回空列表。均不吞异常、不抛裸异常（降级路径清晰）。
- [x] Checkstyle 全模块 0 违规。

### 维度 4: 代码质量

- [x] 方法长度：`LlmXHSNoteParser.doExtract()` 3 行、`parseAndLink()` 23 行、`XHSOpenApiNoteSource.parseResponse()` 20 行、`buildUrl()` 4 行。全部 ≤50 行。
- [x] 链式职责清晰：`Query → Source(取原文) → Parser(结构化) → Analyzer(聚合/筛选)` 步间无交叉污染。`XHSNoteSource` 退化为只取原文（`mentions` 空），`XHSNoteParser` 独立完成结构化，`XHSNoteAnalyzer` 聚合/筛选不变。单步失败不阻断全链（`parseNotes` 逐笔记 try-catch）。
- [x] 不可变性：`AttractionMention` 为 record（自动不可变）；`XHSNote` 为 record；`XHSAnalysisResult` 为 record。无 setter、无可变集合暴露。
- [x] PMD 净（21 warnings 全模块，无 error）。

### 维度 5: 安全

- [x] **合规红线「禁模拟登录」**: `XHSOpenApiNoteSource` 仅 URL 拼接 + HTTP GET，无 Cookie/Session/登录态代码。`XHSOpenApiNoteSourceTest$Compliance.noCookieOrLoginHeaders` 验证请求不含 `cookie` 关键字。`ReMeXHSNoteSource` 为纯壳无 HTTP 调用。
- [x] **密钥安全**: `XHSOpenApiNoteSource` — key 从构造参数传入、上层从环境变量/配置中心读取（`IllegalArgumentException` 快速失败 blank key）、Javadoc 明确「禁硬编码」；`llmClient` 端口 — 密钥管理由真实 `LlmClient` 实现负责（不在本卡范围，但端口隔离保证无密钥泄漏路径）。CI 密钥扫描 0 命中。
- [x] **外部输入校验**: `XHSOpenApiNoteSource` 对 API 响应做防御式解析（`asString`/`asInt`/`asTags` null-safe）；`XHSAnalysisQuery` 构造 fail-fast（`destination` 非 blank）；`LlmXHSNoteParser.parseSentiment()` 对非法值归一 `NEUTRAL`（不抛异常）。
- [x] **数据不落盘**: `XHSNote` 在 Redis 的持久化仍为端口（`XHSNoteCache`），真实存储归 C-015。`XHSOpenApiNoteSource` 无本地落盘。

### 维度 6: 测试质量

- [x] **增量 B 新增测试 13 项**（阶段③ 强化）:
  - `LlmXHSNoteParserTest$LlmRateLimitBoundary`(3): LLM ≤60/min 额度内正常解析、耗尽抛 `RateLimitedException`（由上层降级）、耗尽后原文标识可读。
  - `XHSOpenApiNoteSourceTest$GovernedIntegration`(5): 额度内正常检索、xhs ≤10/min 前 10 次成功第 11 次不超频、授权失败重试耗尽降级、瞬时超时恢复退避 [1s,2s]、限频耗尽走缓存降级（模拟 Analyzer catch 语义）。
  - `XHSNoteAnalyzerTest$SourceParserAnalyzerChain` Case-6/7/8(5): Case-6 完整链路（`XHSOpenApiNoteSource → LlmXHSNoteParser → Analyzer`）+ 密钥缺失快速失败；Case-7 LLM 限频全部/部分笔记降级跳过；Case-8 逐提及情感互不串扰（A 入选 mentionCount=2/negativeRatio=0.0，B 剔除全负面）。
- [x] **T-8~T-11 既有测试全部回归**: `AttractionMentionRoundTripTest`(1) + `XHSNoteRoundTripTest`(5) + `LlmXHSNoteParserTest`(8→11) + `XHSNoteParserTest`(3) + `XHSOpenApiNoteSourceTest`(6→11) + `XHSNoteAnalyzerTest`（`SourceParserAnalyzerChain` 5→10）。
- [x] **增量 A/C 回归**: `XHSAnalysisAgentTest`(12) + `XHSAnalysisAgentNativeTest`(3) + `XHSAnalysisYunnanScenarioTest`(1) + `XHSAnalysisToolsTest`(6) + 其余全模块全绿。全量 516 测试 0 失败。
- [x] **桩/测试替身**: `LlmClient` 用 lambda 桩（`prompt -> "{...}"`）离线确定性；`HttpClient` 用 lambda 桩（`req -> "{...}"`）；`GovernedExternalCaller` 用 `InMemoryRateLimitStore`（可注入时钟）+ 记录式 `Sleeper`；不 Mock 自写业务类。

### 维度 7: 流程合规

- [x] 变更范围与 change.md 增量 B（R2）+ 阶段③ 强化一致。新增文件：`XHSNoteParser`/`LlmXHSNoteParser`/`HttpClient`/`LlmClient`/`XHSOpenApiNoteSource`/`ReMeXHSNoteSource`/对应测试。修改文件：`AttractionMention`(common 新增)、`XHSNote`(common 字段级契约变更)、`XHSNoteAnalyzer`(串联链路 + parseNotes)、`pom.xml`(无修改)。无夹带无关变更。
- [x] `数据模型.md` §2.5 情感粒度已于 T-8 同步。C-017 占位已作废（D-1/D-2/D-3 收编进本卡增量 B）。

### 维度 8: SDD 合规

- [x] change.md 增量 B 含完整 AC-8~AC-12、增量 B 边界（API 授权失败/LLM 超时/未知 ID 丢弃/情感互不串扰 4 项）、测试策略（解析端口/情感粒度/官方 API 适配/契约迁移回归/串联链路 5 组）、任务 T-8~T-11 + 阶段③ 强化。实现严格对齐且未越界（真实 Redis/ReMe 向量召回确未碰，归 C-015；D-1/D-2/D-3 收编完整、C-017 已作废）。

### 维度 9: TDD 合规

- [x] T-8（情感粒度契约变更）: `AttractionMentionRoundTripTest` + 重写 `XHSNoteRoundTripTest`（含「夸 A 吐槽 B」逐提及用例）先红 → `AttractionMention`(record) + `XHSNote` 字段改造最小实现。
- [x] T-9（LlmXHSNoteParser）: `LlmXHSNoteParserTest` 8 项先红 → `LlmXHSNoteParser` 最小实现。阶段③ 补 3 项 LLM 限频边界先红 → 验证治理集成行为。
- [x] T-10（串联链路）: `SourceParserAnalyzerChain` 5 项先红 → `parseNotes` 最小实现。阶段③ 补 5 项 Case-6/7/8 先红 → 验证 E2E 行为。
- [x] T-11（官方 API 适配）: `XHSOpenApiNoteSourceTest` 6 项先红 → `XHSOpenApiNoteSource` 最小实现。阶段③ 补 5 项 GovernedIntegration 先红 → 验证治理集成行为。
- [x] 无「先实现后补测试」迹象——TDD 分层清楚：端口契约 → 桩测 → 最小实现 → 边界强化。

---

## AC 完整性判定（增量 B）

| AC | 状态 | 归属 task |
|----|------|-----------|
| AC-8 合规来源（官方 API + 治理 + 禁模拟登录） | ✅ 通过 | T-11 + 阶段③ |
| AC-9 ReMe 召回适配壳（端口契约对齐） | ✅ 通过（壳实现，真实向量归 C-015） | T-11 |
| AC-10 解析端口 + LLM 实现 + 治理降级 | ✅ 通过 | T-9 + 阶段③ |
| AC-11 情感粒度契约变更（`AttractionMention` 逐提及） | ✅ 通过 | T-8 |
| AC-12 向后兼容与回归（增量 A Case-1~5 全绿） | ✅ 通过 | T-10 + 阶段③ |

---

## 结论

- **C-006 增量 B**: **放行** ✅ — 0 🔴 严重问题，9 维度全过，3 条 🟡 均为非阻塞（适配壳待 C-015 真实化、api_key 查询参数建议改 Header、提示词策略后续扩展）。
- **合规红线「无模拟登录」**: ✅ — 全量代码无 Cookie/登录态/模拟登录痕迹；`Compliance` 测试 + 评审双重核查通过。
- **密钥禁硬编码**: ✅ — `XHSOpenApiNoteSource` key 从构造参数传入（上层从环境变量读取）；CI 扫描 0 命中。
- **契约迁移回归**: ✅ — 增量 A Case-1~5 + 降级/限频/缓存语义全部在新 `XHSNote`（`mentions: List<AttractionMention>`）契约下回归通过。全模块 516 测试 0 失败。
- **后续门禁**: ⑤ CI 已绿（`mvn clean verify` BUILD SUCCESS，516 测试 0 失败 2 skip，ArchUnit 6/6，覆盖率门禁全过，密钥扫描 0 命中）。可进入 ⑥ 部署验证。

---

# 📋 评审报告: C-006 增量 D — AgentScope 原生 A2A 适配

> **评审范围**：增量 D 全量——`XHSAnalysisAgent` 移除 `extends BaseAgent` + 内联 `receive(Msg)` 模板方法 + 消除 `AgentId`/`AgentReply` 依赖 + 构造器 null guard + `status()` lifecycle + `TripAgentCardFactory` A2A 服务发现 + 全量回归。
> **重点核查**：① 零废弃 import（AC-17）；② 行为等价——增量 A/B/C 全部现有测试不回退（AC-18）；③ 构造器 null guard + lifecycle（AC-20）。

## 总览

- 审查文件: 2 个（`XHSAnalysisAgent.java` 重构 + `XHSAnalysisAgentTest.java` 增强）
- 测试规模: `XHSAnalysisAgentTest` **19**（含 HappyPath 8 + Observability 3 + DegradeAndError 4 + 类级 4）+ `XHSAnalysisAgentNativeTest` **3** + `XHSAnalysisYunnanScenarioTest` **1** = agent-xhs **19 测试 0 失败 0 skip**；全模块 `mvn clean verify` BUILD SUCCESS，ArchUnit 6/6
- 覆盖率: agent-xhs `All coverage checks have been met`（JaCoCo ≥80% 门禁通过）
- 🔴 严重问题: **0**
- 🟡 建议改进: 0
- 🟢 通过项: 9 维度全过

---

## 🔴 严重问题

**无。** 重构后代码无功能回退、无安全漏洞、无架构腐化。`BaseAgent` 模板方法内联完整，行为等价。

---

## 🟡 建议改进

**无。** 增量 D 为纯重构（脱离废弃基类），范围精确，无新功能、无新端口、无契约变更。

---

## 🟢 审查通过项

### 维度 1: 功能完整性（AC-17~AC-20）

- [x] **AC-17（零废弃依赖）**: `huazai-trip-agent-xhs/src` 全量 `grep` 零 import `BaseAgent`/`AgentId`/`AgentReply`/`InMemoryAgentRegistry`/`AgentRegistration`。`XHSAnalysisAgent` 不 `extends BaseAgent`（`public final class XHSAnalysisAgent`）。Javadoc 中仅以 `{@code BaseAgent}` 文档说明迁移历史，非代码依赖。
- [x] **AC-18（行为等价）**: `receive(Msg)→Msg` 公共契约不变——`TASK_ASSIGN` 校验 → `THINKING→ACTING→DONE` lifecycle → `handleTaskAssign` → `Msg.taskResult`/`Msg.taskError`（含 `BaseException` + `RuntimeException` 两层 catch）。增量 A/B/C 全部现有测试不回退（`XHSAnalysisAgentTest` 19 + `XHSAnalysisAgentNativeTest` 3 + `XHSAnalysisYunnanScenarioTest` 1 全绿）。
- [x] **AC-19（A2A 服务发现）**: `XHSAnalysisAgentTest.buildableAsAgentCard()` 验证 `TripAgentCardFactory.buildCard(AGENT_ID, ..., List.of("xhs_note_analysis"))` 构建 `ConfigurableAgentCard`（name=xhs-analyst, skills=[xhs_note_analysis]）成功。
- [x] **AC-20（构造器 null guard + lifecycle）**: 构造器显式校验 `analyzer`/`metrics` 非 null（`InvalidRequestException` 快速失败）；`status()` 返回 `AgentStatus`（IDLE→THINKING→ACTING→DONE lifecycle）。`constructorRejectsNullAnalyzer` + `constructorRejectsNullMetrics` + `statusLifecycleIdleToDone` 三项测试覆盖。

### 维度 2: 架构合规

- [x] `XHSAnalysisAgent` 仅依赖 `common`（`Msg`/`MessageType`/`AgentStatus`/`BaseException`/`InvalidRequestException`/`TravelStyle`）+ `skills`（`XHSNoteAnalyzer`/`AgentMetrics`/`XHSAgentRunner`/`RateLimiter`）。无 agent 间依赖（R1）。ArchUnit 6/6 全绿。`extends BaseAgent` 已移除，`AGENT_URI` 常量化替代 `agentId.uri()`。

### 维度 3: 编码规范

- [x] 全部 public 类/方法含 Javadoc。`AGENT_URI`/`AGENT_ID`/`TASK_TYPE`/`ERROR_CODE_AGENT_FAILURE`/`KEY_CONTEXT`/`CTX_*` 常量化。无魔法值。Checkstyle 0 违规。

### 维度 4: 代码质量

- [x] `XHSAnalysisAgent` 247 行（≤500）。方法均 ≤50 行。`receive()` 模板内联清晰（null/type 校验 → try-catch lifecycle → `handleTaskAssign`）；`resolve()` 双路径逻辑简洁。`Resolved` record 语义明确。PMD 净。

### 维度 5: 安全

- [x] 无新密钥引入。无硬编码密钥。`SensitiveContentScreen` 默认防线不变。密钥扫描 `grep -rP 'sk-[a-zA-Z0-9]{20,}'` → 0 命中。

### 维度 6: 测试质量

- [x] 增量 D 新增 4 项测试：`agentUriNormalized`（AC-17 URI 规范化）、`buildableAsAgentCard`（AC-19 A2A 服务发现）、`constructorRejectsNullAnalyzer` + `constructorRejectsNullMetrics`（AC-20 null guard）、`statusLifecycleIdleToDone`（AC-20 lifecycle）。增量 A/B/C 全部回归不回退。桩用 lambda/内存实现而非 Mock 自写业务类。

### 维度 7: 流程合规

- [x] 变更范围与 change.md 增量 D（R4）一致。修改文件：`XHSAnalysisAgent`（重构主体）+ `XHSAnalysisAgentTest`（新增 AC-17~AC-20 测试）。无夹带无关变更。

### 维度 8: SDD 合规

- [x] change.md 增量 D 含完整 AC-17~AC-20、任务 T-17~T-19。实现严格对齐且未越界——仅脱离废弃 `BaseAgent`，不动 A2A `Msg` 信封、不动增量 A/B/C 行为、不新增功能。

### 维度 9: TDD 合规

- [x] T-17 先写失败测试（`agentUriNormalized`/`buildableAsAgentCard`/`constructorRejectsNullAnalyzer`/`constructorRejectsNullMetrics`/`statusLifecycleIdleToDone` 编译失败=红灯），T-18 重构实现（移除 `extends BaseAgent`、内联 `receive()`、新增 `AGENT_URI`/`status()`/null guard）让测试绿灯。TDD 分层清晰。

---

## AC 完整性判定（增量 D）

| AC | 状态 | 归属 task |
|----|------|-----------|
| AC-17 零废弃依赖 | ✅ 通过 | T-17/T-18 |
| AC-18 行为等价（全量回归不回退） | ✅ 通过 | T-18/T-19 |
| AC-19 A2A 服务发现 | ✅ 通过 | T-17 |
| AC-20 构造器 null guard + lifecycle | ✅ 通过 | T-17/T-18 |

---

## 结论

- **C-006 增量 D**: **放行** ✅ — 0 🔴 严重问题，0 🟡 建议改进，9 维度全过。纯重构，行为等价，零废弃依赖。
- **后续门禁**: ⑤ CI 已绿（`mvn clean verify` BUILD SUCCESS，全模块 19 agent-xhs 测试 0 失败，ArchUnit 6/6，覆盖率门禁全过）。可进入 ⑥ 部署验证。
