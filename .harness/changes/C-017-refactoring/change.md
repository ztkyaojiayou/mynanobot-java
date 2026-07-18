---
id: C-017
slug: design-patterns-refactoring
status: done
created: 2025-07-17
owner: Owner Agent
---

# C-017 设计模式重构

## 用户故事

作为架构师，我希望代码库具有良好的可读性和可扩展性，通过应用 State 模式、Strategy 模式、Repository 模式等设计模式重构核心模块。

## 验收标准

- AC-1: AgentLoop 973 行 → 579 行，7 个 do* 方法提取为 `core/state/` 下的独立 StateHandler 类
- AC-2: `AgentState` 接口 + 8 个实现 (Restore/Compact/Command/Build/Run/Save/Respond)
- AC-3: `ProviderFactory` 策略工厂从 NanobotRunner 抽取，按模型名匹配 Provider
- AC-4: `SessionStore` 从 SessionManager 分离纯文件 I/O，SessionManager 专注锁+业务
- AC-5: `NanobotRunner.run()` 60 行平铺 → 10 行调用链 + 8 个 init* 方法
- AC-6: `CommandContext` 扩展支持 AgentLoop + sessionId 注入
- AC-7: 编译通过 + 59 个单元测试全绿

## 边界情况

- 当新状态需要注册时，在 `initStateHandlers()` 中 put 即可
- 当 consolidator 通过 setConsolidator() 延迟注入时，同步更新 CompactState handler
- ProviderFactory 兜底策略：无匹配时当作 OpenAI 兼容 API

## 非功能需求

| 模式 | 位置 |
|------|------|
| State | core/state/ (8 类) |
| Strategy | providers/ProviderFactory |
| Repository | session/SessionStore |
| Command | command/ (5 命令) |
| Chain of Resp. | core/hook/CompositeHook |
