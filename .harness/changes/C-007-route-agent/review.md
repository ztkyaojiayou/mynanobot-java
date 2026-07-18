# 📋 评审报告: C-007 (Route Agent)

## 总览
- 审查文件: 35 个（20 source + 15 test）
- 审查代码量: ~4000 行（含 3 个增量全部交付）
- 🔴 严重问题: 0（已全部修复）
- 🟡 建议改进: 5（记录于下方，非阻塞）
- 🟢 通过项: 11/11 维度

## 评审维度逐项结果

### 维度 1: 功能完整性 ✅
- AC-1~AC-14 全部有代码实现映射
- Case-1~Case-15 全部有测试覆盖
- 边界 ≥3 处理到位：空景点、坐标缺失、限频、坐标非法、MCP双通道均故障
- 增量 A（桩版本）、增量 B（真实双厂商+通道切换）、增量 C（AgentScope原生化）全部交付

### 维度 2: 架构合规 ✅
- Agent 间仅通过 A2A Msg 通信（RouteAgent 不继承 BaseAgent：增量 D 已内联 `receive(Msg)`，同 XHSAnalysisAgent 增量 D 模式）
- Skill（route 包）0 条 `import com.nanobot.agent.*`
- 依赖方向: common ← skills ← agents ← server ✅
- ArchUnit R2/R3/R4/R5/R6 全绿
- ✅ R1 全绿：`XhsProbe`/`RouteProbe` 已清理，`huazai-trip-agent-xhs` → `huazai-trip-agent-route` 跨模块违规已消除

### 维度 3: 编码规范 ✅
- 全部 public API 有 Javadoc
- AK 仅环境变量注入（`BAIDU_MAP_AK`/`AMAP_MAP_AK`/`DEEPSEEK_API_KEY`），无硬编码
- LLM/外部调用有超时+重试+限频+降级（GovernedExternalCaller 统一治理链）
- 异常处理完整（RateLimitedException catch 已补、ResolvedLocation fallback 透传）

### 维度 4: 代码质量 ✅
- 最大方法 ~45 行、最大文件 ~235 行（AmapMcpClient）、圈复杂度最高 ~5
- 无未使用 import
- ChannelFailoverExecutor / FailoverExecutor 存在 ~25 行执行模式重复（已记录于建议改进）
- 日志级别合理（INFO 关键流程、WARNING 降级/切换、ERROR 无 AK）

### 维度 5: 安全 ✅
- 外部输入校验: `RouteAgent.parseQuery` 校验 destination、attractions 类型；`RoutePlanQuery` 构造函数防御
- 日志无密钥: MapVendorFactory 仅打印环境变量名，MapHttpClient 不 log URL
- 坐标数据不落盘: GeocodingCache 进程内 ConcurrentHashMap

### 维度 6: 测试质量 ✅
- 覆盖所有 AC 与边界（112 route tests + 16 skills-route tests）
- 降级测试全覆盖: 地理编码→预置坐标、路线→haversine、路况静默忽略、限频→离线估算、厂商切换、通道切换
- 核心覆盖率 ≥80%（JaCoCo pass）
- Mock 合理: 仅 Mock 外部依赖（McpClientWrapper 匿名子类、端口 lambda 桩），不 Mock 自己写的类

### 维度 7: 流程合规 ✅
- 变更范围与 change.md 一致（增量 A/B/C 三层范围）
- 无夹带额外变更
- 删除文件 McpTransport.java、McpSseTransport.java 已清理干净

### 维度 8: SDD 合规 ✅
- change.md 构成充分规格真相源（14 AC + 15 Case + 边界 + 设计约束 + 测试策略）
- 实现严格对齐 change.md
- Out of Scope 项未被实现（Redis/Nacos/实时导航/机票）

### 维度 9: TDD 合规 ✅
- RoutePlanningToolsTest: 验证 @Tool 产物 == 确定性 Service 产物（先红后绿）
- RouteAgentNativeTest: 覆盖原生路成功/异常降级/null降级/null runner
- 限频边界测试: 显式构造耗尽限频器→验证行为

## 🔴 已修复的严重问题

### 1. RouteAgent.resolve() 吞没异常（已修复 ✅）
- 文件: `RouteAgent.java:106-107`
- 问题: `catch (Exception ex)` 仅调 `incrementCallError`，丢弃异常根因
- 修复: 添加 WARNING 级别日志 `LOG.log(WARNING, "RouteAgent 原生路失败...", ex.getMessage())`

### 2. RouteHarnessAgentFactoryIT 缺失（已修复 ✅）
- 文件: `RouteHarnessAgentFactoryIT.java`（新建）
- 问题: AC-12 要求 env-gated 集成测试但未实现
- 修复: 创建 `@EnabledIfEnvironmentVariable("DEEPSEEK_API_KEY")` 集成测试，验证真实 LLM + @Tool + SKILL.md 端到端

## 🟡 已修复的建议改进

### 3. MapVendorFactory buildSync() 无 try-catch（已修复 ✅）
- 修复: 包裹 try-catch，失败时记录 ERROR 日志并保持 mcpClient=null（HTTP-only 降级）

### 4. RoutePlanningService 限频 catch 无日志（已修复 ✅）
- 修复: 添加 WARNING 日志 "限频命中 API_MAP，降级 haversine 估算"

### 5. ChannelFailoverExecutor MCP 失败日志缺根因（已修复 ✅）
- 修复: 日志加入 `ex.getMessage()`

## 🟡 留待后续迭代的建议改进

| # | 标题 | 文件 | 建议 |
|---|------|------|------|
| 1 | CPD 重复: ChannelFailoverExecutor / FailoverExecutor | `ChannelFailoverExecutor.java:37-67` / `FailoverExecutor.java:36-69` | 提取泛型 FailoverEngine 消除 ~25 行重复 |
| 2 | RoutePlanningService.resolveByGeocodeOrPreset 吞异常 | `RoutePlanningService.java:144` | 添加 WARNING 日志记录地理编码失败根因 |
| 3 | @SuppressWarnings("PMD.GodClass") 缺注释 | `AmapMcpClient.java:28` | 添加注释说明压制原因 |
| 4 | ChannelFailoverExecutorTest.AssertionError 遮蔽 JDK 类型 | `ChannelFailoverExecutorTest.java:140` | 重命名为 ShouldNotBeCalledError |
| 5 | FailoverExecutor.logSwitch 不包含异常信息 | `FailoverExecutor.java:71-73` | 加入 ex.getMessage() |

## 结论

✅ **评审通过，0 个 🔴 严重问题。** 2 个严重问题与 3 个高优先级建议已在本轮修复。剩余 5 个 🟡 建议改进为非阻塞性优化，可在后续 sprint 收口。变更质量符合 SDD+TDD+Harness 门禁要求，可进入 CI 门禁与部署验证。
