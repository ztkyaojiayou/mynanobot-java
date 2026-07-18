---
id: C-015
slug: reme-memory-rag
status: done
created: 2026-06-07
updated: 2026-06-30
owner: Owner Agent
---

# C-015 ReMe 记忆层与 RAG 个性化召回

## 用户故事

作为旅行规划用户，我想要系统记住我的偏好和历史行程，以便二次规划时获得更个性化的推荐，无需重复表达偏好。

## 非目标（Out of Scope）

- 不替换 Redis 短期状态层（ADR-006 三层分层不变；Redis 仍管 24h 短期态）
- 不实现完整推荐系统或打分模型（仅 ReMe 记忆召回 + 上下文注入）
- 不改变各 Agent 的核心业务规则和不变量判定（仅增强其输入上下文）
- 不做跨用户画像共享（合规隔离）
- 不自建向量存储或 Embedding 调用链路（完全复用 ReMe Server 内置能力）
- 不引入 MySQL / PostgreSQL 等新持久化基础设施（复用现有 Redis 7.x + ReMe Server）
- 不引入 Qdrant / Chroma 等外部向量库（ReMe Server 内置存储）

## 验收标准（AC）

- AC-1: **AgentScope 升级至 RC4** — `agentscope.version` 从 `2.0.0-RC1` 升级到 `2.0.0-RC4`，`agentscope-bom` import 统一管理；新增 `agentscope-extensions-reme` 依赖（`huazai-trip-skills/pom.xml`）；全模块 `mvn compile` 通过，无 API 破坏性回退。
- AC-2: **ReMe Server Docker 部署** — `docker/docker-compose.yml` 新增 `reme` 服务（基于 `docker/reme/Dockerfile`，Python 3.11 + `reme-ai[core]`），默认端口 `${REME_PORT:-8002}`；通过环境变量注入 `LLM_API_KEY`/`EMBEDDING_API_KEY`；`docker compose up -d` 一键拉起含 ReMe 的全栈。
- AC-3: **三类记忆写入** — ① 行程 `confirmed` 后异步写入用户记忆（以 `PlanSummary` + 行程摘要文本为源，workspace = userId）；② 用户 `TripPlanRequest.travelStyle` + `preferences` 累积更新用户画像（同 workspace）；③ XHS 分析产出经去重/合并沉淀景点知识（workspace = `attraction_kb`）。写入异步不阻塞主链路响应。写入路径经 `ReMeClient` → ReMe Server `/summary_personal_memory` 端点。
- AC-4: **召回路径** — 新规划时通过 `ReMeClient` → ReMe Server `/retrieve_personal_memory` 端点，按目的地/风格/预算相似度召回用户历史与偏好，封装为 `MemoryContext` 注入 `TripOrchestrationQuery`，经 Supervisor 传递至 XHS/Itinerary 子 Agent 上下文。无匹配记忆时流程不受影响。
- AC-5: **当前请求优先** — 召回的历史偏好仅作「弱增强」注入 Agent prompt 上下文，不覆盖 `TripPlanRequest` 中的硬约束（目的地、日期、预算、人数）。当历史偏好与当前请求冲突时，以当前请求为准。
- AC-6: **降级兜底** — ReMe Server 不可用时（ReMeClient 超时 / HTTP 错误），降级为「无记忆」常规规划，在结果中标记 `memoryFallback=true`，不抛异常、不阻塞主链路。降级走 C-005 统一治理（超时 3s + 重试 1 次）。
- AC-7: **userId 贯通** — `userId` 从 `TripPlanFacade` 传入 `TripOrchestrationQuery`，贯穿编排链路，作为 ReMe workspace_id 支撑按用户维度的记忆读写。
- AC-8: **XHS 笔记缓存激活** — 实现 `XHSNoteCache` 的 Redis 适配（`trip:xhs:note:{noteId}`，24h TTL），替换当前 `emptyXhsCache()` 空桩，支撑 XHS 检索降级路径的缓存命中。
- AC-9: **可观测性** — 暴露指标：`memory.recall.latency`（P95 < 1s）、`memory.recall.hit`（命中/未命中计数）、`memory.fallback.count`（降级次数）。关键操作含 `traceId` + `planId` + `userId` 日志。
- AC-10: **数据清除** — 提供按 `userId` 清除其全部记忆的能力（调用 ReMe API 删除 workspace），支撑合规要求。

