---
change: C-003
status: partial-blocked-on-env
verifier: deploy-verify
date: 2026-06-07
---

# 🟡 部署验证报告: C-003 运行环境与基础设施搭建

## 结论先行

**⑥ 部分通过、部分受阻于环境。** 应用侧（test profile 上下文冒烟 + `/actuator/health=UP` + 健康判定/降级/env fail-fast 单测）**已验证通过**；**Docker 实例级**验证（compose 起 Nacos/Redis、控制台/PING、停 Redis→DOWN、回滚）因**本机无 Docker（`docker: command not found`）**无法执行，按用户确认**登记为环境阻塞**，待具备 Docker 的环境补做。故 C-003 暂停在 `verifying`，不流转 `done`。

## 已通过项（本机可验证）

| 项 | 对应 AC | 证据 |
|----|---------|------|
| 应用以 test profile 空跑启动、上下文加载 | AC-2/AC-6 | `ServerApplicationContextTest` PASS |
| `GET /actuator/health` 返回 200 + `status=UP` | AC-2 | `HealthEndpointTest`（MockMvc）PASS |
| Nacos 不可达 → 组件 DOWN 且标 `nacosServerAddr`+`reason` | AC-5 | `NacosHealthIndicatorTest` PASS |
| 缺 `DASHSCOPE_API_KEY`/`BAIDU_MAP_AK` → fail-fast 点名变量 | AC-3/AC-4 | `EnvironmentValidatorTest` PASS |
| CI 不依赖外部容器 | AC-6 | `./mvnw clean verify` 全绿（test profile 替身化） |
| 缓存键格式/TTL/前缀 | 数据模型 §4 | `CacheKeysTest` PASS |

## 环境阻塞项（待 Docker 环境补做）

| 项 | 对应 AC / Case | 待执行命令 / 期望 |
|----|----------------|-------------------|
| compose 一键起 Nacos+Redis 且健康 | AC-1 / Case-1 | `docker compose -f docker/docker-compose.yml up -d`；Nacos 控制台 `http://localhost:8848/nacos` 可访问；`redis-cli -a $REDIS_PASSWORD ping`=PONG（<60s） |
| local profile 连真实实例 health 含 redis/nacos 组件 | AC-5 / Case-2 | `SPRING_PROFILES_ACTIVE=local` 启动 server → `/actuator/health` 含 `redis`、`nacos` 组件且 UP |
| 停 Redis → health DOWN 标组件 | AC-5 / Case-3 | `docker compose stop redis` → `/actuator/health` = DOWN 且 redis 组件失败 |
| 回滚预案 | NFR | `docker compose down`；server 仅读 Redis/Nacos、用户数据不落盘 → 无数据迁移/回滚残留 |

> 健康判定的**核心逻辑**（DOWN 标组件、异常降级不裸崩）已被单测覆盖，实例级仅验证真实连通性聚合行为。

## 回滚

- 本变更新增：构建依赖（actuator/data-redis/micrometer）、启动类/配置/健康检查、docker compose、env 样例；无数据库迁移、用户数据不落盘 → 回退对应 commit（含 `C-003`）即可，无残留风险。

## 结论

🟡 应用侧 ⑥ 通过；Docker 实例级冒烟/回滚**环境阻塞**已登记。状态保持 `verifying`，待 Docker 环境补做后回写本文件并流转 `done`。
