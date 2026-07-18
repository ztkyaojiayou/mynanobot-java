# ✅ 部署验证报告: C-008 (Itinerary Agent — 增量 A + 增量 B 全量)

## 环境
- profile: dev（当前无 Docker，构建验证通过）
- JDK: 21 LTS
- 构建产物: `huazai-trip-skills-0.1.0.jar` / `huazai-trip-agent-itinerary-0.1.0.jar`

## 验证矩阵

| 阶段 | 结果 | 详情 |
|------|------|------|
| 编译 | 🟢 | `mvn clean compile` 0 error（10 模块） |
| Checkstyle | 🟢 | 0 violation（10 模块） |
| PMD | 🟢 | BUILD SUCCESS |
| 单元测试（skills-itinerary 含 ToolsTest） | 🟢 | 84 tests, 0 failures |
| 单元测试（agent-itinerary） | 🟢 | 14 tests, 0 failures |
| 单元测试（agent-route 重构回归） | 🟢 | 114 tests, 0 failures |
| 单元测试（全量） | 🟢 | 566 tests, 0 failures, 1 skip（预存 HarnessAgentSpikeTest） |
| JaCoCo 覆盖率（skills itinerary） | 🟢 | ≥80%（阈值 80%，ItineraryHarnessAgentFactory JaCoCo 排除） |
| JaCoCo 覆盖率（agent-itinerary） | 🟢 | ≥80%（阈值 80%） |
| 密钥扫描 | 🟢 | 0 命中（新增文件: ItineraryDesignTools/ItineraryHarnessAgentFactory 均 0 key 引用） |
| 包产物 | 🟢 | `huazai-trip-skills-0.1.0.jar` + `huazai-trip-agent-itinerary-0.1.0.jar` 生成成功 |

## 增量 B 新增文件验证

| 文件 | 用途 | 验证 |
|------|------|------|
| `ItineraryDesignTools.java` | `@Tool design_itinerary` 门面 | 🟢 12 tests 全绿 |
| `ItineraryDesignToolsTest.java` | 确定性单测 | 🟢 构造/lastResult/HappyPath/降级/空/风险/连续/结构 + 4 边界用例 |
| `ItineraryHarnessAgentFactory.java` | 真实 LLM 工厂 | 🟢 编译通过，JaCoCo 排除 |
| `SKILL.md` (itinerary-design) | AgentScope 原生技能 | 🟢 frontmatter name=itinerary_design |

## 验收用例全量覆盖

| Case | 场景 | 测试方法 | 结果 |
|------|------|---------|------|
| Case-1 | 5天15景点+14路线→5 TripDay | `HappyPath.fiveDays_sufficientCandidates` | 🟢 |
| Case-2 | 候选仅够3天→DAYS_MISMATCH | `DaysConsistency.insufficientCandidates_marksDaysMismatch` | 🟢 |
| Case-3 | 交通130min→拆分 | `TransitLimit.overLimitTransit_splitToDifferentDays` | 🟢 |
| Case-4 | FAMILY→≤3/day | `TravelStyleStrategy.family_maxThreePerDay` | 🟢 |
| Case-5 | 附近无餐→放宽+标记 | `DegradationChain.bothEmpty_fallbackMeals` | 🟢 |
| Case-6 | 评分2.8→剔除 | `RatingFilter.lowRating_removedAndFlagged` | 🟢 |
| Case-7 | taskType不匹配→TASK_ERROR | `ErrorHandling.wrongTaskType` | 🟢 |
| Case-8 | routes空→fallback | `EmptyRoutes.nullRouteResult_fallbackTrue` | 🟢 |
| Case-9 | Amap正常→真实餐厅 | `AmapUrlAndSource.amapSuccess_mealFieldsComplete` | 🟢 |
| Case-10 | AK未配置→KB降级 | `ErrorHandling.blankAk_throws` + `DegradationChain.amapEmpty_kbUsed` | 🟢 |
| Case-11 | 高德超时+KB缺城市→FALLBACK | `DegradationChain.bothEmpty_fallbackMeals` + `KbDegradation.unknownCity_empty` | 🟢 |
| Case-12 | LLM正常调用→产物==确定性 | `NativeTest.nativePathSuccess` + `ToolsTest.toolOutputMatchesDeterministicService` | 🟢 |
| Case-13 | LLM异常→降级确定性 | `NativeTest.nativePathFailureFallsBackToDeterministic` | 🟢 |
| Case-14 | LLM未调用工具→降级 | `NativeTest.nativePathNullFallsBack` | 🟢 |
| Case-15 | null runner→纯确定性 | `NativeTest.nullRunnerDeterministicPath` | 🟢 |
| Case-16 | 双key缺失→null factory | `ItineraryHarnessAgentFactory.fromEnvironment` 返回 null（LlmModelFactory.hasAny=false） | 🟢 |

## 可观测性核验

- `agent.call.latency` 指标：`ItineraryAgentTest.recordsLatency()` 🟢
- `agent.call.error` 指标：`ItineraryAgent.resolve()` 异常路径 `incrementCallError(AGENT_URI)` 🟢
- `traceId` 透传：`ItineraryAgentTest.returnsItinerary()` 🟢
- `fallback` 语义：餐饮降级 + 原生路降级 + 叠加标记全覆盖 🟢
- `telemetry.mealFallbackCount`：`ItineraryMealDegradationTest` 多重验证 🟢
- `telemetry.lowRatingRemoved`：`ItineraryDesignBoundaryTest.RatingBoundary` 🟢

## RouteAgent 重构回归核验（同步变更）

- `RouteAgentTest`: 10 tests 🟢
- `RouteAgentNativeTest`: 4 tests 🟢
- `RouteAgent full module verify`: 114 tests 🟢

## 冒烟测试（Docker 环境运行时 — 当前不可用）

| 链路 | 验证点 | 状态 |
|------|--------|------|
| Agent 注册 | ItineraryAgent 在 AgentRegistry 健康列表 | ⏳ 需 Docker |
| A2A 通信 | Supervisor → ItineraryAgent TASK_ASSIGN → TASK_RESULT | ⏳ 需 Docker |
| 降级链 | 模拟高德 API 故障→KB→FALLBACK 生效 | ⏳ 需 Docker |
| 健康检查 | `/actuator/health` = UP | ⏳ 需 Docker |
| 真实 LLM 调用 | HarnessAgent + `@Tool design_itinerary` 端到端 | ⏳ 需 env key |

## 回滚预案

- 上一稳定版本: `728de02` (feat:fix merge)
- 回滚命令: `git checkout 728de02 -- huazai-trip-agent-itinerary/ huazai-trip-skills/src/main/java/com/huazai/trip/skills/itinerary/ huazai-trip-skills/src/test/java/com/huazai/trip/skills/itinerary/ huazai-trip-skills/src/main/resources/skills/itinerary-design/`
- 触发条件: 运行时高德 API 全故障且 KB 缺城市导致无餐 / ItineraryAgent A2A 通信异常 / 编排算法产生空 TripDay / HarnessAgent 工厂异常
- 数据安全: 用户数据不落盘（全进程内内存），回滚无数据残留

## 结论

✅ **验证通过，增量 B 变更可交付。** 全量 566 tests 绿色、0 密钥命中、静态分析 0 violation、jar 打包成功。Docker 环境不可用的冒烟测试需在部署时补充执行。ItineraryHarnessAgentFactory 的 env-gated 集成测试待部署环境有 LLM key 时执行。
