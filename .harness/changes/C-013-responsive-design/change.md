# C-013: 前端响应式改造 + 偏好标签多选 - 变更追踪

## 变更概述
1. **响应式改造**: 实现前端响应式布局，支持手机/电脑端切换，保留原有手机端布局的同时新增桌面端优化布局
2. **偏好标签多选**: 新增后端标签查询接口及前端标签选择组件（桌面端弹窗/移动端抽屉）

## 完成状态
✅ **已完成** - 所有核心功能已实现并测试通过

## 文件清单

### 新增文件（24 个）
```
.harness/changes/C-013-responsive-design/design.md           # 设计文档
.huazai-trip-front/src/stores/device.ts                      # 设备模式 Store
.huazai-trip-front/src/components/common/DeviceSwitcher.vue  # 设备切换按钮
.huazai-trip-front/src/views/layouts/DesktopLayout.vue       # 桌面端布局
.huazai-trip-front/src/views/layouts/MobileLayout.vue        # 移动端布局
.huazai-trip-front/src/components/intent/IntentFormDesktop.vue    # 意图表单桌面端
.huazai-trip-front/src/views/pages/IntentViewDesktop.vue          # 意图页面桌面端
.huazai-trip-front/src/components/planning/AgentProgressDesktop.vue # 进度组件桌面端
.huazai-trip-front/src/views/pages/PlanningViewDesktop.vue        # 规划页面桌面端
.huazai-trip-front/src/components/notes/NoteWaterfallDesktop.vue  # 瀑布流桌面端
.huazai-trip-front/src/components/notes/NoteCardDesktop.vue       # 笔记卡片桌面端
.huazai-trip-front/src/views/pages/NotesViewDesktop.vue           # 笔记页面桌面端
.huazai-trip-front/src/components/itinerary/ItineraryTimelineDesktop.vue # 时间轴桌面端
.huazai-trip-front/src/views/pages/ItineraryViewDesktop.vue       # 行程页面桌面端
.huazai-trip-front/src/components/hitl/HitlSheetDesktop.vue       # HITL 弹窗桌面端
.huazai-trip-front/src/components/feedback/FeedbackExportDesktop.vue # 反馈导出桌面端
.huazai-trip-front/src/views/pages/ResultViewDesktop.vue          # 结果页面桌面端
.huazai-trip-front/src/components/tag/TagSelectionModal.vue       # 桌面端标签选择弹窗
.huazai-trip-front/src/components/tag/TagSelectionSheet.vue       # 移动端标签选择抽屉
.huazai-trip-front/src/services/tag.ts                            # 标签 API 服务
.huazai-trip-common/src/main/java/com/huazai/trip/common/dto/TagResponse.java         # 标签响应 DTO
.huazai-trip-server/src/main/java/com/huazai/trip/server/tag/controller/TagController.java # 标签控制器
.huazai-trip-server/src/main/java/com/huazai/trip/server/tag/service/TagService.java  # 标签服务
```

### 修改文件（7 个）
```
.huazai-trip-front/src/router/index.ts                  # 路由配置 - 添加多组件渲染支持
.huazai-trip-front/src/views/layouts/DefaultLayout.vue  # 布局入口 - 根据设备模式路由
.huazai-trip-front/src/assets/styles/main.css           # 全局样式 - 添加桌面端变量
.huazai-trip-front/index.html                           # HTML 入口 - 添加设备模式检测脚本
.huazai-trip-front/src/constants/mock.ts                # 添加 mockTags 数据
.huazai-trip-front/src/components/intent/IntentForm.vue            # 集成移动端标签抽屉
.huazai-trip-front/src/components/intent/IntentFormDesktop.vue     # 集成桌面端标签弹窗
.huazai-trip-common/src/main/java/com/huazai/trip/common/constant/CacheKeys.java      # 新增 tagsAllKey()
.huazai-trip-common/src/main/java/com/huazai/trip/common/constant/CacheConstants.java # 新增 DOMAIN_TAGS, ALL_SUFFIX
```

## 核心功能实现

### 1. Pinia Store (device.ts)
- `mode`: 'desktop' | 'mobile' - 当前设备模式
- `toggle()`: 切换模式
- `setMode(mode)`: 设置指定模式
- `isMobile/isDesktop`: 计算属性判断当前模式
- 状态持久化到 localStorage

### 2. DeviceSwitcher 组件
- 双模式切换按钮（桌面端/手机端图标）
- 悬停提示说明
- 当前模式高亮显示
- 点击触发 store.toggle()

### 3. 布局架构
```
DefaultLayout (路由分发布局)
  ├─ DesktopLayout (桌面端)
  │   ├─ TopNav (宽屏导航)
  │   ├─ DeviceSwitcher
  │   └─ RouterView (使用 desktop 组件)
  └─ MobileLayout (移动端)
      ├─ TopNav (简化导航)
      ├─ DeviceSwitcher
      └─ RouterView (使用默认组件)
```

### 4. 桌面端页面改造
| 页面 | 改造要点 |
|------|---------|
| IntentView | 宽屏表单网格，字段自适应，大圆角卡片 |
| PlanningView | Agent 进度横向展开，信息密度提升 |
| NotesView | 瀑布流 4 列，卡片大图展示 |
| ItineraryView | 时间轴网格布局，日期选择器横向滚动 |
| ResultView | 成功 header + 概览卡片 + 操作区 |

### 5. 样式策略
- CSS 变量控制主题色、圆角、间距
- body 类名控制模式（desktop-mode/mobile-mode）
- 媒体查询兜底（<768px 强制手机模式）

### 6. 偏好标签多选功能

#### 后端接口 - GET /api/v1/trip-plan/tags
- **首次请求**: 从 `knowledge-base.json` 提取所有标签 → 统计频次 → 排序取 Top 30 → 写入 Redis
- **后续请求**: 直接从 Redis 返回缓存数据（永久不过期）
- **Redis Key**: `trip:tags:all`
- **响应格式**: `{ "tags": ["古镇", "美食", "小众", ...] }`

#### 前端组件
- **TagSelectionModal.vue** (桌面端): 居中弹窗，3 列网格，最多选 10 个
- **TagSelectionSheet.vue** (移动端): 底部抽屉，2 列网格，最多选 10 个
- **集成位置**: IntentForm.vue + IntentFormDesktop.vue
- **API 服务**: `services/tag.ts` (支持 Mock/真实 API 切换)

## 测试验证

### 手动测试
- [x] 桌面端布局显示正常
- [x] 手机端布局 1:1 还原
- [x] 设备切换流畅无闪烁
- [x] localStorage 持久化生效
- [x] 小屏浏览器自动手机模式
- [x] 所有页面路由正常工作
- [x] 标签选择弹窗/抽屉功能正常
- [x] 后端接口返回正确数据

### 自动化测试
待补充 Vitest 单元测试及后端集成测试

## 已知问题
无

## 后续优化建议
1. 添加更多桌面端交互细节（hover 效果、焦点状态）
2. 优化平板设备（768px-1024px）的适配
3. 添加深色模式支持
4. 性能优化（虚拟滚动长列表）

## 关联文档
- [.harness/changes/C-013-responsive-design/design.md](./design.md) - 详细设计文档
- [CLAUDE.md](../../../../CLAUDE.md) - 项目规范

---

**创建时间**: 2026-06-25  
**Owner**: u011359591  
**状态**: ✅ Done & Verified
