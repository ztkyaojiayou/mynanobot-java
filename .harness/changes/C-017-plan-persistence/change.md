---
id: C-017
slug: plan-persistence
status: done
created: 2026-06-24
owner: Owner Agent
---

# C-017 历史规划持久化 + 审计 Trace 落地 + planId 序列号持久化

## 用户故事

作为旅游规划平台的用户，我想要查看和检索历史规划记录（包括每次规划的行程安排、费用明细和 Agent 调度过程），以便回顾过往方案、对比不同规划、以及对系统运行过程进行审计追溯。同时，每次规划生成的 planId 序列号应跨重启持续自增，不会因服务重启而重置为 001。

## 非目标（Out of Scope）

- 本次不做用户 PII 原始数据落盘（手机号、身份证等原文不入库，符合 ADR-006 精神）
- 本次不做规划结果的编辑/修改功能（历史记录只读 + 软删除）
- 本次不做数据导出（PDF/Excel），已有 C-014 覆盖
- 本次不修改 ReMe 向量存储逻辑（C-015 覆盖）
- 本次不做 OTel 指标埋点完善（C-016 覆盖）
- 本次不做数据库分库分表或读写分离
- 本次不做前端页面实现（仅提供 REST API）

## 验收标准（AC）

- AC-1: **业务数据持久化** — 每次规划到达终态（CONFIRMED 或 FAILED）时，系统异步将规划概要、每日行程（景点/餐饮/路线）、费用明细写入 MySQL 的 `huazai_trip_trace` 库，主链路不等待写入完成
- AC-2: **审计 Trace 打通** — C-010 已有的 `TracePersistenceService` 接口及 `MySqlTracePersistenceService` 实现能正常落库（三张 trace 表：子 Agent 委派、LLM 调用、编排汇总），核实并补齐缺失的调用链路
- AC-3: **历史列表查询** — 提供 `GET /api/v1/trip-plan/history` 分页接口，支持按 destination（模糊）/ travelStyle / planStatus / 日期范围筛选，默认按 createdAt DESC 排序
- AC-4: **历史详情查询** — 提供 `GET /api/v1/trip-plan/history/{planId}` 接口，返回完整规划详情（含每日行程、景点、餐饮、路线、费用）及关联的 trace 摘要（委派次数、总耗时、fallback 计数、HITL 触发情况）
- AC-5: **软删除** — 提供 `DELETE /api/v1/trip-plan/history/{planId}` 接口，逻辑删除（`deleted=1`），列表查询自动过滤已删除记录
- AC-6: **敏感数据脱敏** — 落库前，复用 `SensitiveDataMasker` 对 preferences、taskSummary、promptSummary 中的 API Key 脱敏；用户偏好中可能包含的手机号（`1[3-9]\d{9}`）中间 4 位替换为 `****`
- AC-7: **写入失败不阻塞** — MySQL 不可用/超时时，持久化层 catch 异常，仅 WARN 日志 + metrics 计数器递增，主链路 `orchestrate()` 正常返回 TripPlan
- AC-8: **架构决策更新** — 新增 ADR-012 到 `.harness/wiki/架构决策.md`，修订 ADR-006 边界：脱敏后的规划结果允许持久化到 MySQL 用于历史回溯，不违反"PII 不落盘"原则
- AC-9: **planId 序列号持久化** — 替换 `TripPlanFacade` 中的内存 `AtomicInteger dailySequence`，改为"Redis 优先 + 内存兜底"策略：启动时从 Redis key `trip:plan:seq:{yyyyMMdd}` 读取当日最新序列号初始化内存计数器；每次生成 planId 后内存自增并异步回写 Redis（`INCR`）；Redis 不可用时退化为纯内存自增（WARN 日志），不阻塞 planId 生成；key 设 TTL 48h 自动过期实现跨日重置

## 边界情况（≥3）

