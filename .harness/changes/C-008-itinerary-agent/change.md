---
id: C-008
slug: itinerary-agent
status: done
created: 2026-06-07
updated: 2026-06-14
owner: Owner Agent
---

# C-008 行程编排 Agent（ItineraryAgent · 增量交付）

> **修订记录**
> - **R1（2026-06-07）**：初版规格——用户故事 + AC + 边界 + 任务拆解。
> - **R2（2026-06-14）**：对齐 C-006/C-007 增量交付模式，拆为**增量 A（主链路确定性版本）+ 增量 B（AgentScope 原生化）**。增量 A 打通「候选景点 + 路线 → 按天分配 → 餐饮插入 → 时间槽排布 → TripDay 列表」端到端主链路，所有强规则由确定性 Java 强制；增量 B 对齐 ADR-008/009 把 ItineraryAgent 升级为 HarnessAgent + 真实 LLM + @Tool + Skill，业务不变量仍留 Java。
> - **R3（2026-06-14）**：餐饮推荐方案调研结论——美团/大众点评/饿了么均不对普通开发者开放（美团需等保+保证金+≥20 门店，大众点评已并入美团体系，饿了么注册入口关闭且以外卖履约为主）。**采用高德 POI 周边搜索 API（`place/around`）作主力数据源**，复用项目已有高德 AK（`AMAP_MAP_AK`，已在 C-007 使用）；菜系知识库（`CuisineKnowledgeBase`）作高德不可用时的降级兜底。详见 [餐饮推荐方案](#餐饮推荐方案)。

## 用户故事

作为 SupervisorAgent，我想要委派 ItineraryAgent 把候选景点、路线与餐饮编排成逐日行程（含时间槽、餐饮、动线），以便用户获得节奏合理、符合偏好、满足业务不变量的多日行程。

## 本次范围

本卡分两个增量交付，**真相同卡**，复用 C-005 治理能力与 C-006/C-007「薄 Agent + 厚 Skills + 端口化」验证模式。

### 增量 A — 主链路确定性版本（端口 + 桩 + 主逻辑 + 降级 + A2A）

> 对齐 C-006/C-007 增量 A：打通 **TASK_ASSIGN → 按天分配景点 → 插入餐饮 → 排布时间槽 → 交通耗时约束 → TASK_RESULT** 端到端主链路，餐饮推荐以端口（接口）+ 桩实现接入，单测用确定性桩验证契约。

- 做：`itinerary-design` 业务 Skill（按天分配景点、插入餐饮、排布时间槽、去重、时间窗 8:00–20:00 约束、交通耗时≤2h 约束）、`ItineraryAgent` A2A 接入（BaseAgent）、候选不足降级、TravelStyle 个性化策略。
- 端口化：`MealRecommender` 接口（按景点/风格推荐餐厅）+ 双实现——① `AmapMealRecommender`（主力：高德 POI 周边搜索 API，复用现有 `AMAP_MAP_AK`）；② `CuisineKnowledgeBase`（降级：菜系知识库 YAML，高德不可用时兜底）。单测以确定性桩验证契约。
- 结果对象 `ItineraryDesignResult`（days + 风险清单 + fallback + Telemetry），对齐 C-006 `XHSAnalysisResult` / C-007 `RoutePlanResult` 模式。

### 增量 B — AgentScope 原生化（Itinerary 切片）

> 目标：对齐 C-006 增量 C / C-007 增量 C 已验证的成功模式（ADR-008/009），把 ItineraryAgent 从纯确定性 BaseAgent 升级为 **HarnessAgent + 真实 LLM（主 deepseek-v4-pro / 降级 qwen3-max）+ 真实 Skill（`SKILL.md`）+ `@Tool` 暴露确定性编排能力**，业务硬不变量（每日午晚餐、时间窗 8–20、景点不重复、餐厅≤5km、耗时≤2h、评分≥3.5）**仍锁在确定性 Java（经 `@Tool` 暴露）**——LLM 负责理解委派意图、选择工具、编排调用顺序与结果解读，不裁决不变量。

- 做（B-1 工具门面）：`ItineraryDesignTools` 把确定性 `ItineraryDesignService`（编排主逻辑 + 降级）暴露为 `@Tool design_itinerary`，**所有强规则留 Java**，`lastResult()` 捕获权威结果。
- 做（B-2 真实 Skill）：`resources/skills/itinerary-design/SKILL.md`（frontmatter `name=itinerary_design`）经 `ClasspathSkillRepository` 加载——AgentScope 原生渐进式披露机制。Skill 描述行程编排任务的上下文、工具使用指引、输出格式期望。
- 做（B-3 原生 Agent 工厂）：`ItineraryHarnessAgentFactory implements ItineraryAgentRunner`，组装 LLM（**主 deepseek-v4-pro / 降级 qwen3-max + env key，ADR-009**）+ `Toolkit`（`ItineraryDesignTools`）+ Skill + `HarnessAgent`（无状态最小配置，不接 sandbox/Redis/subagent），`run()` 权威编排取自 `lastResult()`。
- 做（B-4 Agent 接缝）：`ItineraryAgent` 新增注入 `ItineraryAgentRunner` 的构造器，`onTaskAssign` 先走原生路、失败/无权威产物**降级回确定性直路**（`fallback=true`），不抛裸异常；旧构造器与增量 A 行为不动。
- **明确不做（拆走）**：`BaseAgent`/`Msg`/`AgentRegistry` 对其余 Agent 的全量原生化 → 独立地基卡（ADR-008 并存期收口）；真实 Nacos 注册 → C-011；真实 Redis 缓存 → C-015。

## 非目标（Out of Scope）

- 本次不做笔记分析与路线底层计算（消费 C-006/C-007 产出）。
- 本次不做费用统筹（归 C-009）。
- 本次不做最终对外装配与 HITL 暴露（归 Supervisor/Server）。
- 本次不实现 A2A/治理基座（复用 C-005）。
- 不接入真实 Redis——结果缓存以进程内内存承载（ADR-006），Redis 适配归 C-015。
- 不接入 Nacos 注册发现——经 C-005 `InMemoryAgentRegistry` 完成 in-process 路由，Nacos 归 C-011。
- 不做真实美团/大众点评/饿了么 API 对接——三大平台均不对普通开发者开放（美团需等保+保证金+≥20门店，大众点评已并入美团体系，饿了么注册入口关闭），以高德 POI 周边搜索作主力替代。
- 不做机票/酒店/城际交通预订。

## 验收标准（AC）

### 增量 A 验收标准

- AC-1: `ItineraryAgent` 继承 C-005 `BaseAgent`，标识 `agent://itinerary-designer`，可注册进 `AgentRegistry`；`receive` 仅受理 `TASK_ASSIGN(taskType=ITINERARY_DESIGN)`，非该类型走 BaseAgent 既有快速失败/`TASK_ERROR` 路径。
- AC-2: 正常路径返回 `TASK_RESULT`，`payload.result` 为 `ItineraryDesignResult`（含 `List<TripDay>` + 风险清单 + fallback），`traceId` 透传，`fallback=false`。
- AC-3: 实现 `itinerary-design` 业务 Skill（`ItineraryDesignService`）：按天分配景点（均匀分布 + 游玩时长在时间窗内），插入餐饮（午餐 + 晚餐 via `MealRecommender`，主力走高德 POI 周边搜索 + 降级走菜系知识库 `CuisineKnowledgeBase`），利用 C-007 路线产出排布相邻景点交通动线，装配 `TripDay{date, dayIndex, attractions, meals, routes, dailyCost}`。
- AC-4: 满足强规则（业务模型.md §4.2）：
  - ① 每个 TripDay 至少含午餐与晚餐；
  - ② 同一 plan 内景点不重复；
  - ③ 每日行程在 8:00–20:00 时间窗内（超出则减载/顺延，不强行塞入）；
  - ④ 餐厅距当日景点 ≤5km（放宽时显式标记风险，保证午晚餐不变量优先）；
  - ⑤ 相邻景点交通耗时 ≤2h（>2h 的不安排在同日相邻，拆分到不同天或剔除）；
  - ⑥ 推荐景点评分 ≥3.5（出现 <3.5 候选时剔除或标记触发 HITL）；
  - ⑦ 餐厅数据来源可追踪：高德 POI 结果标 `source=AMAP`，知识库降级标 `source=CUISINE_KB`，兜底餐标 `source=FALLBACK`。
- AC-5: 天数与 `request.days` 一致；若候选不足以填满则标记天数不一致风险（`DAYS_MISMATCH`），交 Gate/Supervisor 决策，不静默缩水。
- AC-6: 按 `TravelStyle`（FAMILY/COUPLE/SOLO/TEAM）调整编排策略（如亲子慢节奏/每日 ≤3 景点、团建大容量餐厅、情侣氛围餐厅、独行性价比优先），策略作参数化枚举驱动（圈复杂度 ≤10），不硬编码分支膨胀。
- AC-7: 可观测：经 C-005 `AgentMetrics`/`TraceContext` 发射 `traceId/planId?/agentId=itinerary-designer/taskType=ITINERARY_DESIGN/fallback` 及 `agent.call.latency`。

### 增量 B 验收标准（AgentScope 原生化 Itinerary 切片）

- AC-8（不变量留 Java）: 行程编排的六项强规则由确定性 `ItineraryDesignService`（经 `@Tool` 暴露为 `design_itinerary`）强制，**LLM 不得伪造/放宽行程数据**；`ItineraryDesignTools` 单测验证工具产物 == 确定性编排产物。
- AC-9（真实原生链路 + Skill）: 提供 `ItineraryHarnessAgentFactory`，经 `HarnessAgent` + 真实 LLM（**主 deepseek-v4-pro / 降级 qwen3-max，ADR-009**）+ `ClasspathSkillRepository` 加载 `SKILL.md`（原生渐进式披露，非废弃 `SkillBox`）跑通行程编排；API Key 走环境变量（`DEEPSEEK_API_KEY`/`DASHSCOPE_API_KEY`，禁硬编码，CI 密钥扫描 0 命中）；真实链路经 env-gated 集成测试验证（无 `DEEPSEEK_API_KEY` 自动 skip，不伪装通过）。
- AC-10（降级与回归不回退）: `ItineraryAgent` 注入 `ItineraryAgentRunner` 后，原生路失败/无权威产物 → 降级回确定性直路并 `fallback=true`、绝不抛裸异常；未注入 runner 时为纯确定性直路。增量 A 全部回归通过，`mvn clean verify` 全绿、覆盖率 ≥80%（HarnessAgent 工厂因真实 LLM/网络不可离线覆盖，JaCoCo 排除并登记）。
- AC-11（A2A 原生化接缝透明）: 原生化后 `ItineraryAgent` 对外 A2A 契约不变——`TASK_ASSIGN(taskType=ITINERARY_DESIGN)` 入、`TASK_RESULT(ItineraryDesignResult)` 出；`traceId` 透传；`fallback` 语义不变（确定性降级 + 原生路降级叠加标记）。Supervisor 无感知切换。

## 边界情况（≥3）

- 当候选景点数不足以满足 `request.days` 时，返回尽力方案并标记天数不一致风险（`DAYS_MISMATCH`），交 Gate 触发 HITL，不静默缩水。
- 当某日无法在 8:00–20:00 内容纳所有景点时，自动减载（移走游玩时长最长的末尾景点顺延到次日），不超时间窗。
- 当附近 5km 内无合适餐厅时，放宽距离策略并标记餐厅距离风险（保证「至少午晚餐」不变量优先于「≤5km」约束）。
- 当两景点交通 >2h 时，不安排在同日相邻（拆分到不同天或剔除），发现时记入风险清单。
- 当候选景点重复出现时，去重保留评分更高者。
- 当候选景点评分 <3.5 时，剔除并标记触发 HITL。
- 当 routes 输入为空/null（C-007 未产出有效路线）时，按景点顺序编排但不插入交通动线，标记 `fallback=true`。
- 当 `context` 含未知/新增字段时，忽略而非失败（向后兼容，A2A §2.3）。
- 当 `ItineraryAgentRunner` 为 null（未注入）时，`ItineraryAgent` 退化为纯确定性直路（增量 A 行为），不触发任何 LLM 调用。
- 当 HarnessAgent 原生路运行时 LLM 未调用 `design_itinerary` 工具（`lastResult()` 为 null）时，降级回确定性直路并 `fallback=true`，绝不以 LLM 自由文本作为行程数据。
- 当 `DEEPSEEK_API_KEY` 和 `DASHSCOPE_API_KEY` 均缺失时，`ItineraryHarnessAgentFactory.fromEnvironment` 返回 null（不构建原生 Agent），`ItineraryAgent` 自动走纯确定性直路。
- 当高德 POI API 超时/限频/返回空时，自动降级到 `CuisineKnowledgeBase`（菜系知识库），餐厅标 `source=CUISINE_KB`；知识库也缺失时插入兜底餐（"当地特色餐厅"），标 `source=FALLBACK`。
- 当高德 POI 返回的餐厅坐标与景点距离 >5km 时，按距离排序取最近者；全部 >5km 时降级到知识库。
- 当 `AMAP_MAP_AK` 未配置时，`AmapMealRecommender` 不可用，直接走知识库降级，不影响编排主链路。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 5 天行程编排 P95 < 8s（确定性直路，含高德 POI 调用）；真实 LLM 调用（增量 B）P95 < 15s |
| 可靠性 | 候选/餐厅不足时有降级编排策略（高德→知识库→兜底），不裸崩；失败必返回可识别 `TASK_ERROR` |
| 安全 | 不落盘；输出脱敏；LLM API Key 走环境变量（禁硬编码，CI 密钥扫描 0 命中） |
| 可观测 | `agent.call.latency`；不变量违规计数、HITL 触发计数 |
| 质量 | Agent/Skill 核心逻辑单测覆盖率 ≥80% |

## 设计约束

- `ItineraryAgent` 落在 `huazai-trip-agent-itinerary`；编排算法下沉 `huazai-trip-skills` 的 `itinerary` 包（禁依赖其他 Agent 模块）。
- 必须复用 C-005 既有交付：`BaseAgent`/`AgentReply`/`AgentId`/`Msg`/`GovernedExternalCaller`/`AgentMetrics`/`TraceContext`/`AgentRegistry`；不得复制治理逻辑。
- 必须复用 C-004 已定义的 `TripDay`/`Attraction`/`Meal`/`Route`/`MealType`/`TravelStyle`（零新增/零变更 common 模型字段契约）。
- 必须复用 C-006 候选与 C-007 路线，禁止在本模块重算路线或重抓笔记。
- 时间槽/餐饮/动线等强规则在本 Agent 内部优先约束；全局一致性最终由 `OutputQualityGate` 复核。
- 个性化（TravelStyle）作为编排策略参数，不硬编码分支膨胀（圈复杂度 ≤10）。
- 端口声明（skills 模块）：`MealRecommender`（按景点+风格推荐餐厅）；主力实现 `AmapMealRecommender`（高德 POI 周边搜索，复用 C-007 已有 `AMAP_MAP_AK` 和 HTTP 通道）；降级实现 `CuisineKnowledgeBase`（菜系知识库，`resources/cuisine/*.yml`）；桩实现保留为契约基线测试替身。

### 增量 B 设计约束

- `ItineraryDesignTools`（`@Tool` 工具门面）与 `ItineraryHarnessAgentFactory` 落在 `huazai-trip-skills` 的 `itinerary` 包（与增量 A 端口/Skill 同层）。`ItineraryAgentRunner` 接缝接口亦落 `itinerary` 包。
- 真实 Skill `SKILL.md` 放 `huazai-trip-skills/src/main/resources/skills/itinerary-design/SKILL.md`，经 `ClasspathSkillRepository` 加载。
- `ItineraryAgent`（itinerary 模块）通过 `ItineraryAgentRunner` 接缝解耦原生路——单测可注入桩（确定性、无网络），真实实现 `ItineraryHarnessAgentFactory` 经 env key 接 LLM。
- 对齐 C-006/C-007 增量 C 模式：LLM 做编排（理解意图 → 调用 `design_itinerary` 工具 → 解读结果），确定性核心做裁决（不变量/降级由 Java 强制）。
- `ItineraryHarnessAgentFactory.fromEnvironment` 双模型组装逻辑复用 C-006/C-007 同模式（`LlmModelFactory`：主 deepseek → 降级 qwen → 均缺失返回 null）。
- JaCoCo 排除 `ItineraryHarnessAgentFactory`（因真实 LLM/网络不可离线覆盖），登记于覆盖率豁免清单。

## 契约影响

- REST: 无（经 Supervisor 编排）。
- A2A: 新增 `taskType=ITINERARY_DESIGN` 的 `TASK_ASSIGN` payload: `context={request: TripPlanRequest, candidateAttractions: List<AttractionCandidate>, routes: RoutePlanResult}` 与 `TASK_RESULT`: `result=ItineraryDesignResult`，复用 `Msg` 既有信封（向后兼容）。
- 数据模型: 复用 `TripDay`、`Attraction`、`Meal`、`Route`、`MealType`、`TravelStyle`、`TripPlanRequest`（C-004 已交付，零新增/零变更）。
- Skill 端口（skills 模块，本卡新增）: `MealRecommender`（按景点+风格推荐餐厅，主力 `AmapMealRecommender` + 降级 `CuisineKnowledgeBase`）；`ItineraryDesignResult`（days + 风险清单 + fallback + Telemetry）；`ItineraryDesignQuery`（封装请求 + 候选 + 路线）。
- 数据资源: `resources/cuisine/*.yml`（10-15 个热门目的地的菜系知识库，含菜系、经典菜品、人均价格带）。
- Redis / ReMe: `trip:agent:result:{planId}:itinerary` 结果缓存键（本卡不新增 Redis 写入，进程内内存承载）。
- 增量 B 新增:
  - Skill 端口: `ItineraryAgentRunner`（`@FunctionalInterface`，接缝接口，Itinerary 的 `XHSAgentRunner`/`RouteAgentRunner` 等价物）。
  - 工具门面: `ItineraryDesignTools`（`@Tool design_itinerary` 暴露确定性 `ItineraryDesignService`，`lastResult()` 捕获权威结果）。
  - 工厂: `ItineraryHarnessAgentFactory implements ItineraryAgentRunner`（双模型组装，复用 `LlmModelFactory`）。
  - Skill 资源: `resources/skills/itinerary-design/SKILL.md`（AgentScope 原生渐进式披露）。
  - A2A 信封: **不变**——`TASK_ASSIGN/TASK_RESULT` 信封、payload 结构、`traceId/fallback` 语义完全保留，Supervisor 无感知。

## 影响面

- 模块 / Agent / Skill: `huazai-trip-agent-itinerary`（Agent + 增量 B 原生接缝）、`huazai-trip-skills/itinerary`（Skill + 端口 + 增量 B 工具门面/工厂/Skill 资源）；common 零改动。
- 外部 API: LLM（编排/餐厅推荐增强——增量 B，经 C-005 统一治理）；高德 POI 周边搜索 API（`restapi.amap.com/v3/place/around`，复用 C-007 已配置的高德 AK 和 HTTP 通道）；间接复用地图/笔记产出（消费 C-006/C-007 结果，不直接调用外部 API）。
- wiki: 对齐 `业务模型.md`(§4 不变量/§5 风格)、`数据模型.md`(§2.3 TripDay)、`接口协议.md`(§2 A2A ITINERARY_DESIGN)。

## 规则归属

- 业务不变量归属: 每日午晚餐/时间窗 8–20/景点不重复/餐厅就近≤5km/交通耗时≤2h → Itinerary 内部优先；全局复核 → OutputQualityGate；评分<3.5、天数不一致 → 发现并标记，HITL 由 Gate/Server 暴露。
- 外部调用治理归属: LLM 调用（增量 B）走 C-005 统一治理，不自建治理。
- 可观测性要求: `traceId/planId?/agentId=itinerary-designer/taskType=ITINERARY_DESIGN/fallback`。

## 测试策略

### 增量 A 测试策略

- 先写失败测试（Red）: 每日至少午晚餐、景点不重复、时间窗 8–20、餐厅≤5km、相邻耗时≤2h、天数一致、评分≥3.5 → 全部先红。
- Happy Path: 充足候选（5 天 15 景点 + 完整路线）→ days==request.days，全部强规则满足，每天含午晚餐 + 景点 + 路线。
- 边界测试: 候选不足（仅够 3 天但请求 5 天）、时间窗溢出（景点总游玩时长超 12h）、无近距餐厅（放宽并保午晚餐）、耗时超限拆分、重复景点去重、routes 为空/null。
- 降级测试: 高德 POI API 超时/限频/返回空 → 自动降级到 `CuisineKnowledgeBase` + `source=CUISINE_KB`；知识库缺城市 → 兜底餐 + `source=FALLBACK` + `fallback=true`；`AMAP_MAP_AK` 未配置 → 直接走知识库。
- 数据来源测试: `AmapMealRecommender` 返回结果验证 `source=AMAP` + 餐厅名/坐标/人均非空 + 距离 ≤5km；`CuisineKnowledgeBase` 降级结果验证 `source=CUISINE_KB` + 菜系匹配置信度。
- TravelStyle 测试: FAMILY → 每日 ≤3 景点、TEAM → 大容量标记、SOLO → 性价比优先排序。
- 回归测试: 全部强规则纳入回归，防漂移；BaseAgent 收发契约联测。

### 增量 B 测试策略

- 先写失败测试（Red）: `ItineraryDesignTools` `@Tool` 产物 == 确定性 `ItineraryDesignService` 产物（AC-8）、`ItineraryAgent` 原生路失败降级回确定性直路 + `fallback=true`（AC-10）、`ItineraryAgent` LLM 未调用工具降级（边界）→ 先红。
- 工具门面测试（`ItineraryDesignToolsTest`）: 确定性桩输入 → `design_itinerary` 产出 `ItineraryDesignResult` 与直接调 `ItineraryDesignService` 产出完全一致；`lastResult()` 正确捕获；强规则行为由底层确定性 Service 保证，工具门面不重复验证。
- Agent 原生接缝测试（`ItineraryAgentNativeTest`）: ① 注入成功桩 runner → 原生产物 == 确定性产物，`fallback=false`；② 注入异常桩 runner → 降级确定性 + `fallback=true`；③ 注入 null-result 桩 runner → 降级确定性 + `fallback=true`；④ runner 为 null → 纯确定性直路（增量 A 行为）。
- env-gated 集成测试（`ItineraryHarnessAgentFactoryIT`）: 有 `DEEPSEEK_API_KEY` → 真实 LLM 跑通行程编排 Skill，验证 `HarnessAgent` + `@Tool` + `SKILL.md` 端到端；无 key → `@Disabled` skip，不伪装通过。
- 回归: 增量 A 全量回归不回退。

## 餐饮推荐方案

> **R3 新增（2026-06-14）**：经调研确认美团/大众点评/饿了么均不对普通开发者开放（见下表），采用高德 POI 周边搜索作主力替代。详细调研见下文。

### 平台可用性调研结论

| 平台 | 状态 | 原因 |
|---|---|---|
| 美团开放平台 | ❌ 不可用 | 仅限自研开发者（≥20 门店 + 软著 + 商标）或服务商（等保 + 保证金），无普通开发者入口 |
| 大众点评开放平台 | ❌ 实际不可用 | 2013 年曾开放，被美团收购后已并入美团体系，受同一套入驻门槛限制 |
| 饿了么开放平台 | ❌ 不适合 | 注册入口疑似关闭；API 模型为外卖履约链路（搜餐厅→下单→配送），非推荐评价场景 |
| **高德 POI 周边搜索** | ✅ 可用 | 个人开发者免费注册，日 5000 次调用，`place/around` API 完全覆盖需求 |

### 分层架构

```
MealRecommender (接口，skills/itinerary 包)
    │
    ├── AmapMealRecommender (主力，增量 A 交付)
    │   ├── 高德 place/around API (types=050000 餐饮)
    │   ├── 复用 C-007 已有 AMAP_MAP_AK + HTTP 通道
    │   └── 产物标 source=AMAP
    │
    └── CuisineKnowledgeBase (降级，增量 A 交付)
        ├── resources/cuisine/*.yml (10-15 个热门目的地)
        ├── 高德不可用时自动切换
        └── 产物标 source=CUISINE_KB 或 source=FALLBACK
```

### 高德 POI 周边搜索 API

**端点**：`GET https://restapi.amap.com/v3/place/around`

**请求参数**：

| 参数 | 值 | 说明 |
|---|---|---|
| `key` | `AMAP_MAP_AK` | 复用 C-007 高德 Key |
| `location` | `景点.lng,景点.lat` | 搜索中心点 |
| `radius` | `5000` | 半径 5000m（对齐 ≤5km 约束） |
| `types` | `050000` | 餐饮服务大类 |
| `keywords` | 按 TravelStyle 映射 | COUPLE→火锅\|私房菜, FAMILY→家常菜\|粤菜, etc. |
| `offset` | `20` | 每页返回数 |
| `extensions` | `all` | 返回评分、人均、图片等扩展信息 |

**返回字段**（与 `Meal` 模型的映射）：

| 高德字段 | Meal 字段 | 说明 |
|---|---|---|
| `name` | `name` | 餐厅名称 |
| `location` | `location` | 经纬度坐标（用于计算 distanceKm） |
| `biz_ext.rating` | —（暂存扩展属性） | 高德用户评分 |
| `biz_ext.cost` | `avgPrice` | 人均消费 |
| `type` | —（映射菜系分类） | 如"川菜""火锅"→ 匹配 TravelStyle 过滤 |
| `address` | —（存 location.address） | 详细地址 |

### 菜系知识库（降级 + 离线可用）

**位置**：`huazai-trip-skills/src/main/resources/cuisine/`

**文件结构**（YAML）：

```yaml
# chengdu.yml
city: 成都
cuisines:
  - name: 川菜
    avgPriceRange: [40, 120]
    classicDishes: [麻婆豆腐, 回锅肉, 水煮鱼, 宫保鸡丁, 担担面]
    tags: [麻辣, 家常, 市井]
    mealPreference: { 午: [简餐, 面食], 晚: [正餐, 火锅] }
  - name: 火锅
    avgPriceRange: [80, 200]
    classicDishes: [毛肚火锅, 鸳鸯锅, 串串香]
    tags: [麻辣, 聚餐, 热闹]
    mealPreference: { 午: [简餐], 晚: [正餐, 聚餐] }
```

**降级触发链**：
1. 高德 API 超时（>5s）→ 降级到知识库
2. 高德 API 返回空（周边无餐厅）→ 降级到知识库
3. 高德 API 限频（QPS 超限）→ 降级到知识库
4. `AMAP_MAP_AK` 未配置 → 直接走知识库
5. 知识库缺城市 → 兜底中式家常菜 + `source=FALLBACK`

### 确定性生成规则（知识库模式）

当走知识库降级时，餐厅生成可复现（同输入→同输出）：

1. `deterministicSeed(attraction.name, mealType, style)` → 固定随机种子
2. 从该城市菜系池采样 3-5 个候选餐厅
3. 餐厅名 = `[景点区域]·[菜系名]·[风格后缀]`
4. 坐标 = `attraction.location` + 种子偏移（<0.05°，保证 ≤5km）
5. 人均 = 城市基准价 × 风格系数
6. `recommendDishes` = 从经典菜品列表按 mealType 采样 2-4 道

### TravelStyle 个性化过滤

| 风格 | 高德 keywords 映射 | 知识库过滤偏好 |
|---|---|---|
| FAMILY | 家常菜\|粤菜\|江浙菜 | 排除重辣/纯酒标签，环境评分加权 |
| COUPLE | 私房菜\|西餐\|日料 | "观景""露台""私房"后缀提权，人均上浮可接受 |
| SOLO | 小吃\|面馆\|快餐 | 人均 >80 降权，"一人食""小吃"提权 |
| TEAM | 火锅\|烧烤\|大排档 | 大桌/包厢优先，菜量足 |

### 设计决策

| 决策 | 结论 | 理由 |
|---|---|---|
| 高德 API 是否替代桩实现？ | 是，作主力 | 真实 POI 数据（非随机生成），复用已有 AK，零额外注册成本 |
| 知识库是否仍需要？ | 是，作降级 | 高德超时/限频/无 AK 时保证链路不断；离线开发/测试无需外部依赖 |
| Meal 是否增加 `source` 字段？ | 是 | 方便前端展示数据可信度 + 未来混合模式去重合并 |

## 验收用例

### 增量 A 用例

- Case-1: `ITINERARY_DESIGN(成都, 5天, 15 候选景点, 14 段路线)` → 5 个 TripDay，每日含午晚餐、景点不重复、时间在 8–20、`fallback=false`。
- Case-2: 候选仅够 3 天的量但 `request.days=5` → 3 个 TripDay + 标记 `DAYS_MISMATCH` 风险，触发 HITL。
- Case-3: 两景点交通耗时 130min → 不安排在同日相邻，拆到不同天。
- Case-4: `TravelStyle=FAMILY` → 每日 ≤3 景点、节奏更慢。
- Case-5: 附近 5km 无合适餐厅 → 放宽距离 + 标记餐厅距离风险，但保证午晚餐不缺。
- Case-6: 候选含评分 2.8 景点 → 剔除，标记触发 HITL。
- Case-7: 投递 `taskType=ROUTE_PLANNING` → 快速失败返回 `TASK_ERROR`。
- Case-8: routes 为空 → 按景点顺序编排但无交通动线，`fallback=true`。
- Case-9: `AmapMealRecommender` 正常返回成都 5km 内餐饮 → 午餐+晚餐均为真实餐厅名/坐标/人均，`source=AMAP`，`fallback=false`。
- Case-10: `AMAP_MAP_AK` 未配置 → `AmapMealRecommender` 不可用，降级走 `CuisineKnowledgeBase`，餐厅 `source=CUISINE_KB`。
- Case-11: 高德 API 超时 + 知识库缺城市 → 兜底餐 `source=FALLBACK` + `fallback=true`，但午晚餐不缺失。

### 增量 B 用例

- Case-12: 注入 `ItineraryAgentRunner`，LLM 正常调用 `design_itinerary` 工具 → `TASK_RESULT` 产出 == 确定性 `ItineraryDesignService` 产出，`fallback=false`；行程数据来自确定性 Java 而非 LLM 自由文本。
- Case-13: 注入 `ItineraryAgentRunner`，LLM 运行失败（超时/异常）→ 降级回确定性直路 → `TASK_RESULT(fallback=true)`，不抛裸异常，行程数据正常产出。
- Case-14: 注入 `ItineraryAgentRunner`，LLM 未调用工具（`lastResult()` 为 null）→ 降级回确定性直路 → `TASK_RESULT(fallback=true)`。
- Case-15: 未注入 `ItineraryAgentRunner`（null）→ 纯确定性直路（增量 A 行为），Case-1~11 全部回归通过。
- Case-16: `DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY` 均缺失 → `ItineraryHarnessAgentFactory.fromEnvironment` 返回 null，应用正常，走纯确定性直路。

## 任务拆解（≤1 天/项，DAG 无环）

### 增量 A 任务

- [x] T-1: skills `itinerary` 包端口（`MealRecommender` 接口 + `AmapMealRecommender` 主力实现 + `CuisineKnowledgeBase` 降级实现 + `resources/cuisine/*.json` 知识库）+ 查询/结果对象（`ItineraryDesignQuery`/`ItineraryDesignResult`/`ItineraryRisk`）+ 桩实现 + TravelStyle 策略枚举（`ScheduleProfile`）· P0 · 依赖 C-004,C-005,C-007(高德 AK 复用) · 模块 skills
- [x] T-2: 编排主逻辑 `ItineraryDesignService`（按天分配景点 + 去重 + 评分≥3.5 筛选 + 时间窗 8–20 + 交通耗时≤2h 约束）+ 强规则先红测试 · P0 · 依赖 T-1 · 模块 skills
- [x] T-3: 餐饮插入（午晚餐保证 + ≤5km + `MealRecommender` 端口调用 + 放宽降级）+ 餐饮强规则测试 · P0 · 依赖 T-2 · 模块 skills
- [x] T-4: TravelStyle 个性化策略（FAMILY/COUPLE/SOLO/TEAM 参数化编排节奏调整）+ 风格测试 · P1 · 依赖 T-2 · 模块 skills
- [x] T-5: `ItineraryAgent` A2A 接入（接收 Msg、注册 `agent://itinerary-designer`、context 解析 + 候选不足降级 + 可观测派发）+ 收发契约测试 · P0 · 依赖 T-3,C-005 · 模块 itinerary
- [x] T-6: 边界/回归用例固化（Case-1~16 全量 + 强规则回归 + Amap→KB→FALLBACK 降级链）+ 覆盖率 ≥80% · P0 · 依赖 T-5 · 模块 itinerary/skills

### 增量 B 任务（增量 A 交付后启动）

- [x] T-7（B-1 工具门面）: `ItineraryDesignTools`（`@Tool design_itinerary` 包确定性 `ItineraryDesignService`，不变量留 Java，`lastResult()` 捕获权威结果）+ `ItineraryDesignToolsTest` 确定性单测 8 例（AC-8）· P0 · 依赖 T-2,T-3 · 模块 skills
- [x] T-8（B-2/B-3 真实 Skill + 工厂）: `SKILL.md`（`ClasspathSkillRepository`，原生渐进式披露）+ `ItineraryAgentRunner` 接缝接口（已交付）+ `ItineraryHarnessAgentFactory`（**主 deepseek-v4-pro / 降级 qwen3-max + env key，ADR-009**，复用 `LlmModelFactory`）· P0 · 依赖 T-7 · 模块 skills
- [x] T-9（B-4 Agent 接缝）: `ItineraryAgent` 注入 `ItineraryAgentRunner`，原生路 + 确定性降级兜底 + `ItineraryAgentNativeTest`（5 例：成功/异常降级/null 降级/null runner/未知字段）；增量 A 回归不回退 + RouteAgent 同步去 BaseAgent 重构回归（AC-10/AC-11）· P0 · 依赖 T-7,T-8 · 模块 itinerary

## 流水线进度

### 增量 A — 主链路确定性版本

- [x] ① 需求分析（analyzing）— R1~R3 迭代
- [x] ② 编码实现（coding）— T-1~T-6 全部交付 + RouteAgent 同步去 BaseAgent 重构
- [x] ③ 单测编写（testing，覆盖率 skills ~94.9% / agent ~92.8% ≥80%）
- [x] ④ 专家评审（reviewing，0 严重问题）→ [review.md](review.md)
- [x] ⑤ CI 门禁（ci，全绿）— 517 tests / Checkstyle 0 / PMD 0
- [x] ⑥ 部署验证（verifying）→ [verify.md](verify.md)
- [x] 交付（done，wiki 已同步）

### 增量 B — AgentScope 原生化（Itinerary 切片）

- [x] ① 需求分析（analyzing）— R2 规格已落档
- [x] ② 编码实现（coding）— T-7 ItineraryDesignTools + T-8 SKILL.md + ItineraryHarnessAgentFactory + T-9 Agent 接缝全部交付
- [x] ③ 单测编写（testing，覆盖率 ≥80%，ItineraryDesignToolsTest 8 例 + 全量回归 562 tests）
- [x] ④ 专家评审（reviewing，0 严重问题）→ [review.md](review.md)
- [x] ⑤ CI 门禁（ci，全绿）— 562 tests / Checkstyle 0 / PMD 0
- [x] ⑥ 部署验证（verifying）→ [verify.md](verify.md)
- [x] 交付（done，T-7~T-9 全部完成）
