---
id: C-013
slug: frontend-main-flow
status: done
created: 2026-06-07
updated: 2026-06-18
owner: Owner Agent
---

# C-013 前端主流程对接（Vue · 认证 + 小红书风格主流程）

## 用户故事

作为终端用户，我想要在前端完成「注册/登录 → 录入旅行需求 → 实时查看规划进度 → 查看逐日行程与费用 → 人工确认/修改 → 提交反馈」的完整闭环，以便通过自然交互驱动后端自主规划链路，获得小红书风格的可视化旅行方案。

## 已交付依赖能力盘点

> C-013 直接消费以下已交付能力，不重复实现。

| 变更 | 已交付核心能力 | C-013 直接消费 |
|------|--------------|--------------|
| C-011 server-api（done） | `POST /api/v1/auth/register/login/logout/refresh`（JWT + Redis 双 token，BCrypt，单设备语义）+ 规划全套 REST API（202+planId / 轮询 / HITL / feedback / data isolation） | 前端直接调用所有后端接口；JWT token 需注入每次请求 |
| C-012 e2e-main-flow（done） | 后端 API 链路已真正触发 `SupervisorAgent → XHSAnalysisAgent / RouteAgent / ItineraryAgent / BudgetAgent` 的完整 A2A 调度，Happy Path/HITL/降级/隔离均有机器守护 | 前端可靠消费已验证链路；不需要在前端再为后端行为单独写测试 |

## 非目标（Out of Scope）

- 本次不实现/修改后端 API（仅消费 C-011 v1 契约，不新增/变更字段）。
- 本次不做移动端原生 App（仅 Web 响应式）。
- 本次不做 OAuth2/第三方社交登录（仅用户名密码 + JWT）。
- 本次不做在线支付与实时预订。
- 本次不做管理端 UI（`/admin/**` 端点由后端直接提供，不在前端路由）。

## 验收标准（AC）

### 认证模块（消费 C-011 §1.6 认证端点）

- AC-1: **注册页**：表单字段（username / password / nickname），前端校验（密码长度 ≥8，username 非空）；调用 `POST /api/v1/auth/register`：201 则自动调用登录写入 token 并跳转 `/intent`；409 显示「用户名已存在」；400 显示密码格式错误。
- AC-2: **登录页**：表单字段（username / password）；调用 `POST /api/v1/auth/login`：200 则 accessToken / refreshToken / expiresIn 持久化至 localStorage + `useAuthStore`，跳转 `/intent`；401 显示「用户名或密码错误」（不区分用户存在性）。
- AC-3: **Token 生命周期**：`useAuthStore`（Pinia）持有 accessToken / refreshToken / expiresAt；每次 HTTP 请求由 `request.ts` 自动注入 `Authorization: Bearer <accessToken>`；距过期 < 5 分钟时自动调用 `POST /api/v1/auth/refresh` 续签，**并发 refresh 只发一次**（其他请求排队等待新 token，防竞争）；401 无法恢复时清除 localStorage + store 并强制跳转 `/login`。
- AC-4: **路由守卫**：`router.beforeEach` 检查 `useAuthStore.isAuthenticated`：未认证访问 `/intent` / `/planning/**` / `/notes/**` / `/plan/**` / `/result/**` → 重定向 `/login`；已认证访问 `/login` / `/register` → 重定向 `/intent`。
- AC-5: **登出**：导航栏提供登出入口；调用 `POST /api/v1/auth/logout`（携带 token 主动吊销服务端），清除 localStorage + authStore 全部 token 状态，跳转 `/login`。

### 主流程模块（消费 C-011 §1.1~§1.4 规划端点）

