# SDD-TDD 模式规范

> **作用域**: 定义 mynanobot-java 在 Harness 体系下如何采用 SDD + TDD。
> **状态**: 核心逻辑推荐 TDD，配置/CLI/渲染层面不强制。

---

## 1. 三层关系

- **SDD (Spec-Driven Development)**：定义做什么，规格真相源是 `.harness/changes/C-NNN/change.md`
- **TDD (Test-Driven Development)**：定义怎么写对，`Red → Green → Refactor`
- **Harness**：定义怎么被约束、验证、留档、收口

对应关系：

```
change.md（规格真相源：用户故事 + AC + 边界）
    ↓
Red：先写失败测试（基于 AC/边界/降级路径）
    ↓
Green：最小实现让测试通过
    ↓
Refactor：在测试保护下优化
    ↓
verify.md：Harness 收口（mvn test 全绿）
```

---

## 2. 适用场景

| 场景 | TDD | 理由 |
|------|-----|------|
| Agent 核心逻辑 (AgentLoop/AgentRunner/TurnContext) | ✅ 推荐 | 状态转换、工具调度容易出错 |
| 安全组件 (PermissionManager/Guards/RuleEngine) | ✅ 推荐 | 安全边界必须正确 |
| 工具实现 (Tool impl) | ✅ 推荐 | 参数校验、结果格式需验证 |
| Provider (OpenAI/DeepSeek) | 🔶 可选 | 大量 HTTP mock，投入产出比低 |
| CLI/TUI (CliChannel/MarkdownRenderer) | ❌ 不强制 | 交互逻辑靠手工验证 |

---

## 3. TDD 小循环示例

```java
// ① RED — 先写失败测试
@Test
@DisplayName("Plan 模式拒绝写工具")
void testPlanModeRejectsWriteTools() {
    assertFalse(PermissionMode.PLAN.allowsTool(new WriteFileTool()));
}

// ② GREEN — 最小实现
PLAN { public boolean allowsTool(Tool t) { return t.isReadOnly(); } }

// ③ REFACTOR — 测试保护下可安全重构
```

---

## 4. 测试命名约定

```java
// 格式: test<场景> + @DisplayName 中文描述
@Test
@DisplayName("MessageBus 超时消费返回 null")
void testConsumeWithTimeout() { ... }
```

---

## 5. 覆盖率目标

| 层级 | 目标 | 当前 |
|------|------|------|
| core/ (引擎+State+Hook) | ≥80% | 部分覆盖 |
| security/ (Permission+Guard) | ≥80% | 待补充 |
| tools/ (工具实现) | ≥60% | 部分覆盖 |
| providers/ | ≥50% | 待补充 |
| CLI/v3/ | ≥30% | 手工为主 |

> 当前 59 个单元测试全绿，11 个测试类。建议后续开发优先补 core/ 和 security/ 的测试。

---

## 6. 最终原则

不追求形式主义。只要四点成立，SDD + TDD + Harness 就算落地：

1. 规格清楚（change.md 有 AC 有边界）
2. 核心逻辑先测后写
3. 变更可追溯（change/verify 留档）
4. mvn test 全绿
