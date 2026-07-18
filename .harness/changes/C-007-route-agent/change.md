---
id: C-007
slug: route-agent
status: done
created: 2026-06-07
updated: 2026-06-13
owner: Owner Agent
---

# C-007 路线规划 Agent（RouteAgent · 增量交付）

> **修订记录**
> - **R1（2026-06-07）**：初版规格——主链路 + 百度地图 MCP。
> - **R2（2026-06-10）**：两轮审批——① C-018 撤销并入本卡（真实 MCP）；② 高德同卡 + 智能切换。重析完成。
> - **R3（2026-06-12）**：按 C-006 成功模式拆为**增量 A/B** 分阶段交付；对齐 ADR-008/009 原生化方向（RouteAgent 的 HarnessAgent 原生化归后续增量或独立地基卡，本卡增量 A/B 沿 BaseAgent 交付）。
> - **R4（2026-06-13）**：新增**增量 C「AgentScope 原生化（Route 切片）」**——对齐 C-006 增量 C 成功模式（ADR-008/009），把 RouteAgent 从纯确定性 BaseAgent 升级为 **HarnessAgent + 真实 LLM（主 deepseek-v4-pro / 降级 qwen3-max）+ 真实 Skill（`SKILL.md`）+ `@Tool` 暴露确定性路线规划能力**，业务硬不变量（耗时≤120min 标记、三类降级、限频≤30/min）仍锁在确定性 Java。**已审批并完成交付**。

## 用户故事

作为 SupervisorAgent，我想要委派 RouteAgent 基于候选景点的地理位置规划点间交通路线（距离/耗时/方式），以便行程编排（C-008）能在交通合理（耗时 ≤2h）的前提下排布每日动线。

## 本次范围

本卡分两个增量交付，**真相同卡**，复用 C-005 治理能力与 C-006「薄 Agent + 厚 Skills + 端口化」验证模式。

### 增量 A — 主链路桩版本（端口 + 桩 + 主逻辑 + 降级 + A2A）✅ 已交付

> 对齐 C-006 增量 A：打通 **TASK_ASSIGN → 坐标解析 → 相邻点路线 → 超限标记 → TASK_RESULT** 端到端主链路，地图工具以端口（接口）+ 桩实现接入，单测用确定性桩验证契约。

- 做：`route-planning` 业务 Skill（坐标解析、相邻点路线、haversine 离线估算、≤120min 超限标记）、`RouteAgent` A2A 接入（BaseAgent）、三类降级（地理编码/路线/路况）、限频 `map ≤30/min` 治理接入。
- 端口化（定义接口 + 桩实现作测试替身）：`MapGeocoder`（地理编码）、`MapRouter`（点间路线）、`MapTraffic`（路况，可选增强）、`PresetCoordinateCatalog`（预置坐标降级源）。
- 结果对象 `RoutePlanResult`（routes + 超限清单 + 不可达说明 + Telemetry），对齐 C-006 `XHSAnalysisResult` 模式。

### 增量 B — 真实双厂商 HTTP + 高德 MCP 主用 + 智能切换（R2 审批并入）✅ 已交付

> 目标：把端口的桩实现替换为真实百度 + 高德 HTTP 直连适配，并实现厂商智能切换（健康度驱动故障转移）。增量 A 桩实现保留为契约基线与测试替身。**高德地图官方 MCP Server（AgentScope 原生 McpClientBuilder）作为主路，HTTP 直连作为降级层**（MCP 不可用时自动切换 HTTP，双通道均不可用时离线降级）。

- 做（B-1 HTTP 基建）：`MapHttpExecutor`/`MapHttpClient` 直连百度 + 高德 REST API + 进程内坐标 TTL 缓存（`GeocodingCache`）。
- 做（B-2 百度三工具映射）：`BaiduGeocoder`/`BaiduRouter`/`BaiduTraffic` → `MapGeocoder`/`MapRouter`/`MapTraffic` 端口实现（HTTP 直连，无 MCP）。
- 做（B-3 高德 MCP 主用）：`AmapMcpClient` 封装高德 MCP Server（`https://mcp.amap.com/sse?key=xxx`），实现 `MapGeocoder`/`MapRouter`/`MapTraffic`，**MCP 为主路**。
- 做（B-4 高德 HTTP 降级）：`AmapGeocoderHttp`/`AmapRouterHttp`/`AmapTrafficHttp` 实现同端口，作为 MCP 的**第二降级层**。
- 做（B-5 厂商智能切换）：failover 组合实现 + 双通道熔断（`mcpHealth` + `httpHealth`）+ 主选配置 + 单 AK 模式。
- ~~仍延后：AgentScope 原生 HarnessAgent 入口（归独立地基卡或后续增量，对齐 ADR-008 并存期收口）。~~
  **R4 修订**：不再延后，增量 C 已纳入本卡并完成交付。

