---
id: C-010
slug: supervisor-agent
status: done
created: 2026-06-07
updated: 2026-06-16
owner: Owner Agent
---

# C-010 主管 Agent（SupervisorAgent：意图理解与编排委派）

## 用户故事

作为系统，我想要 SupervisorAgent 理解用户意图、经 AgentScope v2 的 HarnessAgent ReAct 循环自主委派四个子 Agent（agent_spawn），最终调用 `orchestrate_trip` 确定性收口产出 `TripPlanResult`，以便把一句话需求自主转化为可交付的行程方案。复杂需求走 Plan Mode（只读调研/设计，产出 Markdown 计划文件）+ Task List（`enableTaskList(true)` 注册 TodoTools + TaskReminderMiddleware）分解执行；简单需求 LLM 自然顺序调工具；含分支需求 LLM 自然处理条件逻辑——三种行为从 LLM + 可用工具中涌现，无需显式模式选择策略。

## 非目标（Out of Scope）

- 本次不实现子 Agent 业务逻辑（复用 C-006~C-009 的 @Tool 类 + SKILL.md）。
- 本次不实现 REST 入口（归 C-011）。
- 本次不实现 A2A/治理/Gate 基座（复用 C-005）。
- 本次不做前端交互（归 C-013）。
- 本次不引入 Pipeline/StateGraph/PlanNotebook 三模式选择（AgentScope v2 已删除，由 ReAct 循环自然涌现）。

## 验收标准（AC）

- AC-1: SupervisorAgent 注册为 `agent://supervisor`，接收规划任务（含 `TripPlanRequest`），经 HarnessAgent ReAct 循环驱动编排。LLM 负责意图理解 + 委派顺序 + 上下文传递，最终必须调用 `orchestrate_trip` 工具收口，由确定性 `TripOrchestrationService` 产出 `TripPlanResult`。LLM 不直接产出行程数据。
- AC-2: 复杂需求走 Plan Mode（只读调研/设计阶段，产出 Markdown 计划文件到 workspace）+ Task List（`enableTaskList(true)` 注册 TodoTools + TaskReminderMiddleware），LLM 自主分解步骤并委派子 Agent。不再使用 `trip:step:{planId}` 断点续传——v2 由 AgentScope 框架 session 机制处理持久化与恢复。
- AC-3: 编排行为从 ReAct 循环自然涌现，无需显式模式选择策略（ADR-005 三模式已随 AgentScope v2 删除）。LLM 根据需求复杂度自主决定：简单→自然顺序调工具；复杂→Plan Mode + Task List 分解；含分支→自然处理条件逻辑。所有行为可观测（traceId/planId 贯穿）。
- AC-4: 编排顺序为 XHS（候选景点）→ Route（路线规划）→ Itinerary（行程编排）→ Budget（费用核算）。原生路经 `agent_spawn` 委派子 Agent（框架从 `workspace/subagents/<id>.md` 读取声明 → 从父 Toolkit 按 `tools: [...]` 白名单过滤 → 创建临时 HarnessAgent 叶子节点 → 注入 task 作为 user prompt → ReAct 循环 → 最终回复作为 TOOL_RESULT 返回父 Agent → 销毁）。确定性兜底路经 `SubAgentDispatcher`（A2A `TASK_ASSIGN`/`TASK_RESULT`）委派。任一子 Agent 降级（`fallback=true`）时整体仍能产出标记降级的方案。两条路最终均走 `TripOrchestrationService.orchestrate()` 收口，保证业务结果同源一致。
- AC-5: 汇总后调用 `OutputQualityGate` 统一复核全局不变量，置 `requiresHITL`。超预算≥15%、天数不一致、评分<3.5 任一触发 HITL 进入 `review` 状态。Gate 同时作用于原生路和确定性兜底路（均由 `TripOrchestrationService` 统一调用）。
- AC-6: 递归保护由 AgentScope 框架强制：子 Agent 不可再 spawn（强制叶子），硬上限 3 层。替代原始"链深≤5"约束。Plan Mode 下 Task List 项数 > 30 视为异常，告警并安全终止。

