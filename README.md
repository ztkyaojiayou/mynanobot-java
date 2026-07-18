# Nanobot-Java

[![Java 17](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![Spring Boot 3.2](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

基于 Java 17 的轻量级 **AI Agent 框架**，对标港大 [nanobot](https://github.com/nanobot/) (Python) 和 Claude Code。

**"Core stays small; extend at the edges"** — 核心保持精简，通过边缘扩展。

---

## 快速开始

```bash
# 1. 克隆项目
git clone https://github.com/ztkyaojiayou/mynanobot-java.git
cd mynanobot-java

# 2. 配置 API Key
export DEEPSEEK_API_KEY=your-api-key      # DeepSeek（默认）
# 或
export OPENAI_API_KEY=your-api-key        # OpenAI

# 3. 编译
mvn compile

# 4. CLI 模式启动（推荐）
./scripts/nanobot                         # Mac / Linux
scripts\nanobot.bat                       # Windows

# 5. 开始对话
> 你好！帮我写一个..."
```

**Spring Boot Web 模式**：
```bash
mvn spring-boot:run
# http://localhost:8080 (聊天) | /sessions.html (会话管理)
```

---

## 核心特性

| 特性 | 说明 |
|------|------|
| **CLI 终端交互** | 类 Claude Code 体验，JLine 终端 + Markdown 流式渲染 |
| **Plan Mode** | `/plan` 进入规划模式（只读出计划）→ `/plan approve` 审批执行 |
| **17 个内置工具** | 文件读写、代码搜索、Shell 执行、Web 搜索、任务追踪、子 Agent 等 |
| **MCP 支持** | Model Context Protocol 标准化工具接入，支持 stdio 和 HTTP |
| **4 级权限** | PLAN / DEFAULT / ACCEPT_EDITS / BYPASS，交互式确认 (1/2/3) |
| **会话管理** | 持久化会话历史、重命名、恢复（Web 界面 + CLI 命令） |
| **State 模式引擎** | 7 状态 Agent Loop，每个状态独立处理器 |
| **Provider 策略工厂** | 按模型名自动匹配 LLM 提供商（DeepSeek / OpenAI / 兼容） |
| **记忆系统** | 长期记忆 (Dream) + 历史压缩 (Consolidator) + 项目记忆 (NANOBOT.md) |
| **三大模式** | V1 独立 / V2 Spring Boot / V3 CLI，共享核心引擎 |

---

## 设计理念

```
┌─────────────────────────────────────────┐
│  入口: V3 CLI | V2 Web | V1 Server     │
├─────────────────────────────────────────┤
│  核心: AgentLoop (State 模式)           │
│         RESTORE→COMPACT→BUILD→RUN→...   │
│         AgentRunner (LLM+Tool 循环)     │
├─────────────────────────────────────────┤
│  扩展: Tool | Provider | Hook | Command │
├─────────────────────────────────────────┤
│  安全: Guard → Rule → Mode Pipeline     │
├─────────────────────────────────────────┤
│  存储: Session | Memory | Task Store    │
└─────────────────────────────────────────┘
```

**设计模式**：State、Strategy、Command、Chain of Responsibility、Template Method、Repository、Factory — 全部手写，不依赖 AI 框架。

---

## 项目结构

```
src/main/java/com/nanobot/
├── core/          核心引擎 (AgentLoop, AgentRunner, State 处理器, Hook)
├── tools/         工具系统 (17 内置 + MCP)
├── providers/     LLM 提供商 (OpenAI, DeepSeek + 策略工厂)
├── security/      权限 + 守卫 + 规则引擎
├── session/       会话管理 + 存储分离
├── bus/           消息总线
├── command/       命令系统 (/exit, /help, /init, /mode, /plan, /resume)
├── memory/        记忆 (Dream + Consolidator)
├── mcp/           MCP 客户端
├── skill/         技能系统
├── rules/         规则系统
├── identity/      身份系统 (SOUL/IDENTITY/USER)
├── v1/            V1 独立模式
├── v2/            V2 Spring Boot (REST + WebSocket + 前端)
└── v3/            V3 CLI (JLine 终端 + Markdown 渲染)
```

---

## CLI 命令速查

| 命令 | 功能 |
|------|------|
| `/help` | 列出所有命令 |
| `/init` | 分析项目生成 NANOBOT.md |
| `/plan` (或 `/mode plan`) | 进入规划模式（只读分析出计划） |
| `/plan approve` | 审批计划，切换到执行模式 |
| `/mode default` | 默认模式（读放行，写需确认） |
| `/mode accept_edits` | 编辑放行模式 |
| `/mode bypass` | 全部放行（谨慎） |
| `/resume` | 列出/恢复历史会话 |
| `/clear` | 清空当前上下文 |
| `/exit` (`/q`) | 退出 |
| `Esc` | 中断当前流式回复 |

---

## 技术栈

| 技术 | 用途 |
|------|------|
| Java 17 LTS | 运行环境 |
| Spring Boot 3.2 | V2 Web 框架 |
| Maven | 构建 & 依赖管理 |
| JLine 3 | 跨平台终端输入（Esc 检测） |
| Jackson | JSON / YAML 处理 |
| SLF4J + Logback | 日志 |
| JUnit 5 | 测试 |
| Jsoup | HTML 解析 (web_fetch) |

---

## 文档

- **[ARCHITECTURE_AND_LEARNING_ROADMAP.md](ARCHITECTURE_AND_LEARNING_ROADMAP.md)** — 完整架构说明 + AI 概念演进 + Claude Code 实战 + 学习路线
- `docs/` 目录 — 部署文档、StreamResponseCallback 详解等

---

## 本项目的构建方式

这个项目**全程使用 Claude Code 构建**——从架构设计到代码实现到文档编写。它是 Agent-Driven Development 的实践产物。

> 详见 [ARCHITECTURE_AND_LEARNING_ROADMAP.md § 三、Claude Code 实战指南](ARCHITECTURE_AND_LEARNING_ROADMAP.md)

---

## License

MIT — 自由使用、修改、分发。