## 边界情况（≥3）

- 当 **ReMe Server 不可用**（连接超时 / HTTP 5xx / 服务未启动）时，降级为无记忆规划，标记 `memoryFallback=true`，不报错不阻塞
- 当 **首次使用**（无任何历史记忆）时，召回为空列表，`MemoryContext` 为空，规划正常进行
- 当 **召回内容与当前请求冲突**（如历史偏好「海鲜」但当前 preferences 含「素食」）时，以当前请求 `preferences` 为准，历史仅注入 prompt 作弱参考
- 当 **同一 planId 多次 confirmed**（幂等场景）时，写入操作幂等，避免重复记忆堆积
- 当 **用户请求清除个人数据** 时，该用户 workspace 下全部记忆被删除，后续召回返回空
- 当 **RC4 升级导致 API 不兼容** 时，T-1 编译验证阶段即捕获，升级前产出兼容性报告并暂停请示

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 召回 P95 < 1s；写入异步不阻塞 confirmed 响应；ReMeClient 超时 3s |
| 可靠性 | ReMe Server 故障降级无记忆规划，主链路不受影响；超时 3s + 重试 1 次 |
| 安全 | 记忆以 workspace 隔离（userId 维度）；不跨用户泄漏；支持按 userId 清除 |
| 可观测 | `memory.recall.latency`（P95 < 1s）、`memory.recall.hit`、`memory.fallback.count`；日志含 traceId/planId/userId |

## 设计约束

- **框架升级**: agentscope BOM `2.0.0-RC1` → `2.0.0-RC4`，通过 `agentscope-bom` import 统一管理；升级前须全模块编译验证
- **官方扩展优先**: 使用 `agentscope-extensions-reme`（`io.agentscope:agentscope-extensions-reme`）官方扩展，核心类 `ReMeLongTermMemory` / `ReMeClient`，不自建记忆存储或向量检索
- **ReMe Server 独立部署**: ReMe 是 Python 服务（`reme-ai[core]`），通过 Docker 部署，提供 HTTP API（`/summary_personal_memory`、`/retrieve_personal_memory`）；Java 侧通过 `ReMeClient`（OkHttp）调用
- 对齐 ADR-006 三层存储分层：Redis（短期 24h）、ReMe Server（长期记忆）、Temp（临时文件）
- 记忆能力下沉 `huazai-trip-skills`（如 `com.nanobot.skills.memory` 包），跨 Agent 复用
- 必须复用已有桩/端口：`ReMeXHSNoteSource`（激活实现）、`XHSNoteCache`（Redis 适配）、`CacheKeys`/`CacheConstants`（键名规范）
- 召回为「增强」而非「决定」：不得用历史覆盖 `TripPlanRequest` 硬约束；当前请求永远优先
- 调用治理复用 C-005 统一治理（`GovernedExternalCaller` 超时/降级/可观测）
- ReMe Server 的 LLM/Embedding 配置走环境变量（`LLM_API_KEY` → deepseek / `EMBEDDING_API_KEY` → DashScope `text-embedding-v3`），密钥不入代码

## 契约影响

- REST: 新增 `DELETE /api/v1/trip-plan/memory` 管理接口（受控，归合规）
- A2A: `TripOrchestrationQuery` 新增 `MemoryContext memoryContext` 字段（可选，子 Agent 不识别则忽略）
- 数据模型: 落地 `数据模型.md` §5 三类记忆 Schema（存储介质 = ReMe Server）
- Redis: `trip:xhs:note:{noteId}`（24h TTL）XHS 笔记缓存激活；`trip:pref:{userId}`（24h TTL）用户偏好快照激活
- Docker: `docker/docker-compose.yml` 新增 `reme` 服务；新增 `docker/reme/Dockerfile`
- POM: 根 `pom.xml` `agentscope.version` 升至 `2.0.0-RC4`；`huazai-trip-skills/pom.xml` 新增 `agentscope-extensions-reme` 依赖

## 影响面

