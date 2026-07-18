---
id: C-001
slug: project-bootstrapping
status: done
created: 2026-06-07
owner: Owner Agent
---

# C-001 Maven 多模块骨架搭建

## 用户故事

作为 mynanobot-java 的开发者，我想要一套符合 ADR-002 的 Maven 多模块工程骨架（根 POM + 9 子模块 + 工具链锁定），以便后续所有变更都能在一致、可编译、依赖方向正确的结构上进行。

## 非目标（Out of Scope）

- 本次不实现任何业务逻辑（Agent / Service / Tool 均为空骨架）。
- 本次不引入 Checkstyle / PMD / ArchUnit / JaCoCo 等质量门禁（归 C-002）。
- 本次不搭建 Docker / Nacos / Redis 运行环境（归 C-003）。
- 本次不定义公共领域模型（归 C-004）。
- 本次不修改前端 `huazai-trip-front`。

## 验收标准（AC）

- AC-1: 根目录存在 `pom.xml`，`packaging=pom`，统一管理 JDK 21、Spring Boot 4.0.x 等版本（`<dependencyManagement>` + `<properties>`），AgentScope 通过 `import` scope 引入 `agentscope-bom:2.0.0-RC1`（子模块依赖 `agentscope-harness` 等不写版本号），且 `<modules>` 注册全部 9 个子模块。
- AC-2: 存在 9 个子模块目录且各自有 `pom.xml`：`huazai-trip-common`、`huazai-trip-agent-supervisor/xhs/route/itinerary/budget`、`huazai-trip-skills`、`huazai-trip-server`、`huazai-trip-tests`。
- AC-3: 子模块 `<dependency>` 仅声明工程结构规范允许的依赖方向（`common ← skills ← agents ← server ← tests`），不出现逆向或 Agent 互相依赖的声明。
- AC-4: `.java-version` 锁定为 `21`，`.mvn/wrapper/` 提供 Maven Wrapper（`mvnw` / `mvnw.cmd`），`.editorconfig` 存在并约定 UTF-8 + LF + 缩进。
- AC-5: 在干净环境执行 `./mvnw clean compile` 全模块编译通过（允许零业务类）。
- AC-6: 每个子模块包根为 `com.nanobot.<module>`，且生成占位 `package-info.java` 或空入口类以验证包结构。

## 边界情况（≥3）

- 当某子模块未在根 POM `<modules>` 注册时，构建应直接失败而非静默跳过（通过 reactor 校验）。
- 当子模块 POM 声明了违反依赖方向的依赖（如 agent 依赖 server）时，编译期即应暴露依赖错误（后续由 C-002 ArchUnit 机械化拦截，本次至少不在 POM 中写出逆向依赖）。
- 当本机 Maven 版本低于 3.9 时，应通过 Maven Wrapper 锁定版本，保证构建可复现。
- 当 JDK 版本 ≠ 21 时，`maven-compiler-plugin` 的 `release=21` 应使构建明确失败并给出可读提示。
- 当 AgentScope `2.0.0-RC1` 在 Maven 中央仓不可用或坐标/groupId 不符时，应停下请示人类，不擅自改用其它版本或 SNAPSHOT。（已实测：中央仓 2.x 仅有 `2.0.0-RC1`，无 `2.0.0` GA，经人类确认锁定 RC1。）

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 全量 `clean compile` 在 CI 冷构建 < 3min |
| 可靠性 | 通过 Maven Wrapper 锁定 Maven 版本，构建可复现；禁止 SNAPSHOT 依赖 |
| 安全 | POM 中不出现任何明文密钥；版本集中管理避免依赖混入 |
| 可观测 | 构建日志清晰列出各 reactor 模块编译顺序与结果 |

## 设计约束

- 必须遵循 `.harness/rules/工程结构.md` 的目录结构、模块职责与依赖方向（单向不可逆）。
- 必须遵循 ADR-002（Maven 多模块，9 子模块）与技术栈铁律（JDK 21 / Spring Boot 4.0.x / AgentScope **2.0.0-RC1** / Maven 3.9+）。注：经核验，AgentScope 2.0.0-RC1 的 starter 按 Spring Boot 4.0.x 编译，故集成基线由原 3.5.x 上调至 4.0.x（人类已确认）。
- **决策记录（版本实测澄清）**: 文档原记 `2.0.0+ GA`，但实测 Maven 中央仓 `io.agentscope` 全部 artifact 的 `maven-metadata.xml`，**2.x 仅有 `2.0.0-RC1`，无 `2.0.0` GA**（最后 GA 为 `1.0.12`）。经人类确认锁定 `2.0.0-RC1`，并采用 `agentscope-bom`（import scope）统一管理；已回写 `架构决策.md` ADR-001 与各技术栈表。RC 为正式 release，不违反禁 SNAPSHOT 铁律。
- 不允许引入未经评审的第三方依赖；版本全部在根 POM `<dependencyManagement>` 统一锁定。
- 必须保证 `huazai-trip-common` 不依赖任何业务模块，`huazai-trip-skills` 不依赖任何 agent。