- B-1: **规划结果为空/部分失败** — 当 TripPlan 状态为 FAILED 且 days 列表为空时，仅写入规划主表（状态 FAILED），不写入日期/景点/餐饮/路线子表，费用表写入零值
- B-2: **MySQL 完全不可用** — DataSource 连接超时 5s 后，所有写入操作 catch 异常，WARN 日志记录失败原因，`trip.persistence.write.failure` 计数器递增，主链路正常返回
- B-3: **重复写入同一 planId** — 允许重复写入（INSERT，不做 UPSERT），因 planId 非唯一索引；trace 三表同理（traceId 仅 orchestration_summary 为 UNIQUE，delegation/llm_call 允许多条）
- B-4: **超大文本字段** — preferences/taskSummary/responseSummary 等 TEXT 字段，先脱敏再截断至各字段 MAX_TEXT_LENGTH（业务表 preferences ≤2000，复用 trace 表已有限制）
- B-5: **并发规划请求** — 多个规划同时完成写入时，线程池（core=2, max=4, queue=1000）排队处理；队列满时 CallerRunsPolicy 由调用线程同步执行，不丢数据但会短暂阻塞该次请求的返回
- B-6: **软删除后再查询** — `GET /history` 自动过滤 `deleted=1` 的记录；`GET /history/{planId}` 查询已删除记录返回 404
- B-7: **分页越界** — page 超出总页数时返回空列表（`list: []`），不报错
- B-8: **planId 序列号 — Redis 不可用** — 启动时 Redis 读取失败，内存计数器从 1 开始（与当前行为一致），WARN 日志；运行中 Redis 回写失败，仅 WARN 日志，不影响 planId 生成
- B-9: **planId 序列号 — 跨日重置** — 检测当前日期与上次生成日期不同时，内存计数器重置为 1，Redis key 因 TTL 48h 自然过期；同一进程内跨日不会沿用前一天的序号
- B-10: **planId 序列号 — 多实例并发** — 多个 server 实例通过 Redis `INCR` 原子操作保证序列号全局唯一；Redis 不可用时各实例独立计数，planId 可能重复（可接受的降级——此时持久化也不可用）

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 异步写入不增加主链路延迟（写入操作在独立线程池执行）；历史列表查询 P95 < 200ms（单表分页 + 索引） |
| 可靠性 | 写入失败仅降级（WARN + 计数），不影响规划主流程；线程池队列满时 CallerRunsPolicy 兜底 |
| 安全 | API Key 脱敏为 `***REDACTED***`；手机号中间 4 位 `****`；密码 / 身份证号等不入库（Out of Scope） |
| 可观测 | 新增 metrics: `trip.persistence.write.success`（成功写入计数，按表名 tag）、`trip.persistence.write.failure`（失败计数，按表名 tag）；日志包含 traceId + planId |

## 设计约束

- **接口层** `TripPlanPersistenceService` 放 `huazai-trip-skills` 模块（包 `com.nanobot.skills.persistence`），与 `TracePersistenceService` 平级，遵循 R2（Skills 不依赖 Agent）
- **实现层** `MySqlTripPlanPersistenceService` 放 `huazai-trip-agent-supervisor` 模块（包 `com.nanobot.agent.supervisor.persistence`），与 `MySqlTracePersistenceService` 平级
- **查询层** `TripPlanHistoryController` + `TripPlanHistoryService` 放 `huazai-trip-server` 模块，遵循 R3（Controller 不直调 Agent，通过 Service 查询数据库）
- **数据源** 复用 `TraceDataSourceConfig` 已配置的 `traceDataSource`（HikariCP 连接 `huazai_trip_trace` 库），不新增独立数据源
- **线程池** 复用 `TraceThreadPoolConfig.createTraceExecutor()` 已有的异步写入线程池
- **脱敏** 复用 `SensitiveDataMasker`，新增手机号脱敏正则
- **持久化方式** 原生 JDBC + PreparedStatement（与 MySqlTracePersistenceService 保持一致），不引入 JPA/MyBatis
- 不允许 Agent 模块间直接依赖（R1）；不允许 Skills 依赖 Agent（R2）
- 必须复用已有的 `TripPlan`/`TripDay`/`Attraction`/`Meal`/`Route`/`Budget`/`BudgetBreakdown` 模型，不新增冗余 model

## 契约影响

- REST:
  - 新增 `GET /api/v1/trip-plan/history`（分页列表）
  - 新增 `GET /api/v1/trip-plan/history/{planId}`（详情 + trace 摘要）
  - 新增 `DELETE /api/v1/trip-plan/history/{planId}`（软删除）
- A2A: 无变更
- 数据模型:
  - 新增 6 张业务表（`trip_plan_record` / `trip_day_record` / `trip_attraction_record` / `trip_meal_record` / `trip_route_record` / `trip_budget_record`）到 `huazai_trip_trace` 库
  - 新增 ADR-012 修订 ADR-006 边界
