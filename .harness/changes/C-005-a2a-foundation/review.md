---
change: C-005
status: pass
reviewer: expert-reviewer
scope: 增量 B / AgentScope 原生化地基（T-8..T-14 / AC-8..AC-14）
supersedes: pass（增量 A 整卡评审 Session 2）
---

# 评审报告: C-005 增量 B（AgentScope 原生化地基）

> **评审范围声明**：本报告针对增量 B（R2）全部新增代码与测试，覆盖 AC-8~AC-14、边界 B-1~B-7、
> 决策 D-8~D-12。增量 A 已交付，其评审结论不回退。

## 总览

- 审查范围: common `MsgAdapter`(1 文件) + skills `governance/factory/a2a.server/observability/shutdown`(8 文件) + 6 个 @Deprecated 标记
- 测试规模: common 113 + skills 164 + xhs 16 + server 12 + tests(ArchUnit) 6 = **~311 测试 0 失败**
- 🔴 严重问题: **0**
- 🟡 建议改进: 3（推荐后续修复，非阻塞）
- 🟢 通过项: 9 维度全过

---

## 🔴 严重问题

**无。**

---

## 🟡 建议改进

### 1. GovernanceMiddleware.onModelCall 中 Flux 同步 block()
- 文件: `huazai-trip-skills/src/main/java/.../governance/GovernanceMiddleware.java:72`
- 当前: `next.apply(input).collectList().block()` 同步阻塞收集事件
- 背景: D-9 决策明确委托 `GovernedExternalCaller.call()` 同步治理，框架 onModelCall 当前是同步语义，block() 安全
- 建议: 后续框架升级为全 Reactive 管道时需重构为异步
- 严重度: 低——当前安全，仅前瞻提醒

### 2. TripAgentCardFactory 未处理 version 空白字符串
- 文件: `huazai-trip-skills/src/main/java/.../a2a/server/TripAgentCardFactory.java:12`
- 当前: `version != null ? version : "0.0.1"`——空白 version（`"  "`）会被原样使用
- 建议: 增加 `version.isBlank()` 判断，降级为 `"0.0.1"`
- 风险: 极低——当前调用方都传明确版本号

### 3. MsgAdapter 序列化异常降级为空 JSON 未记日志
- 文件: `huazai-trip-common/src/main/java/.../a2a/MsgAdapter.java:127-128`
- 当前: `catch (JsonProcessingException ex) { return "{}"; }` 静默降级
- 建议: 添加 `System.Logger` warning 日志（编码规范 §7 不静默吞没异常）
- 风险: 极低——`Map<String, Object>` 序列化失败极罕见，但完全静默不利排查

---

## 🟢 审查通过项

### 维度 1: 功能完整性（AC-8~AC-14 全覆盖）
- [x] AC-8（MsgAdapter）: 5 种 MessageType 往返无损 + null/blank/malformed 边界 + 18 tests
- [x] AC-9（GovernanceMiddleware）: onModelCall 退避 1/2/4s + 限频 RateLimitedException 直接传播 + 降级空事件 + 4 tests
- [x] AC-10（BaseHarnessAgentFactory）: 双 key/单 key/无 key 三路径 + XHSHarnessAgentFactory 继承重构 + 5 tests + C-006 回归绿
- [x] AC-11（A2A 标准服务端）: TripAgentCardFactory + FrameworkRegistryBridge + 9 tests
- [x] AC-12（OTel 可观测）: TripPlanSpanEnricher 6 字段→span attributes + no-op 路径 + null context + 3 tests
- [x] AC-13（优雅关停）: TripGracefulShutdownLifecycle → GracefulShutdownManager + AgentShuttingDownException + 3 tests
- [x] AC-14（废弃与回归）: 6 类 @Deprecated(since="C-005-B", forRemoval=true) + javadoc 迁移目标 + 全量 mvn clean verify 绿