## 契约影响

- REST: 无
- A2A: 无
- 数据模型: 无（仅建立 `common` 模块占位包，字段定义归 C-004）
- Redis / ReMe: 无

## 影响面

- 模块 / Agent / Skill: 新建全部 9 个 Maven 模块的骨架
- 外部 API: 无
- wiki: 对齐 `工程结构.md`、`架构决策.md`（ADR-002）；**已回写 ADR-001 版本为 `2.0.0-RC1`（实测中央仓无 2.0.0 GA），并同步 CLAUDE.md / owner.md 技术栈表 + 采用 agentscope-bom 管理**

## 规则归属

- 业务不变量归属: 不适用（本次无业务逻辑）
- 外部调用治理归属: 不适用（本次无外部调用）
- 可观测性要求: 构建期日志；运行期可观测字段在后续变更引入

## 测试策略

- 先写失败测试: 无业务逻辑，TDD 弱化适用（脚手架类）；以「构建必须通过」为验收信号。
- Happy Path: `./mvnw clean compile` 全模块成功。
- 边界测试: 故意制造未注册模块 / 逆向依赖时构建失败（手工验证一次并记录，不长期保留破坏性测试）。
- 降级测试: 不适用。
- 回归测试: 后续每个变更的 CI 均隐含回归本骨架的可编译性。

## 验收用例

- Case-1: 干净 checkout → `./mvnw clean compile` → 9 模块全部 BUILD SUCCESS。
- Case-2: 删除根 POM 中某模块注册 → 构建报错指出缺失模块 → 恢复后通过。
- Case-3: 在 agent 模块 POM 写入对 server 的依赖 → 出现非法依赖 → 移除后通过（验证依赖方向意识）。

### 验收用例执行记录（③ 阶段，2026-06-07）

> 按已批准测试策略：边界=一次性手工破坏验证并记录、**不长期保留破坏性测试**；降级=不适用；回归=CI 隐含。每项验证后均已还原，末尾以全量 `clean compile` BUILD SUCCESS 收尾。

- **Case-1 ✅ Happy Path**：`./mvnw clean compile` → 9 子模块 + 聚合 POM 共 10 个 reactor 条目全部 `SUCCESS`，`BUILD SUCCESS`（Total ~14s）。
- **Case-2 ✅ 边界·未注册模块**：临时从根 POM `<modules>` 移除 `huazai-trip-common` → `BUILD FAILURE`：
  `Could not resolve dependencies for project ...huazai-trip-skills ... dependency: com.nanobot:huazai-trip-common:jar:0.1.0 ... was not found`。证明未注册模块不会被静默跳过，而是令下游解析失败。已还原。
- **Case-3 ✅ 边界·逆向依赖**：临时在 `huazai-trip-agent-budget` POM 写入对 `huazai-trip-server` 的依赖（server 本依赖该 agent）→ `BUILD FAILURE`：
  `The projects in the reactor contain a cyclic reference: ...agent-budget --> ...server --> ...agent-budget`（ProjectCycleException）。证明逆向依赖在构建期（先于 C-002 的 ArchUnit）即被暴露。已还原。
- **Case-4 ✅ 边界·JDK≠21 fail-fast**：临时将 enforcer `requireJavaVersion` 改为 `[22,23)` → `BUILD FAILURE`：
  `RequireJavaVersion failed ... Detected JDK version 21 (JAVA_HOME=...jdk-21) is not in the allowed range [22,23).`（同时日志显示 `RequireMavenVersion passed`、`RequireReleaseDeps passed`，确认三条守卫均生效）。已还原为 `[21,22)`。
- **Case-5 ✅ 边界·禁 SNAPSHOT**：临时在 `huazai-trip-common` 加入 `commons-lang3:3.18.0-SNAPSHOT` → `BUILD FAILURE`：
  `Could not find artifact org.apache.commons:commons-lang3:jar:3.18.0-SNAPSHOT`。说明未配置任何 SNAPSHOT 仓库使 SNAPSHOT 无法被消费（叠加 `requireReleaseDeps` 守卫，对可解析的 SNAPSHOT 会显式拦截）。已还原。