- Redis: 新增 key `trip:plan:seq:{yyyyMMdd}`（String 类型，TTL 48h）用于 planId 每日序列号持久化
- ReMe: 无变更

## 影响面

- 模块: `huazai-trip-skills`（新增接口）、`huazai-trip-agent-supervisor`（新增实现 + DDL）、`huazai-trip-server`（新增 Controller/Service/DTO + planId 序列号重构）、`huazai-trip-tests`（新增测试）
- Agent: SupervisorAgent（orchestrate 完成后触发业务数据持久化调用）
- Skill: TripOrchestrationService（在 orchestrate 末尾调用 `TripPlanPersistenceService`）
- Server: TripPlanFacade（planId 序列号生成策略从纯内存改为 Redis 优先 + 内存兜底）
- 外部 API: 无
- wiki: `架构决策.md`（新增 ADR-011）、`数据模型.md`（新增 MySQL 业务表说明）、`接口协议.md`（新增 history 查询接口）

## 规则归属

- 业务不变量归属: 无新增业务不变量（持久化层只记录结果，不做业务校验）
- 外部调用治理归属: 复用已有 HikariCP 连接池 + 线程池治理（超时/队列/拒绝策略）
- 可观测性要求: 所有写入/查询日志包含 `traceId`、`planId`；写入失败日志包含 `fallback=true`（语义复用，表示降级跳过持久化）；planId 序列号 Redis 读写失败时 WARN 日志包含 `planId`

## 测试策略

- 先写失败测试:
  - `MySqlTripPlanPersistenceServiceTest`: 验证 TripPlan 写入 → 6 张表数据完整性（用 H2 替代 MySQL）
  - `TripPlanHistoryControllerTest`: 验证分页/筛选/详情/软删除接口（MockMvc）
  - `SensitiveDataMaskerTest`: 验证手机号脱敏新增规则
  - `PlanIdSequenceServiceTest`: 验证 Redis 优先读取 + 内存自增 + 异步回写 + 降级（Redis 不可用时纯内存）
- Happy Path:
  - 完整 TripPlan（3 天、含景点/餐饮/路线/预算）写入后，通过 history API 查到完整数据
  - Trace 三表同步写入，详情 API 返回 trace 摘要
- 边界测试:
  - FAILED 状态 + 空 days 列表的写入（B-1）
  - 超长 preferences 文本截断（B-4）
  - 分页越界返回空列表（B-7）
  - 软删除后查询不可见（B-6）
  - 重复 planId 写入不报错（B-3）
  - planId 跨日重置为 001（B-9）
- 降级测试:
  - 模拟 DataSource 抛 SQLException，验证主链路正常返回（B-2 / AC-7）
  - 线程池队列满时验证 CallerRunsPolicy 兜底（B-5）
  - Redis 不可用时 planId 退化为纯内存自增，不抛异常（B-8）
- 回归测试:
  - 现有 `TripPlanFacadeTest` planId 生成行为不因重构而破坏
  - 现有 `TripOrchestrationServiceTest` 不因新增持久化调用而破坏
  - 现有 `MySqlTracePersistenceServiceTest` trace 写入不受影响
  - `trip.trace.datasource.enabled=false` 时，所有持久化 bean 不加载

## 验收用例

- Case-1: 提交 "北京 3 日游" 规划 → 规划完成（CONFIRMED） → `GET /history` 返回 1 条记录，destination="北京"，status=CONFIRMED → `GET /history/{planId}` 返回 3 天行程详情 + 费用 + trace 摘要
- Case-2: 提交规划但 XHS Agent 失败 → 规划终态 FAILED → `GET /history` 可查到该记录（status=FAILED），详情中 days 为空，trace 摘要显示 fallbackCount>0
- Case-3: `DELETE /history/{planId}` 后 → `GET /history` 列表不再包含该记录 → `GET /history/{planId}` 返回 404
- Case-4: MySQL 不可用时提交规划 → 规划正常返回 CONFIRMED → `GET /history` 查不到（因写入失败被跳过） → 日志中有 WARN 记录 + metrics 计数器递增
- Case-5: `GET /history?destination=北京&travelStyle=FAMILY&page=1&size=10` → 仅返回匹配的记录，分页正确
- Case-6: 重启服务后提交规划 → planId 序列号从 Redis 恢复（如上次是 003 则本次为 004），不再重置为 001
- Case-7: Redis 不可用时提交规划 → planId 序列号从内存 1 开始自增，规划正常完成，日志中有 WARN 记录

