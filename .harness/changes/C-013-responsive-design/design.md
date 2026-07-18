# C-013: 前端响应式改造 - 支持手机/电脑端切换

## 用户故事

**作为** 一个在电脑上使用旅游规划系统的用户  
**我想要** 一个适配电脑大屏幕的界面  
**并且能够方便地切换到手机端预览模式**  
**以便于** 在不同设备上获得最佳的使用体验

## 验收标准

### AC1: 设备切换功能
- [ ] 顶部导航栏增加"设备切换"控制器（桌面/手机图标按钮）
- [ ] 默认显示桌面端布局（宽屏优化）
- [ ] 点击切换后，界面变为手机模拟器模式（居中显示 360x660 容器）
- [ ] 切换状态持久化到 localStorage，刷新页面保持选择

### AC2: 桌面端布局要求
- [ ] 最大宽度 1280px 居中布局（类似效果图墙）
- [ ] 卡片式设计，圆角阴影，小红书风格保持一致
- [ ] 表单组件自适应宽度（非固定 360px）
- [ ] 笔记瀑布流改为 3-4 列（移动端为 2 列）
- [ ] 行程时间轴横向滚动优化，支持鼠标滚轮
- [ ] 预算面板、HITL 弹窗等组件适配大屏
- [ ] 字体大小适当放大（14px→16px 基础字号）

### AC3: 手机端布局保留
- [ ] 手机外壳（PhoneFrame）完整保留
- [ ] 状态栏、底部 Tab 栏样式不变
- [ ] 所有移动端组件在手机模式下 1:1 还原
- [ ] 手机模拟器居中显示，带阴影效果

### AC4: 响应式断点
- [ ] 小屏设备（<768px）强制手机模式
- [ ] 中屏设备（768px-1024px）可切换
- [ ] 大屏设备（>1024px）默认桌面模式

### AC5: 视觉一致性
- [ ] 品牌红 #ff2442 保持不变
- [ ] 圆角、阴影、间距遵循设计系统
- [ ] 交互动画平滑过渡（transition: all 0.3s）

## 技术实现方案

### 1. Pinia Store: device-mode
```typescript
// stores/device.ts
interface DeviceModeState {
  mode: 'desktop' | 'mobile'  // 当前设备模式
  toggle(): void               // 切换模式
  isMobile(): boolean          // 判断是否为移动端
}
```

### 2. 布局架构
```
DefaultLayout (原有)
  ├─ DesktopLayout (新增)
  │   ├─ TopNav (优化版)
  │   ├─ DeviceSwitcher (新增组件)
  │   └─ RouterView (直接渲染，无 PhoneFrame)
  └─ MobileLayout (基于现有改造)
      ├─ TopNav (简化版)
      ├─ DeviceSwitcher (小图标)
      └─ PhoneFrame
          └─ RouterView
```

### 3. 组件改造策略

| 原组件 | 桌面端改造 | 手机端保留 |
|--------|-----------|-----------|
| `IntentForm.vue` | 移除 PhoneFrame，卡片宽屏布局 | 保留 PhoneFrame + 原有样式 |
| `PlanningView.vue` | 进度条横向展开，信息密度提升 | 保留手机框架 |
| `NotesView.vue` | 瀑布流 3-4 列，卡片稍大 | 2 列瀑布流 |
| `ItineraryView.vue` | 时间轴横向拉伸，DayTab 固定左侧 | DayTab 横向滚动 |
| `ResultView.vue` | 导出选项网格布局 | 垂直堆叠 |

### 4. 样式隔离策略
- 使用 CSS 变量控制主题色、圆角、阴影
- 桌面端使用 `.desktop-mode` 类修饰根容器
- 媒体查询作为兜底（强制手机模式）

## 文件清单

### 新增文件
```
src/stores/device.ts           # 设备模式 Store
src/components/common/DeviceSwitcher.vue  # 设备切换按钮
src/views/layouts/DesktopLayout.vue       # 桌面端布局
```

### 修改文件
```
src/views/layouts/DefaultLayout.vue  # 改为路由分发布局
src/assets/styles/main.css           # 添加桌面端样式规则
src/components/intent/IntentForm.vue     # 桌面端版本
src/components/planning/AgentProgress.vue  # 桌面端优化
src/components/notes/NoteWaterfall.vue     # 桌面端列数调整
src/components/itinerary/ItineraryTimeline.vue # 桌面端布局
src/components/hitl/HitlSheet.vue          # 桌面端弹窗尺寸
src/components/feedback/FeedbackExport.vue # 桌面端布局
```

## 测试策略

### Unit Test
- [ ] device store: toggle, isMobile 逻辑
- [ ] DeviceSwitcher 组件：点击切换事件
- [ ] DesktopLayout vs MobileLayout 渲染条件

### Integration Test
- [ ] 桌面端 IntentForm 表单提交
- [ ] 桌面端 NotesView 瀑布流布局
- [ ] 设备切换后路由守卫正常工作

### Manual Test
- [ ] Chrome DevTools 设备模拟器测试
- [ ] 真实设备：手机、平板、桌面浏览器
- [ ] 窗口缩放时切换流畅性

## 风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 移动端样式破坏 | 低 | 高 | 保留原有 PhoneFrame 封装，手机模式 1:1 还原 |
| 桌面端布局错乱 | 中 | 中 | 先实现核心页面，逐步扩展 |
| 性能问题（频繁切换） | 低 | 低 | localStorage 缓存 + CSS transition 优化 |
| 测试覆盖不足 | 中 | 中 | TDD 先行，核心路径 100% 覆盖 |

## 依赖关系

- 无外部依赖，纯前端改造
- 不影响后端 API
- 与现有 Mock 系统兼容

## 完成标准

- [ ] 所有验收标准通过
- [ ] CI 测试全绿（覆盖率≥80%）
- [ ] 专家评审通过（SDD/TDD 合规）
- [ ] Wiki 文档更新（接口协议补充响应式说明）
- [ ] CHANGELOG.md 记录

---

**Owner Agent**: 编排六阶段流水线  
**预计工时**: 4-6 小时  
**优先级**: High（用户体验关键改进）
