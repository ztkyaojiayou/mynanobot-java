---
id: C-016
slug: observability-reliability
status: analyzing
created: 2026-06-07
owner: Owner Agent
---

# C-016 生产级可观测性与可靠性加固

## 用户故事

作为运维/Owner Agent，我想要一套端到端落地的可观测性栈（指标 + 链路 + 告警）与可靠性基线（退化策略、容量压测、回滚方案），以便系统在准生产/生产环境「能稳定跑」，并可在出问题时快速定位与回滚。

## 非目标（Out of Scope）

- 本次不实现业务功能（聚焦横切的可观测与可靠性）。
- 本次不替换 C-005 的调用治理（在其基础上做指标暴露、告警、容量验证、回滚收口）。
- 本次不搭建外部监控平台（Prometheus/Grafana 部署归运维；本次保证指标可被采集 + 提供示例配置）。

## 验收标准（AC）

- AC-1: 指标落地：暴露 `接口协议.md` §6 与 `运行时可靠性.md` §5 全部关键指标——`agent.call.latency`(P95)、`agent.call.error.rate`、`llm.token.usage`、`api.rate_limit.hit`、`memory.cache.hit.rate`、`human.intervention.count`、`plan.notebook.steps`、Redis/Nacos 可用性、`fallback` 命中率（Micrometer → Prometheus 端点）。
- AC-2: 链路追踪：OpenTelemetry 接入，`traceId` 在 REST→Supervisor→子 Agent→外部调用全链路贯穿，可在日志按 traceId 串联。
- AC-3: 告警阈值：按 §6 定义阈值（latency>10s、error.rate>5%、rate_limit.hit>0、cache.hit.rate<80%、notebook.steps>15）提供告警规则示例。
- AC-4: 退化可观测（`运行时可靠性.md` §4）：Redis 不可用退化内存级并告警、Nacos 抖动不致 Agent 整体失效，任何退化「不静默成功」，均有日志+指标。
- AC-5: 容量/压测基线（§6）：提供压测脚本与报告，回答——单机并发上限、最先瓶颈 Agent、Redis/Nacos/外部 API 谁先达上限、fallback 开启后核心服务是否可维持；结果记录于本 change verify.md 或运维文档。
- AC-6: 回滚基线（§7）：`deploy-verify` 含明确回滚命令/步骤与上一稳定版本记录；无回滚方案不得宣称生产级通过（§8）。

## 边界情况（≥3）

- 当 Redis 不可用时，退化到内存级短暂能力并产生告警日志，健康检查反映、指标可见（非静默）。
- 当 Nacos 短时抖动时，已注册 Agent 依本地缓存/重试维持，不整体失效。
- 当 `plan.notebook.steps` 超 15 时，触发告警且 Supervisor 安全终止（与 C-010 联动）。
- 当某外部 API 达限频上限时，`api.rate_limit.hit` 计数上报并触发降级，不放大故障。
- 当压测达到瓶颈时，fallback 开启后核心规划仍可返回（降级可用），而非雪崩。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 指标/追踪开销对主链路延迟影响 < 5% |
| 可靠性 | 单依赖故障不打死整链；退化均可观测；回滚路径明确 |
| 安全 | 指标/日志脱敏；不暴露密钥/敏感字段 |
| 可观测 | 指标齐全 + traceId 全链路 + 告警规则可用（这是本变更的核心交付） |

## 设计约束

- 必须对齐 `运行时可靠性.md`（§2~§8 强制）、`接口协议.md` §6、`编码规范.md` 日志最小字段。
- 指标埋点统一在 C-005 治理层与各 Agent 边界，避免散落复制；指标命名遵循 §6 既有命名。
- 退化策略复用并收口 C-003/C-005 的健康检查与降级，本变更补齐「可观测 + 告警 + 容量 + 回滚」闭环。
- 压测与回滚产物落档（verify.md / 运维文档），对齐 deploy-verify 技能门禁。

## 契约影响

- REST: 复用 `/actuator/health|metrics|info`（§1.5）；不新增业务接口
- A2A: 不改协议；强化 `traceId/fallback` 的可观测使用
- 数据模型: 无新增实体
- Redis / ReMe: 退化策略可观测；不改 Schema

## 影响面

- 模块 / Agent / Skill: `huazai-trip-skills`（治理指标）、全部 agent（边界埋点）、`huazai-trip-server`（actuator/OTel/告警配置）、`huazai-trip-tests`（压测/容量）
- 外部 API: Prometheus（采集）、OpenTelemetry collector
- wiki: 对齐 `运行时可靠性.md`、`接口协议.md` §6；如新增告警阈值细则可回写

## 规则归属

- 业务不变量归属: 不适用（横切可观测/可靠性）
- 外部调用治理归属: 在 C-005 统一治理上叠加指标/告警/退化可观测
- 可观测性要求: 本变更即「可观测性要求」的统一落地——`traceId/planId/agentId/taskType/msgId/fallback` 全字段 + §6 全指标

## 测试策略

- 先写失败测试: 指标暴露存在性、traceId 端到端贯穿、退化产生告警（非静默）、notebook.steps>15 告警 → 先红。
- Happy Path: 正常请求 → 全指标上报 + traceId 串联可查。
- 边界测试: Redis/Nacos 退化告警、限频上报、步数超限告警。
- 降级测试: 注入依赖故障 → fallback 命中率指标上升 + 核心服务维持。
- 回归测试: 指标/追踪/告警纳入回归；压测周期性复跑。

## 验收用例

- Case-1: `/actuator/metrics`（Prometheus）含 §6 全部关键指标。
- Case-2: 一次规划请求 → 日志可按单一 traceId 串起 REST→Supervisor→4 子 Agent→外部调用。
- Case-3: 停 Redis → 退化内存级 + 告警日志 + 健康检查 DOWN（非静默成功）。
- Case-4: 压测报告回答四问题（并发上限/瓶颈 Agent/先达上限依赖/fallback 下核心可用）。
- Case-5: deploy-verify 含回滚命令 + 上一稳定版本记录。

## 任务拆解（≤1 天/项，DAG 无环）

- [ ] T-1: Micrometer→Prometheus 指标暴露 + §6 指标埋点（先红存在性测试）· P0 · 依赖 C-005 · 模块 skills/server
- [ ] T-2: OpenTelemetry 链路 + traceId 端到端贯穿验证 · P0 · 依赖 C-005,C-011 · 模块 server/skills
- [ ] T-3: 告警规则示例（阈值对齐 §6）+ 退化可观测（Redis/Nacos）· P1 · 依赖 T-1 · 模块 server
- [ ] T-4: 容量/压测脚本 + 报告（四问题）· P1 · 依赖 C-012 · 模块 tests
- [ ] T-5: 回滚方案 + 上一稳定版本记录（deploy-verify 收口）· P0 · 依赖 T-1..T-4 · 模块 server/运维
- [ ] T-6: 覆盖率/门禁补齐 + 生产级判定清单（§8）· P1 · 依赖 T-1..T-5 · 模块 tests

## 流水线进度

- [x] ① 需求分析（analyzing）
- [ ] ② 编码实现（coding）
- [ ] ③ 单测编写（testing，覆盖率 ≥80%）
- [ ] ④ 专家评审（reviewing，0 严重问题）→ review.md
- [ ] ⑤ CI 门禁（ci，全绿）
- [ ] ⑥ 部署验证（verifying）→ verify.md
- [ ] 交付（done，wiki 已同步）