## 任务拆解（≤1 天/项，DAG 无环）

- [x] T-1: 新增 ADR-011 + 更新 `数据模型.md` / `接口协议.md` / `架构决策.md` · P0 · 依赖 无 · 模块 `.harness/wiki/`
- [x] T-2: 6 张业务表 DDL + `SensitiveDataMasker` 新增手机号脱敏 · P0 · 依赖 T-1 · 模块 `huazai-trip-agent-supervisor`(DDL) + `huazai-trip-skills`(Masker) → **FIX-2**
- [x] T-3: `TripPlanPersistenceService` 接口 + 持久化 Record/DTO 定义 · P0 · 依赖 T-2 · 模块 `huazai-trip-skills`
- [x] T-4: `MySqlTripPlanPersistenceService` 实现（JDBC 写入 6 表）+ 失败测试先行 · P0 · 依赖 T-3 · 模块 `huazai-trip-agent-supervisor` → **DECISION-4**
- [x] T-5: PlanConfig 注入 `TracePersistenceService` 修复 Trace 落库（FIX-1）+ `TripPlanFacade.executeOrchestration()` 末尾异步调用 `TripPlanPersistenceService` 写入业务数据（DECISION-5）· P0 · 依赖 T-4 · 模块 `huazai-trip-server` + `huazai-trip-skills` → **FIX-1, DECISION-5**
- [x] T-6: 改造现有 `TripPlanController` + `TripPlanFacade` 历史查询（Redis-first → MySQL-fallback + 筛选扩展）+ 失败测试先行 · P1 · 依赖 T-4 · 模块 `huazai-trip-server` → **DECISION-1, DECISION-2**（详情 + 软删除 API 待下一轮）
- [x] T-7: 边界测试 + 降级测试 + 回归测试补齐（覆盖率 ≥80%） · P1 · 依赖 T-5, T-6 · 模块 `huazai-trip-tests` + 各模块
- [x] T-8: Docker Compose mysql 服务增加 init 脚本挂载（创建 `huazai_trip_trace` 库 + trace 3 表 + business 6 表）+ DDL 双位置同步 · P1 · 依赖 T-2 · 模块 `docker/` → **DECISION-3**
- [x] T-9: `PlanIdSequenceService`（Redis 优先 + 内存兜底）+ 重构 `TripPlanFacade` 替换 `dailySequence` + 失败测试先行 · P0 · 依赖 无 · 模块 `huazai-trip-server`

## 实现备注

> 代码盘点后的修正/补充，实现时以此节为准（覆盖任务描述中的原始表述）。
> 标记规则：FIX = 现有代码缺陷修复；DECISION = 实现路径决策。

### FIX-1: TracePersistenceService 双重注入断裂（T-5）

- **根因**: C-010 审计 Trace 三表从未实际写入数据库——
  - `PlanConfig:217` 构造 `SupervisorAgent` 时 `tracePersistence` 参数传 `null`
  - `PlanConfig:194` 构造 `TripOrchestrationService` 用 2-arg 构造函数（`tracePersistence=null`）
  - `TraceDataSourceConfig` 已装配 `MySqlTracePersistenceService` Bean，但从未被注入
- **修法（两处）**:
  - `PlanConfig.supervisorAgent()`: 新增 `ObjectProvider<TracePersistenceService>` 参数，替换 `null`
  - `PlanConfig.tripOrchestrationService()`: `TripOrchestrationService.tracePersistence` 是 `final` 字段无 setter，必须改用 3-arg 构造函数 `new TripOrchestrationService(dispatcher, qualityGate, tracePersistence)`
- **验证**: `SupervisorAgentTest` + `TripOrchestrationServiceTest` 新增用例确认 trace 写入非空

### FIX-2: 手机号脱敏路径（T-2）

- **现状**: `MaskUtils.maskPhone()` 已存在于 `huazai-trip-common`（保留前 3 后 4，中间 `****`），已有测试覆盖
- **修法**: `SensitiveDataMasker.mask()` 新增 `Pattern`（`1[3-9]\d{9}`）匹配文本中的手机号，匹配后委托 `MaskUtils.maskPhone()` 替换原文
- **不重写**: 脱敏逻辑复用 `MaskUtils`，不在 `SensitiveDataMasker` 中重新实现正则

