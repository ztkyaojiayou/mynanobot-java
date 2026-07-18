# 变更清单路线图（Change Backlog Roadmap）

> 本文件是 `.harness/changes/` 的**清单索引与依赖地图**，供 Owner Agent 断点续传与人类审批使用。
> 单卡真相源仍是各自的 `change.md`；本文件只做导航、阶段分组与依赖编排，**不重复定义需求**。
> 目录使用规范见同目录 `README.md`；冲突处理优先级 `rules > wiki > changes > CLAUDE.md > 历史文档`。

---

## 0. 关键基线前提（本清单成立的技术决策）

| 决策 | 结论 | 依据 |
|------|------|------|
| AgentScope 版本 | **`2.0.0-RC1`**（经 `agentscope-bom` import 统一管理） | 中央仓 2.x 唯一可用版本，无 GA；RC 为正式 release，不违反禁 SNAPSHOT |
| Spring Boot | **`4.0.x`** | AgentScope 2.0.0-RC1 的 starter 按 SB 4.0 编译；3.5.x 运行期不被支持 |
| Nacos | server **`3.2.x`** | 对齐 AgentScope 传递引入的 `nacos-client 3.2.1` |
| 业务 Skills 技术栈 | **AgentScope 原生**（DashScope/MCP/RAG/ReMe），**不引入 Spring AI / Spring AI Alibaba** | AgentScope 原生已全覆盖；引入会与 SB4 硬冲突且功能重叠 |

> 如需正式化，建议回写 `wiki/架构决策.md` ADR-001/006。

---

## 1. 阶段分组与依赖

```
Phase 0  工程地基        C-001 ─┬─ C-002
                                ├─ C-003
                                └─ C-004
Phase 1  契约与协同基座   C-003(环境) · C-004(公共模型) ─▶ C-005(A2A 基座/统一治理/Gate)
Phase 2  五专业 Agent     C-006(XHS) · C-007(Route) ─▶ C-008(Itinerary) · C-009(Budget)
Phase 3  编排与对外       C-010(Supervisor) ─▶ C-011(Server REST)
Phase 4  端到端/体验      C-012(E2E) · C-013(前端) · C-014(PDF 导出)
Phase 5  增强与加固       C-015(ReMe 记忆/RAG) · C-016(可观测/可靠性)
```

依赖 DAG（change 级，箭头 = 被依赖方）：

```
C-001 ─▶ C-002, C-003, C-004
C-004 ─▶ C-005, C-006, C-007, C-008, C-009, C-011, C-014
C-003 ─▶ C-005, C-012, C-015
C-005 ─▶ C-006, C-007, C-008, C-009, C-010, C-011, C-015, C-016
C-007 ─▶ C-008
C-006, C-007, C-008, C-009 ─▶ C-010
C-010 ─▶ C-011, C-012, C-015
C-011 ─▶ C-012, C-013, C-014, C-016
C-002 ─▶ C-012
C-012 ─▶ C-016
```

无环；关键路径：`C-001 → C-004 → C-005 → C-006/07/08/09 → C-010 → C-011 → C-012 → C-016`。

---

## 2. 清单