> **审批决议沿革（2026-06-10，两轮）**: ① C-018 撤销并入本卡；② 高德同卡 + 智能切换；③ Nacos 归 C-011。

### 增量 C — AgentScope 原生化（Route 切片，R4 新增）

> 目标：对齐 C-006 增量 C 已验证的成功模式（ADR-008/009），把 RouteAgent 从「C-005 BaseAgent + 纯确定性」升级为**真正运行 AgentScope 原生链路**——Agent 入口用 `HarnessAgent` + 真实 LLM（**主 deepseek-v4-pro / 降级 qwen3-max**）+ 真实 Skill（`SKILL.md`）+ `@Tool` 暴露确定性路线规划能力。业务硬不变量（耗时≤120min 标记、三类降级、限频≤30/min、haversine 离线估算）**仍锁在确定性 Java（经 `@Tool` 暴露）**——LLM 负责理解委派意图、选择工具、编排调用顺序与结果解读，不裁决不变量。

- 做（C-1 工具门面）：`RoutePlanningTools` 把确定性 `RoutePlanningService`（路线规划主逻辑 + 降级 + 超限标记）暴露为 `@Tool plan_routes`，**耗时≤120min 标记、三类降级、限频≤30/min 留 Java**，`lastResult()` 捕获权威结果。
- 做（C-2 真实 Skill）：`resources/skills/route-planning/SKILL.md`（frontmatter `name=route_planning`）经 `ClasspathSkillRepository` 加载——AgentScope 原生渐进式披露机制（非废弃 `SkillBox`）。Skill 描述路线规划任务的上下文、工具使用指引、输出格式期望。
- 做（C-3 原生 Agent 工厂）：`RouteHarnessAgentFactory implements RouteAgentRunner`，组装 LLM（**主 deepseek-v4-pro / 降级 qwen3-max + env key，ADR-009**）+ `Toolkit`（`RoutePlanningTools`）+ Skill + `HarnessAgent`（无状态最小配置，不接 sandbox/Redis/subagent），`run()` 权威路线取自 `lastResult()`。
- 做（C-4 Agent 接缝）：`RouteAgent` 新增注入 `RouteAgentRunner` 的构造器，`onTaskAssign` 先走原生路、失败/无权威产物**降级回确定性直路**（`fallback=true`），不抛裸异常；旧构造器与增量 A/B 行为不动。
- **明确不做（拆走）**：`BaseAgent`/`Msg`/`AgentRegistry` 对其余 Agent 的全量原生化 → 独立地基卡（ADR-008 并存期收口）；真实 Nacos 注册 → C-011；真实 Redis 缓存 → C-015。

## 非目标（Out of Scope）

- 不做每日行程编排/时间槽/动线优化排序（归 C-008；本 Agent 只按给定访问序产出相邻点路线并标记超限）。
- 不做实时导航；实时路况仅作可选增强，不可用时静默忽略。
- 不实现 A2A 基座/统一治理/QualityGate（复用 C-005 既有交付）。
- 不做双厂商结果合并/比价/一致性仲裁——一次调用仅经一个厂商产出，切换只为可用性。
- 不接入真实 Redis——坐标缓存以进程内内存 TTL 缓存落本卡（ADR-006），Redis 适配不在本卡。
- 不接入 Nacos 注册发现——经 C-005 `InMemoryAgentRegistry` 完成 in-process 路由，Nacos 归 C-011。
- ~~不做 HarnessAgent 原生化——本卡沿 BaseAgent 交付，原生化归独立地基卡（ADR-008 并存期收口）。~~ **R4 修订**：增量 C 已纳入本卡，Route 切片做 HarnessAgent 原生化。但 BaseAgent/Msg/AgentRegistry 的全量原生化仍归独立地基卡。
- 不做机票/城际交通预订。

## 验收标准（AC）

### 增量 A 验收标准