## 边界情况（≥5）

- 当某子 Agent 持续失败/超时时，原生路 `agent_spawn` 返回错误 → Supervisor ReAct 循环感知 → 用降级结果继续，整体方案标记 `fallback=true` 且不挂死；确定性兜底路 `TASK_ERROR` → `StepResult.fallback=true` → 继续后续步骤。
- 当 Plan Mode 下 Task List 项数超过 30 时，终止并返回当前最优草案 + 告警，不无限循环（v2 替代原 PlanNotebook 步数>15 约束）。
- 当多个 HITL 条件同时触发时，全部记录于 `requiresHITL` 原因列表（`QualityGateResult.violations()`），进入 `review`。
- 当 LLM 未调用 `orchestrate_trip` 工具收口时（原生路），`TripOrchestrationTools.lastResult()` 返回 null → `SupervisorAgent` 降级回确定性 `TripOrchestrationService` 直路（`fallback=true`）。
- 当 `subagents/<id>.md` 声明文件不存在或 `tools: [...]` 白名单为空时，框架拒绝创建子 Agent，LLM 收到错误 → 降级确定性兜底路或提示用户。
- 当中途崩溃重启时，由 AgentScope 框架 session 机制恢复对话状态，不从零重跑（替代原 `trip:step:{planId}` 方案）。
- 当 `workspace/subagents/` 目录为空（classpath 资源未复制到 workspace）时，工厂代码负责从 classpath 复制，或编程式 `builder.subagent(SubagentDeclaration.builder()...)` 注册，确保运行时可用。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 简单需求端到端 P95 < 30s；复杂需求 Plan Mode + Task List 进度可见（异步） |
| 可靠性 | 子 Agent 降级不致主链失败；原生路失败降级确定性兜底；框架 session 持久化可恢复 |
| 安全 | 关键节点 HITL；密钥不入消息（走环境变量）；子 Agent 强制叶子不可递归 spawn |
| 可观测 | `traceId/planId/agentId=supervisor/taskType`；`agent.call.latency`（P95 告警>10s）；`agent.call.error`（错误率告警>5%）；HITL 触发计数；子 Agent 委派次数分布 |

## 设计约束

- 必须落在 `huazai-trip-agent-supervisor`，编排 Skill 下沉 `huazai-trip-skills/supervisor/`，SKILL.md 位于 `skills/trip-orchestration/SKILL.md`（禁依赖其他 Agent 模块，仅经 `agent_spawn` 或 `SubAgentDispatcher` 通信，遵守 R1）。
- 跨 Agent 业务编排集中于 Supervisor，禁止下沉到 server（对齐 §2.3 边界）。
- **删除**：三模式共存（ADR-005 已随 AgentScope v2 废弃）。编排行为从 ReAct 循环自然涌现。
- **新增**：结果一致性策略——原生路 LLM 最终必须调 `orchestrate_trip` 收口，由确定性 `TripOrchestrationService` 产出 `TripPlanResult`；确定性兜底路同样走 `TripOrchestrationService`。两条路最终产出同源，保证业务结果一致。
- **新增**：子 Agent 复用 @Tool 类 + SKILL.md。原生路径下框架自己 `HarnessAgent.builder().tools(filtered).build()`，不走 `XHSHarnessAgentFactory` 等工厂。@Tool 类（`XHSAnalysisTools`/`RoutePlanningTools`/`ItineraryDesignTools`/`BudgetCalculationTools`）注册到 Supervisor 的 Toolkit 供子 Agent 白名单过滤。子 Agent 已有的 HarnessAgentFactory 仅在确定性兜底路或独立测试中使用。
- **新增**：`subagents/<id>.md` 必须出现在运行时 `workspace/subagents/` 目录（框架非递归扫描），不能只放 classpath。工厂代码负责从 classpath 复制到 workspace，或编程式 `builder.subagent(SubagentDeclaration.builder()...)` 注册。
- 全局一致性复核统一走 `OutputQualityGate`，Supervisor 不复制各 Agent 的内部规则。

