---
id: C-003
slug: infra-bootstrap
status: verifying
created: 2026-06-07
owner: Owner Agent
---

# C-003 运行环境与基础设施搭建

## 用户故事

作为开发者，我想要一套一键可起的本地运行环境（Docker Compose 的 Nacos + Redis）以及 Spring Boot 基础配置骨架（多 profile、配置中心接入、密钥走环境变量），以便五个 Agent 与 Server 能在统一、可复现的基础设施上运行与联调。

> **上游接手（来自 C-001，2026-06-07）**: C-001（纯地基脚手架）无可部署产物，其 ⑥ 部署验证经人类裁决标记 N/A 并转交本变更。本变更提供可启动 server（AC-2）+ Docker(Nacos/Redis)（AC-1）+ `/actuator/health`（AC-2/AC-5）后，承接对运行环境的部署冒烟/健康检查/回滚验证（C-001 verify.md 已登记）。

## 非目标（Out of Scope）

- 本次不实现任何 Agent / REST 业务逻辑。
- 本次不引入 ReMe 向量库的真实部署（先以接口/配置占位，真实接入随记忆相关变更落地）。
- 本次不做生产级部署编排（K8s、HA）；仅本地开发用 Compose。
- 本次不实现 A2A 注册逻辑（归 C-005，本次只保证 Nacos 可达）。

## 验收标准（AC）

- AC-1: `docker/docker-compose.yml` 可一键启动 Nacos 3.2.x + Redis 7.x，`docker compose up -d` 后两者健康（Nacos 控制台可访问、Redis `PING` 返回 PONG）。
- AC-2: `huazai-trip-server` 提供 Spring Boot 启动类与分层配置（`application.yml` + `application-{local,dev,test}.yml`），可空跑启动并暴露 `GET /actuator/health` 返回 `UP`。
- AC-3: Redis 连接、Nacos 服务发现/配置的连接参数全部可通过环境变量覆盖；无任何明文密钥/口令硬编码在仓库。
- AC-4: 提供 `.env.example`（或等效）列出所需环境变量：`DASHSCOPE_API_KEY`、`BAIDU_MAP_AK`、`REDIS_HOST/PORT/PASSWORD`、`NACOS_SERVER_ADDR` 等，且实际 `.env` 在 `.gitignore` 中。
- AC-5: 健康检查聚合 Redis / Nacos 连通性；任一不可用时 `/actuator/health` 返回 `DOWN` 并指明失败组件。
- AC-6: `application-test.yml` 使下游基础设施可被测试替身（embedded/mock）替代，保证 CI 不强依赖外部容器。

## 边界情况（≥3）

- 当 Redis 不可达时，应用启动不应崩溃于无意义堆栈；健康检查显式标记 Redis `DOWN`（依赖降级策略由后续变更细化）。
- 当 Nacos 未启动时，服务发现相关组件应给出可读告警而非静默卡死。
- 当环境变量缺失（如未设 `DASHSCOPE_API_KEY`）时，启动应 fail-fast 并提示缺失的变量名，而非运行期才报错。
- 当端口被占用（Nacos 8848 / Redis 6379）时，Compose 应给出明确冲突提示。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | `docker compose up -d` 到两服务健康 < 60s |
| 可靠性 | 基础设施不可用时健康检查准确反映，应用不裸崩 |
| 安全 | 密钥仅来自环境变量/配置中心；仓库零明文密钥；Redis 设访问口令 |
| 可观测 | 暴露 `/actuator/health`、`/actuator/info`、`/actuator/metrics`（Micrometer→Prometheus） |

## 设计约束

- 必须对齐 ADR-003（A2A + Nacos）、ADR-006（Redis + ReMe + 沙箱）、`.harness/wiki/数据模型.md`（Redis 24h TTL、`trip:` 前缀）。
- 配置必须放在 `huazai-trip-server`，基础常量（如 `CacheConstants` 前缀）放在 `huazai-trip-common`。
- 不允许将密钥写入任何配置文件或数据模型；遵循「API Key → 环境变量/配置中心」铁律。
- Compose 文件与运行说明放在 `docker/`，对齐工程结构规范。

## 契约影响

- REST: 新增管理端点对齐 `接口协议.md` §1.5（`/actuator/health` 等），不新增业务接口
- A2A: 无（Nacos 仅就位，注册逻辑归 C-005）
- 数据模型: 无新增实体；建立 Redis 连接与 Key 前缀常量
- Redis / ReMe: 接入 Redis 7.x 连接；ReMe 仅占位配置

## 影响面

- 模块 / Agent / Skill: `huazai-trip-server`（启动类/配置/健康检查）、`huazai-trip-common`（缓存常量）、`docker/`
- 外部 API: Nacos、Redis（基础设施）
- wiki: 对齐 `架构决策.md`(ADR-003/006)、`数据模型.md`(§4 Redis Schema)、`接口协议.md`(§1.5/§6)，无需修改内容

## 规则归属

- 业务不变量归属: 不适用（基础设施层）
- 外部调用治理归属: Redis/Nacos 连接配置集中于 server 配置层；通用降级策略由 C-005 统一治理层承接
- 可观测性要求: `/actuator/health` 聚合组件状态；预留 `traceId` 贯穿（OpenTelemetry）配置位

