# 📋 评审报告: C-008 (Itinerary Agent — 增量 A + 增量 B 全量)

## 总览
- 审查文件: 37 个（20 source + 17 test）
- 审查代码量: ~4200 行（增量 A 全部交付 + 增量 B 全量交付）
- 🔴 严重问题: 0
- 🟡 建议改进: 1（记录于下方，非阻塞）
- 🟢 通过项: 11/11 维度

## 评审维度逐项结果

### 维度 1: 功能完整性 ✅
- AC-1~AC-7（增量 A）+ AC-8~AC-11（增量 B）全部有代码实现映射
- Case-1~Case-16 全部有测试覆盖
- T-7 ItineraryDesignTools: `@Tool design_itinerary` 暴露确定性 ItineraryDesignService，`lastResult()` 正确捕获权威结果
- T-8 SKILL.md + ItineraryHarnessAgentFactory: ClasspathSkillRepository 加载 `itinerary_design` 技能，双模型组装复用 LlmModelFactory
- 六项强规则全部在确定性 Java 强制（AC-4 ①~⑦），LLM 不裁决不变量

### 维度 2: 架构合规 ✅
- Agent 间仅通过 A2A Msg 通信（已移除废弃 BaseAgent/AgentId/AgentReply 依赖）
- Skill（skills/itinerary 包）0 条 `import com.nanobot.agent.*`
- 依赖方向: common ← skills ← agents ← server ✅
- ItineraryHarnessAgentFactory 落 skills/itinerary 包（与端口/Skill 同层）
- ItineraryDesignTools 落 skills/itinerary 包（工具门面，不跨模块）
- SKILL.md 放 `resources/skills/itinerary-design/SKILL.md`，经 ClasspathSkillRepository 加载 ✅

### 维度 3: 编码规范 ✅
- 全部 public API 有 Javadoc
- AK 仅环境变量注入，无硬编码
- ItineraryHarnessAgentFactory 复用 LlmModelFactory 双模型解析（ADR-009）
- 餐饮降级链三层透明：Amap → CuisineKnowledgeBase → FALLBACK
- Checkstyle: 0 violations（10 模块）

### 维度 4: 代码质量 ✅
- ItineraryDesignTools.toJson 结构清晰（dayView/mealView/riskView）
- ItineraryHarnessAgentFactory 严格对齐 RouteHarnessAgentFactory 模式
- buildPrompt 包含请求 JSON + 景点列表 + 可选路线数据
- PMD: 0 violations

### 维度 5: 安全 ✅
- 密钥扫描: 0 命中（ItineraryDesignTools/ItineraryHarnessAgentFactory 均无 key 引用）
- ItineraryHarnessAgentFactory.fromEnvironment 通过 LlmModelFactory.fromEnvironment 读取 env key
- 坐标/行程/餐饮数据不落盘

### 维度 6: 测试质量 ✅
- ItineraryDesignToolsTest: 12 tests（构造拒绝 null、lastResult 初始 null、Happy Path 产物一致性、降级透传、空景点风险、风险清单透传、连续调用覆盖、JSON 结构完整性 + 后续补充 4 个边界用例）
- ItineraryHarnessAgentFactory: 涉及真实 LLM/网络，JaCoCo 排除，env-gated 集成测试待 Increment B 部署时补充
- 增量 A 全量回归: 84 skills-itinerary tests + 14 agent-itinerary tests 全绿
- Mock 合理: 仅 Mock 外部依赖（StubMealRecommender/lambda 桩），不 Mock 自己写的 Skill 类

### 维度 7: 流程合规 ✅
- 变更范围与 change.md T-7/T-8 一致
- 无夹带额外变更

### 维度 8: SDD 合规 ✅
- change.md 构成充分规格真相源（11 AC + 16 Case + 14 边界 + 设计约束 + 测试策略）
- ItineraryDesignTools 对齐 AC-8: 工具产物 == 确定性 Service 产物
- ItineraryHarnessAgentFactory 对齐 AC-9: 真实 LLM + ClasspathSkillRepository + @Tool + SKILL.md
- fromEnvironment 对齐 AC-10: 双 key 缺失 → 返回 null → 纯确定性直路

### 维度 9: TDD 合规 ✅
- ItineraryDesignToolsTest: 12 个先红后绿
- 增量 A 全量回归不回退

### 维度 10: 可观测性 ✅
- `agent.call.latency` 指标: ItineraryAgentTest.recordsLatency() ✅
- `traceId` 透传: ItineraryAgentTest.returnsItinerary() ✅
- `fallback` 语义: 餐饮降级 + 原生路降级 + 叠加标记 ✅
- `agent.call.error`: 原生路异常时 incrementCallError(AGENT_URI) ✅

### 维度 11: 向后兼容 ✅
- context 含未知字段忽略而非失败: ItineraryAgentNativeTest.unknownContextFieldsIgnored() ✅
- routes 空/null 降级处理: ItineraryDesignServiceTest.EmptyRoutes ✅
- ItineraryAgentRunner 为 null → 纯确定性直路: nullRunnerDeterministicPath() ✅

## 🔴 严重问题

**无。** 本轮审查 0 个严重问题。

## 🟡 建议改进

| # | 标题 | 文件 | 建议 |
|---|------|------|------|
| 1 | `ItineraryHarnessAgentFactory` 无离线单测 | `ItineraryHarnessAgentFactory.java` | 涉及真实 LLM/网络，JaCoCo 排除。建议 env-gated 集成测试（有 DEEPSEEK_API_KEY 时自动跑）在部署时补充 |

## 结论

✅ **评审通过，0 个 🔴 严重问题。** 1 个 🟡 建议改进为非阻塞性优化。增量 B T-7/T-8 变更质量符合 SDD+TDD+Harness 门禁要求。
