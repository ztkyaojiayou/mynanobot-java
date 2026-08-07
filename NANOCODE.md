# NANOCODE.md

## 项目概述

NanoCode 是一个基于 Java 17 从零实现的 **AI 编程 Agent** 核心系统，不依赖任何 AI 框架，定位编程 Agent，向 Claude Code 致敬。相当于手搓实现了 spring-ai-alibaba-agent 及 datascope 的底层能力。

## 技术栈

- **Java 17** (LTS)，Maven 构建
- **Spring Boot 3.2.5** — Web / WebSocket / Actuator
- **Jackson 2.17.2** — JSON/YAML 处理
- **SLF4J 2.0.9 + Logback 1.4.11** — 日志
- **JUnit 5.10.1** — 测试
- **Lombok 1.18.30** — 样板代码生成
- **Jsoup 1.17.2** — HTML 解析（web_fetch 工具）
- **JLine 3.25.1** — CLI 终端输入（Esc 中断、原始模式按键）

## 项目结构

```
com.nanocode
├── v1/          # 纯 CLI 版本入口 (NanoCode)
├── v2/          # Spring Boot HTTP/SSE + WebSocket 版本入口 (NanoCodeApplication)
├── v3/          # CLI 交互版本入口 (NanoCodeCliApplication) + cli/CliChannel
├── core/        # AgentLoop（状态机引擎）、AgentRunner（LLM 调用循环）
├── bus/         # MessageBus — 三队列异步消息架构（Inbound/Outbound/sessionResponses）
├── config/      # Config 根配置类（分层强类型配置：agents/providers/channels/tools/memory）
├── providers/   # LLMProvider 接口及实现（deepseek 等）
├── tools/       # ToolRegistry 工具注册中心、Tool 接口
├── mcp/         # MCPManager — MCP 服务器管理（stdio）
├── session/     # SessionManager 会话管理
├── skill/       # SkillManager 技能管理
├── rules/       # RuleManager 规则管理
├── hook/        # HookManager、HookContext、HookEvent — 钩子机制
├── identity/    # IdentityManager 身份管理
├── security/    # PermissionManager + guard（CommandGuard/NetworkGuard/PathGuard）
└── NanoCodeRunner # 服务定位器（static getter 暴露核心组件给非 Spring 类）
```

```
resources/
├── application.yml
├── config/
│   ├── config.yaml          # 主配置（workspace、model、MCP servers）
│   ├── secret.yaml          # API 密钥（不提交，见 .gitignore）
│   └── secret.yaml.example
├── logback-cli.xml          # CLI 模式日志配置
├── logback.xml              # 默认日志配置
├── static/                  # index.html、sessions.html
└── templates/
```

## 构建和运行命令

```bash
# 编译
mvn compile

# 测试（指定 UTF-8 避免控制台中文乱码）
mvn test

# 打包可执行 fat JAR（Spring Boot repackage，finalName: nanocode-cli）
mvn package

# 运行 V2 — HTTP/SSE + WebSocket 服务
java -jar target/nanocode-cli.jar

# 运行 V3 — CLI 交互模式（类 Claude Code）
java -cp target/nanocode-cli.jar com.nanocode.v3.NanoCodeCliApplication [--workspace /path] [--resume <sessionId>]

# 不指定 --workspace 时自动取当前目录
```

`scripts/` 目录提供运维脚本：`start.sh` / `stop.sh` / `restart.sh` / `build-dist.sh` / `nanocode` / `nanocode.bat`，以及 MCP 测试服务器（`mcp-test-server.py`、`mcp-http-test-server.py`、`mcp-sse-test-server.py`）。

## 品牌升级说明（v2.4+）

项目由 **nanobot** 品牌升级为 **nanocode**（定位编程 Agent，向 Claude Code 致敬）。兼容策略：
- **环境变量**：`NANOCODE_API_KEY` / `NANOCODE_MODEL` / `nanocode.workspace` 新名优先，旧 `NANOBOT_API_KEY` / `nanobot.workspace` fallback（见 `NanoCodeEnv.java`）
- **运行时目录**：`.nanocode` 优先，旧 `.nanobot` 存在则回退（旧会话/记忆/技能数据不丢失）
- **项目记忆文件**：`NANOCODE.md` 优先，旧 `NANOBOT.md` 存在则回退

## 编码约定

- **包结构**：按功能模块分包（core/bus/tools/mcp/session/skill/rules/hook），不按层分包
- **类注释**：每个核心类必须有 Javadoc，包含定位说明、设计思想、工作流程（ASCII 流程图）
- **配置**：使用 Lombok `@Data` + Jackson `@JsonProperty` 做强类型配置类，所有配置有默认值
- **异步**：核心组件使用 `CompletableFuture` + `ExecutorService`，消息传递走 `MessageBus` 三队列架构
- **日志**：统一 SLF4J，logger 声明为 `private static final Logger logger = LoggerFactory.getLogger(Xxx.class)`
- **安全**：所有工具执行必须经过 `PermissionManager` + 对应 Guard（CommandGuard/NetworkGuard/PathGuard）
- **Spring 管理**：组件创建集中在 `NanoCodeConfig` 的 `@Bean`，`NanoCodeRunner` 只做服务定位（static getter），不创建组件
- **Profile 隔离**：CLI 模式用 `@Profile("!cli")` 跳过 V2 banner

## 关键设计决策

1. **三队列消息总线**：Inbound Queue（容量 100，AgentLoop 单线程消费）+ Outbound Queue（容量 1000，Dispatcher 线程扇出）+ sessionResponses Map（sync /api/chat 轮询）。不用单队列多消费者，因为 `BlockingQueue.take()` 是破坏性消费，SSE/CLI/WS 多通道需要独立队列。

2. **AgentLoop 状态机**：START → RESTORE → COMPACT → ... 显式状态机管理消息处理流转，各状态职责单一。

3. **服务定位器模式**：`NanoCodeRunner` 用 static 字段 + `@Autowired` setter 暴露核心组件，解决 ChatController/CliChannel/WebSocket 等非 Spring Bean 的依赖访问问题。

4. **手搓 LLM 调用循环**：`AgentRunner` 自管理 LLM 调用、工具调用、消息上下文，不依赖 Spring AI 等框架。

5. **双入口共存**：V2（HTTP/SSE/WS）与 V3（CLI）共用 `com.nanocode` 核心包，通过不同启动类和 Profile 隔离。

6. **MCP 兼容**：配置文件中 `mcp_servers` 同时写在两个位置（顶层和 `tools.mcp_servers` 下），兼容不同 Jackson 解析路径。