- AC-1: `RouteAgent` 继承 C-005 `BaseAgent`，标识 `agent://route-planner`，可注册进 `AgentRegistry`；`receive` 仅受理 `TASK_ASSIGN(taskType=ROUTE_PLANNING)`，非该类型走 BaseAgent 既有快速失败/`TASK_ERROR` 路径。
- AC-2: 正常路径返回 `TASK_RESULT`，`payload.result` 为按输入访问序的相邻点 `Route` 列表（字段对齐数据模型 §2.4），`traceId` 透传，`fallback=false`。
- AC-3: 实现 `route-planning` 业务 Skill：`Attraction.location` 已有 → 直接用；缺失 → 经 `MapGeocoder` 端口解析 → 相邻两点经 `MapRouter` 端口取距离/耗时/方式 → 装配 `Route`。`transportMode` 取值对齐前端契约（步行/驾车/骑行/公交），离线估算缺省"驾车"。
- AC-4: 交通合理不变量（业务模型 §4 强规则）：`durationMin > 120` 的路线**保留并标记**于 `RoutePlanResult` 超限清单（不静默剔除、不擅自拆分），供 C-008/QualityGate 决策。
- AC-5: 三类降级齐备且互不阻塞主链路：① 地理编码失败 → `PresetCoordinateCatalog` 预置坐标，该段 `fallback=true`；② 路线服务失败 → haversine 直线距离 + 平均速度推算耗时，`fallback=true`；③ 路况不可用 → 忽略路况用平均速度，不标记失败。预置坐标也无法兜底：跳过该段 + 结果对象记录不可达说明。
- AC-6: 外部地图调用统一经 C-005 `GovernedExternalCaller` 治理：限频 `map ≤30/min`（`RateLimiter.API_MAP`）、超时 30s、重试 3 次（退避 1/2/4s）；限频命中短路降级，绝不超频。
- AC-7: 可观测：经 C-005 `AgentMetrics`/`TraceContext` 发射 `traceId/planId?/agentId=route-planner/taskType=ROUTE_PLANNING/fallback` 及 `agent.call.latency`、`api.rate_limit.hit{api=map}`。

### 增量 B 验收标准（待增量 A 交付后审批）

- AC-8: route 模块 `mcp` 包交付**百度 HTTP 直连 + 高德双通道**：百度为 HTTP 直连（`BaiduGeocoder`/`Router`/`Traffic`）；高德为 MCP 主用 + HTTP 降级（`AmapMcpClient` + `AmapGeocoderHttp`/`RouterHttp`/`TrafficHttp`）；各厂商 AK 仅经独立环境变量注入（禁硬编码，CI 密钥扫描 0 命中）；全部调用经 AC-6 治理链；进程内坐标 TTL 缓存降低重复地理编码调用量。
- AC-9: **厂商 + 通道智能切换**：同端口的 failover 组合实现，业务层对通道无感知。语义：(a) 百度默认主选；高德 MCP 为主通道，HTTP 为降级通道；(b) 主通道单次调用失败 → 自动切换降级通道完成当次请求（切换产出为真实数据，不标记 fallback，记录 `vendor.switch{from=mcp,to=http}`）；(c) 连续失败达阈值 → 熔断标记不健康并冷却，冷却结束半开探测恢复；(d) 仅配置一个 AK → 单厂商模式；(e) 双厂商均不可用/AK 全缺失 → 落 AC-5 离线降级链（`fallback=true`）；(f) 限频命中不触发切换（共用总额度 `API_MAP`）→ 直接走离线降级；(g) 切换/熔断/恢复有可识别日志与指标。
- AC-10: 向后兼容与回归不回退：增量 A 全部 AC-1~AC-7、Case-1~6 回归通过；`mvn clean verify` 全绿、覆盖率 ≥80%。

### 增量 C 验收标准（R4 新增，AgentScope 原生化 Route 切片）

- AC-11（不变量留 Java）: Route 规划的耗时≤120min 超限标记、三类降级（地理编码/路线/路况）、限频≤30/min、haversine 离线估算由确定性 `RoutePlanningService`（经 `@Tool` 暴露为 `plan_routes`）强制，**LLM 不得伪造/放宽路线数据**；`RoutePlanningTools` 单测验证工具产物 == 确定性规划产物。
- AC-12（真实原生链路 + Skill）: 提供 `RouteHarnessAgentFactory`，经 `HarnessAgent` + 真实 LLM（**主 deepseek-v4-pro / 降级 qwen3-max，ADR-009**）+ `ClasspathSkillRepository` 加载 `SKILL.md`（原生渐进式披露，非废弃 `SkillBox`）跑通路线规划；API Key 走环境变量（`DEEPSEEK_API_KEY`/`DASHSCOPE_API_KEY`，禁硬编码，CI 密钥扫描 0 命中）；真实链路经 env-gated 集成测试验证（无 `DEEPSEEK_API_KEY` 自动 skip，不伪装通过）。
- AC-13（降级与回归不回退）: `RouteAgent` 注入 `RouteAgentRunner` 后，原生路失败/无权威产物 → 降级回确定性直路并 `fallback=true`、绝不抛裸异常；未注入 runner 时为纯确定性直路。增量 A/B 全部回归通过，`mvn clean verify` 全绿、覆盖率 ≥80%（HarnessAgent 工厂因真实 LLM/网络不可离线覆盖，JaCoCo 排除并登记）。
- AC-14（A2A 原生化接缝透明）: 原生化后 `RouteAgent` 对外 A2A 契约不变——`TASK_ASSIGN(taskType=ROUTE_PLANNING)` 入、`TASK_RESULT(RoutePlanResult)` 出；`traceId` 透传；`fallback` 语义不变（确定性降级 + 原生路降级叠加标记）。Supervisor 无感知切换。

