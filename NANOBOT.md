# NANOBOT.md

## 项目概述

Nanobot-Java 是一个基于 Java 17 的 AI Agent 核心实现，手搓实现了类似 Spring AI Alibaba Agent 的底层架构，用于学习 Agent 的底层实现和架构思想。

## 技术栈

| 类别 | 技术 | 版本 | 用途 |
|------|------|------|------|
| 语言 | Java | 17 (LTS) | 核心开发语言 |
| 构建 | Maven | - | 依赖管理和构建 |
| 框架 | Spring Boot | 3.2.5 | V2 Web / WebSocket |
| 终端 | JLine 3 | 3.25.1 | 跨平台原始按键 (Esc) |
| JSON | Jackson | 2.17.2 | JSON/YAML 序列化 |
| 日志 | SLF4J + Logback | 2.0.9 / 1.4.11 | 日志门面和实现 |
| 测试 | JUnit 5 | 5.10.1 | 单元测试 |
| 代码简化 | Lombok | 1.18.30 | 减少样板代码 |
| HTML 解析 | Jsoup | 1.17.2 | 网页抓取工具 |

## 项目结构

```
src/
├── main/java/com/nanobot/
│   ├── bus/               # 消息总线 — 异步消息队列，解耦生产者和消费者
│   ├── config/            # 配置加载和强类型配置类
│   ├── command/           # 命令系统 (/exit, /help, /init, /mode, /plan, /resume)
│   ├── core/              # 核心引擎
│   │   ├── AgentLoop      # 消息处理状态机 (State 模式)
│   │   ├── AgentRunner    # LLM 调用循环 + 工具执行
│   │   ├── TaskStore      # 会话级任务追踪
│   │   ├── state/         # State 处理器 (8 个实现类)
│   │   ├── hook/          # Agent 生命周期钩子链
│   │   └── subagent/      # 子 Agent 通信
│   ├── cron/              # 定时任务调度器
│   ├── identity/          # 身份管理 (SOUL/IDENTITY/USER)
│   ├── mcp/               # MCP 协议客户端
│   ├── memory/            # 记忆系统 (Dream, MemoryStore, Consolidator)
│   ├── providers/         # LLM 提供商 + ProviderFactory 策略工厂
│   ├── rules/             # 规则管理器
│   ├── security/          # 安全层 (PermissionManager + Guard + RuleEngine)
│   ├── session/           # 会话管理 + SessionStore 存储分离
│   ├── skill/             # 技能管理器
│   ├── tools/             # 工具系统 (17 个内置工具 + MCP)
│   ├── v1/                # V1 独立模式 (ChannelServer + 手动帧解析)
│   ├── v2/                # V2 Spring Boot (REST/SSE/WebSocket)
│   └── v3/                # V3 CLI (JLine 终端 + Markdown 渲染)
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

# 测试
mvn test

# V3 CLI 模式（推荐）
./scripts/nanobot                    # Mac/Linux
java -jar target/nanobot-cli.jar     # 或直接运行 JAR

# V2 Spring Boot 模式（Web 界面）
mvn spring-boot:run
# → http://localhost:8080 (聊天) | /sessions.html (会话管理)
```

## 编码约定

- **命名**：类名 PascalCase，方法名 camelCase，包名全小写
- **Lombok**：使用 `@Data`、`@Getter`、`@AllArgsConstructor` 减少样板代码
- **日志**：统一使用 `LoggerFactory.getLogger()` + SLF4J，关键路径 INFO，调试路径 DEBUG
- **并发**：`CompletableFuture` + `ExecutorService` + `ConcurrentHashMap`，不直接操作 Thread
- **测试**：JUnit 5 + `@DisplayName`，测试方法用中文描述
- **包结构**：按功能模块分包 `com.nanobot.*`，接口和实现分离
- **文档**：核心类有 Javadoc 含架构说明 + ASCII 流程图
- **提交**：功能完成后 `mvn test` 验证通过再 commit

## 关键设计决策

1. **消息总线解耦**：`MessageBus` (BlockingQueue + ConcurrentHashMap) 解耦消息生产者消费者，SSE/WS 通过 `StreamResponseCallback` 直推。

2. **State 模式状态机**：`AgentLoop` 采用 State 模式，7 个状态独立为 `core/state/` 下的处理类，可读可扩展。

3. **三版本入口**：V1 独立 / V2 Spring Boot / V3 CLI（对标 Claude Code），共享核心引擎。

4. **ProviderFactory 策略工厂**：按模型名自动匹配 LLM 提供商，新增厂商只需注册策略。

5. **权限管道**：PreToolUseHook → Guards (Path/Command/Network) → RuleEngine → PermissionMode，四层防护。

6. **Plan Mode**：`/plan` 进入只读规划 → 出计划 → `/plan approve` 审批执行，对标 Claude Code。

7. **不依赖任何 AI 框架**：纯 Java 17 手搓，所有设计模式（State/Strategy/Command/Chain of Responsibility）自己实现。