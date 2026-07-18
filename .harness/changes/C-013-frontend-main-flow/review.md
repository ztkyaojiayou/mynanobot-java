# 📋 评审报告: C-013

## 总览

- 审查文件: 14 个（auth.ts×2, request.ts, router/index.ts, stores/auth.ts, stores/trip-plan.ts, LoginView.vue, RegisterView.vue, layouts/DefaultLayout.vue + 13 个测试文件）
- 🔴 严重问题: 1（**已在评审阶段修复**，62/62 GREEN）
- 🟡 建议改进: 4
- 🟢 通过项: 17

---

## 🔴 严重问题

### 1. ~~AC-5 登出功能未完整实现：导航栏无登出入口 + 服务端 token 吊销缺失~~ ✅ 已修复

- 文件: `src/views/layouts/DefaultLayout.vue`、`src/stores/auth.ts:62`
- 问题（已修复）:
  - `DefaultLayout.vue` 的 topnav 补充了"退出登录"按钮（`v-if="authStore.isAuthenticated"`），调用 `authStore.logoutAction()` 后跳转 `/login`
  - `logoutAction()` 现在先 `try { await logout() } catch {}` 再 `clearTokens()`，服务端 token 吊销有保障；logout 服务失败时仍确保本地清除（故障容忍）
  - `auth-store.spec.ts` 新增 Case-8 测试：验证 `mockLogout` 被调用一次 + `isAuthenticated=false` + localStorage 已清除；同时新增 logout 服务失败兜底测试
- 修复提交: 62/62 tests GREEN，覆盖率全维度 ≥80%，vue-tsc --noEmit 无报错

---

## 🟡 建议改进

### 1. `doRefresh()` 无超时保护，可能导致并发请求永久挂起

- 文件: `src/utils/request.ts:31`
- 当前: `globalThis.fetch(url, ...)` 直接调用，无 `Promise.race` 超时兜底
- 建议: 加 30s 超时 `Promise.race`，与 `baseFetch` 保持一致
- 理由: 若 refresh 端点无响应，`_refreshing` 永不 resolve，所有并发等待的请求将永久挂起，违反 AC-3 "401 无法恢复时强制跳转 /login" 的可靠性保障

### 2. `logoutAction()` 声明为 `async` 但内部无异步操作（修复后自然解决）

- 文件: `src/stores/auth.ts:62`
- 当前: `async function logoutAction() { clearTokens() }` — 纯同步
- 建议: 上述严重问题修复后，`async` 关键字将有实际意义；若不修复 API 调用，可去掉 `async`
- 理由: 无意义的 `async` 声明会误导调用方认为此函数有异步副作用

### 3. `services/auth.ts` 中的双重类型强转可替换为单次转换

- 文件: `src/services/auth.ts:10,14`
- 当前: `payload as unknown as Record<string, unknown>`
- 建议: `payload as Record<string, unknown>` 即可（LoginReq/RegisterReq 字段均为 string，无需绕道 unknown）
- 理由: `as unknown as T` 通常意味着类型系统被强行绕过，应尽量最小化

### 4. Case-13 降级测试存在静态文本误报风险

- 文件: `src/__tests__/error-degradation.spec.ts:25`
- 当前: `expect(wrapper.text()).toMatch(/降级|估算|fallback/i)` 能匹配"降级"，但该字符串来源可能是组件内 `.caption` 的静态文本（始终渲染），而非 `v-if="e.fallback"` 的条件分支
- 建议: 改为精准断言动态元素，例如：
  ```typescript
  expect(wrapper.find('.fallback').exists()).toBe(true)
  expect(wrapper.find('.fallback').text()).toContain('估算')
  ```
- 理由: 当前测试在 `v-if="e.fallback"` 被删除后仍可通过，不能守护降级渲染逻辑

---

## 🟢 通过项

