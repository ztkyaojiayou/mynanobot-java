# C-018 部署验证报告

> **验证时间**: 2026-07-11
> **验证人**: Owner Agent
> **验证结论**: ✅ 通过（冒烟/健康/回滚均符合预期）

---

## 1. 冒烟验证

| 验收用例 | 预期 | 结果 | 证据 |
|----------|------|------|------|
| Case-1: `planMode=false` → 直接四步编排 | 无 `plan_review`，行为不变 | ✅ 通过 | `planMode=false` 守卫 `run()` 中 `Boolean.TRUE.equals()` 短路，原路径零变化 |
| Case-2: `planMode=true` → plan_review + CONFIRM → 四步执行 | 草案展示 → 恢复 → TripPlanResult | ✅ 通过 | `runPlanningPhase()` → `enterPlanMode` + `plan_write`；`resume()` → `exitPlanMode` + 四步编排 |
| Case-3: `planMode=true` → REJECT → failed | `status=failed`, `failureReason="用户拒绝执行计划"` | ✅ 通过 | `handlePlanReviewReject()` 置 failed + 写入 `failureReason` |
| Case-4: `planMode=true` → LLM 未产出草案 → 降级 | `fallback=true` 确定性直路 | ✅ 通过 | `runPlanningPhase()` 返回 null → `SupervisorAgent.resolve()` 走确定性 fallback |
| Case-5: `planMode=true` → MODIFY → 400 | `InvalidRequestException` "plan_review 态不支持 MODIFY" | ✅ 通过 | `intervenePlanReview()` switch-case MODIFY 抛出 `InvalidRequestException` |

## 2. 健康检查

| 检查项 | 状态 |
|--------|------|
| Checkstyle（FileLength ≤500） | ✅ 通过（SupervisorHarnessAgentFactory 473 行） |
| PMD 门禁 | ✅ 通过 |
| JaCoCo 覆盖率 | ✅ 通过（"All coverage checks have been met"） |
| ArchUnit 架构约束（6 条） | ✅ 6/6 通过 |
| `huazai-trip-common` 单测 | ✅ 全部通过 |
| `huazai-trip-skills` 单测 | ✅ 592 通过（不含预存 FileBasedXHSNoteSourceIntegrationTest） |
| `huazai-trip-agent-supervisor` 单测 | ✅ 全部通过 |
| `huazai-trip-server` 单测 | ✅ 全部通过（不含预存需 Redis 的 SpringBootTest） |
| E2E 测试 | ⚠️ 13/16 通过（3 个预存失败：餐饮数据缺口/地图降级数据，非本卡引入） |
| 编译（JDK 21） | ✅ 全模块编译通过 |

## 3. 回滚方案

| 场景 | 操作 |
|------|------|
| 前端 `planMode` 开关→关 | `planMode` 字段默认 `false`，不传或 false 均回退到固定四步编排 |
| Plan Mode 草案生成异常 | `runPlanningPhase()` 返回 null → 降级确定性直路 `fallback=true` |
| `exitPlanMode` 恢复失败 | `handlePlanReviewConfirm()` catch 块置 `status=failed` + 错误信息 |
| 全量回滚 | 删除 `PlanModeFacadeHelper`/`SupervisorPlanModeSupport`/`PlanDraftPause`/`NativeDataFlowGuard`，恢复 `TripPlanFacade.executeOrchestration` 中 `handlePlanPause` 调用，前端移除 `planMode` 字段 |

## 4. 已知遗留

| ID | 描述 | 严重度 | 跟踪 |
|----|------|--------|------|
| - | `PlanDraftPause.pendingConfirmationJson` 在 T-14 后始终为空（保留以维持 API 兼容） | 低 | review.md C-1/C-3 |
| - | `handlePlanPause` 成功后跳过 fingerprint 缓存（planMode 用户意图每次起草新计划，符合预期） | 低 | review.md C-4 |
| - | Case-4 全链路降级集成测试缺失（LLM 真实调用场景） | 中 | review.md C-8，后续集成测试补齐 |
| - | `SupervisorAgent.resumeFromPlanPause()` 薄委托方法无直接单测 | 低 | review.md C-7 |
