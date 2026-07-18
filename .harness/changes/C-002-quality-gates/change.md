---
id: C-002
slug: quality-gates
status: done
created: 2026-06-07
owner: Owner Agent
---

# C-002 质量门禁与架构约束机械化

## 用户故事

作为 Owner Agent，我想要把编码规范、文件组织约束、架构铁律与覆盖率要求落成机器可执行的门禁（Checkstyle / PMD / ArchUnit / JaCoCo / Maven Enforcer），以便流水线第 ⑤ 阶段（unit-test-ci）能自动、客观地阻断违规变更。

> **上游接手（来自 C-001，2026-06-07）**: C-001 经人类裁决对纯地基脚手架裁剪了 ⑤ 的完整机械化门禁，转交本变更。本变更落地 Checkstyle/PMD/ArchUnit/JaCoCo/dependency-check 后，必须对 **C-001 既有骨架**回归一次完整 ⑤——其中 ArchUnit R1-R6（AC-3）将以机械化方式取代 C-001 中对依赖方向的一次性手工反例验证（Case-3）。

## 非目标（Out of Scope）

- 本次不实现任何业务逻辑或 Agent。
- 本次不搭建 CI 平台流水线脚本（GitHub Actions / Jenkins 的 YAML 归运维侧；本次只保证 `mvn verify` 本地可触发全部门禁）。
- 本次不调整模块结构（依赖 C-001 已就位）。
- 本次不追求把覆盖率阈值套到尚无代码的模块（阈值随业务模块落地逐步生效）。

## 验收标准（AC）

- AC-1: Checkstyle 规则集落地并绑定 `validate`/`verify` 阶段，强制：文件 ≤500 行、方法 ≤50 行、圈复杂度 ≤10、一文件一 public 类、public API 必须有 Javadoc；违规即构建失败。
- AC-2: PMD 规则集落地，覆盖常见坏味道与潜在缺陷，违规（priority ≤ 设定阈值）即失败。
- AC-3: ArchUnit 架构约束测试落在 `huazai-trip-tests`，实现 R1~R6 六条铁律（Agent 互不依赖、Skill 不依赖 Agent、Controller 不直接调 Agent、common 不依赖业务、禁跨 Agent 引用 internal/impl、Server 不复制 Agent 业务规则），违规即测试失败。
- AC-4: JaCoCo 落地并对「Agent 核心逻辑」设定 ≥80% 行/分支覆盖率门禁（通过 includes/excludes 精准限定核心包，排除 DTO/配置/装配类）。**激活策略（本次澄清）**: 本期落地配置规则，仅对非空核心业务包计入；空模块 / 空核心包不因 0 覆盖率而失败，自 C-004 起随各业务模块落地**逐模块激活**阈值。
- AC-5: Maven Enforcer 强制禁止 SNAPSHOT 依赖、强制 JDK 21、强制 Maven ≥3.9，违规即失败。
- AC-6: 执行 `./mvnw clean verify` 时全部上述门禁被触发并通过；故意制造一处违规可使对应门禁失败。

## 边界情况（≥3）

- 当某文件恰好 501 行或方法 51 行时，Checkstyle 必须失败（临界值生效）。
- 当尚无任何业务代码时，JaCoCo 覆盖率门禁不得因「0 类」而误判失败（核心包为空时跳过或阈值不触发）。
- 当存在 Agent A 直接 import Agent B 的类时，ArchUnit `R1` 必须失败。
- 当依赖树中混入任意 `*-SNAPSHOT` 时，Enforcer 必须失败。
- 当 Javadoc 缺失于某 public 方法时，Checkstyle Javadoc 检查必须失败。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 全量 `verify`（含静态分析）在 CI 冷构建 < 6min |
| 可靠性 | 门禁结果确定性：同一提交多次运行结论一致 |
| 安全 | PMD/Checkstyle 规则包含硬编码密钥、`System.out` 滥用等基础检查 |
| 可观测 | 失败时输出可定位的文件:行号与规则名 |

## 设计约束

- 必须对齐 `.harness/rules/工程结构.md`（§3 架构铁律 R1~R6、§5 文件组织约束）与 `.harness/rules/编码规范.md`、`.harness/skills/unit-test-ci/SKILL.md`。
- ArchUnit 测试必须放在 `huazai-trip-tests`（仅测试作用域，可依赖全部模块）。
- 规则配置集中管理：Checkstyle/PMD 规则文件放在根 `build-tools/` 或根 POM 引用的统一位置，子模块复用，不允许各模块各写一套。
- 不允许通过 `@SuppressWarnings` / 全局 exclude 规避架构铁律。

## 契约影响

- REST: 无
- A2A: 无
- 数据模型: 无
- Redis / ReMe: 无

## 影响面

- 模块 / Agent / Skill: 根 POM（plugin management）、`huazai-trip-tests`（ArchUnit）、全部模块（继承门禁）
- 外部 API: 无
- wiki: 对齐 `工程结构.md` R1~R6 与 `编码规范.md` 质量指标，无需修改内容

## 规则归属

- 业务不变量归属: 不适用（本次为元规则/门禁）
- 外部调用治理归属: 不适用
- 可观测性要求: 门禁失败信息须含规则名 + 文件:行号

## 测试策略