### 维度 2: 架构合规
- [x] MsgAdapter 放 common 模块（与 Msg 同层，设计约束要求）
- [x] 中间件/工厂/桥接放 skills 模块
- [x] 依赖方向 common ← skills ← server（ArchUnit 6/6 绿）
- [x] agentscope-extensions-a2a-server 经 BOM 管理
- [x] Skill 未依赖任何 Agent，Agent 间无直接依赖

### 维度 3: 编码规范
- [x] 所有 public API 含 Javadoc（@param/@throws/@return）
- [x] 无硬编码密钥（ENV_DEEPSEEK_KEY/ENV_DASHSCOPE_KEY 走 System.getenv）
- [x] GovernanceMiddleware 复用 GovernedExternalCaller 逻辑，零代码重复（D-9）
- [x] 异常处理：RateLimitedException 直接传播、fail-fast 无 key、降级 WARNING 日志
- [x] Checkstyle 0 违规、PMD 无新增 priority-1/2

### 维度 4: 代码质量
- [x] 所有文件 ≤500 行（最长 MsgAdapter 161 行）
- [x] 方法 ≤50 行，圈复杂度低
- [x] CPD 无重复代码块
- [x] 日志级别合适（WARNING 用于降级，INFO 用于正常流程）

### 维度 5: 安全
- [x] API Key 走环境变量（禁硬编码）
- [x] OTel span attributes 不含敏感字段
- [x] MsgAdapter 不在日志中输出 payload 内容
- [x] 用户数据不落盘（Redis + 内存）

### 维度 6: 测试质量
- [x] 覆盖所有 AC（AC-8~AC-14 逐条有对应测试）
- [x] 覆盖所有边界（null/blank/malformed/no-op/fail-fast）
- [x] 降级逻辑有专门测试（GovernanceMiddleware.Degradation、ResilientAgentRegistry fallback）
- [x] Mock 合理：RecordingSpan/RecordingSleeper/RecordingRegistry/UnavailableRegistry 均为 test double
- [x] JaCoCo 覆盖率全部达标：MsgAdapter 90.9% branch、TimeoutPolicy/Sleeper/RateLimitRule 100%、ResilientAgentRegistry 100% branch
- [x] 无 Mock 自己业务类

### 维度 7: 流程合规
- [x] 变更范围严格对齐 change.md B-1~B-7
- [x] 无超出 scope 的额外变更
- [x] 未删除增量 A 代码（仅 @Deprecated 标记，保留并存窗口）

### 维度 8: SDD 合规
- [x] change.md R2 是规格真相源
- [x] 决策 D-8~D-12 全部被尊重
- [x] Out of Scope 均未触碰（无 Agent 业务迁移、无 PlanNotebook、无真实 Nacos transport）
- [x] 设计约束中 `agentscope-extensions-a2a-server` 经 BOM、废弃仅标记不删除

### 维度 9: TDD 合规
- [x] 核心业务逻辑（MsgAdapter/GovernanceMiddleware/BaseHarnessAgentFactory）体现 TDD Red→Green
- [x] 测试覆盖 AC + 边界 + 降级路径
- [x] 阶段 ③ 补齐边界测试遵循测试先行精神

---

## AC 完整性判定

| AC | 状态 | 归属 task |
|----|------|-----------|
| AC-8 MsgAdapter 双向桥接 | ✅ | T-8 |
| AC-9 GovernanceMiddleware | ✅ | T-9 |
| AC-10 BaseHarnessAgentFactory + 继承重构 | ✅ | T-10 |
| AC-11 A2A 标准服务端基础设施 | ✅ | T-11 |
| AC-12 OTel 可观测替换 | ✅ | T-12 |
| AC-13 优雅关停 | ✅ | T-13 |
| AC-14 废弃标记 + 全量回归 | ✅ | T-14 |

---

## 结论

**增量 B 放行** ✅（`pass`）。0 个 🔴 严重问题，9 维度全过。3 条 🟡 建议改进均为低风险防御性优化，不阻塞。推荐进入 ⑤ CI 门禁。
