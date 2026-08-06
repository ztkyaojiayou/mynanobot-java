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

## 使用指南

### 日常对话

```bash
> 帮我用 Java 写一个单例模式
> 这段代码有什么问题？
> 运行一下测试看看有没有报错
> 帮我把明文密码改成 BCrypt 加密
```

### Plan Mode（推荐工作流）

```bash
> /plan                           # 进入规划模式（只读）
📋 已进入规划模式

> 实现用户注销功能，包括清除 JWT 和 Session 失效

AI：(只读探索项目 → 出计划)
  ## 需求理解：在现有认证系统增加注销端点...
  ## 影响范围：AuthController, JwtUtil, SecurityConfig
  ## 实现步骤：1. JwtUtil 黑名单 → 2. POST /logout → ...

> /plan approve                   # 审批通过，开始执行
✅ 计划已审批，进入执行模式...
AI：(按计划逐步实现 → 跑测试 → 完成)
```

### 权限管理

```
> /mode plan          只读模式，适合探索不熟悉的项目
> /mode default       默认模式，写操作需确认
> /mode accept_edits  编辑放行，Shell 执行仍需确认
> /mode bypass        全部放行（谨慎使用）
```

### 会话管理

```bash
> /resume                        # 查看最近 5 个会话
> /resume cli-1784128421194      # 恢复到指定会话
> /clear                         # 清空当前上下文
```

**Web 界面**：Spring Boot 模式下访问 `/sessions.html`，可查看详情、重命名、删除会话。

### 项目初始化

```bash
> /init                          # AI 分析项目 → 生成 NANOBOT.md
# NANOBOT.md 会每次对话自动加载，可手动编辑补充编码规范
```

---

## 部署

### 环境要求

| 依赖 | 版本 |
|------|------|
| JDK | 17+ |
| Maven | 3.9+ |
| Node.js | 18+（仅 Claude Code 需要） |

### 全局安装（推荐）

```bash
# 把 scripts/ 加到 PATH，在任何目录下使用
export PATH="/path/to/mynanobot-java/scripts:$PATH"

# 现在可以在任意项目目录启动
cd /any/project
nanobot

# 指定工作目录
nanobot --workspace /path/to/project
```

### 打包部署

```bash
# 打包 fat JAR
mvn clean package -DskipTests

# 直接运行
java -jar target/nanobot-cli.jar

# 或通过脚本（自动检测源码更新并重建）
./scripts/nanobot
```

### 分发部署

```bash
# 一键生成分发包
./scripts/build-dist.sh
# → dist/nanobot/ 目录，复制到目标机器即可使用
```

### Docker

```bash
# 编译
mvn clean package -DskipTests

# 构建镜像
docker build -t nanobot -f- . <<'EOF'
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY target/nanobot-cli.jar app.jar
ENV DEEPSEEK_API_KEY=your-api-key
CMD ["java", "-jar", "app.jar"]
EOF

# 运行
docker run -d --name nanobot -e DEEPSEEK_API_KEY=your-key nanobot
```

### Windows

```cmd
REM 设置 API Key
set DEEPSEEK_API_KEY=your-api-key

REM 启动
scripts\nanobot.bat
```

---

## 核心特性

| 特性 | 说明 |
|------|------|
| **CLI 终端交互** | 类 Claude Code 体验，Markdown 流式渲染 + 代码语法高亮 |
| **终端自适应** | CMD 纯文本 / Windows Terminal + Linux + macOS 彩色双栏 |
| **工具调用着色** | exec=红 read=绿 write=橙 web=蓝，一目了然 |
| **Token 用量** | 每轮结束显示耗时+token数，prompt 显示模型名和用量 |
| **Plan Mode** | `/plan` 进入只读规划 → `/plan approve` 审批执行 |
| **17 个内置工具** | 文件读写、代码搜索、Shell 执行、Web 搜索、任务追踪、子 Agent |
| **MCP 支持** | Model Context Protocol，支持 stdio / HTTP / SSE 三种传输 |
| **4 级权限** | PLAN / DEFAULT / ACCEPT_EDITS / BYPASS + 交互确认 (1/2/3) |
| **会话管理** | 持久化 + 恢复 + Web 管理界面 |
| **7 状态引擎** | Agent Loop State 模式，每个状态独立处理器 |
| **记忆系统** | Dream 长期记忆 + Consolidator 上下文压缩 + NANOBOT.md |
| **三入口** | V3 CLI / V2 Spring Boot / V1 独立，共享核心引擎 |

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
| `/help` | 列出所有命令（彩色格式化） |
| `/history` | 查看输入历史 |
| `!!` | 重复上一条命令 |
| `!N` | 重复第 N 条历史命令 |
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

## 配置

所有配置遵循统一优先级链：`环境变量 > workspace/.nanobot/ > ~/.nanobot/ > classpath 默认`

**API Key**（三选一）：

```bash
export DEEPSEEK_API_KEY=sk-xxx              # ① 环境变量（推荐）
# 或
mkdir ~/.nanobot && echo "providers: ..."   # ② ~/.nanobot/secret.yaml
# 或编辑 config.yaml 的 apiKey 字段         # ③ 项目本地
```

**config.yaml**（业务配置）：

```yaml
agents:
  defaults:
    model: "deepseek-chat"
    maxTokens: 8192
    temperature: 0.7

providers:
  deepseek:
    apiKey: ""        # 留空，走环境变量或 secret.yaml
    apiBase: "https://api.deepseek.com"

tools:
  exec:
    enable: true
    timeout: 60       # 前台命令超时；后台服务用 background: true
  web:
    enable: true
```

`application.yml` 只管 Spring Boot 层（端口、日志），nanobot 业务配置全在 `config.yaml`。

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

| 文档 | 说明 |
|------|------|
| `ARCHITECTURE_AND_LEARNING_ROADMAP.md` | 完整架构 + AI 概念演进 + 学习路线 |
| `docs/deployment.md` | 启动、部署、配置架构 |
| `docs/features.md` | 权限控制 + CLI 交互模块 |
| `docs/线上问题总结.md` | 19 个历史 bug 分析 + 修复 |
| `NANOBOT.md` | 项目编码约定（AI 自动加载） |

### 常见问题

**编译错误 "无效的目标发行版: 17"**
```bash
export JAVA_HOME="D:/devSoftWare/jdk17/jdk-17.0.19+10"
export PATH="$JAVA_HOME/bin:$PATH"
```

**SSE 流式超时** — Spring MVC 异步超时已配 300s，若仍有问题检查 `application.yml` 中 `spring.mvc.async.request-timeout`。

**CMD 中文输入乱码** — 已修复（`Charset.defaultCharset()` 自动匹配控制台编码）。若仍出现，手动 `chcp 65001` 切 UTF-8 代码页。

### 前端布局（Web 模式）

- 左侧 260px 侧边栏：新聊天 + 历史会话列表 + 清空
- 右侧主面板：标题栏 + 消息画布 (max-width 768px) + 底部固定输入栏
- 多行 textarea，Enter 发送 / Shift+Enter 换行

---

## 本项目的构建方式

这个项目**全程使用 Claude Code 构建**——从架构设计到代码实现到文档编写。它是 Agent-Driven Development 的实践产物。

> 详见 [ARCHITECTURE_AND_LEARNING_ROADMAP.md § 三、Claude Code 实战指南](ARCHITECTURE_AND_LEARNING_ROADMAP.md)

---

## License

MIT — 自由使用、修改、分发。
