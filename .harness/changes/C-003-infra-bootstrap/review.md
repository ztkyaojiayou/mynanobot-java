---
change: C-003
status: pass
reviewer: expert-reviewer
date: 2026-06-07
---

# 📋 评审报告: C-003 运行环境与基础设施搭建

## 总览

- 审查范围: `docker/`（compose+README）、`.env.example`/`.gitignore`、`huazai-trip-common`（CacheConstants/CacheKeys）、`huazai-trip-server`（启动类/health/config/application*.yml/spring.factories）及对应测试。
- 🔴 严重问题: 0
- 🟡 建议改进: 3（不阻塞放行）
- 🟢 通过项: 9 维度全部通过

> 评审基线：`工程结构.md`（依赖方向/§2.3 边界）、`编码规范.md`（§5 防御式/§7 安全）、`数据模型.md §4`、`架构决策.md ADR-003/006`、`change.md`（规格真相源）。

---

## 🔴 严重问题

无。

---

## 🟡 建议改进

### 1. AC-1 与 ⑥ 的实例级验证未在本机执行（环境阻塞）
- **现象**: 本机无 Docker，`docker compose up -d` 起容器、控制台可访问、停 Redis→DOWN、回滚冒烟未实跑。
- **建议**: 保持 compose/README 产出与 `verify.md` 登记；在具备 Docker 的环境补做并回写 verify.md（经人类确认采用此处理，参照 C-001 把 ⑥ 转交先例）。
- **理由**: 健康判定核心逻辑已由 `NacosHealthIndicatorTest` 单测覆盖（DOWN 标组件）；实例级仅差运行环境。

### 2. Nacos 健康检查为轻量 TCP 探针而非完整客户端
- **文件**: `health/SocketNacosProbe.java`
- **现象**: 经人类确认采用 JDK Socket 连通性探针，不引入 spring-cloud-alibaba。
- **建议**: C-005 落地 A2A 注册时若需更强语义（如就绪/实例数），再扩展探针或切换原生能力，并回写 ADR-003。
- **理由**: 对齐"Nacos 只就位、注册归 C-005"与"重写优于包装"，规避 SB4 兼容风险。

### 3. compose healthcheck 依赖镜像内 `curl`
- **文件**: `docker/docker-compose.yml`
- **现象**: Nacos healthcheck 用 `curl`，部分镜像不含。
- **建议**: README 已注明可改 `wget`/TCP；实例验证时按镜像实际调整。

---

## 🟢 审查通过项

- [x] **维度1 功能完整性**：AC-2（启动类+分层 yml+`/actuator/health=UP`）、AC-3（连接参数 env 覆盖、零明文密钥）、AC-4（`.env.example` 全变量 + `.gitignore` 忽略 `.env` + 缺密钥 fail-fast 点名）、AC-5（Redis+Nacos 健康聚合，DOWN 标组件）、AC-6（test profile 替身化，CI 不依赖容器）均满足；AC-1 产出齐备，实例验证登记环境阻塞。
- [x] **维度2 架构合规**：配置置于 `server`，缓存常量置于 `common`（设计约束）；`server → common/spring` 依赖方向正确；未实现 A2A 注册（尊重非目标，归 C-005）；ArchUnit R1~R6 全绿。
- [x] **维度3 编码规范**：public 类/方法均有 Javadoc；`CacheKeys`/`EnvironmentValidator` 入参防御式校验（§5）；异常信息含上下文不泄密钥；无魔法值（键段常量集中 `CacheConstants`）。
- [x] **维度4 代码质量**：方法/文件/复杂度合规（Checkstyle 0 违规）；核心逻辑与 Spring 适配/网络探针解耦（§8.2 可测试性）。
- [x] **维度5 安全（§7 红线）**：仓库零明文密钥；密钥仅环境变量/配置中心；Redis 设访问口令；`.env` 不入库；用户数据 Redis 24h TTL 不落盘（ADR-006）。
- [x] **维度6 测试质量**：健康聚合 DOWN、env fail-fast、键格式/边界均有测试；先写失败测试（DOWN 路径）后实现；server/common 核心包 JaCoCo ≥80% 达标。
- [x] **维度7 流程合规**：未实现 Agent/REST 业务、未接 ReMe 真实库、未做生产编排（尊重非目标）。
- [x] **维度8 SDD 合规**：实现严格对齐 change.md AC/边界/契约影响。
- [x] **维度9 TDD 合规**：降级/校验/键逻辑测试先行，非事后补测。

---

## 结论

✅ **0 个 🔴 严重问题，④ 专家评审通过，放行进入 ⑤ CI 门禁。** 状态流转：`reviewing → ci`。
⑥ 部署验证中 compose/实例级冒烟因无 Docker 环境阻塞，详见 `verify.md`，不影响 ④/⑤ 收口。