## 边界情况（≥3）

- 当输入景点数 ≤1（含 attractions 缺失/空列表）时，返回空 `Route` 列表 + 可读说明，`fallback=false`，不报错。
- 当某景点无 location 且地理编码失败时，降级预置坐标并标记该段 `fallback=true`；预置坐标也缺失 → 跳过该段 + 不可达说明，不抛裸异常。
- 当地图调用触发限频（≤30/min）时，`GovernedExternalCaller` 短路 `RATE_LIMITED`，该段走离线估算 `fallback=true`，断言外部调用次数不超额度。
- 当两景点间耗时 >120min 时，路线保留并进入超限清单（发现与标记归本 Agent，处置归 C-008/Gate）。
- 当实时路况不可用时，忽略路况按平均速度估算，主链路不受影响（不标记 fallback）。
- 当相邻两景点坐标相同（distance=0）时，产出零距离路线；坐标非法（经纬度超界）时，按坐标缺失走降级链。
- 当 `context` 含未知/新增字段时，忽略而非失败（向后兼容，A2A §2.3）。
- 当地图 AK 全部未配置时（增量 B），应用正常启动，路线调用走离线降级链 + 告警日志；仅配置一个厂商 AK 时进入单厂商模式。
- 当高德 MCP 主通道故障（增量 B）时，自动切换 HTTP 降级通道；双通道均故障 → 离线降级。
- 当高德 MCP 熔断触发后，冷却期内请求直达 HTTP；冷却结束探测成功 → 恢复 MCP 主通道。
- 当百度故障（增量 B）时，自动切换高德（双通道）；双厂商均故障 → 离线降级。
- 当厂商连续失败触发熔断（增量 B）后，冷却期内请求直达健康厂商；冷却结束半开探测恢复。
- 当 `RouteAgentRunner` 为 null（未注入）时，`RouteAgent` 退化为纯确定性直路（增量 A/B 行为），不触发任何 LLM 调用。
- 当 HarnessAgent 原生路运行时 LLM 未调用 `plan_routes` 工具（`lastResult()` 为 null）时，降级回确定性直路并 `fallback=true`，绝不以 LLM 自由文本作为路线数据。
- 当 `DEEPSEEK_API_KEY` 和 `DASHSCOPE_API_KEY` 均缺失时，`RouteHarnessAgentFactory.fromEnvironment` 降级为 null（不构建原生 Agent），`RouteAgent` 自动走纯确定性直路。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 桩端口单次多点规划 P95 < 3s；离线 haversine 估算单段 < 100ms；真实 MCP 调用（增量 B）P95 < 10s |
| 可靠性 | 三类地图工具均有降级；任一失败该段 `fallback=true`，主链路不阻塞；失败必返回可识别 `TASK_ERROR` |
| 安全 | 地图 AK 走环境变量/配置中心（禁硬编码，CI 密钥扫描 0 命中）；坐标数据不落盘（进程内内存缓存） |
| 可观测 | `api.rate_limit.hit{api=map}`、`agent.call.latency`、`fallback` 标记；增量 B 新增 `map.vendor.switch{from,to}` |
| 质量 | Agent/Skill 核心逻辑单测覆盖率 ≥80% |

## 设计约束