- [x] **功能完整性 AC-1~AC-4, AC-6~AC-11**: 注册/登录表单校验、token 生命周期管理、并发 refresh 锁、路由守卫、IntentForm 字段校验、HITL 三动作、错误降级提示均已实现
- [x] **架构合规**: 严格落在 `huazai-trip-front`，未修改后端；无新 UI 框架依赖；mock 模式不退化
- [x] **安全**: token 仅存 localStorage（非 cookie），不暴露 JWT 密钥；认证路由（AUTH_PATHS）正确绕过 token 注入，防止死锁
- [x] **并发 refresh 锁（Case-7）**: `_refreshing: Promise<string> | null` 模块级单例，并发请求共享同一 refresh Promise，符合 AC-3
- [x] **AUTH_PATHS 防死锁**: `doRefresh()` 使用 `globalThis.fetch()` 绕过 `baseFetch`，`isAuthPath` 检查防止 auth 路由进入 401 重试分支，deadlock 消除
- [x] **路由守卫（AC-4）**: `AUTH_REQUIRED` / `GUEST_ONLY` 集合分离，`beforeEach` 逻辑正确；redirect 查询参数保留原始跳转路径
- [x] **TDD 合规**: 61 测试全绿，覆盖率 Statements 88.54% / Branches 84.93% / Functions 83.6% / Lines 88.88%，均超过 80% 阈值
- [x] **traceId 可观测性（AC-11）**: `baseFetch` 将 `traceId` 附加到 Error 对象，Case-12 有测试验证
- [x] **类型系统**: `vue-tsc --noEmit` 全通过，`src/models/auth.ts` 字段对齐 §1.6 契约
- [x] **Mock 模式不退化**: `useMock=true` 时跳过所有 token 逻辑，Mock 流程可继续独立运行
- [x] **SDD 范围约束**: 未创建/修改任何后端代码；未自创接口字段；Out of Scope 条目（OAuth2/移动端/管理端）均无涉及
- [x] **vi.hoisted 防 TDZ**: 测试文件正确使用 `vi.hoisted(() => vi.fn())` 避免 mock factory 提升导致的引用错误
- [x] **测试隔离**: 每个测试文件独立 `localStorageMock` + `setActivePinia(createPinia())`，测试间无状态泄漏

---

## 结论

评审阶段发现 1 个 🔴 严重问题（AC-5 登出功能不完整）并已在本评审周期内完成修复：`DefaultLayout.vue` 补充登出按钮，`logoutAction()` 补充服务端 token 吊销调用，`auth-store.spec.ts` 补充 Case-8 测试（62/62 GREEN，覆盖率全维度 ≥80%，vue-tsc 无报错）。

**0 个未解决 🔴 → 进入 ⑤ CI 门禁。**

---

## 补充评审：本分支新增实现（agent-chapter-14，2026-06-29）

- 审查文件: 桌面端新增组件 14 个、修改页面 8 个、修改 feedback 组件 2 个
- 🔴 严重问题: 0
- 🟡 建议改进: 2
- 🟢 通过项: 8

### 🟡 建议改进

#### 1. PlanningView.vue `watch` 立即触发时轮询已启动

- 文件: `src/views/pages/PlanningView.vue:68`、`PlanningViewDesktop.vue:68`
- 当前: `{ immediate: true }` watch 在挂载前触发，若 status 已为 `confirmed` 立即调用 `router.replace`；`onMounted` 仍会调 `store.startPolling(planId)`（在 watch 跳转后）
- 建议: watch 在 `router.replace` 前先调 `store.stopPolling()`，避免 replace 后轮询仍在运行占用资源
- 风险: 轻微（路由跳转后组件会卸载，`onUnmounted` 最终会清理），不影响正确性

#### 2. 桌面端组件缺少单元测试

- 文件: `src/components/**/*Desktop.vue`、`src/views/pages/*Desktop.vue`（共 14 个文件）
- 当前: 移动端组件已有测试覆盖（62 tests），桌面端组件暂无对应测试文件
- 建议: 补充至少 `FeedbackExportDesktop.spec.ts`（goToPlan 路由行为）和 `ResultViewDesktop.spec.ts`（plan 数据缺失时触发 fetch）
- 风险: 中等（Bug B-2 / B-3 的回归保护缺失）

### 🟢 通过项

- [x] **B-1 缓存命中逻辑正确**: `{ immediate: true }` + `old === 'review'` 区分正常 HITL 确认路径与缓存命中路径，逻辑明确
- [x] **B-2 plan 空检测**: `|| !store.detail.plan` 加在四个页面 `onMounted` 条件中，覆盖完整
- [x] **B-3 统计项可点击**: `FeedbackExportDesktop` 与 `FeedbackExport` 均传入 `planId` prop，`goToPlan` 只在 `planId` 存在时跳转，防御性正确
- [x] **响应式布局**: `DefaultLayout.vue` 通过 `DESKTOP_MAP + computed isDesktop` 按路由名动态切换，与原有移动端路径解耦，无 breaking change
- [x] **路由扩展**: `gallery` 路由仅新增，未修改现有路由 name/path，无 redirect 风险
- [x] **架构合规**: 所有改动在 `huazai-trip-front` 内，未引入新第三方依赖
- [x] **类型安全**: `planId?: string` 可选 prop 设计正确，避免强制传参带来的使用方复杂度
- [x] **SDD 符合**: 改动均在 C-013 变更范围内；无越界修改后端或其他模块

### 结论

**0 个未解决 🔴 → 已可进入 ⑤ CI 门禁。**  
桌面端组件测试缺失（🟡 建议项 2）建议在后续 Sprint 补齐，不阻塞当前发布。
