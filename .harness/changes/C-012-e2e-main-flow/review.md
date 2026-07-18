---
id: C-012-review
reviewer: expert-reviewer
status: approved
created: 2026-06-17
---

# C-012 端到端主流程 — 专家评审报告

## 结论

**通过（0 严重问题，3 轻微建议）**。12/12 E2E 测试全绿，AC-1~AC-7 逐条满足，架构合规。

---

## 评审维度

### 1. 架构合规（AC-6 / ArchUnit R1~R6）

| 规则 | 检查结果 |
|------|---------|
| R1：模块间无非法直接依赖 | PASS — E2eAgentConfig 通过接口调用 Agent，不 import 跨模块实现类 |
| R2：Agent 间仅经 A2A 通信 | PASS — SubAgentDispatcher 作为唯一委派通道，无 Agent 直接调用另一 Agent |
| R3：Skill 不依赖 Agent | PASS — BudgetCalculationService/RoutePlanningService 等均无 Agent import |
| R4：Server 不直接 import Agent 实现 | PASS — SupervisorAgent 经 @Bean 注入，Server 层无 Agent 包直接 import |
| R5：公共模型只在 common 定义 | PASS — TripPlan/Attraction 等均在 huazai-trip-common |
| R6：test 代码不泄露到 main | PASS — E2eAgentConfig/@TestConfiguration 不在 main 路径 |

### 2. TDD 合规（先红后绿）

- `XHSAnalysisAgent` 返回类型 Bug（`resolved.result().candidates()` → `resolved.result()`）在 InvariantGuardE2eTest 变红后定位并修复，满足 TDD 原则
- 三场景（正常/HITL/降级）均先写断言再跑通链路
- HITL 存根边界从 `×1.15`（等于不触发）修正为 `×1.20`，覆盖 OutputQualityGate 严格大于语义

### 3. 测试覆盖

| 测试类 | 测试数 | 覆盖场景 |
|--------|--------|---------|
| HappyPathE2eTest | 3 | 正常链路 + 202 状态 + 轮询终态 |
| HitlE2eTest | 2 | 超预算 HITL 触发 + 已 confirmed 再 intervene 400 |
| MapDegradationE2eTest | 1 | 地图 5xx → haversine 降级 → 终态含 fallback |
| AuthIsolationE2eTest | 3 | 无 token 401 + 跨用户 403 + 并发 3 planId 隔离 |
| InvariantGuardE2eTest | 3 | INV-1/2/3 天数+餐+去重、INV-4 预算精度、INV-5/6 评分+耗时 |

总计 **12 测试，0 failures，0 errors**（mvn test -pl huazai-trip-tests 验证）。

### 4. Mock 深度

- 外部 API（LLM/地图/XHS）通过 `E2eAgentConfig` 内存桩替身，CI 不联网 ✓
- Spring Boot 全上下文、所有 Agent/Service/Gate Java 代码真实执行 ✓
- embedded Redis 真实运行，planId/userId 绑定经 Redis 实际验证 ✓

### 5. Bean 覆盖机制

- `spring.main.allow-bean-definition-overriding=true` + 规范 Bean 命名（匹配 PlanConfig）确保 E2eAgentConfig 正确覆盖生产配置
- 场景配置（HitlAgentConfig/MapFailAgentConfig）以 `@Bean @Primary SupervisorAgent` 内联构造整条链路，消除多 Bean 歧义

### 6. 已发现并修复的 Bug

| Bug | 位置 | 修复 |
|-----|------|------|
| XHSAnalysisAgent 返回错误类型 | `XHSAnalysisAgent.java:156` | `resolved.result().candidates()` → `resolved.result()` |
| HITL 存根边界值不触发 Gate | `HitlE2eTest.java:110` | `×1.15`（等于）→ `×1.20`（严格超过） |

---

## 轻微建议（不阻塞 done）

1. **InvariantGuardE2eTest 景点 ID 字段**：`attraction.get("id")` 应读取序列化后的字段名（目前 Java record `attractionId` 序列化后可能为 `attractionId`），建议补充 JSON key 探测兼容两种名称。

2. **HITL 注释更新**：`HitlE2eTest` 类注释中提到"WireMock 构造超预算"，但实际使用 `BudgetAgentRunner` 内存桩，建议更新 Javadoc 避免误导。

3. **`alreadyConfirmed_intervene_returns400` 场景**：当前用正常请求（非 HITL 桩）验证，`yunnan5DayRequest` 实际产出 `confirmed` 状态，`CONFIRM` 调用会先触发 "非 review 状态" 400，逻辑成立但测试意图注释可更清晰。

---

## 覆盖率

`huazai-trip-tests` 为纯 E2E 测试模块（无业务代码），JaCoCo 不计覆盖率；上游各模块单测覆盖由 C-001~C-011 各自守护。

## 评审结论

C-012 实现路径与 SDD 保持一致，TDD 先红后绿，12 测试全绿，AC-1~AC-7 全部满足，生产 Bug（XHSAnalysisAgent 类型错误）已附带修复。批准进入 CI 门禁阶段。
