---
id: C-009
slug: budget-agent
status: done
created: 2026-06-07
updated: 2026-06-14
owner: Owner Agent
---

# C-009 费用统筹 Agent（BudgetAgent · 增量交付）

> **修订记录**
> - **R1（2026-06-07）**：初版规格——用户故事 + AC + 边界 + 任务拆解。
> - **R2（2026-06-14）**：对齐 C-006/C-007/C-008 增量交付模式，拆为**增量 A（确定性 BigDecimal 核算 + HITL 判定 + 住宿/交通估算降级）+ 增量 B（AgentScope 原生化）**。增量 A 不继承废弃 `BaseAgent`（对齐 XHSAnalysisAgent 模式），实现确定性 `BudgetCalculationService` + 四类费用拆分 + 对账一致性 + `overrunRate` 精确判定 + HITL 触发与调整建议 + 缺失费用项保守估算；增量 B 对齐 ADR-008/009 把 BudgetAgent 升级为 HarnessAgent + 真实 LLM + @Tool + Skill。

## 用户故事

作为 SupervisorAgent，我想要委派 BudgetAgent 对编排好的行程做费用拆分与预算管控，并在超预算 ≥15% 时触发 HITL，以便用户得到不超预算（或经确认调整）的可控方案。

## 本次范围

本卡分两个增量交付，**真相同卡**，复用 C-005 治理能力与 C-006/C-007/C-008「薄 Agent + 厚 Skills + 端口化」验证模式。

### 增量 A — 确定性 BigDecimal 核算 + HITL 判定 + 降级

> 对齐 C-006/C-007/C-008 增量 A：打通 **TASK_ASSIGN → 四类费用拆分 → totalCost 汇总 → overrunRate 计算 → HITL 判定 → 调整建议 → TASK_RESULT** 端到端主链路。

- 做：`cost-calculation` 业务 Skill（`BudgetCalculationService`）：四类费用拆分（交通/住宿/餐饮/门票）、`BigDecimal` 精确核算、`overrunRate` 红线判定（含等号 `>= 0.15`）、超支调整建议生成、逐日小计与总计对账一致性。
- 做：`BudgetAgent` **不继承废弃 `BaseAgent`**（对齐 XHSAnalysisAgent C-006 增量 D 模式），直接实现 `receive(Msg)` + `agentUri()` + `status()`，用 `Msg.taskResult`/`Msg.taskError` 构造结果。
- 做：缺失费用项保守估算降级——住宿：`headcount × days × 200`（`fallback=true`）；交通：`Σ distanceKm × 1 元/km`，路线缺失则 `days × 100`（`fallback=true`）。
- 结果对象 `BudgetCalculationResult`（`Budget` + 逐日小计 + `requiresHITL` + 调整建议 + `fallback` + `Telemetry`），对齐 C-006 `XHSAnalysisResult` / C-007 `RoutePlanResult` / C-008 `ItineraryDesignResult` 模式。

### 增量 B — AgentScope 原生化（Budget 切片）

> 目标：对齐 C-006/C-007/C-008 增量 B/C 已验证的成功模式（ADR-008/009），把 BudgetAgent 从纯确定性升级为 **HarnessAgent + 真实 LLM（主 deepseek-v4-pro / 降级 qwen3-max）+ 真实 Skill（`SKILL.md`）+ `@Tool` 暴露确定性核算能力**，业务硬不变量（BigDecimal 精确核算、overrunRate ≥0.15 触发 HITL、对账一致性）**仍锁在确定性 Java（经 `@Tool` 暴露）**——LLM 负责理解委派意图、选择工具、编排调用顺序与结果解读，不裁决不变量。

- 做（B-1 工具门面）：`BudgetCalculationTools` 把确定性 `BudgetCalculationService` 暴露为 `@Tool calculate_budget`，**所有强规则留 Java**，`lastResult()` 捕获权威结果。
- 做（B-2 真实 Skill）：`resources/skills/cost-calculation/SKILL.md`（frontmatter `name=cost_calculation`）经 `ClasspathSkillRepository` 加载。
- 做（B-3 原生 Agent 工厂）：`BudgetHarnessAgentFactory implements BudgetAgentRunner`，组装 LLM（**主 deepseek-v4-pro / 降级 qwen3-max + env key，ADR-009**）+ `Toolkit`（`BudgetCalculationTools`）+ Skill + `HarnessAgent`。
- 做（B-4 Agent 接缝）：`BudgetAgent` 新增注入 `BudgetAgentRunner`，`handleTaskAssign` 先走原生路、失败/无权威产物**降级回确定性直路**（`fallback=true`），不抛裸异常；旧构造器与增量 A 行为不动。

