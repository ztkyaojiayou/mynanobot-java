---
id: C-014
slug: pdf-export
status: done
created: 2026-06-07
updated: 2026-06-30
owner: Owner Agent
---

# C-014 行程方案 PDF 导出

## 用户故事

作为终端用户，我想要把已确认的行程方案导出为一份排版清晰的 PDF（逐日行程 + 费用拆分），以便离线保存、打印或分享给同行者。

## 非目标（Out of Scope）

- 本次不做在线协作编辑/多人批注。
- 本次不做图片/地图截图的实时渲染（如需地图图，先用静态占位）。
- 本次不做导出为 Word/Excel 等其他格式。
- 本次不改变规划业务逻辑（仅消费已生成的 `TripPlan`）。
- 本次不做后端异步导出 + 进度轮询（单次同步生成即可）。

## 验收标准（AC）

- AC-1: 后端提供导出接口 `GET /api/v1/trip-plan/{planId}/export?format=pdf`，对 `status ∈ {review, confirmed}` 的 plan 生成 PDF 并返回（`Content-Type: application/pdf`，`Content-Disposition: attachment`）。
- AC-2: PDF 内容完整：封面（目的地/日期/人数/风格）、逐日 TripDay（景点/餐饮/路线/当日小计）、费用拆分（四类 + 总费用 + overrunRate）、生成时间与 traceId 页脚。
- AC-3: 仅 `confirmed` 可导出正式版；`review` 状态草案导出时标注「草案/未确认」水印（对齐 `业务模型.md` §6.3「PDF 导出前预览」）。
- AC-4: 临时文件写入沙箱 `/tmp/agentscope/{agentId}/`，单文件 ≤10MB，请求结束后清理（对齐 `数据模型.md` §1/§6）。
- AC-5: 金额单位元/两位小数、时间 ISO-8601、敏感字段脱敏；中文字体正确嵌入不乱码。
- AC-6: 导出失败（plan 不存在/未到可导出状态/渲染异常）返回统一错误体 `{code,message,traceId}`，不产生半成品文件。
- AC-7: 前端「导出 PDF 行程单」按钮调用后端导出 API，触发浏览器文件下载（替换当前 `window.print()` 占位），下载期间按钮显示加载态，失败时提示用户。

## 边界情况（≥3）

- 当 `planId` 不存在或处于 `failed/analyzing/planning/assembling/budgeting` 等不可导出状态时，返回 `400 PLAN_NOT_EXPORTABLE` 错误，不生成 PDF。
- 当行程数据部分降级（某 Route/Meal 的 `fallback=true`）时，PDF 仍可生成，在对应条目标注「⚠ 降级估算」。
- 当生成文件超过 10MB 上限时，压缩图片或裁剪冗余内容，记录告警日志，不写超限文件到沙箱。
- 当并发请求对同一 planId 导出时，文件名加随机后缀隔离或复用缓存，互不覆盖。
- 当中文/emoji 字体缺失时，回退到内置 CJK 字体（如思源黑体子集），保证不乱码。
- 当前端网络中断或后端超时时，前端恢复按钮可点击状态并提示重试。

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 单份 PDF 生成 P95 < 3s（5 天行程基准） |
| 可靠性 | 渲染失败不留半成品文件；沙箱文件请求结束清理 |
| 安全 | 临时文件限沙箱 ≤10MB；脱敏；不落盘到仓库/持久盘；需认证（userId 校验） |
| 可观测 | `pdf.export.latency`(P95)、`pdf.export.success.rate`、`pdf.export.size.bytes` 指标；失败带 traceId |

## 设计约束

- 导出接口与状态门禁落在 `huazai-trip-server`（Controller + Facade 层）。
- PDF 渲染能力可下沉 `huazai-trip-skills`（如 `PdfRenderService`），禁止依赖 Agent 模块。
- 只消费 `TripPlan`/`TripDay`/`Budget` 等 C-004 已有模型，不在导出层复制或新增任何业务规则。
- PDF 库选型遵循「选无聊技术、不引入未经评审依赖」原则，在实现阶段完成选型评审并记录于 change 或 ADR。
- 临时文件严格限定沙箱目录，遵守 ADR-006 不落盘约束。
- 前端变更限于导出按钮行为替换和 API 服务方法新增，不改变页面结构和其他功能。

## 契约影响

- REST: 新增 `GET /api/v1/trip-plan/{planId}/export?format=pdf` 端点（需认证），需补充 `接口协议.md`
- A2A: 无
- 数据模型: 复用 `TripPlan`；沙箱临时文件约定（已在数据模型.md §1/§6 覆盖）
- Redis / ReMe: 读 `trip:plan:{planId}` 获取完整行程数据；可选缓存已生成 PDF 引用（TTL 与 plan 一致）
- 前端: `trip-plan.ts` 新增 `exportPdf(planId)` 方法；导出按钮替换 `window.print()` 为 API 调用 + blob 下载

