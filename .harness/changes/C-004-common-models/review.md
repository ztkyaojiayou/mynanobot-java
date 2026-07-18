# 📋 评审报告: C-004 公共领域模型与基础设施类

> 阶段 ④ expert-reviewer · 输入：`huazai-trip-common` 主代码 + 单元测试 · 评审日期 2026-06-07

## 总览

- 审查文件: 主代码 28 个（model ×8、dto ×1、enums ×7、exception ×5、util ×4、constant ×2、package-info ×N）+ 测试 9 个
- 🔴 严重问题: **0**（必须修复）
- 🟡 建议改进: 2（推荐，不阻断放行）
- 🟢 通过项: 9 维度全部通过

## 🔴 严重问题

无。

## 🟡 建议改进

### 1. `JsonUtils.toJson` 对 `null` 入参的异常信息会触发二次 NPE 风险（理论）
- 文件: `util/JsonUtils.java:47`
- 当前: catch 块用 `value.getClass().getName()` 拼接错误信息。`writeValueAsString(null)` 实际返回字符串 `"null"` 并不抛异常，故现状不会进入 catch、不会 NPE；但若未来某类型在序列化中抛 `JsonProcessingException` 且 `value` 为 null，则信息拼接会二次 NPE 掩盖根因。
- 建议: catch 内对 `value == null` 做判空，或用 `String.valueOf(value)`/`Objects.toString`。
- 理由: 防御式编程（编码规范 §5），属健壮性增强，非功能缺陷。当前无触发路径，故列为 🟡。

### 2. `fromJson` 与 `toJson` 异常类型不一致（设计取舍，建议补注释固化）
- 文件: `util/JsonUtils.java:43/60/77`
- 当前: `toJson` 抛 `IllegalStateException`（视为编程错误：产出对象本应可序列化）；`fromJson` 抛 `IllegalArgumentException`（视为外部坏输入）。语义自洽且合理。
- 建议: 在类级 Javadoc 补一句「序列化失败=编程错误→IllegalState；反序列化失败=坏输入→IllegalArgument」以固化约定，避免下游误判。
- 理由: 可维护性，非缺陷。

## 🟢 通过项

- [x] **维度 1 功能完整性**: AC-1..AC-6 全部落地。模型/枚举/DTO/异常/工具与 `change.md` 任务拆解 T-1..T-6 一一对应；边界（days 0/31、budget 0/null/负、preferences=null、金额精度、planId 序列进位）均有对应测试与实现。无遗漏、无多余功能。
- [x] **维度 2 架构合规**: common 处于最底层，仅依赖 JDK + Jackson + jakarta.validation **注解 API**（均经 BOM 管理、非业务模块），ArchUnit R4 依赖方向通过（6/6 @ArchTest 全绿）。无 Agent/Skill/Server 反向依赖。决策 D-1..D-6 已沉淀于 `change.md`（record 承载、显式校验等，属实现决策非新 ADR，合规）。
- [x] **维度 3 编码规范**: 全部 public API 有 Javadoc；命名符合 Alibaba 规范；常量集中（`CacheConstants`/`MIN_DAYS` 等），无魔法值；无硬编码密钥；异常体系继承 `BaseException` 携带 `ErrorCode`，不吞异常、cause 透传。纯模型层无 LLM/外部调用，超时/重试/限频不适用。
- [x] **维度 4 代码质量**: 全部为 record / final 工具类，最大文件 `CacheKeys` 128 行（≪500）、最长方法 ≪50 行、圈复杂度低；Checkstyle **0 违规**；PMD 仅 1 个 P3 `DataClass`（落在 C-003 `CacheConstants`，非本卡、非阻断）；CollectionUtils 收敛集合 null 安全消除 CPD 重复。
- [x] **维度 5 安全**: `TripPlanRequest.validate()` 校验外部输入；`MaskUtils` 对手机号/身份证脱敏且长度不足时整体掩码不泄露原文；模型无任何密钥字段；用户数据纯内存模型不落盘（ADR-006）。
- [x] **维度 6 测试质量**: 62 测试全绿，common 指令/分支覆盖率 **100%**（≥80%）；覆盖 AC + 全部边界 + 降级标记保真（`Route.fallback` round-trip）；未 Mock 自写类（纯真实对象 round-trip）。
- [x] **维度 7 流程合规**: 变更范围严格限于 `huazai-trip-common`，与 `change.md` Out-of-Scope 一致（未触碰 Agent 业务/A2A Msg/Redis 仓储）。`constant/` 归 C-003 既有产物，本卡未夹带。
- [x] **维度 8 SDD 合规**: `change.md` 含用户故事/AC/边界/NFR/设计约束/契约影响/决策记录 D-1..D-6，构成充分规格真相源；实现逐字段对齐 `数据模型.md` §2/§3，未擅自扩字段或扩 scope。
- [x] **维度 9 TDD 合规**: `change.md` 记录 Red 先行（test-compile 因缺符号失败复现）→ 最小实现 → 补边界/降级/回归；测试覆盖 AC/边界/降级路径，无「代码先写、测试事后补形」迹象。

## 结论

✅ **0 个 🔴 严重问题，放行**。2 项 🟡 为健壮性/可维护性增强建议，不阻断进入 ⑤ CI 门禁。状态 `reviewing → ci`。