## 非目标（Out of Scope）

- 本次不做在线支付、实时机酒报价。
- 本次不做行程编排（消费 C-008 产出）。
- 本次不实现 A2A/治理基座与 Gate（复用 C-005）。
- 本次不暴露对外 HITL 接口（归 C-011，本 Agent 只产出 `requiresHITL` 判定与建议）。
- 不接入真实 Redis——结果缓存以进程内内存承载（ADR-006），Redis 适配归 C-015。
- 不接入 Nacos 注册发现——经 C-005 `InMemoryAgentRegistry` 完成 in-process 路由，Nacos 归 C-011。
- 不继承废弃 `BaseAgent`/`AgentId`/`AgentReply`（对齐 C-006 增量 D / XHSAnalysisAgent 已验证模式）。

## 验收标准（AC）

### 增量 A 验收标准

- AC-1: `BudgetAgent` **不继承废弃 `BaseAgent`**（对齐 XHSAnalysisAgent 模式），标识 `agent://budget-controller`，直接实现 `receive(Msg)` + `agentUri()` + `status()`；`receive` 仅受理 `TASK_ASSIGN(taskType=COST_CALCULATION)`，非该类型抛 `InvalidRequestException`。
- AC-2: 正常路径返回 `TASK_RESULT`，`payload.result` 为 `BudgetCalculationResult`（含 `Budget` + 逐日小计 + `requiresHITL` + 调整建议 + `fallback`），`traceId` 透传。
- AC-3: 实现 `cost-calculation` 业务 Skill（`BudgetCalculationService`）：按四类拆分（交通/住宿/餐饮/门票）核算，金额一律 `BigDecimal`，禁用 `double`。
  - **门票（ticket）**：`Σ TripDay.attractions[].ticketPrice()`，`ticketPrice` 为 null 时计 `BigDecimal.ZERO`。
  - **餐饮（food）**：`Σ TripDay.meals[].avgPrice() × headcount`，`avgPrice` 为 null 时按保守单价 `30 元/人·餐` 估算 + `fallback=true`。
  - **交通（transport）**：`Σ TripDay.routes[].distanceKm() × 1 元/km`；路线缺失（无 routes 或 routes 为空）时按 `days × 100 元` 保守估算 + `fallback=true`。
  - **住宿（hotel）**：`headcount × days × 200 元`（当前无酒店数据源，一律保守估算 + `fallback=true`）。
- AC-4: `totalCost = transport + hotel + food + ticket`（`BudgetBreakdown` 四类之和）。
- AC-5: `overrunRate = (totalCost - budget) / budget`，用 `BigDecimal.divide(budget, 10, RoundingMode.HALF_UP)` 保留足够标度后比较，避免浮点误差。`budget ≤ 0` 时作为防御性分支返回参数错误。
- AC-6: 预算红线强规则：**`overrunRate >= 0.15` 时触发 HITL**（含等号），`overrunRate < 0.15` 时不触发。判定用 `BigDecimal.compareTo(new BigDecimal("0.15")) >= 0`，禁用 `double` 比较。
- AC-7: 超支 ≥15% 时返回 `requiresHITL=true` + 超支项清单（哪类费用占比最大）+ 可选调整建议（如降档住宿/减景点/换交通方式），建议为确定性生成（非 LLM 自由文本）。
- AC-8: 各 `TripDay` 的 `dailyCost` 之和与 `totalCost` **对账一致性**：以明细重算为准（`Σ perDayTicket + perDayFood + perDayTransport + perDayHotel`），若与 `ItineraryDesignResult` 中原 `dailyCost` 有差异（因叠加住宿/交通），在结果中标记 `dailyCostReconciled=true` 并更新。
- AC-9: 当行程为空（0 天 / `ItineraryDesignResult.days()` 为空）时，`totalCost=ZERO`、`overrunRate` 定义为不超支（`BigDecimal.ZERO`），不除零崩溃，`requiresHITL=false`。
- AC-10: 可观测：经 C-005 `AgentMetrics`/`TraceContext` 发射 `traceId/planId?/agentId=budget-controller/taskType=COST_CALCULATION/fallback` 及 `agent.call.latency`；HITL 触发计数。