- `RouteAgent` 落在 `huazai-trip-agent-route`；可复用的路线计算/降级能力下沉 `huazai-trip-skills` 的 `route` 包（禁依赖其他 Agent 模块）。
- 必须复用 C-005 既有交付：`BaseAgent`/`AgentReply`/`AgentId`/`Msg`/`GovernedExternalCaller`/`RateLimiter`（`API_MAP` 额度已就绪）/`AgentMetrics`/`TraceContext`/`AgentRegistry`；不得复制治理逻辑。
- 端口声明（skills 模块）：`MapGeocoder`、`MapRouter`、`MapTraffic`、`PresetCoordinateCatalog`；桩实现保留为契约基线与测试替身。
- MCP 适配（增量 B）仅存在于 route 模块 `mcp` 包（工程结构 §4）；MCP 客户端优先复用 AgentScope 原生 MCP 能力，不引入未经评审的新依赖。
- 不动 common `Route`/`GeoLocation` 字段契约；超限标记由 skills 结果对象 `RoutePlanResult` 承载。
- 交通合理（≤2h）属强规则；本 Agent 只负责**发现与标记**，处置归 C-008 + QualityGate。

> **设计澄清**：common `Route` record 不含"超限标记"字段，字段级契约不属本卡可改。`durationMin > 120` 的发现与标记由 `RoutePlanResult`（routes + 超限路线清单 + Telemetry）承载，随 `TASK_RESULT` payload 交付下游。

### 增量 C 设计约束（R4 新增）

- `RoutePlanningTools`（`@Tool` 工具门面）与 `RouteHarnessAgentFactory` 落在 `huazai-trip-skills` 的 `route` 包（与增量 A 端口/Skill 同层）。`RouteAgentRunner` 接缝接口亦落 `route` 包。
- 真实 Skill `SKILL.md` 放 `huazai-trip-skills/src/main/resources/skills/route-planning/SKILL.md`，经 `ClasspathSkillRepository` 加载。
- `RouteAgent`（route 模块）通过 `RouteAgentRunner` 接缝解耦原生路——单测可注入桩（确定性、无网络），真实实现 `RouteHarnessAgentFactory` 经 env key 接 LLM。
- 对齐 C-006 增量 C 模式：LLM 做编排（理解意图 → 调用 `plan_routes` 工具 → 解读结果），确定性核心做裁决（不变量/降级/限频由 Java 强制）。
- `RouteHarnessAgentFactory.fromEnvironment` 双模型组装逻辑复用 C-006 `XHSHarnessAgentFactory` 同模式（主 deepseek → 降级 qwen → 均缺失返回 null）。
- JaCoCo 排除 `RouteHarnessAgentFactory`（因真实 LLM/网络不可离线覆盖），登记于覆盖率豁免清单。

## 契约影响

- REST: 无（经 Supervisor 编排）。
- A2A: 约定 `taskType=ROUTE_PLANNING` 的 `TASK_ASSIGN` payload: `context={destination, attractions:[{id,name,location?}], transportMode?}` 与 `TASK_RESULT`: `result=RoutePlanResult`，复用 `Msg` 既有信封。
- 数据模型: 复用 `Route`、`GeoLocation`、`Attraction`（C-004 已交付，零新增/零变更）。
- Skill 端口（skills 模块，本卡新增）: `MapGeocoder`、`MapRouter`、`MapTraffic`、`PresetCoordinateCatalog`；`RoutePlanResult`（routes + 超限清单 + 不可达说明 + Telemetry）。
- transportMode 取值约定（本卡固化）: 步行/驾车/骑行/公交，离线估算缺省"驾车"；收口时回写数据模型 §2.4。
- 外部工具契约（ §3 新增量 B）: 高德三工具（`amap.*`）为接口协议增行；限频 `map ≤30/min` 双厂商共用总额度。
- Redis / ReMe: 本卡不新增 Redis 键；坐标缓存为进程内内存 TTL 缓存。
- 增量 C 新增（R4）:
  - Skill 端口: `RouteAgentRunner`（`@FunctionalInterface`，接缝接口，Route 的 `XHSAgentRunner` 等价物）。
  - 工具门面: `RoutePlanningTools`（`@Tool plan_routes` 暴露确定性 `RoutePlanningService`，`lastResult()` 捕获权威结果）。
  - 工厂: `RouteHarnessAgentFactory implements RouteAgentRunner`（双模型组装，复用 C-006 同模式）。
  - Skill 资源: `resources/skills/route-planning/SKILL.md`（AgentScope 原生渐进式披露）。
  - A2A 信封: **不变**——`TASK_ASSIGN/TASK_RESULT` 信封、payload 结构、`traceId/fallback` 语义完全保留，Supervisor 无感知。

## 影响面