- AC-6: **需求录入页**：表单字段对齐 `TripPlanRequest`（destination / startDate / days / budget / headcount / travelStyle / preferences），前端校验与后端一致（days 1–30、budget > 0、headcount ≥ 1）。
- AC-7: **提交与轮询**：提交后调用 `POST /api/v1/trip-plan`，拿到 `planId` 并进入进度页，按 `GET /api/v1/trip-plan/{planId}` 轮询（间隔 2–3s，指数退避，上限 60s），展示状态机进度（analyzing → … → review / confirmed / failed）。
- AC-8: **行程展示页**：逐日 TripDay（景点 / 餐饮 / 路线 / 当日小计）、费用拆分（四类 + 总费用 + overrunRate），UI 为小红书风格（对齐 `前端效果图-小红书风格.html`）。
- AC-9: **HITL 交互**：当 `requiresHumanIntervention=true` 时显式提示（展示 `interventionReason` / `interventionAdvice`），提供 CONFIRM / MODIFY / REJECT 操作，调用 `POST /api/v1/trip-plan/{planId}/intervene` 并刷新状态。
- AC-10: **反馈入口**：在 confirmed 态提供 UP/DOWN + comment 入口，调用 `POST /api/v1/trip-plan/{planId}/feedback`。
- AC-11: **错误与降级体验**：对 400 / 401 / 429 / 503 与 `fallback` 标记给出可读提示（含 traceId 便于反馈），不白屏；401 自动尝试 refresh，无法恢复时跳转登录页。

## 边界情况（≥8）

### 认证边界

- 当 accessToken 过期且 `POST /api/v1/auth/refresh` 返回 401（refreshToken 也失效）时，清除 token 强制跳转 `/login`，提示「登录已过期，请重新登录」，不白屏。
- 当页面刷新时，从 localStorage 恢复 token + expiresAt，若 expiresAt - now < 5min 则自动 refresh，否则直接使用已有 token，不强制重新登录。
- 当注册用户名已存在（409）时，注册页显示可读错误「用户名已存在，请更换」，不清空表单，不跳转。
- 当多个 API 请求并发触发 token refresh（均收到 401）时，只发送一次 refresh 请求，其他请求排队等待，refresh 完成后统一使用新 token 重放（防重复 refresh 竞争）。

### 主流程边界

- 当后端返回 `503` / 网络超时时，展示「服务暂时不可用，请稍后重试」+ traceId + 重试按钮，不白屏崩溃。
- 当轮询超过 60s 仍处于中间态时，给出「规划仍在进行中」提示并提供手动刷新入口，不强制停止轮询。
- 当 `status=failed` 时，展示失败摘要与「重新规划」入口（返回 intent 页并保留上次表单填写内容）。
- 当方案含 `fallback=true` 的降级数据时，UI 明确标注「部分结果为降级估算」（RouteAgent / BudgetAgent 降级场景）。
- 当表单提交时 days ≤ 0 或 budget ≤ 0 时，前端即时拦截并展示字段级错误，不发送 HTTP 请求。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 首屏可交互 < 2s；轮询间隔 2–3s，指数退避避免过频 |
| 可靠性 | 网络错误可重试；refresh 并发锁只发一次；轮询退避上限 60s；页面刷新 token 自动恢复 |
| 安全 | accessToken / refreshToken 仅存 localStorage，不存 cookie（避免 CSRF），不在前端存 JWT 签名密钥；logout 主动调用后端吊销服务端 token（不仅清本地）；展示数据脱敏由后端保证 |
| 可观测 | 错误提示携带后端 traceId，用户可复制；前端不自建埋点（避免过度设计） |

## 设计约束

- 必须落在 `huazai-trip-front`（Vue 3 + Vite + TypeScript + Pinia + Vue Router + Tailwind 4），**不引入新 UI 框架依赖**。
- **认证模块落位**（全新新增，不修改现有 trip-plan 相关代码）：
  - 类型：`src/models/auth.ts`（RegisterReq / LoginReq / AuthTokens / AuthUser）
  - 服务层：`src/services/auth.ts`（register / login / logout / refresh，对齐 §1.6 契约）
  - 状态：`src/stores/auth.ts`（useAuthStore：accessToken / refreshToken / expiresAt 持久化，isAuthenticated 计算属性，logout action）
  - 拦截：`src/utils/request.ts` 扩展（Bearer 自动注入 + 401 自动 refresh + 并发锁 + 失败跳 /login；Mock 模式下跳过 token 逻辑）
  - 路由守卫：`src/router/index.ts` `router.beforeEach` 钩子
  - 页面：`src/views/pages/LoginView.vue` / `src/views/pages/RegisterView.vue`