### 增量 B 验收标准（AgentScope 原生化 Budget 切片）

- AC-11（不变量留 Java）: 费用核算的强规则由确定性 `BudgetCalculationService`（经 `@Tool` 暴露为 `calculate_budget`）强制，**LLM 不得伪造/放宽费用数据**；`BudgetCalculationTools` 单测验证工具产物 == 确定性核算产物。
- AC-12（真实原生链路 + Skill）: 提供 `BudgetHarnessAgentFactory`，经 `HarnessAgent` + 真实 LLM（**主 deepseek-v4-pro / 降级 qwen3-max，ADR-009**）+ `ClasspathSkillRepository` 加载 `SKILL.md`（原生渐进式披露）跑通费用核算；API Key 走环境变量（`DEEPSEEK_API_KEY`/`DASHSCOPE_API_KEY`，禁硬编码，CI 密钥扫描 0 命中）；真实链路经 env-gated 集成测试验证（无 key 自动 skip，不伪装通过）。
- AC-13（降级与回归不回退）: `BudgetAgent` 注入 `BudgetAgentRunner` 后，原生路失败/无权威产物 → 降级回确定性直路并 `fallback=true`、绝不抛裸异常；未注入 runner 时为纯确定性直路。增量 A 全部回归通过，`mvn clean verify` 全绿、覆盖率 ≥80%（HarnessAgent 工厂因真实 LLM/网络不可离线覆盖，JaCoCo 排除并登记）。
- AC-14（A2A 原生化接缝透明）: 原生化后 `BudgetAgent` 对外 A2A 契约不变——`TASK_ASSIGN(taskType=COST_CALCULATION)` 入、`TASK_RESULT(BudgetCalculationResult)` 出；`traceId` 透传；`fallback` 语义不变。Supervisor 无感知切换。

## 边界情况（≥3）

- 当 `overrunRate` 恰为 0.15（临界）时，**必须触发 HITL**（含等号），用 `BigDecimal.compareTo` 判定。
- 当 `overrunRate` 为 0.1499…（低于临界）时，**不触发 HITL**。
- 当某类费用数据缺失（如门票 `ticketPrice` 为 null、餐饮 `avgPrice` 为 null、路线缺失）时，采用保守估算并标记 `fallback=true`，不静默置 0。
- 当 `budget ≤ 0` 时（理论上被 C-004 校验拦截），作为防御性分支返回参数错误（`InvalidRequestException`）。
- 当行程为空（0 天）时，`totalCost=ZERO`、`overrunRate=ZERO`（不超支），不除零崩溃。
- 当四类拆分之和与逐日小计对不上时，以明细为准重算并记录 `dailyCostReconciled=true`。
- 当 `context` 含未知/新增字段时，忽略而非失败（向后兼容，A2A §2.3）。
- 当 `BudgetAgentRunner` 为 null（未注入）时，`BudgetAgent` 退化为纯确定性直路（增量 A 行为），不触发任何 LLM 调用。
- 当 HarnessAgent 原生路运行时 LLM 未调用 `calculate_budget` 工具（`lastResult()` 为 null）时，降级回确定性直路并 `fallback=true`，绝不以 LLM 自由文本作为费用数据。
- 当 `DEEPSEEK_API_KEY` 和 `DASHSCOPE_API_KEY` 均缺失时，`BudgetHarnessAgentFactory.fromEnvironment` 返回 null（不构建原生 Agent），`BudgetAgent` 自动走纯确定性直路。
- 当 `headcount=0`（理论不可能，C-004 约束 ≥1）时，防御性按 1 处理。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 费用核算 P95 < 2s（确定性直路）；真实 LLM 调用（增量 B）P95 < 10s |
| 可靠性 | 缺失费用项有保守估算降级，`fallback=true`；金额用 BigDecimal 不丢精度 |
| 安全 | 金额单位人民币元、两位小数；不落盘；LLM API Key 走环境变量（禁硬编码，CI 密钥扫描 0 命中） |
| 可观测 | `human.intervention.count`（超支触发）、`agent.call.latency` |
| 质量 | Agent/Skill 核心逻辑单测覆盖率 ≥80% |

## 设计约束

