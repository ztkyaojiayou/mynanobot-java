# 部署验证报告: C-013

## 验证环境

- 前端: `huazai-trip-front`（本地 dev server `npm run dev`，Vite + Vue 3）
- 后端: `huazai-trip-server`（需启动方可做真实 HTTP 验证；以下静态验证基于 Mock 模式）
- 验证时间: 2026-06-18

---

## ⑥ 验证项目

### 静态验证（已完成）

| 项目 | 结果 | 说明 |
|------|------|------|
| `vue-tsc --noEmit` | ✅ PASS | 无 TypeScript 类型错误 |
| 62 单测全绿 | ✅ PASS | 13 测试文件 × 62 cases，0 失败 |
| 覆盖率 Statements 88.65% | ✅ PASS | 阈值 80% |
| 覆盖率 Branches 84.93% | ✅ PASS | 阈值 75% |
| 覆盖率 Functions 83.6% | ✅ PASS | 阈值 80% |
| 覆盖率 Lines 89% | ✅ PASS | 阈值 80% |

### Mock 模式主流程冒烟（`useMock=true`）

以下为 Mock 模式下可验证的 UI 行为路径（`npm run dev` 启动后手动或 E2E 验证）：

| Case | 路径 | 期望行为 | 状态 |
|------|------|---------|------|
| Case-1 | 注册页 → 提交合法表单 | Mock token 写入，跳转 `/intent` | ✅ 代码路径已测试 |
| Case-2 | 注册页 → 409 | 显示「用户名已存在」，不跳转，表单保留 | ✅ 测试覆盖 |
| Case-3 | 登录页 → 成功 | localStorage 写入 accessToken，跳转 `/intent` | ✅ 测试覆盖 |
| Case-4 | 未认证访问 `/intent` | 重定向 `/login` | ✅ 测试覆盖 |
| Case-5 | token 临近过期 | 自动 refresh，原请求无感续签 | ✅ 测试覆盖 |
| Case-6 | refresh 也失败 | 清除 token + 跳转 `/login` | ✅ 测试覆盖 |
| Case-7 | 并发 3 请求同时 401 | 只发一次 refresh | ✅ 测试覆盖 |
| Case-8 | 点击退出登录 | `POST /auth/logout` + 清本地 + 跳 `/login` | ✅ 测试覆盖（logoutAction mock） |
| Case-9 | 填表提交 → 轮询 → 行程展示 | Mock 状态机推进至 review，显示 HITL 面板 | ✅ trip-plan-store-mock 覆盖 |
| Case-10 | days=0 → 提交 | 前端拦截，不发 HTTP | ✅ 测试覆盖 |
| Case-11 | HITL CONFIRM | 状态变 confirmed | ✅ 测试覆盖 |
| Case-12 | 503 | 可读错误 + traceId | ✅ 测试覆盖 |
| Case-13 | fallback=true 数据 | UI 标注降级估算 | ✅ 组件渲染已测试 |

### 真实后端联调（待执行）

> 以下需后端 `huazai-trip-server` 启动后执行，属于 C-012 E2E 覆盖范围，C-013 前端已具备消费能力：

```
1. 启动后端: cd huazai-trip-server && mvn spring-boot:run
2. 启动前端: cd huazai-trip-front && npm run dev (useMock=false)
3. 注册新用户 → 验证 201 + 自动登录 → localStorage 含真实 accessToken
4. 填写旅行需求 → 提交 → 观察进度页轮询状态推进
5. 等待 review 状态 → 验证 HITL 面板显示 interventionReason/Advice
6. 点 CONFIRM → 状态变 confirmed → 导航至 result 页
7. 提交 UP 反馈 → 验证 POST /feedback 调用成功
8. 点击退出登录 → 验证 Redis 中 token 被吊销（后端日志确认）
```

---

## 结论

所有静态验证项目通过（type-check + 62/62 tests + 覆盖率全维度 ≥80%）。Mock 模式 13 个 Cases 全部有测试守护。真实后端联调需后端环境就绪后单独执行（属 C-012 E2E 范围，前端接口消费能力已就绪）。

**⑥ 静态部署验证通过 → 进入交付。**

---

## 本分支手动验证（agent-chapter-14）

### 验证日期：2026-06-29

### 验证项目

| 验证项 | 结果 | 说明 |
|--------|------|------|
| 桌面端响应式布局切换（全面屏 / 手机模式） | ✅ 手动验证通过 | DefaultLayout DESKTOP_MAP 正确切换六个桌面端页面 |
| 效果图墙（/gallery）页面 | ✅ 手动验证通过 | 路由 gallery 新增，页面可正常渲染 |
| **B-1 缓存命中跳转**：同参数再次提交 → 直接跳 plan 页（不经规划中页） | ✅ 手动验证通过 | backend log 显示 "命中缓存，跳过编排"，前端直接展示行程详情页 |
| **B-2 plan 数据空白转圈**：缓存命中导航后结果页正常加载数据 | ✅ 手动验证通过 | 加入 `\|\| !store.detail.plan` 条件后正常 fetch 并渲染 |
| **B-3 统计项导航**：结果页点击天数/景点/美食/评分/节省进入行程详情 | ✅ 手动验证通过 | 五个 stat-item `@click="goToPlan"` 可正常跳转 /plan/:planId |

### 待完成

- 桌面端组件单测覆盖（当前移动端已有覆盖，桌面端组件待补）
- 统一 CI 全量跑通验证
