```markdown
# NANOBOT.md

## 项目概述

Nanobot-Java 是一个基于 Java 17 的 AI Agent 核心实现，手搓实现了类似 Spring AI Alibaba Agent 的底层架构，用于学习 Agent 的底层实现和架构思想。

## 技术栈

| 类别 | 技术 | 版本 | 用途 |
|------|------|------|------|
| 语言 | Java | 17 (LTS) | 核心开发语言 |
| 构建 | Maven | - | 依赖管理和构建 |
| JSON | Jackson | 2.17.2 | JSON/YAML 序列化 |
| 日志 | SLF4J + Logback | 2.0.9 / 1.4.11 | 日志门面和实现 |
| 测试 | JUnit 5 | 5.10.1 | 单元测试 |
| 框架 | Spring Boot | 3.2.5 | Web / WebSocket / Actuator |
| 代码简化 | Lombok | 1.18.30 | 减少样板代码 |
| HTML 解析 | Jsoup | 1.17.2 | 网页抓取工具 |

## 项目结构

```
src/
├── main/java/com/nanobot/
│   ├── bus/               # 消息总线 — 异步消息队列，解耦生产者和消费者
│   ├── config/            # 配置加载和强类型配置类
│   ├── core/              # 核心引擎
│   │   ├── AgentLoop      # 消息处理状态机引擎
│   │   ├── AgentRunner    # LLM 调用循环核心
│   │   └── hook/          # Agent 生命周期钩子
│   ├── cron/              # 定时任务调度器
│   ├── identity/          # 身份管理
│   ├── mcp/               # MCP 管理器
│   ├── memory/            # 记忆系统 (Dream, MemoryStore)
│   ├── providers/         # LLM 提供商抽象和实现 (OpenAI, DeepSeek)
│   ├── rules/             # 规则管理器
│   ├── security/          # 安全组件 (权限、命令/网络/路径守卫)
│   ├── session/           # 会话管理器
│   ├── skill/             # 技能管理器
│   ├── tools/             # 工具注册中心和内置工具实现
│   ├── v1/                # CLI 入口 (Nanobot)
│   ├── v2/                # Spring Boot HTTP/SSE + WebSocket 入口
│   └── v3/                # CLI 交互入口 (类 Claude Code 体验)
├── main/resources/
│   ├── application.yml    # Spring Boot 配置
│   ├── config/config.yaml # Nanobot 业务配置
│   ├── logback.xml        # 日志配置
│   ├── static/            # 前端静态页面 (index.html, sessions.html)
│   └── templates/         # 模板文件
├── test/java/com/nanobot/ # 测试代码
scripts/                   # 启动/停止脚本 (nanobot, start.sh, stop.sh 等)
docs/                      # 项目文档
```

## 构建和运行命令

```bash
# 编译
mvn clean compile

# 运行测试
mvn test

# 打包 (生成可执行 fat JAR)
mvn clean package

# 运行 V2 (HTTP/SSE + WebSocket)
java -jar target/nanobot-java-1.0.0-SNAPSHOT.jar

# 运行 V1 (CLI)
java -cp target/nanobot-java-1.0.0-SNAPSHOT.jar com.nanobot.v1.Nanobot

# 运行 V3 (CLI 交互，类 Claude Code)
java -cp target/nanobot-java-1.0.0-SNAPSHOT.jar com.nanobot.v3.NanobotCliApplication [--workspace /path]

# V3 恢复会话
java -cp target/nanobot-java-1.0.0-SNAPSHOT.jar com.nanobot.v3.NanobotCliApplication --resume <sessionId>
```

## 编码约定

- **Java 17 特性**：使用 `record`、`sealed class`、`pattern matching` 等现代语法
- **Lombok**：使用 `@Data`、`@Builder`、`@Slf4j` 减少样板代码
- **日志**：全部通过 `LoggerFactory.getLogger()` 获取 logger，使用 SLF4J API
- **配置**：强类型配置类，所有配置有合理默认值，支持 JSON/YAML 和环境变量覆盖
- **并发**：使用 `CompletableFuture`、`ExecutorService`、`BlockingQueue`，避免裸 `Thread`
- **测试**：JUnit 5，测试方法名遵循 `should_expectedBehavior_when_condition` 模式
- **包结构**：按功能模块分包，`com.nanobot.*`，避免循环依赖
- **文档**：核心类必须有 Javadoc，包含架构说明和 ASCII 流程图

## 关键设计决策

1. **消息总线解耦**：使用 `MessageBus` (BlockingQueue + ConcurrentHashMap) 解耦消息生产者和消费者，SSE/WS 通过 `StreamResponseCallback` 直推，不走队列。

2. **三版本入口**：
   - V1: 纯 CLI 入口
   - V2: Spring Boot HTTP/SSE + WebSocket 服务端
   - V3: 类 Claude Code 的 CLI 交互体验，通过 Profile `!cli` 隔离

3. **Agent 状态机**：`AgentLoop` 实现状态机引擎，`AgentRunner` 管理 LLM 调用循环和工具调用。

4. **安全三层守卫**：`CommandGuard`、`NetworkGuard`、`PathGuard` 分别控制命令执行、网络访问和文件系统操作。

5. **不依赖任何 AI 框架**：纯 Java 技术栈手搓实现，LLM 提供商通过 SPI 方式扩展（`OpenAIProvider`、`DeepSeekProvider`）。

6. **Lombok 注解处理器**：在 `maven-compiler-plugin` 中显式配置 `annotationProcessorPaths`，确保编译时 Lombok 生效。

7. **Maven Surefire 编码**：测试执行时指定 `-Dfile.encoding=UTF-8`，避免控制台中文乱码。
```