- **降级测试**：N/A —— 本变更为纯构建骨架，无运行期外部依赖（LLM / 地图 / Redis / Nacos）可降级；最接近的"失败路径"即上述 fail-fast 工具链守卫（Case-4/5），已实测。
- **回归测试**：依 CI 隐含 —— 后续每个变更的 `clean compile/verify` 均回归本骨架可编译性；不在 C-001 单独新增保留测试（结构/架构约束机械化校验归 C-002）。
- **末尾还原校验 ✅**：所有临时改动还原后再次 `./mvnw clean compile` → 10 reactor 条目全 `SUCCESS`，`BUILD SUCCESS`。

## 任务拆解（≤1 天/项，DAG 无环）

- [x] T-1: 编写根 `pom.xml`（packaging=pom、properties 版本、dependencyManagement 中 import `agentscope-bom:2.0.0-RC1` + `spring-boot-dependencies:4.0.4`、9 modules、compiler release=21、Enforcer 禁 SNAPSHOT + requireJavaVersion[21,22) + requireMavenVersion[3.9,)）· P0 · 依赖 无 · 模块 根
- [x] T-2: 生成 9 个子模块目录与各自 `pom.xml`，声明合规依赖方向（common ← skills ← agents ← server ← tests，agent 间无互相依赖）· P0 · 依赖 T-1 · 模块 全部
- [x] T-3: 添加 `.java-version=21` / `.editorconfig`(UTF-8+LF+缩进) / Maven Wrapper（script-only，锁定 Maven 3.9.8，无 jar 入库）· P0 · 依赖 T-1 · 模块 根
- [x] T-4: 各模块建立包根 `com.nanobot.<module>`（agent 模块为 `..agent.<name>` 对齐 ArchUnit `..agent..`）与占位 `package-info.java` · P1 · 依赖 T-2 · 模块 全部
- [x] T-5: 全量 `./mvnw clean compile` 验证通过（10 reactor 条目全 BUILD SUCCESS）· P0 · 依赖 T-2,T-3,T-4 · 模块 根
- [x] T-6: 回写 `架构决策.md` ADR-001 版本为 `2.0.0-RC1`（实测中央仓无 2.0.0 GA）+ 同步 CLAUDE.md / owner.md 技术栈表 + 改为 agentscope-bom 管理 · P0 · 依赖 无 · 模块 wiki（已完成）

## 流水线进度

- [x] ① 需求分析（analyzing）
- [x] ② 编码实现（coding）— `./mvnw clean compile` 全 9 模块 + 聚合 POM 共 10 个 reactor 条目 BUILD SUCCESS；Enforcer（禁 SNAPSHOT + JDK 21 + Maven 3.9）逐模块通过；`agentscope-core:2.0.0-RC1` 与 `spring-boot:4.0.4` 经各自 BOM import 解析为真实 jar
- [x] ③ 单测编写（testing）— 按已批准测试策略以脚手架方式落地：边界（未注册模块 / 逆向依赖 / JDK≠21 / 禁 SNAPSHOT）均一次性手工破坏验证并记录于「验收用例执行记录」，验证后还原；降级 N/A；回归依 CI 隐含。覆盖率门禁对无业务逻辑骨架不适用（无核心逻辑可覆盖），以「构建必须通过」为验收信号。
- [x] ④ 专家评审（reviewing）— 0 个 🔴 严重问题，4 项 🟡 建议（不阻塞），9 维度全过 → 详见 [review.md](review.md)；`reviewing → ci`
- [x] ⑤ CI 门禁（ci）— **按人类裁决规格裁剪收口**：地基脚手架的可用子集全绿（编译 + enforcer 三规则 + 0 测试 + 密钥扫描 0 命中）；完整机械化门禁（Checkstyle/PMD/ArchUnit/JaCoCo/dependency-check）**正式转交 C-002** 作为其门禁项。详见下「⑤ CI 门禁报告」与「收口结论」。
- [x] ⑥ 部署验证（verifying）— **N/A，正式转交 C-003**：C-001 无可部署产物（属地基脚手架）；部署冒烟/健康检查/回滚验证由具备运行环境后的 C-003 承接。详见 [verify.md](verify.md)。
- [x] 交付（done）— 经人类裁决（2026-06-07）对纯地基脚手架裁剪 ⑤/⑥，以「编译 + enforcer + 评审」为收口信号；wiki（ADR-001/002）已由 T-6 同步；完整 CI/部署验证接手项已登记至 C-002/C-003。

---

## ⑤ CI 门禁报告（2026-06-07）

> 真实执行 `./mvnw clean verify`（含 enforcer + 编译 + 测试 + 打包），并对 quality-gate 工具是否存在做了核验。