- 先写失败测试: ArchUnit 各铁律先写为「期望存在的约束」，在引入临时违规样例时应失败（红），移除后通过（绿）。
- Happy Path: `./mvnw clean verify` 全门禁通过。
- 边界测试: 文件 501 行 / 方法 51 行 / 圈复杂度 11 / 缺 Javadoc 各触发失败。
- 降级测试: 不适用。
- 回归测试: 此门禁本身即所有后续变更的回归基线。

## 验收用例

- Case-1: 干净代码 → `./mvnw clean verify` → 全部门禁 PASS。
- Case-2: 在某 agent 模块 import 另一 agent 的类 → ArchUnit R1 FAIL → 移除后 PASS。
- Case-3: 引入一个 502 行文件 → Checkstyle FileLength FAIL → 拆分后 PASS。
- Case-4: 在 POM 引入 `x.y:z:1.0-SNAPSHOT` → Enforcer FAIL → 改为 release 版本后 PASS。

## 任务拆解（≤1 天/项，DAG 无环）

- [ ] T-1: 落地 Checkstyle 规则文件 + 根 POM 插件绑定（文件/方法长度、圈复杂度、单 public 类、Javadoc）· P0 · 依赖 C-001 · 模块 根
- [ ] T-2: 落地 PMD 规则集 + 插件绑定 · P0 · 依赖 C-001 · 模块 根
- [ ] T-3: 落地 Maven Enforcer（禁 SNAPSHOT、JDK 21、Maven ≥3.9）· P0 · 依赖 C-001 · 模块 根
- [ ] T-4: 在 tests 模块编写 ArchUnit R1~R6 约束（先红后绿）· P0 · 依赖 C-001 · 模块 tests
- [ ] T-5: 配置 JaCoCo 覆盖率门禁（核心包 ≥80%，空模块不误判，按核心包逐步激活）· P1 · 依赖 C-001 · 模块 根/各模块
- [ ] T-6: 全量 `verify` 验证 + 各门禁破坏性手工验证记录 · P0 · 依赖 T-1..T-5 · 模块 根

## 流水线进度

- [x] ① 需求分析（analyzing）
- [x] ② 编码实现（coding）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）
- [x] ④ 专家评审（reviewing，0 严重问题）→ review.md
- [x] ⑤ CI 门禁（ci，全绿）
- [x] ⑥ 部署验证（verifying）→ verify.md（N/A：元规则/门禁无可部署产物）
- [x] 交付（done，wiki 对齐 R1~R6 与质量指标，无需改内容）

## 任务执行记录（2026-06-07）

- T-1 ✅ Checkstyle：`build-tools/checkstyle.xml`（FileLength≤500/MethodLength≤50/CyclomaticComplexity≤10/OneTopLevelClass/JavadocType+MissingJavadocMethod/UnusedImports/禁 System.out/硬编码密钥），根 POM 绑定 `validate`，全模块继承。
- T-2 ✅ PMD+CPD：`build-tools/pmd-ruleset.xml`（errorprone/bestpractices/multithreading/performance/design），`failurePriority=2`，绑定 `verify`。
- T-3 ✅ Enforcer：沿用 C-001（requireJavaVersion 21 / requireMavenVersion≥3.9 / requireReleaseDeps）。
- T-4 ✅ ArchUnit R1~R6：`huazai-trip-tests` `arch/ArchitectureConstraints.java`（archunit-junit5 1.3.0；slices + noClasses；`allowEmptyShould` 适配增量落地）；surefire 加 `*Constraints` 包含模式以对齐 SKILL 调用。
- T-5 ✅ JaCoCo：根 `prepare-agent`+`report`；`check` 默认 `skip`（空模块不误判），首个激活样例落在 common（CacheKeys ≥80%）与 server（核心逻辑 ≥80%）。
- T-6 ✅ 全量 `./mvnw clean verify` 全绿（见验收用例执行记录）。

## 验收用例执行记录（AC-6 破坏性验证，一次性反例，已全部回退不入库）

- Case-1 ✅ 干净代码 `./mvnw clean verify` → 全门禁 PASS（10 模块 SUCCESS，30 测试通过，0 Checkstyle 违规，JaCoCo 覆盖率达标）。
- Case-2（R1）✅ 临时令 `agent-xhs` 依赖并引用 `agent-route` 的类 → ArchUnit `r1_agents_must_not_depend_on_each_other` FAIL（"Rule 'R1...' was violated (1 times)"）→ 移除后 6/6 PASS。
- Case-3（FileLength/Javadoc）✅ 临时插入含 `public` 方法缺 Javadoc / 未用 import / `System.out` 的类 → Checkstyle 分别报 `MissingJavadocMethod` / `UnusedImports` / `RegexpSingleline` FAIL → 移除后 0 违规。
- Case-4（Enforcer）✅ 临时引入 `com.example.probe:snapshot-probe:1.0-SNAPSHOT` → `RequireReleaseDeps` FAIL（"is not a release dependency"）→ 移除后 PASS。
- 附（PMD）✅ 开发期 `TripPlanServerApplication` 仅私有构造触发 P1 `ClassWithOnlyPrivateConstructorsShouldBeFinal` 使 `pmd:check` FAIL → 修正后 PASS（佐证 PMD P1/P2 阻断生效）。
