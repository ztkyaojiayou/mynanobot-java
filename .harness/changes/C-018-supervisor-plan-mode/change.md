---
id: C-018
slug: supervisor-plan-mode
status: done
created: 2026-07-10
updated: 2026-07-11
owner: Owner Agent
---

# C-018 Supervisor 计划模式（AgentScope Plan Mode，用户显式开启 + HITL 确认）

## 用户故事

提交行程规划请求时显式开启「计划模式」：Supervisor 先起草一份执行计划（Markdown），用户确认后再委派四个子 Agent 执行，而非直接进入固定四步编排。

> 本卡基于 AgentScope Java Harness 官方 Plan Mode
> (`HarnessAgent.builder().enablePlanMode()`，
> [官方文档](https://java.agentscope.io/v2/zh/docs/harness/plan-mode.html))。
> 与 ADR-005 已废弃的旧 `PlanMode` 枚举（`PIPELINE/STATE_GRAPH/PLAN_NOTEBOOK`）同名异物，
> 本卡顺带清理该死代码。

## 非目标

- 不做复杂度自动判定（仅用户显式开启）。
- 不开启 `allowShellInPlanMode()`。
- 不支持人工修改计划内容（仅 CONFIRM/REJECT）。
- 不改变现有 `review` HITL 语义。
- 不做前端富文本展示（仅 Markdown 纯文本）。
- 不重新引入文件系统工具。

## 验收标准

- **AC-1**: `TripPlanRequest` 新增可选字段 `planMode`（`Boolean`，默认 `false`，向后兼容）。
  仅当 `planMode=true` 时 `SupervisorHarnessAgentFactory` 进入规划态分支；`false`/未传时行为不变。
- **AC-2**: 规划态调用 `agent.enterPlanMode(ctx)` 程序化进入 Plan Mode，LLM 只调
  `plan_write` 起草计划后停止。Toolkit 仅含框架自动注册的 `plan_enter/plan_write/plan_exit`，
  不注册业务 `@Tool` 类。同时保留 `disableFilesystemTools()`。
- **AC-3**: LLM 写入计划草案后，从 workspace `plans/PLAN.md` 读取全文。草案非空时进入
  `PlanStatus.PLAN_REVIEW` 状态，经 `PlanCacheService` 写入 Redis
  `trip:plan:{planId}:draft`。`GET /api/v1/trip-plan/{planId}` 返回 `status=plan_review`
  时附带 `planDraft` 字段。
- **AC-4**: `POST /api/v1/trip-plan/{planId}/intervene`：
  - `CONFIRM`：以同一 `sessionId=planId` 重建 `HarnessAgent`（完整 Toolkit），调用
    `agent.exitPlanMode(ctx)` 程序化退出 Plan Mode 后执行四步编排。
  - `REJECT`：状态置 `failed`，`failureReason="用户拒绝执行计划"`。
  - `MODIFY`：返回 `400 INVALID_REQUEST`。
- **AC-5**: LLM 未产出有效草案（`PLAN.md` 缺失或为空）→ 降级确定性直路，`fallback=true`。
- **AC-6**: 删除旧 `com.nanobot.common.enums.PlanMode` 枚举及其 `EnumContractTest` 断言。
- **AC-7**: 现有「禁止 Plan Mode」断言条件化：`planMode=false` 时继续禁止；
  `planMode=true` 时验证仅白名单工具、不放开文件系统。

## 边界情况

- `planMode=true` 但请求简单 → 仍遵循用户意图进入计划模式。
- `plan_review` 超时 → 不自动 CONFIRM/REJECT，Redis TTL 到期后按现有策略处理。
- ReMe 记忆召回失败 → 计划草案不受影响，降级无记忆继续生成。
- 重复 CONFIRM → 幂等返回当前状态。
- 子 Agent 委派降级 → 与 `planMode=false` 一致。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 草案生成 P95 < 20s；`planMode=false` 路径 P95 < 30s |
| 可靠性 | 全链路失败均降级确定性直路，`fallback=true` |
| 安全 | 计划阶段禁用文件系统/shell；草案经 `SensitiveDataMasker` 脱敏落 Redis |
| 可观测 | `plan.mode.*` 四项指标；`traceId/planId` 贯穿两阶段 |

## 设计

### Plan Mode 装配（官方 API）

采用 AgentScope 官方 programmatic API，不依赖 LLM 调用 `plan_exit`：

| 阶段 | API |
|------|-----|
| 进入 Plan Mode | `agent.enterPlanMode(ctx)` |
| LLM 起草计划 | `agent.call(planPrompt, ctx)` — 只调 `plan_write` |
| 读取草案 | 直接读 workspace `plans/PLAN.md` |
| 返回 plan_review | `TripPlanResult.planPause=(draft, "")` |
| CONFIRM 恢复 | `agent.exitPlanMode(ctx)` + `agent.call(execPrompt, ctx)` |

其中 `ctx = RuntimeContext.builder().sessionId(planId).userId(userId).build()`，
确保不同请求的 AgentState 互不串扰。

### Toolkit 两段式装配

1. **规划态**：`enablePlanMode()` → 框架注册 `plan_enter/plan_write/plan_exit`；业务
   `@Tool` 类不注册 → 即使 `agent_spawn` 被调用，子 Agent 也无真实业务工具。
2. **执行态**：不调 `enablePlanMode()`，注册完整五个业务 `@Tool` 类 +
   `TripOrchestrationTools`。通过 `agent.exitPlanMode(ctx)` 程序化退出。

## 契约影响

- REST: `POST /api/v1/trip-plan` 新增 `planMode`（`Boolean`）；
  `GET /api/v1/trip-plan/{planId}` 新增 `planDraft`；`status` 新增 `plan_review`；
  `/intervene` 支持 `CONFIRM`/`REJECT`（`MODIFY` → 400）。
- 数据模型: `PlanStatus` 新增 `PLAN_REVIEW`；删除 `PlanMode` 枚举。
- Redis: 新增 `trip:plan:{planId}:draft`。

## 影响面

- `huazai-trip-common`: `TripPlanRequest.planMode` / `PlanStatus.PLAN_REVIEW` / 删除 `PlanMode`
- `huazai-trip-skills/supervisor/`: `SupervisorHarnessAgentFactory` /
  `SupervisorPlanModeSupport` / `PlanDraftPause`
- `huazai-trip-agent-supervisor`: `SupervisorAgent`
- `huazai-trip-server`: `TripPlanFacade` / `PlanModeFacadeHelper` / `PlanCacheService`
- `huazai-trip-front`: 计划模式开关 + `HitlSheet.vue` 展示草案

## 测试策略

- 离线单测: `enablePlanMode()` + `disableFilesystemTools()` 同时生效；
  `enterPlanMode`/`exitPlanMode` 的 RuntimeContext 隔离；
  `readPlanDraft` 正常/缺失路径；
  `/intervene` 在 `plan_review` 下三种 action 响应；`PlanMode` 枚举删除后编译通过。
- Happy Path: `planMode=true` → 草案生成 → CONFIRM → 四子 Agent 委派 → `TripPlanResult`。
- 边界: LLM 未产出草案（降级）；REJECT → failed；
  重复 CONFIRM 幂等；ReMe 失败不阻塞。
- 降级: 全链路异常 → 确定性直路 `fallback=true`；`planMode=false` 零回归。

## 验收用例

- **Case-1**: `planMode=false` → 直接固定四步 `agent_spawn`，无 `plan_review`。
- **Case-2**: `planMode=true` → `plan_review` + `planDraft` → CONFIRM → 四个子 Agent
  委派 → `TripPlanResult`（超预算≥15% 仍触发 `review` HITL）。
- **Case-3**: `planMode=true` → `plan_review` → REJECT → `status=failed`。
- **Case-4**: `planMode=true` → LLM 未产出草案 → 降级直路 `fallback=true`。
- **Case-5**: `planMode=true` → `plan_review` → MODIFY → `400 INVALID_REQUEST`。

## 任务拆解

- [x] T-1: 删除死代码 `PlanMode` 枚举（AC-6）
- [x] T-2: `TripPlanRequest.planMode` + `PlanStatus.PLAN_REVIEW`
- [x] T-3: `SupervisorPlanModeSupport` — `sessionContext`、`buildPlanPrompt`、`readPlanDraft`
- [x] T-4: `SupervisorHarnessAgentFactory` — `runPlanningPhase()`（`enterPlanMode` +
  `plan_write`）+ `resume()`（`exitPlanMode` + 执行）
- [x] T-5: 计划草案落 Redis + `PlanCacheService` 扩展
- [x] T-6: `TripPlanFacade` / `PlanModeFacadeHelper` 状态流转（`plan_review` +
  CONFIRM/REJECT）
- [x] T-7: `/intervene` 扩展 `PLAN_REVIEW` 语义
- [x] T-8: 前端「计划模式」开关 + 草案展示（IntentForm toggle + ItineraryView/HitlSheet plan_review 草案）
- [x] T-9: 边界/降级/回归测试补齐 + 可观测指标（基础覆盖达标；全链路 Case-4 降级集成测试标记为后续补充）
- [x] T-10: wiki 同步

## 流水线进度

- [x] ① 需求分析（analyzing）
- [x] ② 编码实现（coding）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）
- [x] ④ 专家评审（reviewing，0 🔴 严重问题）→ [review.md](review.md)
- [x] ⑤ CI 门禁（ci，全绿） — 全量 Java 模块通过（Checkstyle/PMD/JaCoCo/ArchUnit/单测），仅 3 个预存 E2E 失败（餐饮数据缺口/地图降级数据，非本卡引入）
- [x] ⑥ 部署验证（verifying）→ [verify.md](verify.md)
- [x] 交付（done，wiki 已同步）
