---
change: C-NNN
status: draft
verifier: deploy-verify
---

# ✅ 部署验证报告: C-NNN <变更标题>

## 环境

- profile: dev / 镜像: <tag>
- 基础设施: Nacos 3.2.x + Redis 7.x（docker compose up -d）

## 健康检查

- [ ] `/actuator/health` = UP
- [ ] `/actuator/metrics` 可读
- [ ] 5 个 Agent 已注册到 Nacos

## 冒烟测试（关键链路）

| 链路 | 验证点 | 结果 |
|------|--------|------|
| Agent 注册 | 5 Agent 在健康列表 | ⬜ |
| 简单规划 | "北京 1 日游" 端到端返回 | ⬜ |
| A2A 通信 | Supervisor 委派并收到结果 | ⬜ |
| HITL 触发 | 超预算 ≥15% 触发人工介入 | ⬜ |
| 降级 | 地图 API 故障 → 离线估算 | ⬜ |

## 可观测性

- [ ] OpenTelemetry trace 完整
- [ ] 关键指标上报（agent.call.count/duration、human.intervention.count）
- [ ] 日志为结构化 JSON，无敏感信息

## 回滚预案

- 上一稳定版本: <tag>
- 回滚命令: <cmd>
- 触发条件: <如 健康检查持续失败 / 关键链路冒烟失败>
- 数据影响: 用户数据不落盘，回滚无残留

## 结论

<✅ 验证通过，变更可交付（status=done） / ❌ 失败，退回 …>
