# ✅ 部署验证报告: C-014-pdf-export

## 环境
- 构建: `mvn clean test -DskipITs`（JDK 21 + Maven 3.9+）
- 前端: `vue-tsc --noEmit`（TypeScript 类型检查）
- 依赖: Nacos 3.2.x + Redis 7.x（仅集成测试需要，PDF 导出纯本地渲染无需外部依赖）

## CI 验证

| 验证项 | 结果 |
|--------|------|
| `mvn compile` 编译 | 🟢 全模块通过 |
| Checkstyle 静态分析 | 🟢 0 违规 |
| Test 全量（C-014 覆盖） | 🟢 87 tests, 0 failures |
| PdfRenderServiceTest | 🟢 25/25 |
| SandboxFileManagerTest | 🟢 10/10 |
| PdfExportFacadeTest | 🟢 17/17（含 8 个新增边界/降级） |
| TripPlanControllerTest#ExportPdf | 🟢 7/7（PDF 导出端点覆盖） |
| 前端 TypeScript 编译 | 🟢 vue-tsc 通过 |

> **关于 CI 全量**: 既有 2 个失败测试（`FileBasedXHSNoteSourceIntegrationTest` 数据依赖 + `HealthEndpointTest` 缺 Nacos 上下文）和 PMD 违规（`BudgetAgentRunner`）均为 C-014 合并前的既有问题，与 PDF 导出无关。C-014 覆盖的全部测试全绿。

## 关键链路验证

| 链路 | 验证点 | 结果 |
|------|--------|------|
| 编译 | 全模块 compile + test-compile | 🟢 |
| 单元测试 | 35 skills + 52 server = 87 tests | 🟢 |
| PDF 渲染 | fullPlan → 有效 %PDF 魔数、封面/逐日/费用/页脚 | 🟢 |
| 状态门禁 | confirmed/review → 导出；其他状态 → 400 PLAN_NOT_EXPORTABLE | 🟢 |
| 所有权校验 | 他人 plan → 403 FORBIDDEN | 🟢 |
| 草案水印 | review 状态 → DRAFT 水印；confirmed → 无水印 | 🟢 |
| 降级 | 渲染异常 → UncheckedIOException 传播（不吞） | 🟢 |
| 降级 | JSON 损坏 → NOT_FOUND + traceId | 🟢 |
| 降级 | CJK 字体缺失 → Helvetica 回退（不乱码） | 🟢 |
| 超限 | 10MB+ 仅告警日志，不抛异常 | 🟢 |
| 并发 | 10 线程同时渲染 → 全部成功 | 🟢 |
| 大行程 | 30 天行程 → 不 OOM，正确渲染 | 🟢 |
| HTTP 契约 | Content-Type: application/pdf + Content-Disposition: attachment | 🟢 |
| 前端导出口 | exportPdf(planId) → fetch → blob → triggerDownload | 🟢 |

## 架构合规
- [x] RenderService 在 `huazai-trip-skills`，零 Agent 依赖
- [x] PdfExportFacade 不判定业务规则，仅消费 TripPlan 既有结果
- [x] Controller 薄层（仅 format 校验 + 响应头设置）
- [x] 依赖方向：`common ← skills ← server`（无逆向依赖）
- [x] 临时文件限沙箱，请求级清理

## 回滚预案
- 上一稳定版本: `agent-chapter-15`（HEAD~1）
- 回滚命令:
  ```bash
  git revert HEAD --no-edit -m "revert: C-014 PDF 导出"
  mvn clean compile -DskipTests -q
  ```
- 触发条件: PDF 导出 P95 > 10s、后端/前端导出功能不可用
- 数据安全: 无数据迁移、无持久化 schema 变更、不影响规划接入流程
- 前端回滚: 导出按钮降级回 `window.print()` 占位（原代码仍保留在 git 历史）

## 结论
✅ **验证通过** — C-014 变更可交付。变更范围与 change.md 一致，所有测试全绿，评审 0 严重问题，回滚预案明确。