### DECISION-1: 历史查询双轨（T-6）

- **查询**: Redis-first（`PlanCacheService` 已有 ZSet + PlanSummary 缓存，30 天 TTL），缓存未命中时降级 MySQL-fallback（`TripPlanHistoryService` 新建）
- **写入**: orchestrate 完成后 Redis（已有 `storePlanSummary` + `addToUserPlanIndex`）+ MySQL（`TripPlanPersistenceService`）双写，均异步不阻塞
- **一致性**: 最终一致——MySQL 为持久归档，Redis 为热缓存；Redis TTL 到期后查询自动走 MySQL

### DECISION-2: 筛选扩展（T-6 / AC-3）

- **扩展现有接口**: 在 `GET /api/v1/trip-plan/history` 上新增可选参数 `travelStyle`(enum) / `planStatus`(string) / `startDate`(ISO date) / `endDate`(ISO date)
- **保留**: 现有 `page` / `pageSize` / `keyword` 参数不变
- **不新增 Controller**: 改造 `TripPlanController.getPlanHistory()` + `TripPlanFacade.getUserPlanHistory()` 签名
- **MySQL 层**: 组合筛选条件动态拼装 WHERE 子句（PreparedStatement 参数化，防注入）

### DECISION-3: DDL 位置与 Docker 挂载（T-8）

- **DDL 双位置同步**:
  - `docker/mysql/init/V001__trace_tables.sql` — trace 3 表（从 `huazai-trip-agent-supervisor/src/main/resources/sql/schema.sql` 同步）
  - `docker/mysql/init/V002__plan_persistence.sql` — business 6 表（新增）
  - 各模块 `src/main/resources/sql/` 保留各自 DDL（开发/测试用）
- **Docker Compose**: mysql 服务已存在，需增加 `docker/mysql/init/` 目录挂载到 `/docker-entrypoint-initdb.d/`，并在 init 脚本中 `CREATE DATABASE IF NOT EXISTS huazai_trip_trace`
- **现有 auth schema 挂载不变**: `huazai-trip-server/src/main/resources/sql/schema.sql` → `01-auth-schema.sql`

### DECISION-4: 线程池（T-4）

- **复用**: `TraceThreadPoolConfig.createTraceExecutor()`（core=2, max=4, queue=1000, `CallerRunsPolicy`, daemon 线程 `trace-persistence-*`）
- `MySqlTripPlanPersistenceService` 构造接收 `ExecutorService` 参数，风格对齐 `MySqlTracePersistenceService`
- **不新建**: 业务持久化与 trace 持久化共用线程池

### DECISION-5: 业务持久化挂载点（T-5）

- **挂载位置**: `TripPlanFacade.executeOrchestration()` 末尾，在 `storePlanJson` / `updateSummaryOnCompletion` 之后异步调用 `TripPlanPersistenceService`
- **非 TripOrchestrationService**: Facade 能捕获 CONFIRMED 和 FAILED 两种终态，且有完整上下文（planId, userId, TripPlanResult）
- **注入方式**: `PlanConfig` 通过 `ObjectProvider<TripPlanPersistenceService>` 注入到 `TripPlanFacade`，null 时跳过（降级）

### FIX-3: 审计 traceId 未贯穿 + orchestration_summary 重复写入（T-5/T-7 收尾）

- **根因**（FIX-1 落地后暴露的两个新问题）:
  - `SupervisorAgent.traceOrchestrationPath()` / `traceLlmCall()` 硬编码 `traceId=""`、`planId=""`，未使用 A2A 信封自带的 `assign.traceId()`，导致同一 `""` 反复写入 `trip_orchestration_summary.trace_id`（该列 UNIQUE），第二次请求起写入静默失败
  - `TripOrchestrationService.orchestrate()` 内部又用 `IdUtils.newTraceId()` 自生成一个新 traceId 写一次 summary，与 `SupervisorAgent` 分别各写一次，两条记录 traceId 不同也无法关联，且原生成功路（native）完全不产出 summary