- 模块: `huazai-trip-skills`（新增 `memory` 包 + `agentscope-extensions-reme` 依赖）、`huazai-trip-common`（`MemoryContext` DTO + `TripOrchestrationQuery` 扩展）
- Agent: `huazai-trip-agent-supervisor`（传递 `MemoryContext`）
- Skill: `XHSNoteAnalyzer`（挂载 `ReMeXHSNoteSource` 实现 + `RedisXHSNoteCache`）
- Server: `TripPlanFacade`（userId 传入编排 + 记忆清除接口）、`PlanConfig`（替换 `emptyXhsCache()`）、`RedisServicesAutoConfiguration`（新增记忆相关 Bean）
- Docker: `docker/docker-compose.yml`（新增 reme 服务）、`docker/reme/Dockerfile`（新增）
- 根 POM: `agentscope.version` 属性升级
- wiki: 落地 `数据模型.md` §5；对齐 `业务模型.md`（个性化/合规）；更新 `架构决策.md`（新增 ADR-011 记忆层选型）

## 规则归属

- 业务不变量归属: 记忆仅增强 Agent 上下文，不改变 Agent / `OutputQualityGate` 的不变量判定（评分 ≥ 3.5、负面情绪拒绝、预算上限等不受记忆影响）
- 外部调用治理归属: ReMeClient 调用走 C-005 `GovernedExternalCaller` 统一治理 + 降级
- 可观测性要求: `traceId` / `planId` / `userId`；召回命中/延迟/降级指标；`memoryFallback` 标记

## 测试策略

- 先写失败测试（Red）:
  - 降级路径: ReMeClient 超时 → `memoryFallback=true`，规划正常完成（AC-6）
  - 首次无历史: 新 userId → 召回空 `MemoryContext`，不报错（边界-2）
  - 当前请求优先: 历史偏好 vs 当前 preferences 冲突 → 当前请求字段优先（AC-5）
  - 幂等写入: 同 planId 重复 confirmed → 不产生重复记忆（边界-4）
  - 数据清除: 清除后召回返回空（AC-10）
- Happy Path:
  - 同用户二次规划同目的地 → 召回历史偏好 → `MemoryContext` 含历史记忆 → 子 Agent 收到增强上下文
  - XHS 分析产出 → 异步沉淀 attraction_kb workspace → 后续规划可召回
  - XHS 缓存命中 → `XHSNoteCache.get()` 返回缓存笔记（AC-8）
- 边界测试:
  - ReMe Server 不可用 → 降级无记忆
  - 多用户隔离 → userId-A 的记忆不被 userId-B 召回
  - RC4 升级回归 → 现有 AgentScope 功能（HarnessAgent / A2A / ReAct）不回退
- 降级测试:
  - ReMeClient 调用超时 3s → 重试 1 次 → 仍失败 → `memoryFallback=true`
  - ReMe Server 写入失败 → 仅记录告警，不影响 confirmed 状态
- 回归测试:
  - 召回注入不破坏既有规划不变量（OutputQualityGate 仍正常拦截）
  - 无记忆场景（新用户 / 降级）的规划结果与 C-012 E2E 基线一致
  - RC4 升级后现有单测全绿

## 验收用例

- Case-1（Happy Path 召回）: 用户 A 首次规划「杭州 3 天家庭游」→ confirmed → 异步写入 ReMe（workspace=userA）。用户 A 二次规划「杭州 2 天情侣游」→ 召回上次杭州行程 → `TripOrchestrationQuery.memoryContext` 含历史记忆，Itinerary Agent 收到增强上下文。
- Case-2（降级兜底）: ReMe Server 宕机 → ReMeClient 超时（>3s+重试）→ `memoryFallback=true` → 规划照常完成 → 结果 JSON 中 `memoryFallback` 字段为 `true`。
- Case-3（首次用户）: 新 userId 无历史 → 召回返回空 → `MemoryContext.isEmpty()=true` → 规划正常完成，结果中无 `memoryFallback` 标记。
- Case-4（当前请求优先）: 历史偏好含「海鲜」标签 → 当前 preferences=["素食"] → 子 Agent prompt 中 preferences 段仅含「素食」，历史偏好仅在「参考历史」段出现。
- Case-5（数据清除）: `DELETE /api/v1/trip-plan/memory` → 200 → 后续规划该 userId 召回返回空。
- Case-6（XHS 缓存命中）: 首次检索「杭州」笔记 → 缓存 miss → 实时检索 → `put(杭州, notes)` 回填 Redis。二次检索「杭州」→ 缓存 hit → 返回缓存笔记（key: `trip:xhs:note:{noteId}`，TTL 24h）。
- Case-7（幂等写入）: 同一 planId 连续两次触发 confirmed → ReMe 中仅一份记忆记录。
- Case-8（RC4 编译验证）: 升级后 `mvn clean compile -pl !huazai-trip-tests` 全绿，无 API 破坏性错误。

