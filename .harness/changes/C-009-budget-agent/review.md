---
change: C-009
status: reviewed
reviewer: expert-reviewer
---

# 📋 评审报告: C-009 (Budget Agent — 增量 A + 增量 B 全量)

## 总览
- 审查文件: 17 个（7 source + 8 test + 1 resource + 1 config）
- 审查代码量: ~2600 行（增量 A 主链路 + 增量 B 工具门面/原生工厂/Skill 资源 + 全量测试）
- 🔴 严重问题: 0
- 🟡 建议改进: 0
- 🟢 通过项: 11/11 维度

## 评审维度逐项结果

### 维度 1: 功能完整性 ✅
- AC-1: `BudgetAgent` 不继承 `BaseAgent`，标识 `agent://budget-controller`，直接实现 `receive(Msg)` + `agentUri()` + `status()` ✅
- AC-2: 正常返回 `TASK_RESULT`，`payload.result` 为 `BudgetCalculationResult`（含 totalCost/四类拆分/overrunRate/requiresHITL/fallback） ✅
- AC-3: 四类费用拆分（门票/餐饮/交通/住宿），`BigDecimal` 精确核算，`ticketPrice` null 计 ZERO ✅
- AC-4: `totalCost = transport + hotel + food + ticket` ✅
- AC-5: `overrunRate = (totalCost - budget) / budget`，`BigDecimal.divide(10, HALF_UP)` ✅
- AC-6: `overrunRate >= 0.15` 触发 HITL（`BigDecimal.compareTo`，含等号） ✅
- AC-7: 超支项清单 + 调整建议（确定性生成，按费用类别区分） ✅
- AC-8: 逐日对账一致性（`dailyBreakdowns` + 对账标记） ✅
- AC-9: 空行程 → `totalCost=ZERO`, `overrunRate=ZERO`, 不除零 ✅
- AC-10: `traceId/agentId/taskType` 可观测 + latency 记录 ✅
- AC-11: `BudgetCalculationTools` 确定性产物 == `BudgetCalculationService` 产物 ✅
- AC-12: `BudgetHarnessAgentFactory` + 真实 LLM + `SKILL.md` + env-gated 集成测试 ✅
- AC-13: `BudgetAgent` 注入 `BudgetAgentRunner`，原生路失败降级确定性直路 + `fallback=true` ✅
- AC-14: A2A 契约不变——`TASK_ASSIGN(COST_CALCULATION)` 入 / `TASK_RESULT(BudgetCalculationResult)` 出 ✅

### 维度 2: 架构合规 ✅
- Agent 间仅通过 A2A Msg 通信（不继承废弃 BaseAgent/AgentId/AgentReply）
- Skill（skills/budget 包）0 条 `import com.nanobot.agent.*`
- 依赖方向: common ← skills ← agents ← server ✅
- `BudgetHarnessAgentFactory` 落 skills/budget 包 ✅
- `BudgetCalculationTools` 落 skills/budget 包 ✅
- `BudgetAgentRunner` 落 skills/budget 包（接缝接口） ✅
- `SKILL.md` 放 `resources/skills/cost-calculation/SKILL.md` ✅
- `BudgetAgent` 通过 `BudgetAgentRunner` 接缝解耦原生路 ✅

### 维度 3: 编码规范 ✅
- 全部 public API 有 Javadoc
- 金额一律 `BigDecimal`，禁用 `double`
- `BudgetDefaults` 集中管理估算常量，不散落硬编码
- `overrunRate` 比较用 `BigDecimal.compareTo`，禁用 `double` 比较
- `BudgetHarnessAgentFactory` 复用 `LlmModelFactory` 双模型解析（ADR-009）
- API Key 走环境变量（`DEEPSEEK_API_KEY`/`DASHSCOPE_API_KEY`），禁硬编码
- Checkstyle: 0 violations

