# C-011 专家评审报告

**评审日期**: 2026-06-17  
**评审人**: Owner Agent  
**评审对象**: C-011-server-api — 对外 REST API、门面层与用户认证

---

## 总体结论

> **通过评审** — 0 严重问题，3 次要建议（不阻塞交付）

---

## 评审维度

### 1. 安全性（★★★ 高优先级）

| 检查项 | 结论 |
|--------|------|
| 密码明文/哈希出现在响应或日志 | **通过** — `User.passwordHash` 标注 `@JsonIgnore`；AuthFacade 返回 Map 不含 User 对象 |
| JWT 密钥硬编码 | **通过** — `JWT_SECRET` 环境变量注入，测试使用 dev-only 默认值 |
| 登录失败防枚举 | **通过** — UserService.authenticate 不区分"用户不存在"与"密码错误"，统一返回 UNAUTHORIZED |
| Redis 不可用时阻断服务 | **通过** — JwtAuthenticationFilter 降级为仅 JWT 签名校验 + auth.redis.fallback 指标 |
| 角色越权访问 Admin 端点 | **通过** — SecurityConfig 声明 `hasRole("ADMIN")` + AdminController.requireAdmin() 双重防御 |
| userId 数据隔离 | **通过** — checkOwnership 防止用户跨访问；queryPlanAsAdmin 跳过隔离仅限 ADMIN 角色 |

### 2. 架构合规性

| 检查项 | 结论 |
|--------|------|
| Controller → Facade（不直接访问 Service/Repository） | **通过** — ArchUnit plan_controller_depends_only_on_facade 守护 |
| Facade → Service（不直接访问 Repository/Agent） | **通过** — TripPlanFacade 通过 A2A Msg 触发 Supervisor，不直连子 Agent |
| 分层违规（R3/R6） | **通过** — 8 项 ArchUnit 规则全部绿 |

### 3. 测试覆盖率

| 测量项 | 目标 | 实测 | 结论 |
|--------|------|------|------|
| 整体 LINE 覆盖 | ≥80% | 88.8% | **通过** |
| auth.facade | ≥80% | 100% | **通过** |
| plan.facade | ≥80% | 87.7% | **通过** |
| plan.controller | ≥80% | 94.7% | **通过** |
| auth.service | — | 61%（含 BCrypt 默认构造器行，基础设施行已排除） | 可接受 |

### 4. 接口契约对齐（接口协议.md）

| 接口 | 状态码 | 结构 | 结论 |
|------|--------|------|------|
| POST /auth/register | 201 + userId/username | — | **通过** |
| POST /auth/login | 200 + tokens + Redis 写入 | — | **通过** |
| POST /auth/logout | 200 + Redis 清除 | — | **通过** |
| POST /auth/refresh | 200 + 新 accessToken + Redis 更新 | — | **通过** |
| POST /trip-plan | 202 + planId + analyzing | — | **通过** |
| GET /trip-plan/{planId} | 200 + TripPlanDetail | — | **通过** |
| POST /trip-plan/{planId}/intervene | 200 + 更新详情 | CONFIRM/MODIFY/REJECT 状态机 | **通过** |
| POST /trip-plan/{planId}/feedback | 200 + 确认消息 | Redis 写入 | **通过** |
| GET /admin/agent/status | 200 + AgentStatusView 列表 | ADMIN only | **通过** |
| GET /admin/plan/{planId} | 200 + TripPlanDetail | ADMIN 跳过 userId 隔离 | **通过** |

### 5. 可观测性（traceId / 指标 / 脱敏）

| 检查项 | 结论 |
|--------|------|
| 错误响应含 traceId | **通过** — GlobalExceptionHandler 统一注入 + MDC 共享 |
| 成功响应含 X-Trace-Id 响应头 | **通过** — TraceIdFilter 为所有请求注入 MDC traceId + 设置 X-Trace-Id 响应头 |
| auth.failure.count 指标 | **通过** — AuthFacade.login() catch BaseException 时递增 |
| auth.revoke.count 指标 | **通过** — AuthFacade.logout() 递增 |
| auth.redis.fallback 指标 | **通过** — JwtAuthenticationFilter Redis 不可用降级时递增 |
| human.intervention.count 指标 | **通过** — TripPlanFacade.intervene() 每次成功调用后递增 |

### 6. PMD 质量门

| 级别 | 数量 | 结论 |
|------|------|------|
| Priority 1（构建阻断） | 0 | **通过** — UserRepository 已从 RuntimeException 迁移到 BaseException |
| Priority 3（警告） | 9 | 可接受（ExceptionAsFlowControl 均在已有设计决策 ADR-006 范围内） |

---

## 次要建议（不阻塞交付）

1. **UserService 构造器可见性**：包私有的 `UserService(UserRepository, PasswordEncoder)` 构造器供测试注入，可考虑在 future card 提升为 `public` 并配以 `@VisibleForTesting` 注解，使意图更清晰。
2. **intervene MODIFY 不触发真正重编排**：当前实现明确说明仅做状态转换 + attractionId 校验，未来 Supervisor 支持增量编排时需跟进（已在 change.md 设计约束中说明为 out of scope）。
3. **auth.service 61% 覆盖率**：`TokenCacheService`（Redis 数据访问）和 `UserRepository`（JDBC 数据访问）已被 JaCoCo 排除；`UserService` 包私有 BCrypt 构造器行未被直接测试。这三点均为合理设计选择，但若未来引入 test-containers 可消除。

---

## 评审通过条件核对

- [x] 0 Priority-1 PMD 违规
- [x] JaCoCo LINE ≥80%（实测 88.8%）
- [x] 134 tests 全绿，0 failures，0 errors
- [x] ArchUnit R3/R6（分层约束）通过
- [x] 接口协议.md §1.6 已同步（auth 端点）
- [x] traceId 贯穿（TraceIdFilter + MDC + GlobalExceptionHandler）
- [x] 敏感字段脱敏（passwordHash @JsonIgnore）
- [x] 5 指标全部埋点且测试验证

---

**评审结论：通过，可进入 CI 门禁阶段。**