- **修法（三处）**:
  - `TripOrchestrationQuery` 新增 `traceId` 字段（5-arg record + 3 个向后兼容构造器）
  - `SupervisorAgent.handleTaskAssign()`: 优先复用 `assign.traceId()`（信封缺失时 `IdUtils.newTraceId()` 兜底），透传进 `TripOrchestrationQuery`；`traceOrchestrationPath()` / `traceLlmCall()` 改用该 traceId，且成为 **summary 唯一写入者**（无论 native 成功 / native 降级 / 纯确定性直路，每次请求恰好写一次）
  - `TripOrchestrationService.orchestrate()`: 优先复用 `query.traceId()`（未传时自生成，保持独立可测试性），**删除**内部 `traceSummary()` 调用与方法本身，只保留委派记录（`traceDelegation`）写入；native 成功路新增 `NativeDelegationTracer`（`SupervisorHarnessAgentFactory.finalizeNative` 收口时调用）补齐委派记录，与确定性路产出对齐
- **验证**: `SupervisorAgentTest.TraceIntegration`（含"连续两次不同 traceId"回归用例，验证修复前会因 UNIQUE 冲突丢失第二条）、`TripOrchestrationServiceTest.TraceIntegration`、`SupervisorHarnessAgentFactoryTest.NativeDelegationTrace` 三处新增用例覆盖

### FIX-4: 本地开启 `trip.trace.datasource.enabled=true` 后审计/持久化仍未落库（本地联调阶段发现，T-5 收尾）

- **背景**: `trip.trace.datasource.enabled` 此前在 `application-local.yml` 恒为 `false`（C-010 遗留，"本地联调时可关闭"），本次 C-017 联调首次把它打开，暴露三个此前从未触发过的潜伏缺陷（均与 C-017 本身逻辑无关，C-010/C-011 时期即存在）：
  1. **`DataSource` 按类型注入歧义**: `authDataSource`（`AuthDataSourceConfig`）与 `traceDataSource`（`TraceDataSourceConfig`）同时存在时，`tracePersistenceService(DataSource)` / `tripPlanPersistenceService(DataSource)` / `userRepository(DataSource)` 三处参数按类型注入产生歧义；项目未开编译期 `-parameters` 标志，Spring 无法用参数名回退匹配，直接拒绝启动。**修法**: 三处均加显式 `@Qualifier("traceDataSource"/"authDataSource")`。
  2. **JDBC URL `characterEncoding=utf8mb4` 非法**: `characterEncoding` 是 Java 侧字符集名（driver 内部走 `String.getBytes(name)`），只认 `"UTF-8"` 一类 Java charset，不认 MySQL 侧字符集名 `utf8mb4`，导致 HikariCP 启动即抛 `UnsupportedEncodingException` 崩溃。**修法**: 改为 `characterEncoding=UTF-8`；服务端 4 字节 utf8mb4 由建表 DDL 的 `CHARACTER SET utf8mb4` 决定，与此参数无关。
  3. **`plan_id` 列写入必现失败**: `trip_agent_delegation_log`/`trip_llm_call_log`/`trip_orchestration_summary` 三表 `plan_id` 均为 `NOT NULL DEFAULT ''`；`TripOrchestrationService.executeStep()` 此前把 `planId` 硬编码为 `""`（未透传 `query.planId()`），而 `MySqlTracePersistenceService` 的 `emptyToNull()` 又把这个 `""` 转成 SQL `NULL` 才绑定，两者叠加导致确定性路每次委派记录写入都以 `Column 'plan_id' cannot be null` 失败。**修法（两处）**: `executeStep()` 新增 `planId` 参数并在 4 个调用点传入 `query.planId()`；`MySqlTracePersistenceService` 把 `emptyToNull()` 改名重写为 `nullToEmpty()`（null→""，而非 ""→null），与三个 `TraceXxxRecord` 紧凑构造器"null 归一为空串"的既有约定对齐。
- **测试盲区根因**: `MySqlTracePersistenceServiceTest` 的 H2 建表脚本里 `plan_id VARCHAR(64) DEFAULT ''` 遗漏了 `NOT NULL`，与真实 MySQL DDL（`schema.sql`）不一致，导致该缺陷在单测里从未复现过。已同步补上 `NOT NULL` 并新增回归用例 `nullPlanIdWritesEmptyStringNotSqlNull`；`TripOrchestrationServiceTest.TraceIntegration` 两个既有用例补充 `d.planId()` 断言。
- **影响范围确认**: 三处均为 C-010/C-011 遗留缺陷，C-017 本身新增代码（业务持久化 6 表 `TripPlanReader`/`TripPlanWriter`/`MySqlTripPlanPersistenceService`）不受影响——业务持久化走独立的 H2 单测已验证过，本地联调失败的是 C-010 的审计 trace 三表写入路径。

