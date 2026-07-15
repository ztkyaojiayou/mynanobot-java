# Nanobot-Java 模块分类

> v1 = 无 Spring Boot 版 | v2 = Spring Boot 版（当前主要使用）
> 公共模块两个版本共享，改动自动对两版本生效。

---

```
com.nanobot
├── v1/                   ← V1 专属 (5个文件)
│   ├── Nanobot.java              入口：纯 Java main()
│   ├── NanobotLegacy.java        旧版入口
│   └── channel/
│       ├── ChannelServer.java             自建 HTTP/WS 服务器
│       ├── SimpleWebSocketConnection.java
│       └── WebSocketFrame.java
│
├── v2/                   ← V2 专属 (8个文件)
│   ├── NanobotApplication.java   入口：Spring Boot main()
│   ├── NanobotConfig.java        @Configuration Bean 工厂
│   ├── MessageBusConfig.java     MessageBus Bean
│   ├── WebSocketConfig.java      WebSocket 配置
│   ├── controller/
│   │   ├── ChatController.java   /api/chat, /api/chat/stream
│   │   └── HealthController.java /actuator
│   └── websocket/
│       └── NanobotWebSocketEndpoint.java @ServerEndpoint("/ws")
│
├── v3/                   ← V3 专属 (2个文件) ★新增
│   ├── NanobotCliApplication.java  入口：Spring Boot + CLI 交互
│   └── cli/
│       └── CliChannel.java         命令行终端对话
│
├─ ═══════════ 公共模块 (三版本共享) ═══════════
│
├── NanobotRunner.java    ← 组件初始化 Runner
├── config/               ← 配置模型 (Config.java, ConfigLoader.java)
├── bus/                  ← 消息总线
├── core/                 ← 核心引擎 (AgentLoop, AgentRunner, Hooks)
├── providers/            ← LLM 提供商
├── tools/                ← 工具系统
├── memory/               ← 记忆系统
├── session/              ← 会话管理
├── identity/             ← 身份系统
├── rules/                ← 规则系统
├── skill/                ← 技能系统
├── mcp/                  ← MCP 协议
├── security/             ← 权限/安全
├── cron/                 ← 定时任务
├── command/              ← 命令处理
└── utils/                ← 工具类
```
