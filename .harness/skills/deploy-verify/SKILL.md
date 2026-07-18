---
name: deploy-verify
stage: ⑥ 部署验证
description: 部署后冒烟、健康检查、关键链路验证，确保变更在真实环境可用且可回滚
owner: Owner Agent
---

# 部署验证技能（deploy-verify）

> **流水线阶段**: ⑥ 第六步 · 收口
> **输入**: 通过 ⑤ CI 的构建
> **产出**: `.harness/changes/<id>/verify.md`
> **出口门禁**: 冒烟 + 健康检查通过，有回滚预案

---

## 1. 职责

你是上线把关人。确认变更在真实/类生产环境**真的能跑、关键链路真的通**，且出问题能快速回滚。"CI 绿"不等于"线上可用"。

---

## 2. 工作流程

### Step 1: 准备环境
```bash
docker compose up -d            # Nacos 3.2.x + Redis 7.x
mvn clean package -DskipTests   # 已在 CI 验过测试
java -jar huazai-trip-server/target/*.jar --spring.profiles.active=dev
```

### Step 2: 健康检查
```bash
curl localhost:8080/actuator/health        # 期望 UP
curl localhost:8080/actuator/metrics       # 指标可读
curl localhost:8080/api/v1/admin/agent/status   # 5 Agent 已注册到 Nacos
```

### Step 3: 冒烟测试（关键链路）
| 链路 | 验证点 |
|------|--------|
| Agent 注册 | 5 个 Agent 均在 Nacos 健康列表 |
| 简单规划 | "北京 1 日游" 端到端返回方案 |
| A2A 通信 | Supervisor 能委派并收到子 Agent 结果 |
| HITL 触发 | 构造超预算 ≥15% 场景，确认人工介入点触发 |
| 降级 | 模拟地图 API 故障，确认离线估算生效 |

### Step 4: 可观测性核验
- 链路追踪（OpenTelemetry）有完整 trace
- 关键指标上报（agent.call.count/duration、human.intervention.count）
- 日志为结构化 JSON，无敏感信息泄露

### Step 5: 回滚预案
- 记录上一个稳定版本 tag / 镜像
- 明确回滚命令与触发条件
- 确认无破坏性数据迁移（用户数据不落盘，回滚无残留）

---

## 3. 输出格式（写入 verify.md）

```markdown
# ✅ 部署验证报告: C-NNN

## 环境
- profile: dev / 镜像: …

## 健康检查
- [x] /actuator/health = UP
- [x] 5 Agent 已注册

## 冒烟测试
| 链路 | 结果 |
|------|------|
| 简单规划 | 🟢 |
| A2A 通信 | 🟢 |
| HITL 触发 | 🟢 |
| 降级 | 🟢 |

## 可观测性
- [x] trace 完整 / 指标上报 / 日志合规

## 回滚预案
- 上一稳定版本: <tag>
- 回滚命令: <cmd>
- 触发条件: <…>

## 结论
✅ 验证通过，变更可交付 / ❌ 失败，退回 …
```

---

## 4. 约束

- ❌ 禁止跳过冒烟直接判定通过
- ❌ 禁止在无回滚预案的情况下放行
- ✅ 关键链路必须实跑，不靠推断
- ✅ 验证生产配置/密钥来自环境变量或配置中心，非硬编码

---

## 5. 完成标志

验证通过 → 更新 `change.md` 状态 `verifying → done`。变更交付完成，同步相关 `.harness/wiki/` 文档。