### 增量 A 设计约束

- `BudgetAgent` 落在 `huazai-trip-agent-budget`；核算逻辑下沉 `huazai-trip-skills` 的 `budget` 包（禁依赖其他 Agent 模块）。
- **`BudgetAgent` 不继承废弃 `BaseAgent`**（对齐 XHSAnalysisAgent C-006 增量 D 模式），直接实现 `receive(Msg)` + `agentUri()` + `status()`，用 `Msg.taskResult`/`Msg.taskError` 构造结果消息，`AtomicReference<AgentStatus>` 管理生命周期。
- 金额一律 `BigDecimal`，禁用 `double`；比例计算保留 10 位标度后 `compareTo`，避免浮点误差。
- 预算红线与 HITL 触发在本 Agent 内部判定（发现），全局复核与对外暴露 `requiresHumanIntervention` 分别由 Gate / Server 承接。
- 估算系数/默认单价集中为可配置常量（`BudgetDefaults`），不散落硬编码：
  - `HOTEL_UNIT_PRICE = 200`（元/人·天）
  - `TRANSPORT_COST_PER_KM = 1`（元/km）
  - `TRANSPORT_DAILY_FALLBACK = 100`（元/天，路线缺失时）
  - `MEAL_FALLBACK_PRICE = 30`（元/人·餐）
  - `OVERRUN_THRESHOLD = 0.15`（HITL 触发阈值）
- 必须复用 C-004 已定义的 `Budget`/`BudgetBreakdown`/`TripDay`/`TripPlanRequest`（零新增/零变更 common 模型字段契约）。
- 必须复用 C-008 `ItineraryDesignResult`（消费行程编排产出，不重复编排）。
- 必须复用 C-005 既有交付：`Msg`/`MessageType`/`AgentMetrics`/`TraceContext`（不得复制治理逻辑）。

### 增量 B 设计约束

- `BudgetCalculationTools`（`@Tool` 工具门面）与 `BudgetHarnessAgentFactory` 落在 `huazai-trip-skills` 的 `budget` 包。`BudgetAgentRunner` 接缝接口亦落 `budget` 包。
- 真实 Skill `SKILL.md` 放 `huazai-trip-skills/src/main/resources/skills/cost-calculation/SKILL.md`，经 `ClasspathSkillRepository` 加载。
- `BudgetAgent`（budget 模块）通过 `BudgetAgentRunner` 接缝解耦原生路——单测可注入桩（确定性、无网络），真实实现 `BudgetHarnessAgentFactory` 经 env key 接 LLM。
- 对齐 C-006/C-007/C-008 增量模式：LLM 做编排（理解意图 → 调用 `calculate_budget` 工具 → 解读结果），确定性核心做裁决（BigDecimal 精确核算/overrunRate 判定/HITL 触发由 Java 强制）。
- `BudgetHarnessAgentFactory.fromEnvironment` 双模型组装逻辑复用 `LlmModelFactory`（`LlmModelFactory.fromEnvironment()`：主 deepseek → 降级 qwen → 均缺失返回 null）。
- JaCoCo 排除 `BudgetHarnessAgentFactory`（因真实 LLM/网络不可离线覆盖），登记于覆盖率豁免清单。

## 契约影响

- REST: 无（HITL 对外暴露归 C-011）。
- A2A: 新增 `taskType=COST_CALCULATION` 的 `TASK_ASSIGN` payload:
  - `context.request`：`TripPlanRequest`（含 `budget`/`headcount`/`days`/`destination`/`travelStyle`，必填）。
  - `context.itineraryResult`：`ItineraryDesignResult`（含 `List<TripDay>` 每日景点/餐饮/路线/`dailyCost`，必填）。
  - `TASK_RESULT`: `result=BudgetCalculationResult`，复用 `Msg` 既有信封（向后兼容）。
- 数据模型: 复用 `Budget`/`BudgetBreakdown`/`TripDay`/`TripPlanRequest`（C-004 已定义，零新增/零变更）。
- Skill 端口（skills 模块，本卡新增）:
  - `BudgetCalculationService`（确定性核算核心）。
  - `BudgetCalculationQuery`（封装请求 + 行程结果）。
  - `BudgetCalculationResult`（Budget + 逐日小计 + requiresHITL + 调整建议 + fallback + Telemetry）。
  - `BudgetDefaults`（可配置估算常量）。