## 影响面

- 模块: `huazai-trip-server`（Controller/Facade）、`huazai-trip-skills`（PdfRenderService）、`huazai-trip-front`（导出按钮 + API 服务）
- Agent / Skill: 无 Agent 调用
- 外部 API: 无（纯本地渲染）
- wiki: 需补充 `接口协议.md` 导出端点；对齐 `业务模型.md`(§1.1/§6.3)、`数据模型.md`(沙箱)

## 规则归属

- 业务不变量归属: 导出层不判定业务规则，仅按 `TripPlan` 既有结果渲染；可导出状态校验（`review/confirmed`）由 Facade 层承担
- 外部调用治理归属: 无外部调用；文件 IO 限沙箱，请求级生命周期管理
- 可观测性要求: `traceId/planId` 贯穿日志与 PDF 页脚；导出成功率/耗时/文件大小三项指标

## 测试策略

- 先写失败测试: 状态门禁（仅 review/confirmed 可导出）、非法 planId、未确认草案水印、超 10MB 处理、失败不留半成品 → 先红
- Happy Path: confirmed plan → 生成完整 PDF，响应 `application/pdf`，含逐日 + 费用 + 页脚
- 边界测试: 不存在 planId 返回 404、不可导出状态返回 400、空行程兜底、fallback 标注、中文/emoji 不乱码
- 降级测试: 渲染异常 → 返回错误体 + 清理临时文件、字体缺失 → 回退内置 CJK 字体
- 回归测试: 导出契约（响应头/Content-Type/文件名）、沙箱清理、现有 REST 端点不受影响

## 验收用例

- Case-1: 输入 `GET /api/v1/trip-plan/P-20260701-001/export?format=pdf`（planId 存在且 status=confirmed） / 预期 `200 OK`，`Content-Type: application/pdf`，PDF 含封面+逐日行程+费用拆分+traceId 页脚，无水印。
- Case-2: 输入 `GET /api/v1/trip-plan/P-NOT-EXIST/export?format=pdf` / 预期 `404 {code:"NOT_FOUND", message:"规划不存在", traceId:"T-..."}`。
- Case-3: 输入 planId 存在但 status=analyzing / 预期 `400 {code:"PLAN_NOT_EXPORTABLE", message:"当前状态不可导出"}`。
- Case-4: 输入 planId 存在且 status=review / 预期 `200 OK`，PDF 带「草案·未确认」水印。
- Case-5: 输入含 fallback=true 路线的 plan / 预期 PDF 对应条目标注「⚠ 降级估算」。
- Case-6: 前端点击「导出 PDF 行程单」按钮 / 预期浏览器弹出文件下载（文件名含 planId），按钮在下载期间显示加载态。
- Case-7: 导出完成后检查沙箱目录 / 预期临时文件已被清理。

## 任务拆解（≤1 天/项，DAG 无环）

- [x] T-1: PDF 库选型评审 + 渲染骨架（沙箱写入 + 请求级清理） · P0 · 依赖 C-004(done),C-011(done) · 模块 skills
- [x] T-2: 导出接口（Controller/Facade）+ 可导出状态门禁（先写失败测试） · P0 · 依赖 T-1 · 模块 server
- [x] T-3: PDF 模板渲染（封面/逐日行程/费用拆分/页脚 + 中文字体嵌入） · P0 · 依赖 T-1 · 模块 skills
- [x] T-4: 草案水印 + fallback 标注 + 敏感字段脱敏 · P1 · 依赖 T-3 · 模块 skills
- [x] T-5: 文件大小上限校验 / 并发隔离 / 失败清理 + 单测覆盖率 ≥80% · P1 · 依赖 T-2,T-3 · 模块 server,skills
- [x] T-6: 前端导出按钮对接后端 API（替换 window.print + blob 下载 + 加载态 + 错误提示） · P1 · 依赖 T-2 · 模块 front
- [x] T-7: 回写 `接口协议.md` 导出端点契约 + 对齐 wiki · P2 · 依赖 T-2 · 模块 wiki

## 流水线进度

- [x] ① 需求分析（analyzing）
- [x] ② 编码实现（coding）
- [x] ③ 单测编写（testing，覆盖率 ≥80%）
- [x] ④ 专家评审（reviewing，0 严重问题）→ review.md
- [x] ⑤ CI 门禁（ci，全绿）
- [x] ⑥ 部署验证（verifying）→ verify.md
- [x] 交付（done，wiki 已同步）