- 模块 / Agent / Skill: `huazai-trip-agent-route`（Agent + 增量 B mcp 落位 + 增量 C 原生接缝）、`huazai-trip-skills/route`（Skill + 端口 + 增量 C 工具门面/工厂/Skill 资源）；common 零改动。
- 外部 API: 百度地图 + 高德地图 MCP Server（增量 B，共用 ≤30/min）；LLM deepseek-v4-pro / qwen3-max（增量 C，ADR-009）。
- wiki: 对齐 `接口协议.md`(§3/§4)、`数据模型.md`(§2.4 Route transportMode)、`业务模型.md`(§4 交通≤2h)。

## 规则归属

- 业务不变量归属: 交通耗时≤120min 的发现与标记 → Route Agent/Skill 内部；超限处置 → C-008 + QualityGate。
- 外部调用治理归属: 地图调用统一走 C-005 `GovernedExternalCaller`，不自建治理。
- 可观测性要求: `traceId/planId?/agentId=route-planner/taskType=ROUTE_PLANNING/fallback`。

## 测试策略

### 增量 A 测试策略

- 先写失败测试（Red）: 耗时>120min 标记不剔除、地理编码失败→预置坐标降级、路线端口故障→haversine 估算（`fallback=true`）、限频不超频（≤30/min 真实边界）、单景点空路线 → 先红。
- Happy Path: 多景点（坐标齐备）+ 桩路线端口 → 按访问序的相邻点 Route 列表，字段齐全、耗时均≤120min、`fallback=false`、`traceId` 透传。
- 边界测试: 空/单景点、坐标缺失+预置兜底、预置也缺失→跳段+不可达、相同坐标（distance=0）、非法经纬度、未知字段忽略。
- 降级测试: `MapGeocoder`/`MapRouter`/`MapTraffic` 各自故障 → 对应降级 + `fallback=true`；路况降级不标记 fallback；瞬时故障恢复（重试 2 次后成功 → `fallback=false`）。
- 回归测试: haversine 精度（已知城市坐标距离容差）+ 超限阈值（120min 临界：120 不超限、121 超限）+ BaseAgent 收发契约联测。
- 限频真实边界: `RateLimiter.withDefaults`（map=30/min）下前 30 次成功、第 31 次走离线估算。

### 增量 B 测试策略（补充）

- MCP 适配测试: 契约测试 + 故障注入（AK 缺失、连接失败、响应畸形 → 切换/降级链）；CI 不依赖外网与真实 AK；真实调用冒烟留 ⑥ 验证。
- 智能切换测试: 主厂商故障→备厂商接管（不标记 fallback、vendor 记录正确）；连续失败→熔断→冷却→半开恢复；单 AK 模式；双厂商均故障→离线降级；限频命中→不切换、直接离线降级。
- 回归: 增量 A 全量回归。

### 增量 C 测试策略（R4 新增）

- 先写失败测试（Red）: `RoutePlanningTools` `@Tool` 产物 == 确定性 `RoutePlanningService` 产物（AC-11）、`RouteAgent` 原生路失败降级回确定性直路 + `fallback=true`（AC-13）、`RouteAgent` LLM 未调用工具降级（边界）→ 先红。
- 工具门面测试（`RoutePlanningToolsTest`）: 确定性桩输入 → `plan_routes` 产出 `RoutePlanResult` 与直接调 `RoutePlanningService` 产出完全一致；`lastResult()` 正确捕获；超限标记/降级/限频行为由底层确定性 Service 保证，工具门面不重复验证。
- Agent 原生接缝测试（`RouteAgentNativeTest`）: ① 注入成功桩 runner → 原生产物 == 确定性产物，`fallback=false`；② 注入异常桩 runner → 降级确定性 + `fallback=true`；③ 注入 null-result 桩 runner → 降级确定性 + `fallback=true`；④ runner 为 null → 纯确定性直路（增量 A/B 行为）。
- env-gated 集成测试（`RouteHarnessAgentFactoryIT`）: 有 `DEEPSEEK_API_KEY` → 真实 LLM 跑通路线规划 Skill，验证 `HarnessAgent` + `@Tool` + `SKILL.md` 端到端；无 key → `@Disabled` skip，不伪装通过。
- 回归: 增量 A/B 全量回归不回退。

## 验收用例

### 增量 A 用例

- Case-1: `ROUTE_PLANNING(成都, [宽窄巷子, 大熊猫基地, 武侯祠])`（坐标齐备）→ 2 段 Route（distanceKm/durationMin/transportMode 齐全），耗时均≤120min，`fallback=false`。
- Case-2: 路线端口连续超时（重试耗尽）→ haversine 估算 → `fallback=true`。
- Case-3: 某景点无坐标且地理编码失败 → 预置坐标降级 → `fallback=true`；预置也无 → 跳段 + 不可达说明。
- Case-4: 两景点间耗时 150min → 路线保留且进入超限清单。
- Case-5: 投递 `taskType=XHS_ANALYSIS` → 快速失败返回 `TASK_ERROR`。
- Case-6: 限频窗口耗尽 → 不超频，该段走离线估算，`api.rate_limit.hit{api=map}` +1。

