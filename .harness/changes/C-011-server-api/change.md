---
id: C-011
slug: server-api
status: done
created: 2026-06-07
updated: 2026-06-17
owner: Owner Agent
---

# C-011 对外 REST API、门面层与用户认证（Server）

## 用户故事

作为前端/外部调用方，我想要一组对外 REST API（注册/登录、提交规划、查询进度/结果、HITL 介入、反馈、管理健康）来驱动整套规划流程，以便用户通过「注册→登录→提交需求→轮询结果→人工确认」的闭环完成旅行规划。

## 非目标（Out of Scope）

- 本次不在 server 复制任何 Agent 业务规则（编排归 Supervisor，规则归各 Agent/Gate）。
- 本次不实现前端页面（归 C-013）。
- 本次不实现 OAuth2/第三方登录（仅用户名密码 + JWT）。
- 本次不实现复杂 RBAC 权限体系（仅 USER/ADMIN 两个角色）。
- 本次不新增 `接口协议.md` 规划部分未定义的接口（认证端点为本卡新增，需同步更新 wiki）。

## 验收标准（AC）

### 认证模块

- AC-1: 实现 `POST /api/v1/auth/register`（201 + userId + username）、`POST /api/v1/auth/login`（200 + accessToken + refreshToken + expiresIn）和 `POST /api/v1/auth/logout`（200 + 主动登出）。
- AC-2: JWT 认证 + Redis 令牌缓存：accessToken 有效期 2h，refreshToken 有效期 7d；登录时双 token 写入 Redis（`auth:access:{userId}`、`auth:refresh:{userId}`，TTL 与 token 有效期一致）。`POST /api/v1/auth/refresh` 凭 Redis 中有效 refreshToken 签发新 accessToken 并更新 Redis。`POST /api/v1/auth/logout` 删除 Redis 中双 token 实现主动登出。每次请求 `JwtAuthenticationFilter` 校验 JWT 签名 + Redis 中 token 存在性（双重验证），Redis 中不存在即视为已吊销。同一用户再次登录覆写 Redis token，旧 token 自动失效（单设备登录语义）。
- AC-3: SecurityFilterChain 拦截所有 `/api/v1/trip-plan/**` 和 `/api/v1/admin/**`，未认证返回 `401 UNAUTHORIZED`；认证但无权限返回 `403 FORBIDDEN`。管理端点仅 ADMIN 角色可访问。`/api/v1/auth/register`、`/api/v1/auth/login`、`/actuator/health` 公开；`/api/v1/auth/refresh`、`/api/v1/auth/logout` 需携带有效 token。
- AC-4: 密码 BCrypt 哈希存储，响应/日志不返回密码明文或哈希值。用户名唯一约束，注册重复返回 `409 CONFLICT`。
- AC-5: 用户表 `trip_user`（MySQL），字段：id / username / password_hash / nickname / role / created_at / updated_at；启动时由 `schema.sql` 初始化。

### 规划 API

- AC-6: 实现 `接口协议.md` §1 全部接口：`POST /api/v1/trip-plan`（202 + planId + status=analyzing）、`GET /api/v1/trip-plan/{planId}`、`POST /api/v1/trip-plan/{planId}/intervene`、`POST /api/v1/trip-plan/{planId}/feedback`、管理端点 §1.5。
- AC-7: 异步规划：提交后返回 `planId` 并异步驱动 Supervisor，状态经 `analyzing→...→review/confirmed/failed` 可轮询。提交时关联当前登录用户 userId，查询时做数据隔离（用户只能访问自己的 plan）。
- AC-8: 错误码与结构对齐：`400 INVALID_REQUEST`（如 days≤0）、`401 UNAUTHORIZED`、`403 FORBIDDEN`、`409 CONFLICT`、`429 RATE_LIMITED`、`503 SERVICE_UNAVAILABLE`；统一错误体 `{code,message,traceId}`。
- AC-9: HITL：`intervene` 支持 `CONFIRM/MODIFY/REJECT`，分别使草案进入确认态/局部重算/作废重规划，对外暴露 `requiresHumanIntervention`。
- AC-10: 分层合规：`Controller` 仅依赖 `Facade/ApplicationService`，不直接依赖任何 `*Agent` 类（ArchUnit R3/R6 通过）；server 不复制 Route/Itinerary/Budget 规则。
- AC-11: 所有响应含 `traceId`（OpenTelemetry 贯穿）；敏感字段脱敏（密码哈希、手机号）；时间 ISO-8601、金额元/两位小数。

## 边界情况（≥5）

### 认证边界