- Redis / ReMe: `trip:agent:result:{planId}:budget` 结果缓存键（本卡不新增 Redis 写入，进程内内存承载）。
- 增量 B 新增:
  - Skill 端口: `BudgetAgentRunner`（`@FunctionalInterface`，接缝接口）。
  - 工具门面: `BudgetCalculationTools`（`@Tool calculate_budget` 暴露确定性 `BudgetCalculationService`，`lastResult()` 捕获权威结果）。
  - 工厂: `BudgetHarnessAgentFactory implements BudgetAgentRunner`（双模型组装，复用 `LlmModelFactory`）。
  - Skill 资源: `resources/skills/cost-calculation/SKILL.md`（AgentScope 原生渐进式披露）。
  - A2A 信封: **不变**——`TASK_ASSIGN/TASK_RESULT` 信封、payload 结构、`traceId/fallback` 语义完全保留，Supervisor 无感知。

## DTO 与 context key 语义

### A2A context key 定义

| key | 类型 | 必填 | 语义 |
|-----|------|------|------|
| `context.request` | `TripPlanRequest` | 是 | 用户规划请求，提供 `budget`（总预算 BigDecimal）、`headcount`（人数）、`days`（天数）、`destination`、`travelStyle` |
| `context.itineraryResult` | `ItineraryDesignResult` | 是 | C-008 行程编排产出，含 `List<TripDay>`（每日景点/餐饮/路线/dailyCost）、风险清单、fallback |

### 新增 DTO（skills/budget 包）

| DTO | 字段 | 语义 |
|-----|------|------|
| `BudgetCalculationQuery` | `request: TripPlanRequest`, `itineraryResult: ItineraryDesignResult` | 核算请求封装 |
| `BudgetCalculationResult` | `budget: Budget`, `dailyBreakdowns: List<DailyCostDetail>`, `requiresHITL: boolean`, `hitlReason: String`, `adjustmentAdvice: List<String>`, `fallback: boolean`, `telemetry: Telemetry` | 核算结果封装 |
| `BudgetCalculationResult.Telemetry` | `fallbackItemCount: int`, `hitlTriggered: boolean` | 可观测遥测 |
| `DailyCostDetail` | `dayIndex: int`, `ticket: BigDecimal`, `food: BigDecimal`, `transport: BigDecimal`, `hotel: BigDecimal`, `dailyTotal: BigDecimal` | 逐日费用明细（对账用） |
| `BudgetDefaults` | 常量类：`HOTEL_UNIT_PRICE`, `TRANSPORT_COST_PER_KM`, `TRANSPORT_DAILY_FALLBACK`, `MEAL_FALLBACK_PRICE`, `OVERRUN_THRESHOLD` | 可配置估算系数 |

## 影响面

- 模块 / Agent / Skill: `huazai-trip-agent-budget`（Agent + 增量 B 原生接缝）、`huazai-trip-skills/budget`（Skill + 端口 + 增量 B 工具门面/工厂/Skill 资源）；common 零改动。
- 外部 API: LLM（费用解读/建议增强——增量 B，经 C-005 统一治理）。
- wiki: 对齐 `业务模型.md`(§4 预算红线/HITL)、`数据模型.md`(§2.4 Budget)、`接口协议.md`(§2 A2A COST_CALCULATION)。

## 规则归属

- 业务不变量归属: 预算红线 `overrunRate >= 0.15` 触发 HITL、BigDecimal 精确核算、对账一致性 → Budget Agent 发现 + OutputQualityGate 复核 + Server 对外暴露 `requiresHumanIntervention`。
- 外部调用治理归属: 估算辅助 LLM 调用（增量 B）走 C-005 统一治理，不自建治理。
- 可观测性要求: `traceId/planId?/agentId=budget-controller/taskType=COST_CALCULATION/fallback`；HITL 计数。

## 测试策略

### 增量 A 测试策略

- 先写失败测试（Red）: overrunRate 计算精度、临界 0.15 触发（含等号）、0.1499 不触发、BigDecimal 禁 double、dailyCost 求和对账、缺失项保守估算、budget≤0 参数错误、空行程不除零 → 先红。
- Happy Path: 总费用在预算内 → `requiresHITL=false`，`Budget` 四类 breakdown 完整，`overrunRate` 为负或零。
- 边界测试:
  - `overrunRate` 恰为 0.15 → 触发 HITL。
  - `overrunRate` 为 0.1499（BigDecimal 精确）→ 不触发。
  - `overrunRate` 为负值（结余）→ 不触发。
  - `budget=0` → 参数错误。
  - 空行程（0 天）→ `totalCost=ZERO`，不除零。
  - 对账不一致 → 以明细为准重算。
