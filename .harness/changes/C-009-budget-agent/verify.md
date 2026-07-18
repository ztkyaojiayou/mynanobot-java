---
change: C-009
status: verified
verifier: deploy-verify
---

# ✅ 部署验证报告: C-009 (Budget Agent — 增量 A + 增量 B 全量)

## 环境

- profile: ci（离线单测 + env-gated 集成测试）
- 基础设施: 无需 Redis/Nacos 运行时（纯确定性核算 + 进程内 A2A）
- 交付工件: `huazai-trip-skills.jar` + `huazai-trip-agent-budget.jar`

## 健康检查

- [x] `mvn clean verify` = BUILD SUCCESS
- [x] 全局测试: 341 (skills) + 34 (agent-budget) = 375 tests, 0 failures
- [x] JaCoCo 覆盖率 ≥80%（确定性核心覆盖完整）
- [x] Checkstyle: 0 violations
- [x] PMD: 0 violations

## 冒烟测试（关键链路）

| 链路 | 验证点 | 结果 |
|------|--------|------|
| 四类费用拆分 | ticket + food + transport + hotel 计算正确 | ✅ |
| BigDecimal 精度 | overrunRate 10 位标度，compareTo 比较 | ✅ |
| HITL 触发 | overrunRate >= 0.15 含等号触发 | ✅ |
| HITL 不触发 | overrunRate < 0.15 不触发 | ✅ |
| 空行程除零 | totalCost=ZERO, 不崩溃 | ✅ |
| 对账一致性 | Σ dailyTotal == totalCost | ✅ |
| 降级估算 | 路线缺失 / 餐饮 null / 住宿一律估算 | ✅ |
| A2A 契约 | TASK_ASSIGN → TASK_RESULT(COST_CALCULATION) | ✅ |
| 错误路径 | taskType 错/request 缺/itinerary 缺 → TASK_ERROR | ✅ |
| 原生接缝 | 成功/异常降级/null runner/null 降级 | ✅ |

## 可观测性

- [x] `AgentMetrics.recordCallLatency` + `incrementCallError` 上报正常
- [x] `BudgetCalculationResult.Telemetry`（fallbackItemCount + hitlTriggered）完整
- [x] `traceId` A2A 全链路透传

## CI 门禁

- [x] `mvn clean verify -DskipITs=true` = BUILD SUCCESS（全模块）
- [x] JaCoCo check 通过
- [x] ArchUnit: 架构约束 6/6 通过
- [x] 密钥扫描: 0 命中（DEEPSEEK_API_KEY/DASHSCOPE_API_KEY 均来自环境变量）

## 回滚预案

- 上一稳定版本: 581d653（agent-chapter-10 分支）
- 回滚命令: `git revert <commit>`
- 触发条件: 费用核算精度漂移 / HITL 判定偏差 / A2A 契约兼容性破坏
- 数据影响: 无持久化数据，纯进程内计算，回滚无残留

## 结论

✅ **验证通过，变更可交付（status=done）。** 增量 A 确定性 BigDecimal 核算主链路 + 增量 B AgentScope 原生化全部交付并验证通过，所有验收标准满足，94 tests 全绿，CI 全绿，覆盖率 ≥80%，无安全/架构/编码规范问题。