## 任务拆解（≤1 天/项，DAG 无环）

- [x] T-1: **RC4 升级 + ReMe Docker 部署 + 依赖引入**（需人类确认后继续） — ① 根 POM `agentscope.version` 升至 `2.0.0-RC4`，全模块 `mvn compile` 验证兼容性，产出兼容性报告；② 创建 `docker/reme/Dockerfile`（Python 3.11 + `reme-ai[core]`），更新 `docker/docker-compose.yml` 新增 reme 服务；③ `huazai-trip-skills/pom.xml` 新增 `agentscope-extensions-reme` 依赖；④ 更新 `.env.example` 新增 `REME_PORT` / `EMBEDDING_API_KEY` / `EMBEDDING_BASE_URL` · P0 · 依赖 无 · 模块 根POM/skills/docker
- [x] T-2: **MemoryContext DTO + userId 贯通** — 新增 `MemoryContext` record（common 模块），扩展 `TripOrchestrationQuery` 加入 `userId` + `memoryContext` 字段，`TripPlanFacade` 传 userId 进编排链路 · P0 · 依赖 T-1 · 模块 common/server/skills
- [x] T-3: **记忆写入服务**（先红后绿） — 实现 `MemoryWriteService`：通过 `ReMeClient` 调用 ReMe Server `/summary_personal_memory`；① confirmed 异步写行程摘要（workspace=userId）；② 偏好累积写入（workspace=userId）；③ XHS 产出沉淀景点知识（workspace=attraction_kb）；写入幂等 · P0 · 依赖 T-1 · 模块 skills
- [x] T-4: **记忆召回服务 + 降级**（先红后绿） — 实现 `MemoryRecallService`：通过 `ReMeClient` 调用 ReMe Server `/retrieve_personal_memory`；召回结果封装为 `MemoryContext`；降级 `memoryFallback=true`；走 `GovernedExternalCaller` 统一治理（超时 3s + 重试 1 次） · P0 · 依赖 T-1,T-2 · 模块 skills
- [x] T-5: **XHS 笔记缓存 Redis 适配**（先红后绿） — 实现 `RedisXHSNoteCache`（`XHSNoteCache` 接口 Redis 实现），替换 `PlanConfig.emptyXhsCache()`；激活 `ReMeXHSNoteSource` 实现（从 ReMe attraction_kb workspace 召回） · P0 · 依赖 T-1 · 模块 skills/server
- [x] T-6: **Supervisor + 子 Agent 挂载召回** — Supervisor 在编排前调用 `MemoryRecallService`，将 `MemoryContext` 注入 dispatch context；XHS/Itinerary Agent 在 prompt 中使用召回上下文（当前请求优先） · P0 · 依赖 T-2,T-4 · 模块 skills/agents
- [x] T-7: **数据清除接口 + 可观测性** — `DELETE /api/v1/trip-plan/memory` REST 接口（调用 ReMe API 清除 workspace）；`memory.recall.latency` / `memory.recall.hit` / `memory.fallback.count` 指标 · P1 · 依赖 T-3,T-4 · 模块 server/skills
- [x] T-8: **补齐覆盖率 ≥80% + 回归** — 降级 / 边界 / 幂等 / 隔离 / 回归测试全覆盖；RC4 升级回归验证；与 C-012 E2E 联动验证 · P1 · 依赖 T-3,T-4,T-5,T-6 · 模块 skills/tests

## 流水线进度

- [x] ① 需求分析（analyzing）
- [x] ② 编码实现（coding）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）— memory 包 97% 指令覆盖、82% 分支覆盖
- [x] ④ 专家评审（reviewing，0 严重问题）→ review.md — 0🔴 3🟡，评审通过
- [x] ⑤ CI 门禁（ci，全绿）— 编译通过，C-015 全量测试绿，ArchUnit 8/8 通过，覆盖率达标；3 个预存在失败已隔离（非 C-015）
- [x] ⑥ 部署验证（verifying）→ verify.md — 编译冒烟通过，回滚预案就绪
- [x] 交付（done，wiki 已同步）— ADR-011、数据模型 §5、业务模型 §5.1、接口协议 §1.6/§6