- 降级测试:
  - 门票 `ticketPrice` 为 null → 计 ZERO。
  - 餐饮 `avgPrice` 为 null → 保守 30 元/人·餐 + `fallback=true`。
  - 路线缺失 → `days × 100` 保守估算 + `fallback=true`。
  - 住宿一律保守估算 `headcount × days × 200` + `fallback=true`。
- A2A 契约测试:
  - 投递 `taskType=ITINERARY_DESIGN` → 快速失败 `InvalidRequestException`。
  - `context.request` 缺失 → 参数错误。
  - `context.itineraryResult` 缺失 → 参数错误。
  - `context` 含未知字段 → 忽略不失败。
- 回归测试: 全部强规则（BigDecimal 精度、临界值、对账）纳入回归，防漂移。

### 增量 B 测试策略

- 先写失败测试（Red）: `BudgetCalculationTools` `@Tool` 产物 == 确定性 `BudgetCalculationService` 产物（AC-11）、`BudgetAgent` 原生路失败降级回确定性直路 + `fallback=true`（AC-13）、`BudgetAgent` LLM 未调用工具降级（边界）→ 先红。
- 工具门面测试（`BudgetCalculationToolsTest`）: 确定性输入 → `calculate_budget` 产出 `BudgetCalculationResult` 与直接调 `BudgetCalculationService` 产出完全一致；`lastResult()` 正确捕获；强规则行为由底层确定性 Service 保证，工具门面不重复验证。
- Agent 原生接缝测试（`BudgetAgentNativeTest`）: ① 注入成功桩 runner → 原生产物 == 确定性产物，`fallback=false`；② 注入异常桩 runner → 降级确定性 + `fallback=true`；③ 注入 null-result 桩 runner → 降级确定性 + `fallback=true`；④ runner 为 null → 纯确定性直路（增量 A 行为）。
- env-gated 集成测试（`BudgetHarnessAgentFactoryIT`）: 有 `DEEPSEEK_API_KEY` → 真实 LLM 跑通费用核算 Skill；无 key → `@Disabled` skip，不伪装通过。
- 回归: 增量 A 全量回归不回退。

## 验收用例

### 增量 A 用例

- Case-1: `budget=8000, totalCost=7820` → `overrunRate ≈ -0.0225`，`requiresHITL=false`，breakdown 四类齐全。
- Case-2: `budget=8000, totalCost=9200` → `overrunRate = 0.15`（恰好临界）→ `requiresHITL=true` + 调整建议。
- Case-3: `budget=8000, totalCost=9199.99` → `overrunRate ≈ 0.14999…` → `requiresHITL=false`（临界精确不触发）。
- Case-4: `Σ dailyBreakdown.dailyTotal == totalCost`（对账一致性）。
- Case-5: 门票 `ticketPrice` 全为 null → ticket 类拆分 = ZERO，不崩溃。
- Case-6: 路线全缺失（routes 为空）→ transport 保守估算 `days × 100` + `fallback=true`。
- Case-7: 住宿估算 `headcount=4, days=5` → hotel = `4 × 5 × 200 = 4000`，`fallback=true`。
- Case-8: 投递 `taskType=ITINERARY_DESIGN` → 快速失败返回 `TASK_ERROR`。
- Case-9: 空行程（0 天）→ `totalCost=ZERO`，`overrunRate=ZERO`，`requiresHITL=false`。
- Case-10: `budget=0` → `InvalidRequestException`。
- Case-11: 超支 20%（远超阈值）→ `requiresHITL=true`，调整建议含"降档住宿"或"减景点"。

### 增量 B 用例