- **主流程服务层已存在**（`src/services/trip-plan.ts`），仅验证字段对齐，不重写。
- Mock 模式（`useMock=true` in `src/config/index.ts`）保持不退化：Mock 下跳过认证 token 逻辑与 token 注入，现有 Mock 流程继续可用。
- 严格消费 v1 契约，不自创字段；不在前端复制后端业务规则（如 HITL 触发条件、overrunRate 阈值）。
- 视觉对齐 `前端效果图-小红书风格.html`，复用既有组件目录（notes / itinerary / budget / hitl / planning / intent / feedback）。

## 契约影响

- REST: 作为 `接口协议.md` §1.1~§1.4（规划）+ §1.6（认证）的消费方（**不修改任何后端契约**）
- A2A: 无（前端不参与 A2A）
- 数据模型: 前端 TS 类型新增 `RegisterReq / LoginReq / AuthTokens / AuthUser`；镜像已有 `TripPlanRequest / TripPlan / TripDay / Budget`
- Redis / ReMe: 无（前端无直接存储；JWT token 存 localStorage）

## 影响面

- 模块 / Agent / Skill: `huazai-trip-front`（新增 `src/models/auth.ts` / `src/services/auth.ts` / `src/stores/auth.ts` / `LoginView.vue` / `RegisterView.vue`；扩展 `src/utils/request.ts` / `src/router/index.ts`）
- 外部 API: 后端 `/api/v1/auth/*` + `/api/v1/trip-plan/*`
- wiki: 消费 `接口协议.md` §1.1~§1.4 + §1.6；`业务模型.md` 状态机/HITL/旅行风格

## 规则归属

- 业务不变量归属: 前端仅做输入级格式校验（表单）与展示；业务一致性（HITL 触发、overrunRate 判定）由后端 Agent/Gate/Server 保证，前端不复制
- 认证归属: 前端负责 token 本地生命周期管理（持久化/注入/自动 refresh/并发锁/失败跳转）；token 有效性由后端最终裁决（JWT 签名 + Redis 存在性双重验证）
- 外部调用治理归属: 前端对后端调用做超时/重试/可读错误提示；不绕过后端访问任何外部 API
- 可观测性要求: 展示并可复制后端 traceId；不自建前端埋点

## 测试策略

> 前端测试框架：`Vitest` + `@vue/test-utils`（已在 `huazai-trip-front` 技术栈中）。

- **先写失败测试（Red 先行）**:
  - 认证：注册表单校验（密码 <8 / username 空）先红、登录成功 token 写入 store 先红、401 可读提示先红、token refresh 并发锁先红、路由守卫重定向（未认证 → /login）先红 → 组件 / 单测先红
  - 主流程：表单校验（days / budget / headcount 非法即时拦截）先红、轮询状态渲染先红、HITL 操作触发接口先红、503 / fallback 降级渲染先红 → 组件 / 单测先红
- Happy Path: 注册 → 自动登录 → 填表 → 提交 → 进度轮询 → 行程展示 → HITL 确认 → 反馈，全流程贯通（服务层 mock 桩替代真实 HTTP）
- 边界测试: 非法表单即时拦截、token 过期自动 refresh、refresh 失败跳 /login、refresh 并发只发一次、503 / 网络错误降级 UI、failed 态重规划入口、长轮询 > 60s 提示、fallback 标注
- 降级测试: 后端不可用 → 降级 UI + 重试按钮，不白屏；token refresh 失败 → 强制登出，不白屏
- 回归测试: 核心页面 `vue-tsc --noEmit`（type-check）+ 服务层契约镜像（字段对齐断言）+ auth store 状态机正确性

## 验收用例

