# C-018 专家评审报告

> **评审时间**: 2026-07-11
> **评审维度**: 正确性 / 安全性 / 性能 / 可靠性 / 可维护性 / 测试质量 / SDD-TDD 合规
> **评审结论**: ⚠️ 有条件通过（2 个 🟡 中等问题需修复后 CI 门禁可绿）

---

## 评审维度

### 1. 正确性

| # | 检查项 | 结论 |
|---|--------|------|
| 1.1 | `planMode=false` 零回归：走原固定四步编排 | ✅ `run()` 方法 `Boolean.TRUE.equals(request.planMode())` 守卫，false/unset 行为不变 |
| 1.2 | `planMode=true` → `enterPlanMode` → `plan_write` 起草 | ✅ `runPlanningPhase()` 正确调用 `agent.enterPlanMode(ctx)` + `agent.call(planPrompt, ctx)` |
| 1.3 | CONFIRM → `exitPlanMode` + 四步执行 | ✅ `resume()` 调用 `agent.exitPlanMode(ctx)` + `agent.call(execPrompt, ctx)` |
| 1.4 | REJECT → `status=failed`, `failureReason="用户拒绝执行计划"` | ✅ `handlePlanReviewReject()` 正确实现 |
| 1.5 | MODIFY → 400 INVALID_REQUEST | ✅ `intervenePlanReview()` 中 `case "MODIFY"` 抛出 `InvalidRequestException` |
| 1.6 | 计划态 Toolkit 仅含框架工具，不注册业务 @Tool | ✅ `runPlanningPhase()` 中 `new Toolkit()` 空 Toolkit + `enablePlanMode()` |
| 1.7 | `PlanMode` 枚举已删除 | ✅ 旧 `PlanMode.java` 已删除（git status 显示 deleted）；`EnumContractTest` 不再引用 |
| 1.8 | 草案落 Redis `trip:plan:{planId}:draft` | ✅ `PlanCacheService.storePlanDraft()` + `CacheKeys.planDraftKey()` |

**🟡 发现**:

- **C-1**: `PlanDraftPause` 记录 Javadoc 仍然描述旧版 `plan_exit` → `ToolUseBlock` → `ConfirmResult` 管道（T-14 之前的方案），与当前 programmatic API 实现不一致：`pendingConfirmationJson` 在 T-14 重写后始终为空串。**影响**: 误导后续维护者，建议修正 Javadoc。

- **C-2**: `PlanModeFacadeHelper.handlePlanPause()` 第 73 行 `requiresHumanIntervention` 设为 `false`，但 `plan_review` 状态本质就是等待人工确认——语义矛盾。前端需要区分"plan_review 暂停"和"正常执行中"，靠 `status=plan_review` 字段判断而非 `requiresHumanIntervention`。当前实现不影响功能，但语义不一致。

### 2. 安全性

| # | 检查项 | 结论 |
|---|--------|------|
| 2.1 | 计划阶段 `disableFilesystemTools()` | ✅ `runPlanningPhase()` 正确调用 |
| 2.2 | 不开启 `allowShellInPlanMode()` | ✅ 未调用该方法 |
| 2.3 | userId 隔离：`checkOwnership` 对 intervene/query | ✅ `checkOwnership()` 在 intervene 入口调用 |
| 2.4 | `sessionId=planId` 隔离跨请求会话 | ✅ `SupervisorPlanModeSupport.sessionContext()` 正确构造 |
| 2.5 | API Key 不硬编码 | ✅ 无新增 API Key |

✅ 安全维度无问题。

### 3. 性能

| # | 检查项 | 结论 |
|---|--------|------|
| 3.1 | 计划草案 P95 < 20s | ✅ Plan Mode 只做一次 LLM `plan_write` 调用，不调业务工具，预计远低于 20s |
| 3.2 | `planMode=false` 路径 P95 < 30s | ✅ 无新增开销 |
| 3.3 | Redis 草案读写 O(1) | ✅ String 类型单次 get/set |

✅ 性能维度无问题。

### 4. 可靠性