- 注册时 username 已存在 → `409 CONFLICT` + 可读错误信息。
- 登录时用户名或密码错误 → `401 UNAUTHORIZED`（不区分"用户不存在"与"密码错误"，防枚举攻击）。
- accessToken 过期但 refreshToken 有效 → refresh 返回新 accessToken 并更新 Redis。
- accessToken 和 refreshToken 均过期（Redis TTL 到期自动清除）→ `401` 要求重新登录。
- 请求头无 Authorization 或格式非 `Bearer <token>` → `401`。
- 注册时密码不满足格式要求（长度 < 8）→ `400 INVALID_REQUEST`。
- 用户已登出（token 已从 Redis 删除）后携带旧 token 访问 → `401`（JWT 签名仍合法但 Redis 无记录即吊销）。
- 用户在设备 A 登录后又在设备 B 登录（Redis token 被覆写）→ 设备 A 旧 token 自动失效 → `401`。
- Redis 不可用时 `JwtAuthenticationFilter` 降级为仅验证 JWT 签名（不阻断服务），并记录告警指标。

### 规划 API 边界

- 当 `days <= 0` 或必填缺失时返回 `400 INVALID_REQUEST` + traceId，不进入规划。
- 当查询不存在的 `planId` 时返回 `404`（统一错误体），不返回脏数据。
- 当下游 Agent/外部 API 不可用时返回 `503 SERVICE_UNAVAILABLE` + traceId。
- 当触发限频时返回 `429 RATE_LIMITED`。
- 当对已 `confirmed` 的 plan 再次 `intervene` 时，按状态机拒绝非法转换并给出可读错误。
- 当 `MODIFY` 引用不存在的 attractionId 时，返回校验错误而非静默忽略。
- 用户 A 查询/操作用户 B 的 planId → `403 FORBIDDEN`（数据隔离）。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 注册/登录 < 500ms；提交接口（202）响应 < 500ms（异步）；查询 < 1s |
| 可靠性 | 下游不可用时 503 而非裸 500；幂等的状态转换；JWT + Redis 双重验证（Redis 不可用时降级仅 JWT 签名校验，不阻断） |
| 安全 | BCrypt 密码哈希；JWT 签名密钥从环境变量 `JWT_SECRET` 读取不硬编码；入参校验（复用 C-004 Bean Validation）；脱敏；无密钥泄漏；防枚举（登录错误不泄露用户存在性） |
| 可观测 | 每响应 traceId；接入 `/actuator/health\|metrics\|info`；接口级指标；认证失败计数 `auth.failure.count`；token 吊销计数 `auth.revoke.count`；Redis 降级告警 `auth.redis.fallback` |

## 设计约束

### 分层约束（强制，ArchUnit 守护）

| 层 | 可依赖 | 禁止依赖 |
|----|--------|----------|
| Controller | Facade | Service / Repository / Agent |
| Facade | Service | Repository / Agent |
| Service | Repository / Cache | — |
| Repository | —（只做数据访问） | Service / Facade / Controller |

所有 public 方法必须有 Javadoc。跨层依赖违反即 ArchUnit 测试失败。

- 必须落在 `huazai-trip-server`，对齐工程结构 §2.3：Controller→Facade/ApplicationService→（经 A2A）Supervisor。
- 认证模块落在 `com.nanobot.server.auth`，分子包：`controller/` / `facade/` / `service/` / `repository/` / `model/` / `filter/`。安全配置落 `com.nanobot.server.config.SecurityConfig`。
- 规划模块落在 `com.nanobot.server.plan`，分子包与 auth 一致：`controller/` → `facade/` → `service/`；**Facade 通过 `SupervisorAgent.receive(Msg)` 发 A2A 消息触发编排，不直接调用 `TripOrchestrationService` 或任何子 Agent**（ArchUnit R3/R6 守护）。
- 新建 `PlanCacheService` 封装规划 Redis 三层 key：`trip:plan:{planId}`（TripPlanDetail JSON）、`:status`、`:userId`，TTL 均 24h。Key 前缀复用 `CacheConstants`。
- 规划部分严格实现 `接口协议.md` v1 契约，不擅自增删字段；认证端点为本卡新增，交付时同步更新 `接口协议.md` §1.6。
- 复用 C-004 DTO/异常、C-005 治理与可观测、C-010 编排能力。
- 状态查询数据源为 Redis，不另建关系库（ADR-006）。
- JWT 令牌 Redis 缓存 key 规范：`auth:access:{userId}` → accessToken 字符串（TTL 2h）；`auth:refresh:{userId}` → refreshToken 字符串（TTL 7d）。登录覆写、登出删除、refresh 更新 access key。
- 用户数据持久化到 MySQL（HikariCP + plain JDBC，不引入 JPA），`schema.sql` 初始化。
- Feedback 存储：写入 Redis `trip:plan:{planId}:feedback`（JSON，与 plan 同 TTL）。
- 管理端点 `GET /admin/agent/status` 复用已有 `AgentStatusService`（C-005 产物）。
- HITL MODIFY 本次仅做状态转换 + attractionId 存在性校验，不触发真正重编排（Supervisor 不支持增量编排，留后续迭代）。
- 限频：Spring Interceptor 复用 skills `RateLimiter`，触发抛 `RateLimitedException` → GlobalExceptionHandler → 429。
- 下游降级：Facade catch 编排异常 → `SERVICE_UNAVAILABLE` → 503 + traceId。
- JWT 签名密钥通过环境变量 `JWT_SECRET` 注入。
- 新增依赖：`spring-boot-starter-security`、`io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson`（0.12.x）。

