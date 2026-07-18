---
id: C-012
slug: identity-system
status: done
created: 2025-06-11
owner: Owner Agent
---

# C-012 身份系统

## 用户故事

作为 Agent 系统，我需要一个身份管理系统控制 Agent 的自我认知和行为规范，通过三个 Markdown 文件 (SOUL/IDENTITY/USER) 定义 Agent 的名字、个性和用户偏好，采用首位+近因效应双重注入对抗模型身份混淆。

## 验收标准

- AC-1: `IdentityManager` 从 `.nanobot/` 加载 SOUL.md / IDENTITY.md / USER.md
- AC-2: `Soul` 定义 Agent 核心身份（名字、使命、底线）
- AC-3: `Identity` 定义个性特征（语气、风格、偏好）
- AC-4: `UserProfile` 定义用户信息（称呼、环境、偏好）
- AC-5: `getSystemPrompt(currentDate)` 首位效应注入身份+日期，近因效应结尾再强调
- AC-6: 日期覆盖：以 currentDate 覆盖训练数据中的过期日期

## 边界情况

- 当身份文件不存在时，使用内置默认提示词
- 当身份文件为空时，不影响其他提示词注入
- 工具结果格式说明 [TOOL_OK]/[TOOL_ERR] 在近因效应段注入

## 非功能需求

| 维度 | 指标 |
|------|------|
| 文件格式 | Markdown (.md) |
| 加载方式 | IdentityLoader 静态工厂方法 |
| 注入位置 | 首位 (开头) + 近因 (结尾) |