| ID | 标题 | 目标（一句话） | 依赖 | 状态 |
|----|------|----------------|------|------|
| C-001 | 项目骨架搭建 | Maven 多模块骨架 + 工具链锁定（agentscope-bom 2.0.0-RC1 / SB4 / JDK21） | — | done |
| C-002 | 质量门禁与架构约束 | Checkstyle/PMD/ArchUnit/JaCoCo/Enforcer 机械化门禁 | C-001 | done |
| C-003 | 运行环境与基础设施 | Docker(Nacos 3.2.x + Redis 7.x) + Spring Boot 配置骨架 + 健康检查 | C-001 | verifying（⑥ Docker 实例级冒烟环境阻塞） |
| C-004 | 公共领域模型 | common 模块领域模型/DTO/枚举/异常/工具（字段级契约真相） | C-001 | done |
| C-005 | A2A 协同基座与统一治理 | 增量 A：Msg/BaseAgent/Nacos 注册/超时重试降级/OutputQualityGate/可观测；增量 B：AgentScope 原生化地基（MsgAdapter/GovernanceMiddleware/BaseHarnessAgentFactory/A2A 标准服务端/OTel/GracefulShutdown/自研栈废弃） | C-003,C-004 | 增量 A=done；增量 B=analyzing（待审批） |
| C-006 | 小红书分析 Agent | 增量 A：XHS 笔记检索分析 + 候选景点（评分≥3.5/情感）主链路；增量 B：合规真实来源（官方 API / ReMe 召回，排除模拟登录）+ `XHSNoteParser` 解析/情感 + `XHSNote` 情感粒度契约变更（逐提及）；增量 C：AgentScope 原生化 XHS 切片（HarnessAgent + 真实 qwen3-max + Skill，不变量留确定性 Java） | C-004,C-005 | done |
| C-007 | 路线规划 Agent | 双厂商（百度+高德）地图 MCP 真实接入 + 智能切换（健康度故障转移/熔断恢复） + 点间路线（耗时≤2h）+ 三类降级（离线估算）（2026-06-10 决议：原拟 C-018 撤销并入本卡） | C-004,C-005 | done |
| C-008 | 行程编排 Agent | 逐日 TripDay（时间窗/餐饮/动线/风格）+ 强规则 + 高德周边美食搜索 | C-004,C-005,C-007 | done |
| C-009 | 费用统筹 Agent | 四类拆分 + 预算红线 ≤×1.15 + 超支≥15% 触发 HITL（BigDecimal 临界）+ 缺失费用估算降级 | C-004,C-005 | done |
| C-010 | 主管 Agent（SupervisorAgent） | AgentScope v2 原生：HarnessAgent ReAct 循环 + agent_spawn 委派四个子 Agent + orchestrate_trip 确定性收口 + OutputQualityGate 复核 + Plan Mode/Task List 复杂需求分解 + MySQL 审计追踪三表 + SensitiveDataMasker 脱敏 + 有界线程池异步写入 | C-005,C-006,C-007,C-008,C-009 | **done** |
| C-011 | 对外 REST API 与门面 | 接口协议 §1 全部接口 + 异步 planId + HITL + 分层合规 + `NacosAgentRegistry` 真实适配（2026-06-10 决议归此卡，进入 ① 阶段时补入规格） | C-004,C-005,C-010 | analyzing |
| C-012 | 端到端主流程集成 | E2E（提交→五 Agent→方案→HITL）+ 不变量守护 + ArchUnit | C-002,C-003,C-010,C-011 | analyzing |
| C-013 | 前端主流程对接 | Vue 小红书风格：录入/进度轮询/行程展示/HITL/反馈 | C-011 | analyzing |
| C-014 | 行程 PDF 导出 | confirmed/review plan 导出 PDF（沙箱/水印/脱敏/中文字体） | C-004,C-011 | analyzing |
| C-015 | ReMe 长期记忆与 RAG | 三集合 embedding 写入/召回（当前请求优先 + memoryFallback 降级）；**仅 ReMe 向量库，不承接 Redis 适配** | C-003,C-005,C-006,C-008,C-010 | analyzing |
| C-016 | 可观测性与可靠性加固 | §6 全指标 + OTel traceId 全链路 + 告警 + 容量压测 + 回滚 | C-005,C-011,C-012 | analyzing |
| C-017 | 历史规划持久化 | MySQL `huazai_trip_trace` 复用 + 6 张业务表 + 异步写入 + 脱敏 + planId Redis 序列号 | C-010,C-011 | done |
| C-018 | Supervisor 计划模式 | 用户显式开启 AgentScope 官方 Plan Mode（非 ADR-005 旧三模式）+ 只读计划草案 + HITL 确认后放行 agent_spawn 委派，沿用 disableFilesystemTools() 防 C-012-fix 事故重演 | C-010,C-011 | analyzing |

> **C-005 双增量说明（2026-06-12 R2）**：C-005 分两个增量同卡交付。增量 A（自建 A2A 基座）已 `done`；增量 B（AgentScope 原生化地基）新增 **MsgAdapter 消息桥接**、**GovernanceMiddleware 治理中间件**、**BaseHarnessAgentFactory 通用工厂**、**AgentScopeA2aServer 标准服务端**、**OTel 可观测替换**、**GracefulShutdown 优雅关停**、**自研栈废弃标记**，为 C-007~C-010 各 Agent 原生化提供可复用基础设施。依据 ADR-008（独立地基卡收口）。增量 A 已交付不回退。
>
> **C-006 三增量说明（2026-06-11 R3）**：C-006 分三个增量同卡交付。增量 A（主链路桩版本）已 `done`；增量 B（真实来源与解析）新增合规真实来源适配（官方授权 API / ReMe 召回，排除模拟登录）、`XHSNoteParser` 解析/情感、`XHSNote` 情感粒度契约变更；增量 C（AgentScope 原生化 XHS 切片）已 `coding`（Gate 0 已过）。原拟 C-017 已作废，收编进 C-006 增量 B。