## 契约影响

- REST: 落地 `接口协议.md` §1 全部接口 + 新增 §1.6 认证端点（register / login / logout / refresh）
- A2A: server 作为发起方经 Facade 触发 Supervisor 编排（不直连子 Agent）
- 数据模型: 复用 `TripPlanRequest/TripPlan/Budget`（C-004）；新增 `User` 实体（server 内部，不下沉 common）；响应结构对齐 §1.2
- Redis: 读写 `trip:plan:{planId}`、`:status`、`:userId`；新增 `auth:access:{userId}`（TTL 2h）、`auth:refresh:{userId}`（TTL 7d）
- MySQL: 新增 `trip_user` 表（id / username / password_hash / nickname / role / created_at / updated_at）
- ErrorCode: 新增 `UNAUTHORIZED`(401)、`FORBIDDEN`(403)、`CONFLICT`(409)、`NOT_FOUND`(404)

## 影响面

- 模块 / Agent / Skill: `huazai-trip-server`（auth/ / config/ / controller/ / facade/ / 异常处理）、`huazai-trip-common`（ErrorCode 新增四值）
- 外部 API: 经 Supervisor 间接驱动全部下游
- wiki: 落地 `接口协议.md` §1/§5/§6 + 新增 §1.6 认证（含 logout）；与 `业务模型.md` 状态机一致

## 规则归属

- 业务不变量归属: server 仅做对外保护性校验与 `requiresHumanIntervention` 暴露；全局一致性 → OutputQualityGate；领域规则 → 各 Agent
- 认证归属: server 层全权负责用户认证与授权——JWT 令牌签发/验证/刷新/吊销（Redis 缓存生命周期）、BCrypt 哈希、角色校验、数据隔离；不下沉到 common 或 skills
- 外部调用治理归属: 限频/降级沿用 C-005；server 层不重复实现重试/降级
- 可观测性要求: 全响应 traceId；接口错误率/延迟指标；HITL 暴露计数；`auth.failure.count` 认证失败计数；`auth.revoke.count` 登出/吊销计数；`auth.redis.fallback` Redis 不可用降级告警

## 测试策略

### 认证测试（先红）

- 先写失败测试: 注册成功 201、注册重复 409、登录成功 200+token+Redis 写入、登录失败 401（防枚举）、无 token 访问 401、过期 token 401、已登出 token 401（Redis 无记录）、角色不足 403、refresh 成功+Redis 更新、logout 成功+Redis 清除、密码格式校验 400 → 先红。
- Happy Path: 注册→登录（token 写 Redis）→携带 token 提交 trip-plan→202→成功→登出（Redis 清除）→旧 token 访问→401。
- 边界测试: username 已存在、密码太短、token 过期、refresh token 过期、非法 token 格式、ADMIN 端点 USER 角色访问被拒、Bearer 格式错误、设备 A 登录后设备 B 重新登录→设备 A token 失效、Redis 不可用降级仅 JWT 签名校验。

### 规划 API 测试（先红）

- 先写失败测试: 参数校验(days≤0→400)、503 下游不可用、429 限频、HITL 三动作状态转换、非法状态转换拒绝、数据隔离(用户A访问用户B的plan→403)、ArchUnit R3/R6 → 先红。
- Happy Path: 合法提交→202+planId→轮询至 review→CONFIRM→confirmed。
- 边界测试: 不存在 planId、重复 intervene、MODIFY 引用无效 id、缺失必填。
- 降级测试: 下游不可用 → 503 + traceId（不裸 500）。
- 回归测试: 接口契约 + 分层约束 + 认证隔离纳入回归（与 C-012 E2E 联动）。

## 验收用例