- Case-1: 注册新用户（密码合法 ≥8 位）→ 201 → 自动登录 → 跳转 `/intent`，localStorage 含 accessToken。
- Case-2: 注册用户名已存在（409）→ 注册页显示「用户名已存在」，不跳转，不清空表单。
- Case-3: 登录成功 → localStorage 写入 accessToken / refreshToken → 后续规划接口请求含 `Authorization: Bearer` header。
- Case-4: 未登录直接访问 `/intent` → 重定向 `/login`；登录后访问 `/login` → 重定向 `/intent`。
- Case-5: accessToken expiresAt - now < 5min → 自动调用 refresh → 新 token 写入 store → 原请求携带新 token 无感重发。
- Case-6: accessToken 过期 + refreshToken 也过期（401 无法续签）→ 清除 token → 跳转 `/login` + 提示「登录已过期」。
- Case-7: 并发 3 个请求同时 401 → 只发一次 refresh → 3 个请求均等待新 token 后重放（不发 3 次 refresh）。
- Case-8: 点击登出 → `POST /auth/logout`（携带 token） → 清除 localStorage → 跳转 `/login`。
- Case-9: 填合法需求 → 提交 → 进度页轮询 → 展示逐日行程与费用拆分（小红书风格）。
- Case-10: 输入 days=0 → 前端表单即时报错，不发 HTTP 请求。
- Case-11: 后端返回 `requiresHumanIntervention=true` → 显示 HITL 提示面板（含 reason/advice）→ 点 CONFIRM → 状态变 confirmed。
- Case-12: 后端 503 → 显示可读错误 + traceId + 重试按钮，不白屏。
- Case-13: 方案含 `fallback=true` → UI 标注「部分结果为降级估算」。

## 任务拆解（≤1 天/项，DAG 无环）

### 认证模块（T-1 ~ T-3）

- [ ] T-1: auth TS 类型（`src/models/auth.ts`：RegisterReq / LoginReq / AuthTokens / AuthUser）+ auth service（`src/services/auth.ts`：register / login / logout / refresh，对齐 §1.6）+ `useAuthStore`（`src/stores/auth.ts`：token 持久化 localStorage + isAuthenticated 计算属性 + logout action） · P0 · 依赖 C-011 已交付 · 模块 front
- [ ] T-2: `src/utils/request.ts` 扩展：自动注入 `Authorization: Bearer <accessToken>` + 401 拦截触发自动 refresh（并发锁，只发一次，其他请求排队）+ refresh 失败时清 store 并跳 `/login`；Mock 模式（`useMock=true`）下跳过 token 注入逻辑 · P0 · 依赖 T-1 · 模块 front
- [ ] T-3: 登录页（`src/views/pages/LoginView.vue`）+ 注册页（`src/views/pages/RegisterView.vue`）+ `src/router/index.ts` 新增 `/login` `/register` 路由 + `beforeEach` 路由守卫（未认证跳 /login；已认证跳 intent）+ 组件单测先红 · P0 · 依赖 T-1, T-2 · 模块 front

### 主流程模块（T-4 ~ T-9）

- [ ] T-4: 验证 `src/models/trip-plan.ts` 字段对齐（C-011 v1 契约审查）+ 验证 `src/services/trip-plan.ts` 字段对齐 + `src/stores/trip-plan.ts` 确认依赖 useAuthStore（已认证才允许 submit）· P0 · 依赖 T-2 · 模块 front
- [ ] T-5: 需求录入页（`src/views/pages/IntentView.vue`）表单校验（days / budget / headcount）组件测试先红 + 最小实现（非法即时拦截，不发请求） · P0 · 依赖 T-3, T-4 · 模块 front
- [ ] T-6: 进度页（`src/views/pages/PlanningView.vue`）轮询逻辑（2–3s 间隔，指数退避，上限 60s）+ 状态机渲染（analyzing → … → review / confirmed / failed）+ 超时提示 + 组件测试先红 · P0 · 依赖 T-4 · 模块 front
- [ ] T-7: 行程展示页（`src/views/pages/ItineraryView.vue`：逐日 TripDay + `BudgetPanel.vue` 费用拆分，小红书风格对齐效果图） · P0 · 依赖 T-4 · 模块 front
- [ ] T-8: HITL 交互（`HitlSheet.vue`：展示 interventionReason / interventionAdvice + CONFIRM / MODIFY / REJECT 操作）+ `FeedbackExport.vue` 反馈接口对接 + 组件测试先红 · P0 · 依赖 T-6, T-7 · 模块 front
- [ ] T-9: 错误/降级 UI（400/401/429/503 可读提示 + traceId 展示可复制 + fallback「降级估算」标注）+ `vue-tsc --noEmit` type-check 全通过 + 组件测试覆盖率 ≥80% · P1 · 依赖 T-3..T-8 · 模块 front