- Case-12: 注入 `BudgetAgentRunner`，LLM 正常调用 `calculate_budget` 工具 → `TASK_RESULT` 产出 == 确定性 `BudgetCalculationService` 产出，`fallback=false`；费用数据来自确定性 Java 而非 LLM 自由文本。
- Case-13: 注入 `BudgetAgentRunner`，LLM 运行失败（超时/异常）→ 降级回确定性直路 → `TASK_RESULT(fallback=true)`，不抛裸异常，费用数据正常产出。
- Case-14: 注入 `BudgetAgentRunner`，LLM 未调用工具（`lastResult()` 为 null）→ 降级回确定性直路 → `TASK_RESULT(fallback=true)`。
- Case-15: 未注入 `BudgetAgentRunner`（null）→ 纯确定性直路（增量 A 行为），Case-1~11 全部回归通过。
- Case-16: `DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY` 均缺失 → `BudgetHarnessAgentFactory.fromEnvironment` 返回 null，应用正常，走纯确定性直路。

## 任务拆解（≤1 天/项，DAG 无环）

### 增量 A 任务

- [x] T-1: skills `budget` 包端口 + 查询/结果对象（`BudgetCalculationQuery`/`BudgetCalculationResult`/`DailyCostDetail`/`BudgetDefaults`）+ `BudgetAgentRunner` 接缝接口 · P0 · 依赖 C-004,C-005,C-008 · 模块 skills
- [x] T-2: `BudgetCalculationService` 确定性核算主逻辑（四类拆分 + totalCost + overrunRate BigDecimal 精确计算 + 逐日对账 + 缺失项保守估算降级）+ 先红临界/精度测试 · P0 · 依赖 T-1 · 模块 skills
- [x] T-3: HITL 判定（overrunRate >= 0.15 含等号触发）+ 调整建议确定性生成 + 临界 0.15/0.1499 精确测试 · P0 · 依赖 T-2 · 模块 skills
- [x] T-4: `BudgetAgent` A2A 接入（不继承 BaseAgent，对齐 XHSAnalysisAgent 模式：`receive(Msg)` + `agentUri()` + `status()` + context 解析 + 可观测派发）+ 收发契约测试 · P0 · 依赖 T-3,C-005 · 模块 budget
- [x] T-5: 边界/降级/回归用例固化（Case-1~11 + 空行程/budget≤0/对账不一致/路线缺失/门票null/餐饮null）+ 覆盖率 ≥80% · P0 · 依赖 T-4 · 模块 budget/skills

### 增量 B 任务（增量 A 交付后启动）

- [x] T-6（B-1 工具门面）: `BudgetCalculationTools`（`@Tool calculate_budget` 包确定性 `BudgetCalculationService`，不变量留 Java，`lastResult()` 捕获权威结果）+ `BudgetCalculationToolsTest` 确定性单测（AC-11）· P0 · 依赖 T-2,T-3 · 模块 skills
- [x] T-7（B-2/B-3 真实 Skill + 工厂）: `SKILL.md`（`ClasspathSkillRepository`，原生渐进式披露）+ `BudgetHarnessAgentFactory`（**主 deepseek-v4-pro / 降级 qwen3-max + env key，ADR-009**，复用 `LlmModelFactory`）+ `BudgetHarnessAgentFactoryIT` env-gated 集成测试 · P0 · 依赖 T-6 · 模块 skills
- [x] T-8（B-4 Agent 接缝）: `BudgetAgent` 注入 `BudgetAgentRunner`，原生路 + 确定性降级兜底 + `BudgetAgentNativeTest`（4 例：成功/异常降级/null 降级/null runner）；增量 A 回归不回退（AC-13/AC-14）· P0 · 依赖 T-6,T-7 · 模块 budget

## 流水线进度

### 增量 A — 确定性 BigDecimal 核算 + HITL 判定 + 降级

- [x] ① 需求分析（analyzing）— R1~R2 迭代
- [x] ② 编码实现（coding）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）
- [x] ④ 专家评审（reviewing，0 严重问题）→ [review.md](review.md)
- [x] ⑤ CI 门禁（ci，全绿）
- [x] ⑥ 部署验证（verifying）→ [verify.md](verify.md)
- [x] 交付（done，wiki 已同步）

### 增量 B — AgentScope 原生化（Budget 切片）

- [x] ① 需求分析（analyzing）— R2 规格已落档
- [x] ② 编码实现（coding）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）
- [x] ④ 专家评审（reviewing，0 严重问题）→ [review.md](review.md)
- [x] ⑤ CI 门禁（ci，全绿）
- [x] ⑥ 部署验证（verifying）→ [verify.md](verify.md)
- [x] 交付（done）