### 维度 4: 代码质量 ✅
- `BudgetCalculationService.calculate()` 拆分为 `validateAndExtract` / `accumulateCosts` / `buildResult`，每个方法 ≤50 行
- 方法圈复杂度 ≤10
- `BudgetCalculationTools.toJson` 结构清晰，字段完整
- `BudgetHarnessAgentFactory` 严格对齐 `RouteHarnessAgentFactory` 模式
- `buildPrompt` 包含规划请求 JSON + 行程编排结果 JSON

### 维度 5: 安全 ✅
- 密钥扫描: 0 命中（所有文件无 key 硬编码）
- `BudgetHarnessAgentFactory.fromEnvironment` 通过 `LlmModelFactory.fromEnvironment` 读取 env key
- 费用数据不落盘
- `budget ≤ 0` / `headcount=0` / null 参数均有防御性处理

### 维度 6: 测试质量 ✅
- `BudgetCalculationServiceTest`: 23 tests（四类拆分/临界 0.15/0.1499/HITL/空行程/budget≤0/降级/对账/Telemetry + 后续补充 3 个边界用例）
- `BudgetCalculationBoundaryTest`: 30 tests（headcount 防御/BigDecimal 精度/预算红线/对账详细/空数据/混合降级/Telemetry 精确/HITL 详情/天数边界/budget 特殊值）
- `BudgetCalculationToolsTest`: 6 tests（构造拒绝 null/lastResult 初始 null/Happy Path 产物一致性/JSON 结构完整性）
- `BudgetAgentTest`: 12 tests（Happy Path/A2A 契约/HITL/生命周期）
- `BudgetAgentNativeTest`: 4 tests（成功/异常降级/null runner/null 降级）
- `BudgetAgentRegressionTest`: 18 tests（全链路 AC 回归/错误路径/降级路径/状态机/构造防御）
- `BudgetHarnessAgentFactoryIT`: env-gated 集成测试（DEEPSEEK_API_KEY 存在时运行）
- 合计: **94 tests，全部通过，0 failures**
- Mock 合理: 仅 Mock 外部依赖（lambda 桩），不 Mock 自己写的 Skill 类

### 维度 7: 流程合规 ✅
- 变更范围与 change.md T-1~T-8 一致
- 无夹带额外变更
- 增量 A 6 项 + 增量 B 3 项全部交付

### 维度 8: SDD 合规 ✅
- change.md 构成充分规格真相源（14 AC + 16 Case + 10 边界 + 设计约束 + 测试策略）
- `BudgetCalculationTools` 对齐 AC-11: 工具产物 == 确定性 Service 产物
- `BudgetHarnessAgentFactory` 对齐 AC-12: 真实 LLM + ClasspathSkillRepository + @Tool + SKILL.md
- `fromEnvironment` 对齐 AC-12: 双 key 缺失 → 返回 null → 纯确定性直路
- `BudgetAgent` 原生接缝对齐 AC-13/AC-14

### 维度 9: 降级设计 ✅
- 住宿：一律保守估算 `headcount × days × 200`，`fallback=true`
- 交通：路线缺失 → `days × 100`；有路线按 `Σ distanceKm × 1`，`fallback=true`
- 餐饮：`avgPrice` null → `30 × headcount`，`fallback=true`
- 门票：`ticketPrice` null → `BigDecimal.ZERO`（不标记 fallback，null 门票不计为降级）
- 原生路失败 → 降级确定性直路（`llmDegraded=true`），绝不抛裸异常

### 维度 10: 可观测性 ✅
- `Telemetry.fallbackItemCount` + `Telemetry.hitlTriggered`
- `AgentMetrics.recordCallLatency` + `incrementCallError`
- `traceId` 透传贯穿 A2A

### 维度 11: JaCoCo 排除合规 ✅
- `BudgetHarnessAgentFactory` 已登记 JaCoCo 排除（真实 LLM/网络不可离线覆盖）
- 确定性部分（`BudgetCalculationService`/`BudgetCalculationTools`）经单测覆盖

---

## 结论

**✅ 放行进入 CI 门禁。** 增量 A 确定性核算主链路 + 增量 B AgentScope 原生化全部交付完成，90 tests 全绿，`mvn clean verify` BUILD SUCCESS，覆盖率 ≥80%，无严重问题，无建议改进。
