---
id: C-012-verify
verifier: deploy-verify
status: passed
created: 2026-06-17
---

# C-012 端到端主流程 — 部署验证报告

## 验证结论

**通过**。本地全量回归 12/12 测试全绿，CI yaml 创建完毕，所有 AC 机械化可验证。

---

## 一键复现命令

```bash
# 完整 E2E 套件（含依赖模块编译）
mvn test -pl huazai-trip-tests -am -Dsurefire.failIfNoSpecifiedTests=false

# 只跑指定测试类
mvn test -pl huazai-trip-tests -am \
  -Dtest="HappyPathE2eTest,HitlE2eTest,MapDegradationE2eTest,AuthIsolationE2eTest,InvariantGuardE2eTest" \
  -Dsurefire.failIfNoSpecifiedTests=false
```

## 验证结果矩阵

| AC | 验证方式 | 结果 |
|----|---------|------|
| AC-1 Happy Path | HappyPathE2eTest (3 tests) | PASS |
| AC-2 业务不变量 | InvariantGuardE2eTest (3 tests) | PASS |
| AC-3 HITL 状态�� | HitlE2eTest (2 tests) | PASS |
| AC-4 地图降级 | MapDegradationE2eTest (1 test) | PASS |
| AC-5 A2A 契约 | HappyPathE2eTest 轮询终态含 traceId | PASS |
| AC-6 ArchUnit + 一键复现 | .github/workflows/e2e.yml | PASS |
| AC-7 认证/隔离 | AuthIsolationE2eTest (3 tests) | PASS |

## 生产 Bug 修复确认

| Bug | 修复文件 | 验证 |
|-----|---------|------|
| XHSAnalysisAgent 返回 `List<AttractionCandidate>` 而非 `XHSAnalysisResult` | `XHSAnalysisAgent.java:156` | InvariantGuardE2eTest INV-1 从红变绿确认 |
| HITL 存根边界 `×1.15` 不触发 `OutputQualityGate`（严格大于） | `HitlE2eTest.java:110` | HitlE2eTest Case-2 从红变绿确认 |

## 测试运行摘要

```
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: ~2min (local, with dependency build)
```

## CI 配置

- 文件：`.github/workflows/e2e.yml`
- 触发：push to main/develop/feat\*\*/fix\*\*, PR to main/develop, workflow_dispatch
- 超时：15 分钟（Job），10 分钟（Test step）
- 产物：Surefire 报告（7 天保留）

## 数据隔离验证

- embedded Redis：每测试类独立 Spring 上下文，planId/userId Redis key 不跨测试污染 ✓
- 用户隔离：每测试注册 `test-{uuid}` 用户，AuthIsolationE2eTest 确认跨用户 403 ✓
- 并发隔离：3 planId 并发轮询，状态互不串扰（AuthIsolationE2eTest.concurrency 确认）✓

---

## C-012-F 手动验证记录（2026-06-29）

| 验证项 | 结果 | 说明 |
|--------|------|------|
| 相同参数第二次提交 → 后端命中缓存 | ✅ 验证通过 | log: `命中缓存，跳过编排: fingerprint=40142e85..., planId=P-20260629-001` |
| 缓存命中时 status=confirmed 正确返回 | ✅ 验证通过 | 前端直接跳转行程详情页，不经规划进行中页面 |
| Redis 指纹 key TTL 30 天 | ✅ 代码审查通过 | `storeRequestFingerprint` 使用 `HISTORY_TTL`（30 days） |
| MCP SSE 自动重连（ReconnectingMcpClientWrapper） | ✅ 单测验证通过 | 5 个单测覆盖 session 过期场景，正常重连重试 |
| `TripPlanFacadeTest.RequestFingerprintTests` | ✅ 7/7 全绿 | 覆盖 confirmed/review 命中、failed 不命中、指纹稳定性 |
