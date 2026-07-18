---
name: unit-test-write
stage: ③ 单元测试编写
description: 为实现代码编写单元测试，核心逻辑覆盖率 ≥80%，覆盖 AC 与边界
owner: Owner Agent
---

# 单元测试编写技能（unit-test-write）

> **流水线阶段**: ③ 第三步
> **输入**: ② 阶段的实现代码 + change.md
> **出口门禁**: 测试通过 · 核心逻辑覆盖率 ≥80% · 覆盖全部 AC、边界与降级路径

---

## 1. 职责

你是测试驱动质量的工程师。你的职责分两段：编码前让失败测试先存在，编码后补齐边界、降级、回归测试。目标不是为覆盖率而测，而是验证每条 AC 与每个边界、每条降级路径真实成立。

---

## 2. 工作流程

### Step 1: 梳理测试矩阵
从 `change.md` 提取所有 AC 与边界情况，建立"测试点 → 测试用例"映射表，确保无遗漏。

同时建立：
- `AC -> Spec Test`
- `边界 -> Boundary Test`
- `降级 -> Failure/Fallback Test`
- `缺陷修复 -> Regression Test`

### Step 2: 分层测试
| 层级 | 内容 |
|------|------|
| Spec Tests | 每条 AC 的 happy path / 核心行为 |
| Boundary Tests | 空/零值、极值、并发、重复执行 |
| Failure/Fallback Tests | 依赖故障时的降级逻辑（超时/限频/API 不可用） |
| Regression Tests | 已修复 bug / 历史故障回归 |

### Step 3: 编写测试
- 命名: `should_<期望>_when_<条件>` 或 `测试方法名 + 场景`
- 用 JUnit 5 + Mockito + AssertJ
- 每个测试**单一断言意图**，Arrange-Act-Assert 三段清晰
- **降级逻辑必须实测**，不满足于"应该能处理"
- 若 ② 阶段尚未先写失败测试，应先补 Red 测试再完善其余测试

### Step 4: Mock 原则
- Mock 外部依赖（LLM API、Web Search、File System）
- **禁止 Mock 自己写的业务类**（那样测的是 Mock 不是逻辑）
- 用 Stub 提供可控的故障场景（超时、异常、空返回）

### Step 5: 覆盖率核验
- 跑 JaCoCo，核心逻辑（Agent/Service/Skill）≥80%
- 覆盖率不足 → 补测试，而非降低标准
- 纯 getter/setter/配置类可豁免

---

## 3. 测试质量红线

- ❌ 只测 happy path，边界靠注释"应该能处理"
- ❌ 断言空泛（`assertNotNull` 当主断言）
- ❌ 测试间相互依赖、有顺序耦合
- ❌ Mock 自己写的类
- ❌ 只有实现后补测试，却没有体现失败测试先行
- ✅ 每条 AC 至少一个测试
- ✅ 每个边界情况至少一个测试
- ✅ 降级逻辑有专门测试

---

## 4. 示例

```java
@Test
void should_trigger_HITL_when_budget_overrun_exceeds_15_percent() {
    TripPlan plan = planWithCost(11500); // 预算 10000，超支 15%
    BudgetCheckResult result = budgetAgent.check(plan);
    assertThat(result.requiresHumanIntervention()).isTrue();
}

@Test
void should_return_offline_estimate_when_map_api_unavailable() {
    when(baiduMapTool.distance(any(), any())).thenThrow(new TimeoutException());
    RouteResult result = routeService.plan(req);
    assertThat(result.isFallback()).isTrue();   // 降级路径被实测
}
```

---

## 5. 完成标志

测试全绿 + 覆盖率达标 → 更新 `change.md` 状态 `testing → reviewing`，进入 ④ 专家评审。
