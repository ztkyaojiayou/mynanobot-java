---
id: C-004
slug: common-models
status: done
created: 2026-06-07
owner: Owner Agent
---

# C-004 公共领域模型与基础设施类

## 用户故事

作为各 Agent 与 Server 的开发者，我想要在 `huazai-trip-common` 中拥有一套字段级契约一致的领域模型、DTO、枚举、异常与工具类，以便所有上层模块复用同一真相，避免模型在各处漂移。

## 非目标（Out of Scope）

- 本次不实现任何 Agent 业务逻辑或 Service。
- 本次不实现 A2A `Msg` 协议（归 C-005）。
- 本次不实现 Redis 读写仓储（仅提供常量与序列化工具，仓储归各模块/后续变更）。
- 本次不新增任何 wiki 未定义的实体或字段（严格对齐 `数据模型.md`）。

## 验收标准（AC）

- AC-1: 按 `数据模型.md` §2 实现领域模型：`TripPlan`、`TripDay`、`Attraction`、`Meal`、`Route`、`Budget`、`GeoLocation`，字段名/类型/约束与文档一致（金额用 `BigDecimal`，日期用 `LocalDate`）。
- AC-2: 实现 DTO：`TripPlanRequest`（字段约束：`days>0 且 ≤30`、`budget>0`、`headcount≥1`、`destination/startDate` 非空），并提供 Bean Validation 注解。
- AC-3: 实现全部枚举（`数据模型.md` §3）：`AgentStatus`、`TravelStyle`、`PlanMode`、`BudgetLevel`、`PlanStatus`、`MealType`、`SentimentType`。
- AC-4: 实现统一异常体系：业务异常基类 + 子类（如 `InvalidRequestException`、`DownstreamUnavailableException`、`RateLimitedException`），携带错误码（对齐 `接口协议.md` 错误码：`INVALID_REQUEST/RATE_LIMITED/SERVICE_UNAVAILABLE`）。
- AC-5: 实现工具类：`JsonUtils`（Jackson，ISO-8601 时间）、`IdUtils`（生成 `P-yyyyMMdd-NNN` planId）、脱敏工具（手机号等）；`CacheConstants` 若未在 C-003 落地则在此补齐。
- AC-6: `huazai-trip-common` 不依赖任何业务模块、Spring 业务上下文、AgentScope；模型可序列化/反序列化往返一致（round-trip 测试通过）。

## 边界情况（≥3）

- 当 `TripPlanRequest.days <= 0` 或 `> 30` 时，校验应失败并对应 `INVALID_REQUEST`。
- 当 `budget <= 0` 或为 `null` 时，校验失败。
- 当对 `BigDecimal` 金额做序列化/反序列化时，精度与标度不得丢失（如 `1620.00`）。
- 当 `preferences` 为空或 `null` 时，模型应可正常构造（可空字段）。
- 当 planId 在同一天生成超过序列上限（NNN 进位）时，`IdUtils` 行为需明确（递增/异常）。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 单对象 JSON 序列化 < 1ms（常规规模） |
| 可靠性 | 序列化往返不丢字段、不丢精度 |
| 安全 | 脱敏工具对手机号/身份证等敏感字段输出掩码；模型不含任何密钥字段 |
| 可观测 | 异常携带可读 code + message，便于日志检索 |

## 设计约束

- 必须严格对齐 `.harness/wiki/数据模型.md`（字段级契约）与 `业务模型.md`（实体语义、不变量阈值如 rating≥3.5、route durationMin≤120）。
- 实体放在 `com.nanobot.common.model`，DTO 放 `...common.dto`，枚举放 `...common.enums`，异常放 `...common.exception`，工具放 `...common.util`，常量放 `...common.constant`。
- 不允许在 common 引入业务/框架重依赖；遵循「common 仅 JDK + 基础库」。
- 模型尽量不可变（builder + final 字段或 record，视 Jackson 兼容性而定）。

## 契约影响

- REST: 这些 DTO/模型是 `接口协议.md` 请求/响应的承载体（结构对齐，不改变协议）
- A2A: 模型将作为 A2A `payload` 载荷的内容（协议本体归 C-005）
- 数据模型: 实现 `数据模型.md` 定义的全部领域模型与枚举（落地，不新增）
- Redis / ReMe: 序列化用 `JsonUtils`；Key 前缀对齐 `CacheConstants`

## 影响面

- 模块 / Agent / Skill: `huazai-trip-common`（被全部上层依赖）
- 外部 API: 无
- wiki: 落地 `数据模型.md`；如发现字段缺口需回写 wiki（受控）

## 规则归属

- 业务不变量归属: 字段级约束（非空、范围）由 DTO Bean Validation 承接；全局一致性（天数一致、无重复景点）归 C-005 `OutputQualityGate`
- 外部调用治理归属: 不适用（纯模型层）
- 可观测性要求: 异常 code/message 规范；脱敏在日志与序列化路径生效

## 测试策略