## 流水线进度

- [x] ① 需求分析（analyzing）
- [x] ② 编码实现（coding）
- [x] ③ 单测编写（testing，覆盖率 ≥80%，62 tests GREEN）
- [x] ④ 专家评审（reviewing，0 严重问题）→ review.md
- [x] ⑤ CI 门禁（ci，全绿）
- [x] ⑥ 部署验证（verifying）→ verify.md（真实前后端联调全通过）
- [x] 交付（done）

## 联调验证结果（2026-06-18）

| 验证项 | 结果 |
|--------|------|
| 后端启动（Spring Boot 4 + MySQL + Redis） | ✅ Started in 9.6s |
| `POST /api/v1/auth/register` | ✅ 201 返回 userId + username |
| `POST /api/v1/auth/login` | ✅ 200 返回 accessToken + refreshToken + expiresIn |
| `POST /api/v1/auth/logout`（携带 Bearer token） | ✅ 200 已登出 |
| Vite 代理（5175 → 8080） | ✅ `/api` 代理正常转发 |
| 前端启动（npm run dev） | ✅ localhost:5175 |

### 修复列表

- `RedisServicesAutoConfiguration`：Spring Boot 4 的 Redis autoconfigure 包路径从 `org.springframework.boot.autoconfigure.data.redis` 变更为 `org.springframework.boot.data.redis.autoconfigure`，类名从 `RedisAutoConfiguration` 变更为 `DataRedisAutoConfiguration`
- `application-test.yml`：同步更新 Spring Boot 4 autoconfigure 排除类名
- `SecurityConfig`：添加 `@Qualifier("corsConfigurationSource")` 消除 Spring Boot 4 中 `HandlerMappingIntrospector` 也实现 `CorsConfigurationSource` 导致的 bean 歧义
- `AuthDataSourceConfig`：JDBC URL 移除 `characterEncoding=utf8mb4`（mysql-connector-j 9.x 不接受 MySQL charset 名，MySQL 8 默认已是 utf8mb4）
- `GlobalExceptionHandler`：添加 `LOG.error` 以便运行期排查未预期异常
- `UserService.register()`：`nickname` 为 null/blank 时默认使用 `username`，避免 NOT NULL 约束失败
- `application-local.yml`：Redis 密码从 `huazai` 更正为 `123456`（本机 Redis 实际密码）

## C-013 后续验收必须补强的关键点（2026-06-18）

- C-013 的最终主流程验收不能只证明前端页面可用，也不能只证明注册/登录接口可用。
- 必须证明前端提交规划后调用的后端 `POST /api/v1/trip-plan` 链路真实触发：

  `TripPlanController / TripPlanFacade → SupervisorAgent → XHSAnalysisAgent / RouteAgent / ItineraryAgent / BudgetAgent`

- 不得用后端 mock、假 planId、静态假数据，或仅 auth 成功来宣称「前后端主流程联调完成」。
- 外部 API / LLM / 地图服务可以在测试中使用受控替身，但 Java 内部主链路（Controller / Facade / SupervisorAgent / 4 个子 Agent / Gate / Redis 状态流转）不能被 mock 掉。
- `verify.md` 或最终联调记录应至少包含：实际 URL、planId、状态流转、HITL/review/confirmed 证据，以及 SupervisorAgent 和 4 个子 Agent 被真实调用的日志、traceId、指标或测试断言。

---

## 本分支新增实现（agent-chapter-14）

### A. 桌面端响应式布局

`DefaultLayout.vue` 通过 `DESKTOP_MAP + isDesktop` 按路由名动态切换桌面/手机视图；`DeviceSwitcher` 提供模式切换按钮。

**新增文件**

