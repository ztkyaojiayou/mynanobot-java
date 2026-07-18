---
id: C-008
slug: session-management
status: done
created: 2025-06-14
owner: Owner Agent
---

# C-008 会话管理

## 用户故事

作为用户，我需要会话持久化存储、历史消息加载、会话列表查看、重命名和删除功能，以便在不同时间恢复之前的对话上下文。

## 验收标准

- AC-1: `SessionManager` 会话级锁 (ConcurrentHashMap) 保证读写串行
- AC-2: `SessionStore` 纯文件 I/O 层：saveHistory/loadHistory/saveMetadata/loadMetadata
- AC-3: 会话重命名：`PATCH /api/sessions/{key}` + `renameSession()` 写入 metadata.json
- AC-4: 会话列表：`listSessionDetails()` 含 name/messageCount/lastModified
- AC-5: 会话删除：`deleteSession()` 递归删除目录 + 清除锁
- AC-6: `sessions.html` 前端页面：列表查看、点击名称编辑、查看详情、删除
- AC-7: `/resume` CLI 命令：列出最近 5 个会话 + `/resume <key>` 恢复

## 边界情况

- 当会话目录不存在时，createDirectories 自动创建
- 当重命名时 key 不存在，返回 false
- 会话名保存为 metadata.json 的 name 字段

## 非功能需求

| 维度 | 指标 |
|------|------|
| 存储路径 | {workspace}/sessions/{safeKey}/ |
| 并发安全 | ConcurrentHashMap + synchronized |
| 增量保存 | 只追加新增消息，不全量覆写 |