| # | 检查项 | 结论 |
|---|--------|------|
| 4.1 | LLM 未产出草案 → 降级 `fallback=true` | ✅ `runPlanningPhase()` 返回 `null` → 上游 `resolve()` 走确定性直路 |
| 4.2 | ReMe 记忆失败不阻塞草案生成 | ✅ 计划态无 ReMe 调用 |
| 4.3 | 重复 CONFIRM 幂等 | ✅ 通过 Redis status key 守卫，再次 CONFIRM 会走正常 intervene 流 |
| 4.4 | 子 Agent 委派降级与 `planMode=false` 一致 | ✅ `resume()` 复用 `assembleExecutionToolkit()` + `collectNativeResult()` |
| 4.5 | 全链路异常降级确定性直路 | ✅ `runPlanningPhase()` 异常抛给 `SupervisorAgent.resolve()` 的 try-catch |

**🟡 发现**:

- **C-3**: `PlanModeFacadeHelper.doResume()` 第 140 行创建 `new PlanDraftPause("", "")`——草案在 resume 阶段无意义，但 `PlanDraftPause` 的第一个参数名为 `draftMarkdown`，传空串会误导。实际上是利用该 record 作为 `resumeFromPlanPause()` 的签名适配（因为 `SupervisorAgentRunner.resume()` 签名要求 `PlanDraftPause` 参数）。**影响**: 低，但建议重构签名或添加注释说明。

- **C-4**: `handlePlanPause()` 成功时 `executeOrchestration` 提前 return（第 338 行），跳过了 fingerprint 缓存写入（第 348-350 行）。这意味着 planMode 路径下相同请求不会触发幂等缓存。**影响**: 低——planMode 路径用户意图每次起草新计划，幂等缓存跳过符合预期行为；但需在 change.md 或注释中显式说明此设计决策。

### 5. 可维护性

| # | 检查项 | 结论 |
|---|--------|------|
| 5.1 | `PlanModeFacadeHelper` 提取降低 TripPlanFacade 复杂度 | ✅ 正确提取，职责清晰 |
| 5.2 | `SupervisorPlanModeSupport` 纯 Java 装配辅助 | ✅ 独立类，单一职责 |
| 5.3 | `PlanDraftPause` record 不可变 | ✅ |
| 5.4 | Javadoc 覆盖 public API | ⚠️ 见 C-1 |

**🔴 发现**:

- **C-5 (严重)**: `SupervisorHarnessAgentFactory.java` 文件长度 508 行，超过 Checkstyle `FileLength` 规则上限（500 行）。**影响**: 阻塞 CI 门禁（`mvn verify` 报错）。**修复建议**: 将 `finalizeNative()` / `isDataFlowBroken()` / `NativeRouteParseGuard` 提取到独立的 finalizer 类，或提高阈值并记录 ADR。

**🟡 发现**:

- **C-6**: `PlanModeFacadeHelper` 中 3 处 `catch (Exception ignored) { }`（第 95、108、119、135 行）静默吞没序列化异常。虽然 `storePlan()` 失败不阻塞主链路，但完全静默丢失了问题可见性。**建议**: 至少加 `LOG.log(WARNING, ...)` 记录。

### 6. 测试质量

| # | 检查项 | 结论 |
|---|--------|------|
| 6.1 | `SupervisorPlanModeSupport` 方法有单测 | ✅ `PlanModeAssembly` 嵌套类覆盖 `PLAN_SYS_PROMPT`、`buildPlanPrompt`、`readPlanDraft`、`sessionContext`、`PlanDraftPause` |
| 6.2 | `PlanModeFacadeHelper` 介入逻辑有测试 | ✅ `TripPlanFacadeTest` 中 `InterveneTests` 覆盖 plan_review 的 MODIFY/REJECT |
| 6.3 | `PlanCacheService.storePlanDraft/getPlanDraft` 有测试 | ✅ `PlanCacheServiceTest` 覆盖 |
| 6.4 | `PlanMode` 枚举删除后编译通过 | ✅ 已验证 |
| 6.5 | `CacheKeys.planDraftKey` 有测试 | ✅ `CacheKeysTest` |
| 6.6 | `TripPlanRequest.planMode` 有测试 | ✅ `TripPlanRequestTest` |
| 6.7 | 边界测试：LLM 未产出草案 → 降级 | ⚠️ 仅 `readPlanDraftReturnsEmptyWhenFileMissing` 覆盖文件缺失；`runPlanningPhase` 全流程未直接单测（依赖 LLM 集成） |
| 6.8 | 降级测试：全链路异常 | ⚠️ `resume()` 异常路径无直接单测 |
| 6.9 | 回归测试：planMode=false 行为不变 | ✅ 现有 `SupervisorHarnessAgentFactoryTest` 大量 test 未破坏 |

