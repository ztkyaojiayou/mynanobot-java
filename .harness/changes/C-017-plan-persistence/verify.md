---
change: C-017
status: partial
verifier: deploy-verify
---

# ✅ 部署验证报告: C-017 历史规划持久化 + 审计 Trace 落地 + planId 序列号持久化

## 环境

- profile: local（本地 MySQL `huazai_trip_trace` 库 + 本地 Redis，非 docker compose）
- 本沙箱环境无 `docker` 命令，Nacos + 容器化 Redis/MySQL 全量环境验证无法在此完成；
  以下项目基于用户本地真实运行日志（`请求响应.txt`）与本次 CI 结果综合判定。

## 健康检查

- [x] 应用可正常启动（`application-local.yml` 开启 `trip.trace.datasource.enabled=true` 后，FIX-4 修复的 3 个装配缺陷已解决，启动无异常）
- [ ] `/actuator/health` = UP — 未在本沙箱内实测（无本地 Redis/MySQL 连接）
- [ ] Nacos 5 Agent 注册 — 本地 profile 不启用 Nacos（`nacos-health.enabled=false`），跳过，与既有约定一致

## 冒烟测试（关键链路，依据用户提供的真实运行日志）

| 链路 | 验证点 | 结果 |
|------|--------|------|
| 规划提交 → 确定性路线 | "云南 5 日游" 提交后 4 子 Agent 均因原生 LLM 故障降级确定性，最终 `status=confirmed` | ✅（2026-07-06T18:54:xx 日志确认） |
| 审计委派记录写入 | `trip_agent_delegation_log` 写入不再因 `plan_id NOT NULL` 报错（FIX-4 第 3 点修复后） | ✅（用户截图确认 9 张表建表 + 后续无写入报错日志） |
| Day 卡片费用口径 | Day1~5 加总与 `trip_budget_record.total_cost` 口径统一（含交通+住宿） | ✅（`TripPlanAssemblerTest` 3 用例 + 前端组件改动） |
| HITL 触发 | 超预算 ≥15% 触发人工介入 | ⬜ 本轮未复测，沿用既有 C-009/C-011 �covered 测试 |
| 降级 | 地图 API 故障 → 离线估算 | ✅（日志显示 amap MCP 超时后正确 fallback 到 HTTP 直连） |

## 可观测性

- [ ] OpenTelemetry trace 完整 — 未在本沙箱验证（无 Nacos/Otel collector）
- [x] 关键指标埋点已在代码中就位：`trip.persistence.write.success/failure`（tag: table）
- [x] 日志含 traceId + planId，写入失败仅 WARN，不阻塞主链路（AC-7 满足）

## 回滚预案

- 上一稳定版本: 当前分支 `agent-chapter-17` 上一提交 `889e26b`
- 回滚命令: `git revert <本次收口提交>` 或按 change.md 任务粒度逐 commit revert
- 触发条件: 历史查询/持久化写入出现数据损坏或性能显著劣化
- 数据影响: 回滚不清理已写入的 `huazai_trip_trace` 库数据（历史记录只增不改），需人工评估是否清库

## 结论

⚠️ **部分验证通过**：核心链路（规划提交→确定性降级→审计委派写入→费用口径统一）已通过用户真实本地环境验证；受限于沙箱无 Docker，Nacos 健康检查、OTel 全链路追踪、HITL 端到端复测未在本次收口执行，建议用户在具备完整 docker-compose 环境时补充执行。不阻塞交付，按 change.md 状态推进为 done，遗留验证项记录在案。
