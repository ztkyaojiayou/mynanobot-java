---
name: unit-test-ci
stage: ⑤ CI 与质量门禁
description: 机械化执行全量质量门禁——静态分析、架构约束、全量测试、安全扫描
owner: Owner Agent
---

# CI 与质量门禁技能（unit-test-ci）

> **流水线阶段**: ⑤ 第五步
> **输入**: 通过 ④ 评审的完整变更
> **出口门禁**: 所有 CI 阶段全绿

---

## 1. 职责

你是质量门禁的机械化执行者。把约束从"靠人记"变成"靠机器验"。任一检查失败即红灯，禁止放行。

---

## 2. CI 流水线（每次提交触发）

```
stage-1  编译检查
  mvn clean compile
  mvn checkstyle:check          # 风格：文件≤500行/方法≤50行/圈复杂度≤10
  mvn pmd:check                 # 静态分析 + 重复代码

stage-2  架构约束
  mvn test -pl huazai-trip-tests -Dtest=ArchitectureConstraints
  # Agent 不互相依赖 · Skill 不依赖 Agent · Controller 不直调 Agent · 依赖方向正确

stage-3  单元测试 + 覆盖率
  mvn test
  mvn jacoco:report             # 核心逻辑覆盖率 ≥80%

stage-4  安全扫描
  mvn enforcer:enforce          # 依赖版本一致、禁 SNAPSHOT
  dependency-check:check        # 依赖漏洞
  git-secrets / 扫描硬编码密钥

stage-5  集成测试（PR 时）
  mvn verify -pl huazai-trip-tests -Dtest="*IT"
```

---

## 3. 门禁判定表

| 检查项 | 通过标准 | 失败处理 |
|--------|---------|---------|
| 编译 | 0 error | 退回 ② 编码 |
| Checkstyle | 0 violation | 退回 ② 编码 |
| PMD | 0 priority-1/2 | 退回 ② 编码 |
| ArchUnit | 全部 @ArchTest 通过 | 退回 ②（架构腐化，严重） |
| 单元测试 | 0 failed | 退回 ② / ③ |
| 覆盖率 | 核心 ≥80% | 退回 ③ 补测试 |
| 规格覆盖 | 关键 AC / 降级路径有对应测试 | 退回 ③ 补测试 |
| 依赖一致性 | enforcer 通过、无 SNAPSHOT | 请示人类（依赖变更） |
| 密钥扫描 | 0 命中 | 退回 ②（安全红线） |

---

## 4. 输出格式

```markdown
## 🚦 CI 门禁报告: C-NNN

| 阶段 | 结果 | 详情 |
|------|------|------|
| 编译 | 🟢 | 0 error |
| Checkstyle | 🟢 | 0 violation |
| PMD | 🟢 | — |
| 架构约束 | 🟢 | 5/5 @ArchTest 通过 |
| 单元测试 | 🟢 | 42 passed |
| 覆盖率 | 🟢 | 核心 84% |
| 规格覆盖 | 🟢 | AC / 边界 / 降级测试齐备 |
| 安全扫描 | 🟢 | 0 命中 |

结论: ✅ 全绿，可进入部署验证 / ❌ stage-X 失败，退回 …
```

---

## 5. 约束

- ❌ 禁止注释/绕过 ArchUnit 测试以"通过"门禁（熵检测信号，严重违规）
- ❌ 禁止降低覆盖率阈值
- ❌ 禁止跳过任何 stage
- ✅ 失败必须定位到具体 stage 与原因
- ✅ 红灯即停，不进入 ⑥

---

## 6. 完成标志

全绿 → 更新 `change.md` 状态 `ci → verifying`，进入 ⑥ 部署验证。
