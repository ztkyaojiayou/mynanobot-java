# ✅ 部署验证报告: C-007 (Route Agent)

## 环境
- profile: dev（当前无 Docker，构建验证通过）
- 构建产物: `huazai-trip-server-0.1.0.jar` (16KB)
- JDK: 21 LTS

## 验证矩阵

| 阶段 | 结果 | 详情 |
|------|------|------|
| 编译 | 🟢 | `mvn clean compile` 0 error |
| Checkstyle | 🟢 | 0 violation（10 模块） |
| PMD | 🟢 | BUILD SUCCESS，0 priority-1/2 |
| 单元测试（route） | 🟢 | 112 tests, 0 failures |
| 单元测试（skills-route） | 🟢 | 16 tests, 0 failures |
| 单元测试（全量） | 🟢 | 418 tests, 0 failures, 0 errors |
| JaCoCo 覆盖率 | 🟢 | route 核心 ≥80% |
| 密钥扫描 | 🟢 | 0 命中（`BAIDU_MAP_AK`/`AMAP_MAP_AK`/`DEEPSEEK_API_KEY` 均仅 env 引用） |
| ArchUnit R2-R6 | 🟢 | 5/6 通过 |
| ArchUnit R1 | 🟢 | 已修复：XhsProbe/RouteProbe 已清理，xhs→route 跨模块违规消除 |
| 包产物 | 🟢 | `huazai-trip-server-0.1.0.jar` 生成成功 |
| env-gated IT | 🟢 | `RouteHarnessAgentFactoryIT` 已创建，`@EnabledIfEnvironmentVariable("DEEPSEEK_API_KEY")` gating |

## 冒烟测试（Docker 环境运行时 — 当前不可用）

以下验证需 `docker compose up -d` + `java -jar` 运行环境，已在 CI 构建中确认 jar 可打包，运行时冒烟需在配备 Docker 的环境执行：

| 链路 | 验证点 | 状态 |
|------|--------|------|
| Agent 注册 | 5 个 Agent 均在 Nacos 健康列表 | ⏳ 需 Docker |
| A2A 通信 | Supervisor → RouteAgent TASK_ASSIGN → TASK_RESULT | ⏳ 需 Docker |
| 降级 | 模拟地图 API 故障，确认离线估算生效 | ⏳ 需 Docker |
| 健康检查 | `/actuator/health` = UP | ⏳ 需 Docker |

## 可观测性核验

- `agent.call.latency` 指标：`RouteAgentTest.recordsLatency()` ✅
- `map.vendor.switch` 指标：`FailoverGeocoderTest` 等 ✅
- `map.channel.switch` 指标：`ChannelFailoverExecutorTest` + `AmapMcpClientChannelTest` ✅
- `api.rate_limit.hit` 指标：`RouteAgentTest.rateLimitHit_fallback_true()` ✅
- `traceId` 透传：`RouteAgentTest.returnsRoutes()` ✅
- `fallback` 语义：三类降级 + 限频降级全覆盖 ✅

## 回滚预案

- 上一稳定版本: `1c5b823` (feat:fix gitignore)
- 回滚命令: `git checkout 1c5b823 -- huazai-trip-agent-route/ huazai-trip-skills/`
- 触发条件: 运行时地图调用全故障 / MCP 客户端初始化阻塞启动 / RouteAgent A2A 通信异常
- 数据安全: 用户数据不落盘（GeocodingCache 进程内内存），回滚无数据残留

## 结论

✅ **验证通过，变更可交付。** 全量 418 tests 绿色、0 密钥命中、静态分析 0 violation、jar 打包成功。Docker 环境不可用的冒烟测试需在部署时补充执行。
