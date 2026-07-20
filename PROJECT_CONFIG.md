# Nanobot-Java 项目配置文档

> 最后更新: 2026-07-20

---

## 1. 环境配置

### 1.1 Java 环境
- **JDK 版本**: Java 17 (LTS)
- **JDK 路径**: `D:\devSoftWare\jdk17\jdk-17.0.19+10`
- **编译命令**:
  ```bash
  export JAVA_HOME="D:/devSoftWare/jdk17/jdk-17.0.19+10"
  export PATH="$JAVA_HOME/bin:$PATH"
  mvn compile
  ```

### 1.2 Maven 配置
- **Maven**: 项目自带 Maven Wrapper 或使用系统 Maven
- **Maven 版本**: 3.9+

---

## 2. 项目路径

### 2.1 工作目录
- **主项目目录**: `D:\IdeaProjects\个人项目\ai-vibe-coding\nanobot-java`

### 2.2 关键文件位置

| 文件/目录 | 路径 |
|----------|------|
| V1 入口 | `src/main/java/com/nanobot/v1/Nanobot.java` |
| V2 Spring Boot 入口 | `src/main/java/com/nanobot/v2/NanobotApplication.java` |
| V3 CLI 入口（推荐） | `src/main/java/com/nanobot/v3/NanobotCliApplication.java` |
| 业务配置 | `src/main/resources/config/config.yaml` |
| 前端页面 | `src/main/resources/static/index.html` |
| 会话管理页 | `src/main/resources/static/sessions.html` |
| 项目文档 | `NANOBOT.md` |
| 架构文档 | `ARCHITECTURE_AND_LEARNING_ROADMAP.md` |
| 编译输出 | `target/` |

---

## 3. Git 配置

### 3.1 仓库信息
- **GitHub**: `github.com/ztkyaojiayou/mynanobot-java`
- **用户**: `ztkyaojiayou`
- **默认分支**: `main`

### 3.2 常用操作
```bash
# 编译通过后提交
mvn compile -q && git add -A && git commit -m "..." && git push
```

---

## 4. 运行命令

### 4.1 编译
```bash
export JAVA_HOME="D:/devSoftWare/jdk17/jdk-17.0.19+10"
export PATH="$JAVA_HOME/bin:$PATH"
mvn compile
```

### 4.2 打包
```bash
export JAVA_HOME="D:/devSoftWare/jdk17/jdk-17.0.19+10"
export PATH="$JAVA_HOME/bin:$PATH"
mvn package -DskipTests
```

### 4.3 V3 CLI 模式（推荐，类 Claude Code）
```bash
./scripts/nanobot                    # Mac/Linux
java -jar target/nanobot-cli.jar     # 或直接运行 JAR

# 常用选项
./scripts/nanobot --workspace /path/to/project   # 指定工作区
./scripts/nanobot --resume <sessionId>            # 恢复会话
```

### 4.4 V2 Spring Boot Web 模式
```bash
mvn spring-boot:run
# → http://localhost:8080 (聊天) | /sessions.html (会话管理)
```

### 4.5 启动后访问
- **HTTP API**: `http://localhost:8080/api/chat`
- **SSE 流式**: `http://localhost:8080/api/chat/stream`
- **WebSocket**: `ws://localhost:8080/ws`
- **前端页面**: `http://localhost:8080`

---

## 5. 项目特性

### 5.1 核心架构
- ✅ 8 状态 State 模式 AgentLoop
- ✅ AgentRunner LLM+Tool 循环（最多 100 轮迭代）
- ✅ MessageBus 异步消息总线
- ✅ ProviderFactory 策略工厂（DeepSeek / OpenAI）
- ✅ 17 个内置工具（文件读写、Shell、搜索、Agent 等）
- ✅ MCP 协议支持（stdio + HTTP）
- ✅ 4 级权限管道（Hook → Guard → Rules → Mode）
- ✅ Plan Mode（`/plan` → `/plan approve`）
- ✅ CLI 交互确认（`[y/N]` + Esc 中断流式）

### 5.2 三版本入口
| 版本 | 入口 | 说明 |
|------|------|------|
| V3 CLI | `NanobotCliApplication` | 类 Claude Code，JLine 终端 + Markdown 渲染 |
| V2 Web | `NanobotApplication` | Spring Boot，REST + SSE + WebSocket |
| V1 Legacy | `Nanobot` | 纯 Java，内嵌 HTTP 服务器 |

### 5.3 配置要点
- **LLM 模型**: `deepseek-chat`
- **API Base**: `https://api.deepseek.com`
- **API Key**: 环境变量 `DEEPSEEK_API_KEY`
- **搜索提供商**: `baidu_web`（免费，国内可访问，无需 API Key）
- **默认端口**: `8080`
- **工作区**: `.nanobot/workspace`

### 5.4 CLI 命令速查

| 命令 | 功能 |
|------|------|
| `/help` | 列出所有命令 |
| `/init` | 分析项目生成 NANOBOT.md |
| `/plan`（或 `/mode plan`） | 进入规划模式 |
| `/plan approve` | 审批计划，执行模式 |
| `/mode default` | 默认权限模式 |
| `/mode accept_edits` | 编辑放行 |
| `/mode bypass` | 全部放行 |
| `/resume` | 列出/恢复历史会话 |
| `/clear` | 清空当前上下文 |
| `/exit`（`/q`） | 退出 |
| `Esc` | 中断当前流式回复 |

---

## 6. 前端布局

### 6.1 当前设计（ChatGPT 风格）
- 左侧 260px 窄侧边栏：新聊天按钮 + 历史会话列表 + 清空按钮
- 右侧主聊天面板：标题栏 + 消息画布（max-width 768px 居中窄栏）+ 底部固定输入栏
- Bot 消息纯白底无气泡，hover 显示操作按钮（复制/点赞/重新生成）
- 多行 textarea 输入，Enter 发送 / Shift+Enter 换行

---

## 7. 常见问题

### 7.1 编译错误 - 无效的目标发行版
**问题**: `无效的目标发行版: 17`

**原因**: JAVA_HOME 指向了非 JDK 17 的版本

**解决方案**:
```bash
export JAVA_HOME="D:/devSoftWare/jdk17/jdk-17.0.19+10"
export PATH="$JAVA_HOME/bin:$PATH"
```

### 7.2 前端无响应
**检查项**:
1. 服务是否正常启动在 `http://localhost:8080`
2. 浏览器控制台是否有 JS 错误
3. WebSocket 连接状态
4. 后端日志 `🚀 [DO-RUN]` → `🤖 [LLM-CALL]` 链路是否完整

### 7.3 SSE 流式超时
- Spring MVC 异步超时默认 30s，已配置为 300s（匹配 SSE 5 分钟超时）
- 多轮对话超时问题已在 `线上问题总结.md` 记录完整根因和修复

---

## 8. 相关文档

| 文档 | 说明 |
|------|------|
| `README.md` | 项目概述 + 快速开始 |
| `NANOBOT.md` | 编码约定 + 关键设计决策 |
| `ARCHITECTURE_AND_LEARNING_ROADMAP.md` | 完整架构说明 |
| `线上问题总结.md` | 历史 bug 分析 + 修复方案 |
| `docs/PROJECT_STATUS.md` | 文件清单 + 模块统计 |
| `docs/features.md` | 权限模块文档 |
| `.harness/changes/` | 18 个变更卡片（C-001 ~ C-018） |