## 契约影响

- REST: 为 C-011 提供规划编排能力（异步 planId 驱动）
- A2A: 确定性兜底路作为 `from=supervisor` 的委派方，使用全部 `taskType`（XHS_ANALYSIS/ROUTE_PLANNING/ITINERARY_DESIGN/COST_CALCULATION）；原生路不经过 A2A 协议（`agent_spawn` 是框架内部机制）
- 数据模型: 装配最终 `TripPlan`（C-004），写 `PlanStatus` 流转；**删除** `PlanMode` enum（三模式已废弃）
- Redis / ReMe: `trip:plan:{planId}`、`trip:plan:{planId}:status`、`trip:session:{sessionId}`；**删除** `trip:step:{planId}`（断点续传由框架 session 机制替代）；ReMe `history_plans` 召回增强
- AgentScope v2 框架: `HarnessAgent.builder().enableTaskList(true)` 注册 TodoTools + TaskReminderMiddleware；`workspace/subagents/` 子 Agent 声明目录；框架 session 持久化

## 影响面

- 模块 / Agent / Skill: `huazai-trip-agent-supervisor`、`huazai-trip-skills/supervisor/`（`TripOrchestrationService`、`TripOrchestrationTools`、`SupervisorAgentRunner`、`SupervisorHarnessAgentFactory`、`SubAgentDispatcher`）、`skills/trip-orchestration/SKILL.md`、`subagents/*.md`（四个子 Agent 声明）、`OutputQualityGate`（复用）
- **删除**: `huazai-trip-skills/plan-notebook/`（Skill 不存在于 v2）、`PlanMode` enum（`com.nanobot.common.enums.PlanMode`）、`trip:step:{planId}` Redis key（`CacheKeys.stepKey()`）
- 外部 API: LLM（deepseek-v4-pro 主 / qwen3-max 降级，ADR-009）；间接驱动全部子 Agent 外部依赖
- wiki: 对齐 `业务模型.md`(§6 状态机/HITL)、`架构决策.md`（**删除 ADR-005** 三模式条目）、`接口协议.md`(§2/§6)

## 规则归属

- 业务不变量归属: Supervisor 汇总后统一交 `OutputQualityGate` 复核；HITL 触发由 Gate 判定、Supervisor 驱动状态进入 `review`。原生路与确定性兜底路均经 `TripOrchestrationService.orchestrate()` 收口。
- 外部调用治理归属: 子 Agent 委派原生路走 AgentScope 框架 `agent_spawn`（框架治理）；确定性兜底路走 `SubAgentDispatcher`（A2A 消息）；LLM 调用走 C-005 统一治理（双模型 ADR-009）。
- 递归保护: AgentScope 框架强制子 Agent 不可再 spawn（叶子），硬上限 3 层。
- 可观测性要求: `traceId/planId/agentId=supervisor/taskType/msgId`；子 Agent 委派次数与延迟分布；Plan Mode / Task List 使用次数指标。

## 测试策略

- 先写失败测试: 编排顺序正确性（XHS→Route→Itinerary→Budget）、子 Agent 降级整体不崩（`fallback=true` 仍产出）、Gate 触发 HITL（超预算≥15%、天数不一致、评分<3.5）、原生路失败降级确定性兜底、`orchestrate_trip` 未调用降级、构造参数校验 → 先红。
- Happy Path: 简单需求 → 完整 TripPlan，days 一致、未超预算、`requiresHITL=false`（覆盖原生路桩 + 确定性直路）。
- 边界测试: 子 Agent TASK_ERROR 降级（逐个四子 Agent）、全部子 Agent 降级、多 HITL 条件同时触发、空行程、request 缺失字段、Task List 项数超 30 终止。
- 降级测试: 任一子 Agent `fallback=true` → 整体方案标记降级仍可交付；原生路异常/无产物 → 降级确定性兜底 `fallback=true`；确定性兜底路子 Agent 全部失败 → 仍产出 `TripPlan`（空 days + 全 fallback 标记）。
- 回归测试: 编排顺序与状态机流转纳入回归（与 C-012 E2E 联动）；`OutputQualityGate` 五条规则全部覆盖；`TripOrchestrationService` 确定性编排流程不变。