- 先写失败测试: `TripPlanRequest` 校验（days/budget/headcount 临界）、`BigDecimal` 序列化精度、planId 格式 先写失败测试。
- Happy Path: 合法请求构造与 JSON round-trip 一致。
- 边界测试: days=0/31、budget=0/null、preferences=null、金额精度、planId 序列进位。
- 降级测试: 不适用（无外部调用）。
- 回归测试: 模型契约一旦稳定，后续变更对其改动须由本测试集保护。

## 验收用例

- Case-1: 合法 `TripPlanRequest`(days=5,budget=8000,headcount=2) → 校验通过。
- Case-2: `days=0` → 校验失败，错误码 `INVALID_REQUEST`。
- Case-3: `Budget{total:8000, breakdown{transport:2200,hotel:3000,food:1620,ticket:1000}}` JSON round-trip → 字段与精度完全一致。
- Case-4: `IdUtils.newPlanId(LocalDate.of(2026,7,1), 1)` → `"P-20260701-001"`，匹配正则 `^P-\d{8}-\d{3}$`（D-5）。
- Case-5: `IdUtils.newPlanId(date, 1000)`（或 `0`）→ 抛 `InvalidRequestException`，码 `INVALID_REQUEST`（进位/越界边界，D-5）。
- Case-6: `MaskUtils.maskPhone("13800138000")` → `"138****8000"`；`null` → `null`（安全 NFR）。

## 决策记录（C-004 分析增补，人类已审批进入 coding）

> 以下为进入 ② coding 前与人类确认/收敛的技术决策，作为本卡实现的补充真相源。

- **D-1 模型承载形式 = Java record**（人类确认）。全部领域模型/值对象/DTO 用 JDK 21 record：不可变、零样板、Jackson 2.18 原生支持，契合「不可变消息/无状态」铁律，规避 Lombok `@Data` 的 equals/hashCode 陷阱。带集合字段的 record 用紧凑构造器做 null-safe 不可变拷贝（见 D-5）。
- **D-2 校验落地 = 声明注解 + 显式自校验**（人类确认）。DTO 字段加 `jakarta.validation` 注解（`@NotNull/@Positive/@Min/@Max`）作为对外声明契约（C-011 server `@Valid` 生效）；同时提供 `TripPlanRequest.validate()` 显式抛 `InvalidRequestException(INVALID_REQUEST)`。**common 不引入 hibernate-validator 运行时**，边界测试走 `validate()` 可独立运行。构造不抛错（保证 Jackson 反序列化任意载荷可用、`preferences=null` 可构造），范围校验只在 `validate()` 内。
- **D-3 新增依赖（均由 `spring-boot-dependencies` BOM 统一管理版本，无版本号、非 SNAPSHOT、非业务模块，满足 ArchUnit R4 仅禁 `..agent../..skills../..server..`）**：`com.fasterxml.jackson.core:jackson-databind`、`com.fasterxml.jackson.datatype:jackson-datatype-jsr310`、`jakarta.validation:jakarta.validation-api`。
- **D-4 Budget.breakdown = 强类型 record `BudgetBreakdown(transport,hotel,food,ticket)`**，而非 `Map<String,String>`：四类为固定维度，强类型更安全、消除魔法字符串键，round-trip/精度测试更清晰（仍属「record 形式」）。
- **D-5 `IdUtils` 采用纯函数签名 `newPlanId(LocalDate date, int sequence)`**（取代隐含无参/静态计数器）：避免可变静态业务状态与隐藏时间依赖（编码规范 §6/§8.2）；`sequence` 越界（<1 或 >999，超出 `NNN` 三位）显式抛 `InvalidRequestException`，明确「进位边界」行为。运行时序列源 = Redis `INCR`，归后续仓储层，不在 common 持有。
- **D-6 集合 null 安全统一收敛到 `CollectionUtils.safeCopy(List)`**（返回不可变拷贝，null→空列表），各 record 紧凑构造器复用，避免重复（编码规范 §10 / PMD CPD）。

## 任务拆解（≤1 天/项，DAG 无环）

- [ ] T-1: 枚举 + GeoLocation + 值对象（先行，无依赖）· P0 · 依赖 C-001 · 模块 common
- [ ] T-2: `TripPlanRequest` DTO + Bean Validation + 校验失败测试（先红）· P0 · 依赖 T-1 · 模块 common
- [ ] T-3: 领域模型 `TripPlan/TripDay/Attraction/Meal/Route/Budget` · P0 · 依赖 T-1 · 模块 common
- [ ] T-4: 异常体系 + 错误码（对齐接口协议）· P1 · 依赖 T-1 · 模块 common
- [ ] T-5: `JsonUtils`/`IdUtils`/脱敏工具/`CacheConstants` + 序列化往返测试 · P0 · 依赖 T-3 · 模块 common
- [ ] T-6: 补齐覆盖率（核心校验/工具 ≥80%）· P1 · 依赖 T-2..T-5 · 模块 common

## 流水线进度