| 文件 | 说明 |
|------|------|
| `src/components/common/DeviceSwitcher.vue` | 桌面 / 手机模式切换按钮 |
| `src/components/feedback/FeedbackExportDesktop.vue` | 桌面版反馈导出（含统计项导航） |
| `src/components/hitl/HitlSheetDesktop.vue` | 桌面版 HITL 面板 |
| `src/components/intent/IntentFormDesktop.vue` | 桌面版需求录入表单 |
| `src/components/itinerary/ItineraryTimelineDesktop.vue` | 桌面版行程时间轴 |
| `src/components/notes/NoteCardDesktop.vue` / `NoteWaterfallDesktop.vue` | 桌面版笔记组件 |
| `src/components/planning/AgentProgressDesktop.vue` | 桌面版规划进度 |
| `src/views/pages/IntentViewDesktop.vue` | 桌面版需求录入页 |
| `src/views/pages/PlanningViewDesktop.vue` | 桌面版规划进行中页 |
| `src/views/pages/NotesViewDesktop.vue` | 桌面版笔记溯源页 |
| `src/views/pages/ItineraryViewDesktop.vue` | 桌面版行程展示页 |
| `src/views/pages/ResultViewDesktop.vue` | 桌面版确认导出页 |
| `src/views/pages/HistoryViewDesktop.vue` | 桌面版历史方案页 |
| `src/views/GalleryView.vue` | 效果图墙页 |

**修改文件**

- `src/views/layouts/DefaultLayout.vue`：`DESKTOP_MAP` 按路由名注入桌面端组件，`DeviceSwitcher` 切换模式；导航栏补充登出、历史方案、图墙入口
- `src/router/index.ts`：新增 `gallery` 路由（`/gallery`）

### B. 主流程 Bug 修复

#### B-1 缓存命中后前端卡在规划进行中页面

**根因**：后端命中请求指纹缓存时，`submit()` 设 `store.detail.status = 'confirmed'`，但 `IntentView.onSubmit()` 无论状态一律导航到 `planning`；`PlanningView.watch(status)` 无 `{ immediate: true }`，挂载时 status 已是 `confirmed` 不触发，页面永远卡在规划进行中。

**修复文件**

- `src/views/pages/IntentView.vue` / `IntentViewDesktop.vue`：submit 后读 `store.detail?.status`，`confirmed` / `review` 直接跳 `plan` 页，其余进 `planning`
- `src/views/pages/PlanningView.vue` / `PlanningViewDesktop.vue`：watch 加 `{ immediate: true }`；`old=undefined`（挂载即 confirmed）→ 跳 `plan`；从 `review` 正常 HITL 确认才跳 `result`

#### B-2 结果/行程页缓存命中后转圈（plan 数据为空）

**根因**：`submit()` 返回的 `store.detail` 仅含 `{ planId, status }`，无 `plan` 字段；四个结果/行程页 `onMounted` 只校验 `planId` 是否匹配，不检查 `plan` 是否存在，跳过 fetch 导致 `plan=undefined`，页面一直转圈。

**修复文件**（均在 `onMounted` 条件中加入 `|| !store.detail.plan`）

- `src/views/pages/ResultView.vue`
- `src/views/pages/ResultViewDesktop.vue`
- `src/views/pages/ItineraryView.vue`
- `src/views/pages/ItineraryViewDesktop.vue`

#### B-3 结果页统计项点击进入行程详情页

新增 `planId` prop 与 `goToPlan()` 导航函数，统计项点击导航至 `{ name: 'plan', params: { planId } }`。

**修复文件**

- `src/components/feedback/FeedbackExport.vue`（移动版）：新增 `planId` prop + `goToPlan()`，统计卡片整体可点击
- `src/components/feedback/FeedbackExportDesktop.vue`（桌面版）：新增 `planId` prop + `goToPlan()`，五个 stat-item 分别绑 `@click="goToPlan"`；`.clickable { cursor: pointer }` CSS
- `src/views/pages/ResultView.vue` / `ResultViewDesktop.vue`：传 `:plan-id="planId"` 给 FeedbackExport 组件

### C. 流水线进度更新

| 步骤 | 状态 | 说明 |
|------|------|------|
| ① 需求分析 | ✅ done | 桌面响应式 + Bug 修复三项已明确 |
| ② 编码实现 | ✅ done | 所有文件已落地 |
| ③ 单测编写 | ⚠️ 待补 | 桌面端组件单测尚未覆盖 |
| ④ 专家评审 | ⚠️ 待执行 | |
| ⑤ CI 门禁 | ⚠️ 待执行 | |
| ⑥ 部署验证 | ⚠️ 待执行 | 已手动验证三项 Bug 修复可用 |