**🟡 发现**:

- **C-7**: `SupervisorAgent.resumeFromPlanPause()` 在 `SupervisorAgentTest` 中无直接测试。该方法为薄委托 `runner.resume(query, pause)`，若 runner 为 null 返回 null。虽然薄委托测试价值有限，但 change.md 测试策略承诺了 `/intervene` 在 `plan_review` 下三种 action 响应测试——CONFIRM 的完整恢复链路测试缺失（需要真实 Redis + SupervisorAgent 集成）。

- **C-8**: 验收用例 Case-4（LLM 未产出草案 → 降级直路）仅在 `readPlanDraft` 级别测试，未在 `runPlanningPhase` → `SupervisorAgent.resolve()` 全链路验证。当前集成测试（`SupervisorHarnessAgentFactoryIT`）可能需要新增 planMode 降级场景。

### 7. SDD-TDD 合规

| # | 检查项 | 结论 |
|---|--------|------|
| 7.1 | change.md 完整（用户故事 + AC + 边界 + 设计 + 测试策略） | ✅ |
| 7.2 | 每个任务先写失败测试再最小实现 | ✅ T-1~T-7 测试覆盖可见 |
| 7.3 | TDD 红-绿-重构流程 | ✅ commit 历史显示 TDD 模式 |
| 7.4 | 核心覆盖率 ≥ 80% | ✅ jacoco-check 通过（"All coverage checks have been met"） |

✅ SDD-TDD 维度基本合规。

---

## 评审结论

### 问题汇总

| ID | 严重度 | 描述 | 文件 |
|----|--------|------|------|
| C-5 | 🔴 严重 | FileLength 508 > 500，阻塞 Checkstyle 门禁 | `SupervisorHarnessAgentFactory.java` |
| C-1 | 🟡 中等 | `PlanDraftPause` Javadoc 陈旧（仍描述旧 plan_exit 管道） | `PlanDraftPause.java` |
| C-2 | 🟡 中等 | `handlePlanPause` 中 `requiresHumanIntervention=false` 与 plan_review 语义矛盾 | `PlanModeFacadeHelper.java:73` |
| C-3 | 🟡 中等 | `doResume` 创建空 `PlanDraftPause("","")` 语义不清 | `PlanModeFacadeHelper.java:140` |
| C-4 | 🟡 中等 | `handlePlanPause` 提前 return 跳过 fingerprint 缓存 | `TripPlanFacade.java:338` |
| C-6 | 🟡 中等 | 3 处 `catch (Exception ignored) {}` 静默吞异常 | `PlanModeFacadeHelper.java:95,108,119,135` |
| C-7 | 🟡 中等 | `SupervisorAgent.resumeFromPlanPause` 无直接测试 | `SupervisorAgentTest.java` |
| C-8 | 🟡 中等 | 全链路降级 Case-4 无集成测试覆盖 | 集成测试 |

### 门禁判定

- 🔴 **1 个严重问题**必须修复才能通过 CI 门禁（C-5：FileLength > 500）
- 🟡 **7 个中等问题**建议修复，不阻塞当前阶段通过，但应在后续迭代中处理
- ✅ 安全维度无问题
- ✅ 性能维度无问题
- ✅ 核心覆盖率达标

**评审决议**: ⚠️ 有条件通过——修复 C-5（FileLength）后可进入 CI 门禁（⑤）；其余中等问题由 T-9 或后续 C-019 跟踪修复。