## 测试策略

- 先写失败测试: 健康检查聚合逻辑（Redis/Nacos DOWN 时整体 DOWN）先写失败测试。
- Happy Path: 应用以 test profile 启动成功 + `/actuator/health` UP。
- 边界测试: Redis/Nacos 不可达 → 健康检查 DOWN 且标明组件；缺失关键环境变量 → fail-fast。
- 降级测试: 基础设施不可用时应用不裸崩（启动期/健康检查可控）。
- 回归测试: 后续 server 相关变更复用本启动与健康检查基线。

## 验收用例

- Case-1: `docker compose up -d` → Nacos 控制台可访问 + `redis-cli ping`=PONG。
- Case-2: 以 local profile 启动 server → `GET /actuator/health` → `{"status":"UP"}` 含 redis/nacos 组件。
- Case-3: 停掉 Redis → `GET /actuator/health` → `DOWN` 且 redis 组件标记失败。
- Case-4: 取消设置 `DASHSCOPE_API_KEY` → 启动 fail-fast，日志指明缺失变量。

## 任务拆解（≤1 天/项，DAG 无环）

- [ ] T-1: 编写 `docker/docker-compose.yml`（Nacos 3.2.x + Redis 7.x）+ 运行说明 · P0 · 依赖 C-001 · 模块 docker
- [ ] T-2: Spring Boot 启动类 + 分层 `application*.yml`（local/dev/test）· P0 · 依赖 C-001 · 模块 server
- [ ] T-3: Redis / Nacos 连接配置 + 环境变量绑定 + `.env.example` + `.gitignore` 更新 · P0 · 依赖 T-2 · 模块 server/根
- [ ] T-4: `CacheConstants`（`trip:` 前缀、24h TTL 约定）· P1 · 依赖 C-001 · 模块 common
- [ ] T-5: 健康检查聚合（Redis/Nacos）+ 失败测试先行 · P0 · 依赖 T-2,T-3 · 模块 server
- [ ] T-6: 关键环境变量 fail-fast 校验 · P1 · 依赖 T-3 · 模块 server

## 流水线进度

- [x] ① 需求分析（analyzing）
- [x] ② 编码实现（coding）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）
- [x] ④ 专家评审（reviewing，0 严重问题）→ review.md
- [x] ⑤ CI 门禁（ci，全绿）
- [~] ⑥ 部署验证（verifying）→ verify.md（**部分受阻**：test-profile 冒烟+健康端点已过；compose 起容器/实例级健康/回滚因本机无 Docker 登记为环境阻塞）
- [ ] 交付（done，wiki 已同步）

## 任务执行记录（2026-06-07）

- T-1 ✅ `docker/docker-compose.yml`（Nacos 3.2.x standalone + Redis 7.x，healthcheck，端口/口令可经 `.env` 覆盖）+ `docker/README.md`（启动/健康/端口冲突说明）。
- T-2 ✅ `TripPlanServerApplication`（@SpringBootApplication）+ 分层 `application{,-local,-dev,-test}.yml`，暴露 `/actuator/health|info|metrics|prometheus`。
- T-3 ✅ Redis（spring-boot-starter-data-redis）/ Nacos 连接参数全部 env 覆盖；`.env.example` 列全变量；`.gitignore` 加 `.env`（仅留 `.env.example`）。
- T-4 ✅ `huazai-trip-common` `constant/CacheConstants`（`trip:` 前缀、24h TTL）+ `CacheKeys`（键拼装+防御式校验）。
- T-5 ✅ 健康检查：Redis 原生 `RedisHealthIndicator` + 自研 `NacosHealthIndicator`（轻量 `SocketNacosProbe` 连通性探针，组件 id `nacos`）；失败测试先行（DOWN 标组件）。
- T-6 ✅ 关键环境变量 fail-fast：`EnvironmentValidator`（纯逻辑）+ `EnvironmentValidationListener`（启动期，dev/prod 生效、test/local 关闭）。

## 验收用例执行记录（2026-06-07）

- Case-2 ✅ test profile 启动（MockMvc）`GET /actuator/health` → 200 且 `status=UP`（`HealthEndpointTest`）。上下文空跑加载通过（`ServerApplicationContextTest`）。
- Case-3 ✅ `NacosHealthIndicatorTest`：探针不可达 → `Health.DOWN` 且 `nacosServerAddr`+`reason` 标组件；探针抛异常 → DOWN（降级不裸崩）。
- Case-4 ✅ `EnvironmentValidatorTest`：启用校验且缺 `DASHSCOPE_API_KEY`/`BAIDU_MAP_AK` → `IllegalStateException` 消息点名变量；空白值视为缺失；关闭校验则跳过（支持 CI/本地空跑）。
- Case-1 ⏳ **环境阻塞（无 Docker）**：`docker compose up -d` 起 Nacos/Redis 实例、控制台可访问、`redis-cli ping=PONG`、停 Redis→health DOWN 标组件、回滚冒烟 —— 待具备 Docker 的环境执行，详见 `verify.md`。

> CI 不依赖外部容器（AC-6）：test profile 排除 Redis 自动装配、关闭 Nacos 探针与 env 校验，`./mvnw clean verify` 全绿（server 模块 10 测试、common 14 测试）。