- Case-1: `POST /auth/register`{username:"test",password:"Pass1234",nickname:"测试"} → `201 {userId, username}`。
- Case-2: `POST /auth/register`{username:"test",...} 重复 → `409 {code:"CONFLICT", traceId}`。
- Case-3: `POST /auth/login`{username:"test",password:"Pass1234"} → `200 {accessToken, refreshToken, expiresIn}` + Redis 写入 `auth:access:{userId}`(TTL 2h) 和 `auth:refresh:{userId}`(TTL 7d)。
- Case-4: `POST /auth/login`{password 错误} → `401 {code:"UNAUTHORIZED"}` 不泄露用户存在性。
- Case-5: 无 token `GET /trip-plan/{planId}` → `401 UNAUTHORIZED`。
- Case-5a: `POST /auth/logout`{有效 token} → `200` + Redis 删除双 token → 旧 token 再次访问 → `401`。
- Case-5b: 设备 A 登录→设备 B 同账号登录（Redis 覆写）→ 设备 A 旧 token 访问 → `401`。
- Case-6: `POST /trip-plan`{合法+token} → `202 {planId, status:"analyzing"}`。
- Case-7: `POST /trip-plan`{days:0} → `400 {code:"INVALID_REQUEST", traceId}`。
- Case-8: `GET /trip-plan/{planId}` → `200` 含 plan/budget/`requiresHumanIntervention`。
- Case-9: `POST /trip-plan/{planId}/intervene`{action:CONFIRM} → 进入 confirmed。
- Case-10: 下游 Agent 全不可用 → `503 SERVICE_UNAVAILABLE` + traceId。
- Case-11: USER 角色访问 `/admin/agent/status` → `403 FORBIDDEN`。
- Case-12: 用户 A 查询用户 B 的 plan → `403 FORBIDDEN`。

## 任务拆解（≤1 天/项，DAG 无环）

- [x] T-1: 全局异常处理 + 统一错误体 `{code,message,traceId}` + ErrorCode 新增（UNAUTHORIZED/FORBIDDEN/CONFLICT/NOT_FOUND）+ 校验先红 · P0 · 依赖 C-004 · 模块 server/common
- [x] T-2: 用户认证基础设施：`trip_user` 表 DDL(`schema.sql`) + `UserRepository`(plain JDBC) + `User` 实体 + `UserService`(注册 BCrypt/登录验证) · P0 · 依赖 T-1 · 模块 server
- [x] T-3: JWT 令牌 + Redis 缓存：`JwtTokenProvider`(签发/验证/刷新 accessToken 2h + refreshToken 7d) + `TokenCacheService`(Redis 读写/覆写/删除 `auth:access:{userId}` + `auth:refresh:{userId}`) + `JwtAuthenticationFilter`(OncePerRequestFilter, JWT 签名 + Redis 存在性双重校验, Redis 不可用降级仅签名) + `SecurityConfig`(FilterChain/公开路径/角色) · P0 · 依赖 T-2 · 模块 server
- [x] T-4: `AuthController`(register 201 / login 200+Redis写入 / logout 200+Redis清除 / refresh+Redis更新) + 认证测试全绿 · P0 · 依赖 T-3 · 模块 server
- [x] T-5: `PlanCacheService`(trip:plan:{planId}/:status/:userId 三层 Redis 读写) + `TripPlanFacade`(构造 A2A Msg 调 SupervisorAgent.receive() → CompletableFuture 异步 → 回调写 Redis) + `TripPlanController`(202+planId+analyzing) + 限频 Interceptor(429) + 编排异常降级(503) · P0 · 依赖 C-010,T-3 · 模块 server
- [x] T-6: `GET /trip-plan/{planId}`(PlanCacheService 读 Redis → userId 隔离校验：不匹配 403 / 不存在 404 / 匹配 200 + TripPlanDetail) · P0 · 依赖 T-5 · 模块 server
- [x] T-7: `POST /trip-plan/{planId}/intervene`(状态机：仅 review 态可操作；CONFIRM→confirmed / MODIFY→planning / REJECT→failed；其他态 400；MODIFY 只做状态转换+attractionId 校验不真正重算；userId 隔离) · P0 · 依赖 T-6 · 模块 server
- [x] T-8: `POST /trip-plan/{planId}/feedback`(Redis 存储，userId 隔离) + `AdminController`(agent/status 复用 AgentStatusService; admin/plan/{planId} ADMIN 跳过隔离) · P1 · 依赖 T-7 · 模块 server
- [x] T-9: 指标 ×5(`auth.failure.count`/`auth.revoke.count`/`auth.redis.fallback`/`human.intervention.count`/`api.rate_limit.hit`) + JaCoCo excludes 更新(auth+plan 业务类≥80%) + `接口协议.md` §1.6 认证端点 + ArchUnit 规划分层规则 + 全量测试绿 · P0 · 依赖 T-1..T-8 · 模块 server/tests/wiki

## 流水线进度

- [x] ① 需求分析（analyzing）
- [x] ② 编码实现（coding）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）
- [x] ④ 专家评审（reviewing，0 严重问题）→ review.md
- [x] ⑤ CI 门禁（ci，全绿）
- [x] ⑥ 部署验证（verifying）→ verify.md
- [x] 交付（done，wiki 已同步）