### 增量 B 用例（补充）

- Case-7: 未配置任何 AK 启动 → 应用健康、走离线降级 + 告警日志。
- Case-8: 百度（主选）调用失败 → 自动切高德 → 结果真实、`map.vendor.switch{from=baidu,to=amap}` +1。
- Case-9: 百度连续失败触发熔断 → 冷却期内请求直达高德；冷却结束探测成功 → 恢复。
- Case-10: 双厂商均故障 → haversine 离线估算，`fallback=true`。
- Case-11: 高德 MCP 调用失败 → 自动切 HTTP → 结果真实、`map.channel.switch{from=mcp,to=http}` +1。
- Case-12: 高德 MCP 连续失败触发熔断 → 冷却期内请求直达 HTTP；冷却结束探测成功 → 恢复 MCP。
- Case-13: 高德双通道（MCP+HTTP）均故障 → haversine 离线估算，`fallback=true`。

### 增量 C 用例（R4 新增）

- Case-11: 注入 `RouteAgentRunner`，LLM 正常调用 `plan_routes` 工具 → `TASK_RESULT` 产出 == 确定性 `RoutePlanningService` 产出，`fallback=false`；路线数据来自确定性 Java 而非 LLM 自由文本。
- Case-12: 注入 `RouteAgentRunner`，LLM 运行失败（超时/异常）→ 降级回确定性直路 → `TASK_RESULT(fallback=true)`，不抛裸异常，路线数据正常产出。
- Case-13: 注入 `RouteAgentRunner`，LLM 未调用工具（`lastResult()` 为 null）→ 降级回确定性直路 → `TASK_RESULT(fallback=true)`。
- Case-14: 未注入 `RouteAgentRunner`（null）→ 纯确定性直路（增量 A/B 行为），Case-1~6 全部回归通过。
- Case-15: `DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY` 均缺失 → `RouteHarnessAgentFactory.fromEnvironment` 返回 null，应用正常，走纯确定性直路。

## 任务拆解（≤1 天/项，DAG 无环）

### 增量 A 任务

- [x] T-1: skills `route` 包端口（`MapGeocoder`/`MapRouter`/`MapTraffic`/`PresetCoordinateCatalog`）+ 查询/结果对象（`RoutePlanResult`）+ haversine 离线估算 · P0 · 依赖 C-004,C-005 · 模块 skills
- [x] T-2: 路线规划主逻辑（坐标解析→相邻点路线→装配 Route→≤120min 标记）+ 强规则/临界测试（先红→绿）· P0 · 依赖 T-1 · 模块 skills
- [x] T-3: 三类降级 + 限频治理接入（`GovernedExternalCaller` + `API_MAP`）+ 降级/限频测试 · P0 · 依赖 T-2,C-005 · 模块 skills
- [x] T-4: `RouteAgent` A2A 接入（继承 BaseAgent、注册 `agent://route-planner`、context 解析 + 可观测派发）+ 收发契约测试 · P0 · 依赖 T-3,C-005 · 模块 route
- [x] T-5: 边界/回归用例固化（Case-1~6 全量 + haversine 精度回归 + 120min 临界）+ 覆盖率 ≥80% · P0 · 依赖 T-4 · 模块 route/skills

### 增量 B 任务（增量 A 交付后启动）

- [x] T-6: HTTP 基建（MapHttpExecutor/MapHttpClient/MapApiException）+ 百度三工具（BaiduGeocoder/Router/Traffic）+ 坐标 TTL 缓存（GeocodingCache）+ 22 tests · P0 · 依赖 T-4 · 模块 route ✅
- [x] T-7: 高德 HTTP 工具（AmapGeocoderHttp/RouterHttp/TrafficHttp）+ 7 tests · P0 · 依赖 T-6 · 模块 route ✅
- [x] T-8: 高德 MCP 客户端（AmapMcpClient 封装 `https://mcp.amap.com/sse`，AgentScope 原生 McpClientBuilder + McpClientWrapper + ChannelFailoverExecutor 通道级熔断）+ `map.channel.switch` 指标 + 7 tests · P0 · 依赖 T-7 · 模块 route ✅
- [x] T-9: 厂商 + 通道智能切换（VendorId/VendorHealth 双熔断状态机/FailoverExecutor 泛型引擎/FailoverGeocoder/Router/Traffic/MapVendorFactory）+ `map.vendor.switch` + `map.channel.switch` 指标 + 31 tests · P0 · 依赖 T-8 · 模块 route/skills ✅