- [x] ① 需求分析（analyzing）— 含决策记录 D-1..D-6，人类已审批进入 coding
- [x] ② 编码实现（coding）— 测试先行（Red 已复现：test-compile 因缺符号失败）→ 最小实现；`mvn compile` 通过、0 Checkstyle 违规
- [x] ③ 单测编写（testing）— **62 测试全绿**；common 覆盖率 **指令 100%（819/819）/ 分支 100%（32/32）** ≥ 80%。补齐边界/降级/回归缺口：`JsonUtils` TypeReference 泛型集合 round-trip + 不可序列化对象 fail-fast、`TripPlanRequest` null 目的地 / 负预算边界、`InvalidRequest/RateLimited` cause 构造器、`Route.fallback` 降级标记 round-trip 保真（接口协议.md §2.3）
- [x] ④ 专家评审（reviewing，0 严重问题）→ review.md（0 🔴 / 2 🟡 非阻断；9 维度全通过）
- [x] ⑤ CI 门禁（ci）— 全反应堆 `mvn clean verify` BUILD SUCCESS（10/10 模块）：Checkstyle 0 违规 / PMD 0 阻断（仅 1 个 P3 `DataClass` on C-003 `CacheConstants`，非本卡、不阻断）/ ArchUnit 6/6 @ArchTest 通过（含 R4 common 依赖方向）/ enforcer 通过（依赖一致、禁 SNAPSHOT）/ JaCoCo common 指令&分支 100% ≥80% / common 62 测试全绿。
- [x] ⑥ 部署验证（verifying）→ verify.md — **流程裁剪（诚实裁剪，非跳过）**：纯模型/工具库层无独立可运行制品（无 server/agent 运行面），deploy-verify 的运行时冒烟（Agent 注册 / 简单规划 / A2A / HITL / 降级）此卡 **N/A，不可如实断言**，下沉至 server+agent 具备真实逻辑的后续变更（C-011+）。本卡等价验证全绿：制品可安装（`mvn install` EXIT=0）+ 下游消费绿（全反应堆 9 下游模块均基于本卡 common 编译+测试 SUCCESS）+ 序列化往返保真。详见 verify.md。
- [x] 交付（done）— wiki 无需同步：实现逐字段对齐 `数据模型.md` §2/§3，未发现字段缺口、无受控回写。

## 实现纪要与交接（C-004）

**新增产物**（`huazai-trip-common`，均不可变 record / 无状态工具）：
- 枚举 ×7：`enums/{AgentStatus,TravelStyle,PlanMode,BudgetLevel,PlanStatus,MealType,SentimentType}`
- 模型 ×8：`model/{GeoLocation,Attraction,Meal,Route,BudgetBreakdown,Budget,TripDay,TripPlan}`
- DTO ×1：`dto/TripPlanRequest`（jakarta 声明注解 + `validate()`）
- 异常 ×5：`exception/{ErrorCode,BaseException,InvalidRequestException,RateLimitedException,DownstreamUnavailableException}`
- 工具 ×4：`util/{JsonUtils,IdUtils,MaskUtils,CollectionUtils}`
- 依赖新增（BOM 管理版本，R4 合规）：jackson-databind、jackson-datatype-jsr310、jakarta.validation-api

**AC → 测试映射**：AC-1/AC-6 → `model/TripPlanRoundTripTest`（含 Case-3 精度、空集合 round-trip 不可变、`Route.fallback` 降级标记保真）；AC-2 → `dto/TripPlanRequestTest`（days/budget(含 0/null/负)/headcount/destination(含 null/blank)/startDate 临界 + preferences 可空/不可变）；AC-3 → `enums/EnumContractTest`；AC-4 → `exception/ExceptionHierarchyTest`（三子类错误码 + 三子类 cause 透传）；AC-5 → `util/{JsonUtilsTest,IdUtilsTest,MaskUtilsTest,CollectionUtilsTest}`（Case-4/5/6 + JsonUtils TypeReference 泛型集合 / toJson fail-fast）。

**本轮补齐（testing ③ 收口，+8 测试，54→62）**：
- 边界：`TripPlanRequest` null 目的地（补 `||` 短路未覆盖分支）、负预算（signum<0，与 0/null 互补）。
- 降级：`Route.fallback=true` 序列化往返保真——降级链路关键字段不得静默移除（接口协议.md §2.3），纯模型层的「降级测试」落点。
- 回归：`JsonUtils.fromJson(TypeReference)` 泛型集合往返（数据模型.md §4 `List<TripDay>` 等 Hash 字段序列化路径）、`toJson` 不可序列化对象包装为 `IllegalStateException`、`InvalidRequest/RateLimited` 的 `(message, cause)` 构造器 cause 透传。
- 结果：common 指令/分支覆盖率均达 **100%**，全反应堆 10 模块 `mvn test` SUCCESS（含 ArchUnit R1–R6）。

**给下游（C-011 server）的提示**：common 仅引入 Bean Validation **注解 API**、未引入 provider，故 server 启动时会出现一条 INFO 日志 `NoProviderFoundException ... Add a provider like Hibernate Validator`（Spring `OptionalValidatorFactoryBean` 优雅降级，上下文正常启动）。C-011 接入 `spring-boot-starter-validation`（Hibernate Validator）启用 `@Valid` 后该日志消失，与决策 D-2 一致。
