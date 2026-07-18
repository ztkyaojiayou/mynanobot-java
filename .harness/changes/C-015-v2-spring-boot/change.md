---
id: C-015
slug: v2-spring-boot
status: done
created: 2025-06-15
owner: Owner Agent
---

# C-015 V2 Spring Boot Web 模式

## 用户故事

作为用户，我需要通过浏览器访问 Web 界面进行对话和会话管理，支持同步 API、SSE 流式、WebSocket 三种通信方式。

## 验收标准

- AC-1: `NanobotApplication` Spring Boot 启动，内嵌 Tomcat，端口 8080
- AC-2: `ChatController POST /api/chat` 同步聊天，waitForSessionResponse 阻塞匹配响应
- AC-3: `ChatController POST /api/chat/stream` SSE 流式，SseEmitter 实时推送
- AC-4: `SessionController GET /api/sessions` 列出所有会话（含 name/messageCount）
- AC-5: `SessionController PATCH /api/sessions/{key}` 重命名会话
- AC-6: `SessionController DELETE /api/sessions/{key}` 删除会话
- AC-7: `SessionController GET /api/sessions/{key}` 获取会话消息历史
- AC-8: `HealthController GET /api/health` 健康检查
- AC-9: `NanobotWebSocketEndpoint /ws` Jakarta WebSocket 注解，流式回调推送
- AC-10: `index.html` 聊天界面 + `sessions.html` 会话管理（点击名称可编辑）
- AC-11: `NanobotConfig` Spring Bean 创建 + `NanobotRunner` ApplicationRunner 初始化

## 边界情况

- 当 SessionManager 为 null 时，API 返回空列表
- 当会话不存在时，GET/PATCH/DELETE 返回 404
- WebSocket 连接关闭时自动清理回调

## 非功能需求

| 维度 | 指标 |
|------|------|
| 框架 | Spring Boot 3.2 + Spring MVC + Tomcat |
| WebSocket | Jakarta WebSocket API |
| 前端 | 纯 HTML/JS，无框架 |