| stage | 检查项 | 结果 | 详情 |
|-------|--------|------|------|
| 1 | 编译 `clean compile/verify` | 🟢 | 10 reactor 条目全 `SUCCESS`，0 error，jar 全部产出 |
| 1 | Checkstyle | ⬜ 缺工具 | 插件未引入（C-002 非目标），无法执行 |
| 1 | PMD | ⬜ 缺工具 | 同上 |
| 2 | ArchUnit 架构约束 | ⬜ 缺工具 | `huazai-trip-tests` 无 `ArchitectureConstraints`，ArchUnit 依赖未引入（C-002）。**注：已用一次性手工反例（Case-3 逆向依赖→reactor 环报错）证明依赖方向，但非机械化门禁** |
| 3 | 单元测试 | 🟢(空) | `mvn verify` 0 测试运行、0 failed（本骨架无业务逻辑，无测试） |
| 3 | JaCoCo 覆盖率 | ⬜ N/A | 插件未引入（C-002）；且无核心业务逻辑可度量 |
| 4 | enforcer（禁 SNAPSHOT + JDK21 + Maven3.9） | 🟢 | 逐模块 `RequireJavaVersion/RequireMavenVersion/RequireReleaseDeps passed` |
| 4 | dependency-check 漏洞扫描 | ⬜ 缺工具 | 插件未引入（C-002 非目标） |
| 4 | 密钥扫描 | 🟢 | 全量 grep（pom/java/properties/wrapper）0 硬编码密钥；Wrapper 凭据走环境变量 |
| 5 | 集成测试 `*IT` | ⬜ N/A | 无 IT（属 C-012） |

**结论**: ❌ **⑤ 未整体判绿** —— 可执行子集全绿，但 Checkstyle/PMD/ArchUnit/JaCoCo/dependency-check 五项门禁工具尚未存在于构建中。这些工具是 **C-002（质量门禁与架构约束）** 的交付物，而 ROADMAP DAG 中 `C-002 依赖 C-001`，存在先后次序约束。按 `unit-test-ci` 红线（禁止跳过 stage / 禁止绕过 ArchUnit），**不得伪绿放行**。状态停在 `ci`，卡点 = 被 C-002 阻塞。

## ⑥ 部署验证（无法实跑，记录阻塞）

`deploy-verify` 要求：`docker compose up -d`（Nacos 3.2.x + Redis 7.x）→ `java -jar server` → `/actuator/health=UP` → 5 Agent 注册 Nacos → 关键链路冒烟（简单规划 / A2A / HITL / 降级）。当前 C-001 骨架：

- `huazai-trip-server` 无 `@SpringBootApplication` 入口、无 actuator、无 web 端点 → 无可启动产物。
- 无 `docker/` compose、无 Nacos/Redis → 无运行环境。
- 五 Agent / A2A / HITL / 降级链路均未实现。

以上分别属 **C-003（运行环境与基础设施）** 与 **C-005~C-010（A2A 基座 + 五 Agent + Server REST）**。**⑥ 被 C-003 阻塞**，无法对 C-001 单独实跑，亦无部署物可回滚（用户数据不落盘，无回滚残留风险）。

## 收口结论与卡点

- **历史卡点（已解除）**: 曾卡在 **⑤ CI 门禁**，根因 = 机械化质量门禁工具（Checkstyle/PMD/**ArchUnit**/JaCoCo/dependency-check）属 **C-002** 尚未落地；其后 **⑥** 又被 **C-003** 阻塞。这是"首个地基变更"在六门禁流水线中的固有先后依赖，非实现缺陷。
- **人类裁决（2026-06-07）**: 采用「**规格裁剪收口**」——对"纯地基脚手架"裁剪 ⑤/⑥（依据 `开发流程规范.md §5` 特殊场景思路），把"完整机械化 CI + 部署验证"正式转交 C-002/C-003 作为其门禁项；C-001 以"编译 + enforcer + 评审"为收口信号置 `done`。
- **终态**: `status = done`。
- **接手项登记（防遗漏）**:
  - → **C-002**: 引入 Checkstyle/PMD/**ArchUnit(R1-R6)**/JaCoCo/dependency-check 后，必须对 **C-001 既有骨架**回归一次完整 ⑤（含依赖方向机械化校验，替代 C-001 的一次性手工 Case-3）。
  - → **C-003**: 具备 Docker(Nacos/Redis) + server 可启动产物后，对主链路补 ⑥ 部署冒烟/健康检查/回滚（C-001 的 ⑥ 在此承接）。
- **遗留 🟡（来自 review.md，非阻塞）**: AC-6 措辞与 agent 包根命名歧义、两处外部依赖用途记录、tests 模块 main 占位迁移——建议在 C-002/C-004 顺带处理。