### 增量 C 任务（R4 新增，AgentScope 原生化 Route 切片）

- [x] T-9（C-1 工具门面）: `RoutePlanningTools`（`@Tool plan_routes` 包确定性 `RoutePlanningService`，不变量留 Java，`lastResult()` 捕获权威结果）+ `RoutePlanningToolsTest` 确定性单测（AC-11）· P0 · 依赖 T-2,T-3 · 模块 skills ✅ 7 tests
- [x] T-10（C-2/C-3 真实 Skill + 工厂）: `SKILL.md`（`ClasspathSkillRepository`，原生渐进式披露）+ `RouteAgentRunner` 接缝接口 + `RouteHarnessAgentFactory`（**主 deepseek-v4-pro / 降级 qwen3-max + env key，ADR-009**）+ `LlmModelFactory`（共用双模型构建，消除 CPD 重复）· P0 · 依赖 T-9 · 模块 skills ✅
- [x] T-11（C-4 Agent 接缝）: `RouteAgent` 注入 `RouteAgentRunner`，原生路 + 确定性降级兜底 + `RouteAgentNativeTest`（4 例：成功/异常降级/null 降级/null runner）；增量 A/B 回归不回退（AC-13/AC-14）· P0 · 依赖 T-9,T-10 · 模块 route ✅ 4 tests

## 流水线进度

### 增量 A — 主链路桩版本（R3）

- [x] ① 需求分析（analyzing）— R1~R3 三轮迭代
- [x] ② 编码实现（coding）— T-1→T-4 ✅ 2026-06-13
- [x] ③ 单测编写（testing，覆盖率 ≥80%）— T-5 ✅ 33 tests, 0 failures, JaCoCo pass
- [x] ④ 专家评审（reviewing，0 严重问题）→ [review.md](review.md) ✅ 2026-06-13
- [x] ⑤ CI 门禁（ci，全绿）— `mvn clean verify` 10 模块 BUILD SUCCESS，418 tests / 0 failures，密钥扫描 0 命中 ✅ 2026-06-13
- [x] ⑥ 部署验证（verifying）→ [verify.md](verify.md) ✅ 2026-06-13
- [x] 交付（done） ✅ 2026-06-13

### 增量 B — 真实双厂商 HTTP + 高德 MCP 主用 + 智能切换

- [x] ① 需求分析（analyzing）— AC-8~AC-10、T-6~T-9 已落档
- [x] ② 编码实现（coding）— T-6→T-9 ✅ 2026-06-13（AgentScope 原生 McpClientBuilder + McpClientWrapper，高德 MCP 主用 + HTTP 降级，百度 HTTP 直连）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）— 112 tests（含 T-8 重构 +18 边界测试），0 failures，JaCoCo pass
- [x] ④ 专家评审（reviewing，0 严重问题）→ [review.md](review.md) ✅ 2026-06-13
- [x] ⑤ CI 门禁（ci，全绿）— `mvn clean verify` 10 模块 BUILD SUCCESS，compile/checkstyle/PMD/ArchUnit(R2-R6)/tests/coverage/key-scan 全绿 ✅
- [x] ⑥ 部署验证（verifying）→ [verify.md](verify.md) ✅ 2026-06-13
- [x] 交付（done） ✅ 2026-06-13

### 增量 C — AgentScope 原生化（Route 切片，R4 新增）

- [x] ① 需求分析（analyzing）— R4 规格已落档
- [x] ② 编码实现（coding）— T-9→T-11 ✅ 2026-06-13（`RouteHarnessAgentFactory` + `RoutePlanningTools` + `SKILL.md` + `RouteAgentRunner` 接缝 + `RouteAgent` 原生降级）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）— 11 tests (7 tools + 4 native) + `RouteHarnessAgentFactoryIT` env-gated 集成测试 ✅
- [x] ④ 专家评审（reviewing，0 严重问题）→ [review.md](review.md) ✅ 2026-06-13
- [x] ⑤ CI 门禁（ci，全绿）— `mvn clean verify` 10 模块 BUILD SUCCESS，418 tests / 0 failures ✅
- [x] ⑥ 部署验证（verifying）→ [verify.md](verify.md) ✅ 2026-06-13
- [x] 交付（done） ✅ 2026-06-13
