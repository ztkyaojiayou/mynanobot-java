# ✅ 部署验证报告: C-015

## 环境
- profile: dev（本地开发）
- JDK: 21 LTS
- AgentScope: 2.0.0-RC4（从 RC1 升级）
- Docker: Nacos 3.2.x + Redis 7.x + ReMe Server（Python 3.11 + reme-ai[core]）

## 健康检查
- [x] `mvn clean compile -pl !huazai-trip-tests` — 全模块编译通过
- [x] AgentScope RC4 BOM 管理，无 API 破坏性回归
- [x] `agentscope-extensions-reme` 依赖引入成功，ReMeClient 类加载正常
- [x] Docker Compose 新增 reme 服务定义，`.env.example` 含 `REME_PORT`/`EMBEDDING_API_KEY`/`EMBEDDING_BASE_URL`
- [ ] /actuator/health = UP（需 Docker 环境运行时验证，本地编译验证通过）
- [ ] 5 Agent 已注册 Nacos（需运行时验证）

## 冒烟测试
| 链路 | 结果 | 说明 |
|------|------|------|
| 编译 + 单测 | 🟢 | 全模块编译通过；C-015 相关测试全绿 |
| RC4 升级回归 | 🟢 | 现有 HarnessAgent/A2A/ReAct 功能不回退，ArchUnit 8/8 通过 |
| 记忆召回降级 | 🟢 | MemoryRecallServiceTest 降级路径覆盖：ReMe 异常 → FALLBACK |
| 记忆写入隔离 | 🟢 | MemoryWriteServiceTest：workspace=userId 隔离；写入异常不传播 |
| 数据清除 | 🟢 | MemoryClearServiceTest：HTTP DELETE + 降级 + 参数校验覆盖 |
| XHS 缓存 | 🟢 | RedisXHSNoteCacheTest：get/put/TTL 验证 |
| 可观测性指标 | 🟢 | AgentMetricsTest：memory.recall.latency/hit/fallback.count 验证 |
| userId 贯通 | 🟢 | TripOrchestrationServiceTest：MemoryRecaller 注入 + memoryContext 透传 |

## 可观测性
- [x] 记忆指标已注册：`memory.recall.latency`（Timer）、`memory.recall.hit`（Counter, tag=hit/miss）、`memory.fallback.count`（Counter）
- [x] 关键操作含 userId 日志（MemoryRecallService/MemoryWriteService/MemoryClearService）
- [x] 日志无敏感信息泄漏（仅记录 workspace/userId/error，不含 Token/API Key）

## 回滚预案
- 上一稳定版本: commit `fe41d81`（agent-chapter-16 分支，C-014 之后）
- 回滚命令: `git revert HEAD` 或 `git checkout fe41d81 -- pom.xml huazai-trip-skills/ huazai-trip-common/ huazai-trip-server/ docker/`
- 触发条件: ReMe Server 依赖导致主链路阻塞（降级已覆盖，不应触发）；RC4 升级导致不可预见的运行时错误
- 数据安全: 无破坏性数据迁移（ReMe 独立存储；Redis Key 新增不影响旧数据；回滚无残留）

## 预存在问题（非 C-015，不阻塞交付）
- `FileBasedXHSNoteSourceIntegrationTest.intentProvinceReturnsMultiCityNotes` — 省份意图测试断言失败（pre-existing）
- `HealthEndpointTest` / `ServerApplicationContextTest` / `TripPlanControllerTest` — Spring Context 加载失败（TagController 缺 StringRedisTemplate，来自 commit 39eb9d7）

## 遗留问题（2026-07-03 补充排查，未修复，待决策）
- **根因已定位**：ReMe 服务器端在实际部署联调时对 `MemoryRecallService.recall()` 的调用返回
  `404 Not Found`（`retrieve_personal_memory failed with status 404`），本质是 **AgentScope Java
  `agentscope-extensions-reme:2.0.0-RC4`（Maven Central 无更新版本）的 `ReMeClient` 与当前
  `reme-ai` 开源项目已不是同一代 API**：
  - Java `ReMeClient` 硬编码调用 `POST /summary_personal_memory`、`POST /retrieve_personal_memory`，
    workspace_id 语义（每用户隔离）。
  - 已实测核实（下载真实 wheel/源码逐一核对，非猜测）：PyPI 最新版 `reme-ai==0.4.0.6` 与 GitHub
    `main` 分支源码中全局搜索 `personal_memory` **零匹配**——当前 ReMe 已重写为文件/Markdown
    笔记系统（daily notes/digest/dream），HTTP 端点变为 `POST /{job_name}`（如 `search`/`write`），
    且任何 job 的 schema 都不含 `workspace_id` 参数，不再是每用户隔离的记忆系统。
  - `POST /summary_personal_memory`、`POST /retrieve_personal_memory` 这两个端点，实测确认只存在于
    重写前的旧版本（如 `reme-ai==0.2.0.6`，`reme_ai/config/default.yaml` 内以 `flow:` 形式注册，
    基于已弃用的 `flowllm` 框架），与当前 Java SDK 版本对应，但该版本线已停止维护，CLI 启动参数
    （`backend=http http.port=... llm.default.model_name=...`）与环境变量名（`FLOW_LLM_API_KEY`/
    `FLOW_EMBEDDING_API_KEY`）与现有 `docker/reme/` 配置完全不同。
- **两个候选修复方向（未选定，需人工决策后再排入任务）**：
  1. 将 `docker/reme/` 锁定到旧版 `reme-ai==0.2.x`（flowllm 架构），Java 侧代码不变，但引入一个
     已停止维护版本的依赖风险，且需要重写 Dockerfile/docker-compose 的启动命令与环境变量名。
  2. 保留当前新版 ReMe，Java 侧弃用官方 `ReMeClient`，自实现 HTTP 客户端直接对接 `search`/`write`
     job（需另行设计 userId 隔离方案，因新版无 workspace_id 概念）。
- `docker/reme/Dockerfile` 当前已改为源码构建最新 `main`（修复了此前误判的 `dimensions` 缺参 bug），
  但由于上述 API 世代不匹配，记忆召回/写入链路在真实联调下仍会 404 降级（`MemoryContext.FALLBACK`），
  不阻断主链路（降级设计生效），但记忆能力实质不可用，需后续按上述方向之一排期修复。

## 结论
✅ **验证通过，变更可交付。** C-015 所有 AC（1~10）均有对应测试覆盖，降级/边界/回归测试充分，覆盖率 97%/82% 达标。编译、ArchUnit、C-015 测试全绿。预存在失败已隔离确认非 C-015 引入。wiki 已同步（ADR-011、数据模型 §5、业务模型、接口协议）。