---

## 3. 状态机（摘要，详见 `rules/开发流程规范.md` 与 `README.md`）

```
draft → analyzing → coding → testing → reviewing → ci → verifying → done
                                              └─(打回)─┘
```

- 进度（2026-06-07）：C-001 = `done`；C-002 = `done`；C-003 = `verifying`（⑥ Docker 实例级冒烟环境阻塞）；其余 = `analyzing`（**待人类审批后**方可进入 ② coding）。
- 进度（2026-06-10 R2）：C-004 = `done`；C-005 增量 A = `done`；C-006 增量 A = `done`；C-006 增量 B/C = `done`。
- 进度（2026-06-12 R3）：**C-005 增量 B（AgentScope 原生化地基）规格已落档，状态 `analyzing`，人类已审批，待进入 ② coding**；C-007 = `done`。
- 进度（2026-06-16 R4）：**C-006~C-010 全部 `done`**：C-006（XHS 三增量）/ C-007（Route 双厂商+原生）/ C-008（Itinerary 编排+高德美食）/ C-009（Budget BigDecimal 核算）/ **C-010（Supervisor AgentScope v2 原生编排 + agent_spawn 委派 + orchestrate_trip 收口 + Plan Mode/Task List + MySQL 审计追踪三表 + SensitiveDataMasker 脱敏 + 覆盖率 ≥80% + ArchUnit R1-R6 全绿）**。Phase 0-3 地基+Agent+编排全部交付。C-011（Server REST）为下一优先级。
- 默认规则：没有人类审批，不得从 `analyzing` 进入 `coding`。

---

## 4. 待人类确认的开放项（不阻塞审批，但建议先定）

1. **C-005 的"原生 vs 重写"边界**：A2A `Msg`/注册发现究竟是直接复用 AgentScope `agentscope-extensions-nacos-a2a` 原生能力，还是自建 `BaseAgent`/`Msg` 薄封装？关系到"重新实现优于包装"与"AgentScope 原生 A2A"两条原则的取舍，建议在 C-005 进入 coding 前明确并回写 ADR-003。 ✅ **已定调（2026-06-11，ADR-008）**：走 AgentScope 原生（Agent 入口 `HarnessAgent`）；C-006 增量 C 在 XHS 切片先行落地，C-005 自研栈对其余 Agent 的全量原生化拆独立地基卡分阶段迁移。 ✅ **已落地（2026-06-12，C-005 增量 B）**：地基卡规格已完成（MsgAdapter/GovernanceMiddleware/BaseHarnessAgentFactory/A2A 标准服务端/OTel/GracefulShutdown/自研栈废弃），人类已审批。
2. **AgentScope-on-SB4 冒烟**：建议 C-001/C-003 完成后第一时间做最小冒烟（空 `@SpringBootApplication` + AgentScope 能正常自动装配启动），尽早暴露 SB4 兼容性风险。 ✅ **已验证（2026-06-11，C-006 增量 C Gate 0）**：`agentscope-harness:2.0.0-RC1` + `HarnessAgent` 在 JDK21 + Spring Boot 4.0 BOM 下编译/类加载/装配 0 冲突、SB4 上下文正常启动、enforcer 无 SNAPSHOT 通过（`HarnessAgentSpikeTest` + `ServerApplicationContextTest`）。
3. **基线决策是否正式化**：是否把第 0 节四项决策写入 `wiki/架构决策.md`（ADR-001/006 补充）。
4. ~~**Nacos 真实适配器归属**~~ **已拍板（2026-06-10）：归 C-011**。`NacosAgentRegistry`（C-005 D-7 顺延项）在 C-011 进入 ① 阶段时补入其规格；A2A 主链路当前经 `AgentRegistry` 端口 + `InMemoryAgentRegistry` in-process 路由成立，`A2aConfig` 已留 `@ConditionalOnMissingBean` 覆盖缝、适配器即插即用。同日决议：原拟 C-017 不建档（人类另行分支实现）、原拟 C-018 撤销并入 C-007。
   > **ID 重用说明（2026-07-10）**：C-017/C-018 编号此前被撤销未建档，现均已重新启用建档——
   > C-017 落地为「历史规划持久化」（2026-07-04 done），C-018 落地为「Supervisor 计划模式」
   > （2026-07-10 analyzing），与本条注记的旧提案无关。