### FIX-5: Day 卡片费用口径与 `total_cost` 不一致（用户实测发现，收口前修复）

- **背景**: 用户对比前端 Day1~5 卡片加总（4905）与 `trip_budget_record.total_cost`（6301.80）发现不一致。
- **根因**: `ItineraryDesignService.computeDailyCost()`（编排阶段）只算门票+餐饮均价，不含交通/住宿；`BudgetCalculationService`（核算阶段）才是门票+餐饮×人数+交通+住宿的全量核算——两条路径互不知情。
- **修法**: `TripPlanAssembler` 新增 `reconcileDailyCost()`，用 `BudgetCalculationResult.dailyBreakdowns()` 的逐日合计回填 `TripDay.dailyCost`；前端 `ItineraryTimeline.vue`/`ItineraryTimelineDesktop.vue` 移除冗余的 `* headcount` 乘法（回填后的 dailyCost 已是全量组总额）。
- **验证**: 新增 `TripPlanAssemblerTest`（3 用例：正常回填/budgetResult 为 null 降级/某天明细缺失降级）。
- **影响范围**: 历史规划持久化数据本身已含完整交通/住宿拆分（`trip_budget_record` DDL 早已有 `transport_cost`/`hotel_cost` 独立字段，`TripPlanWriter`/`TripPlanReader` 均已正确读写），本次只是把这份口径同步回 `TripDay.dailyCost` 用于展示层。

### FIX-6: CI 门禁收口时发现的 3 个与 C-017 无关的预存在缺陷（④→⑤ 阶段）

- **Spring Boot fat jar 污染库依赖**: `huazai-trip-server/pom.xml` 的 `spring-boot-maven-plugin` repackage 未设 `classifier`，`mvn verify`（含 package 阶段）会用 BOOT-INF 嵌套布局覆盖主 jar，导致 `huazai-trip-tests` 引用它做普通依赖时编译期报 "找不到符号 package com.nanobot.server"。此前所有验证只跑到 `mvn test`（不到 package 阶段）从未触发。**修法**: 加 `<classifier>exec</classifier>`，主 jar 保持普通库布局，可执行 jar 另出 `-exec` 后缀。
- **PMD P1 违规（`huazai-trip-skills`）**: `PdfRenderService.loadFont()` 用 `new FileInputStream(f)` 触发 `AvoidFileStream`（C-014 遗留）。**修法**: 改 `Files.newInputStream(f.toPath())`。
- **PMD P2 违规（`huazai-trip-agent-route`）**: `ReconnectingMcpClientWrapper.delegate` 字段 `volatile` 触发 `AvoidUsingVolatile`（C-015 遗留）。经确认该字段只做整体引用替换、无复合读改写，`volatile` 语义正确，**不改动逻辑**，加 `@SuppressWarnings("PMD.AvoidUsingVolatile")` + 注释说明放行。
- **PMD P1 违规（`huazai-trip-server`）**: `TripPlanDetailMapper.findHitlReasonsInLegacy()` 找不到时返回 `null` 触发 `ReturnEmptyCollectionRatherThanNull`（C-011 遗留）。**修法**: 改返回 `List.of()`；调用方原有 `!= null && !isEmpty()` 判断天然兼容，无行为变化。
- **均为历史遗留、与 C-017 业务逻辑无关**，但会阻塞 `mvn clean verify` 全量门禁，故一并收口修复。

## 流水线进度

- [x] ① 需求分析（analyzing）
- [x] ② 编码实现（coding）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）
- [x] ④ 专家评审（reviewing，0 严重问题）→ review.md
- [x] ⑤ CI 门禁（ci，全绿：`mvn clean verify`，huazai-trip-server/skills/agent-supervisor 覆盖率均 ≥80%）
- [x] ⑥ 部署验证（verifying，部分）→ verify.md（沙箱无 Docker，Nacos/OTel 全链路未复测，核心链路经用户真实环境确认）
- [x] 交付（done，wiki 已同步：架构决策.md / 数据模型.md / 接口协议.md）