## 验收用例

- Case-1: 简单「北京 1 日游」→ LLM 自然顺序调工具（或直接调 `orchestrate_trip` 一键编排）→ 完整 TripPlan，`requiresHITL=false`，无 HITL 触发。
- Case-2: 「云南 7 天 6 城，预算紧」超预算≥15% → `OutputQualityGate` 检测 `BUDGET_OVERRUN` → `requiresHITL=true` + `review` 状态 + HITL 原因列表含超预算详情。
- Case-3: 复杂多约束需求 → Plan Mode 产出 Markdown 计划文件 + Task List 逐项分解委派，Task List 项数 ≤30，框架 session 持久化中间态，最终 `orchestrate_trip` 收口。
- Case-4: Route 子 Agent 返回 `fallback=true`（如地图 API 全部降级 haversine 估算）→ 最终 TripPlan 标记 `fallback=true` 但仍产出完整方案（含离线估算路线）。
- Case-5: 原生路 LLM 超时/未调 `orchestrate_trip` → `lastResult() == null` → 降级确定性 `TripOrchestrationService` 直路，`fallback=true`，仍产出完整 TripPlan。

## 任务拆解（≤1 天/项，DAG 无环）

- [x] T-1: `trip-orchestration` SKILL.md 完善 + `subagents/*.md` 声明文件对齐 v2（含 `tools: [...]` 白名单 + workspace 复制逻辑）· P0 · 依赖 C-004,C-005 · 模块 skills
- [x] T-2: `TripOrchestrationService` 确定性编排核心（XHS→Route→Itinerary→Budget→QualityGate）+ `TripOrchestrationTools`（`@Tool orchestrate_trip`）+ 确定性编排单测 · P0 · 依赖 C-006..C-009 · 模块 skills
- [x] T-3: `SupervisorAgent` A2A 收发 + 原生/确定性双路接缝（`SupervisorAgentRunner`）+ `SupervisorHarnessAgentFactory` 组装 HarnessAgent（含 Plan Mode + Task List 支持）+ 失败降级逻辑 · P0 · 依赖 T-2 · 模块 supervisor
- [x] T-4: 子 Agent 降级聚合（逐个/全部失败整体不崩 + `fallback` 标记）+ `OutputQualityGate` 复核接入 + HITL 进 `review` · P0 · 依赖 T-3 · 模块 supervisor
- [x] T-5: Plan Mode + Task List 集成（`enableTaskList(true)` + `workspace/subagents/` 注册 + Task List 项数上限 30）+ 框架 session 持久化验证 · P1 · 依赖 T-3 · 模块 supervisor
- [x] T-6: 清理 v1 遗留：删除 `PlanMode` enum、删除 `CacheKeys.stepKey()`（`trip:step:{planId}`）、删除 `plan-notebook` 目录引用、更新 `架构决策.md` ADR-005 废弃标注 · P1 · 依赖 T-1 · 模块 common/ wiki
- [x] T-7: 边界测试 + 降级测试 + 回归测试（覆盖率 ≥80%）· P0 · 依赖 T-3,T-4,T-5 · 模块 supervisor

## 流水线进度

- [x] ① 需求分析（analyzing）
- [x] ② 编码实现（coding）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）
- [x] ④ 专家评审（reviewing，0 严重问题）→ review.md
- [x] ⑤ CI 门禁（ci，全绿）
- [x] ⑥ 部署验证（verifying）→ verify.md
- [x] 交付（done，wiki 已同步）
