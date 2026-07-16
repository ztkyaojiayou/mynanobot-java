# Nanobot 启动与部署

---

## 环境准备

- JDK 17+（项目使用 `D:/devSoftWare/jdk17/jdk-17.0.19+10`）
- Maven 3.6+
- Git Bash（Windows 下执行脚本）

```bash
# 确认环境
java -version   # openjdk 17.0.19
mvn -v          # Apache Maven 3.x
```

---

## 一、V3 CLI 模式（类 Claude Code）

在任何目录下直接对话，自动取当前目录为工作区。

### 安装

```bash
# 把项目目录加入 PATH（写入 ~/.bashrc 或 ~/.bash_profile）
export PATH="/d/IdeaProjects/个人项目/ai-vibe-coding/nanobot-java/scripts:$PATH"
```

### 使用

```bash
# 进入工作目录，直接对话
cd /my-project
nanobot

# 指定工作区
nanobot -w /another-project

# 输出示例：
# ╔══════════════════════════════════╗
# ║       my-nanobot CLI 模式       ║
# ║  基于 Java Agent 框架的 AI 助手  ║
# ╚══════════════════════════════════╝
# > 帮我分析当前项目
# > /exit
```

### 命令

| 命令 | 说明 |
|------|------|
| `/exit` `/q` `/quit` | 退出 CLI |
| `/clear` | 清空上下文 |
| `/mode` | 查看/切换权限模式（plan \| default \| accept_edits \| bypass） |
| `/help` | 帮助 |

### 特点

- 无端口，开多少终端都不冲突
- 流式输出 + Markdown 渲染（代码块彩色、粗体、斜体）
- 交互式权限确认（危险操作提示 y/N）
- 零日志输出

---

## 二、V2 Web 服务模式

常驻 HTTP + WebSocket 服务，浏览器访问。

### 启动

```bash
# 默认端口 8080
./scripts/start.sh

# 自定义端口
./scripts/start.sh --port 9090

# 输出：
# ═══════════════════════════════════════
#   Nanobot 启动
#   模式: v2
#   端口: 8080
# ═══════════════════════════════════════
# [1/2] 编译中...
# [2/2] 启动中...
# Nanobot 已启动 (PID: 12345)
# 访问: http://localhost:8080
```

### 停止 / 重启

```bash
./scripts/stop.sh       # 停止
./restart.sh    # 重启
```

### API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/chat` | 同步聊天 |
| `POST` | `/api/chat/stream` | 流式聊天（SSE） |
| `GET` | `/api/sessions` | 会话列表 |
| `GET` | `/api/sessions/{key}` | 会话详情 |
| `DELETE` | `/api/sessions/{key}` | 删除会话 |
| `GET` | `/health` | 健康检查 |
| `WS` | `/ws` | WebSocket 聊天 |
| `GET` | `/` | 聊天 Web UI |
| `GET` | `/sessions.html` | 会话管理页 |

---

## 三、多实例部署

```bash
# 项目 A
./scripts/start.sh --port 8080

# 项目 B（不同端口）
./scripts/start.sh --port 8081

# CLI 模式（无端口，无限制）
nanobot              # 当前目录
nanobot -w /proj-a   # 指定目录
```

---

## 四、直接 Java 启动（不用脚本）

```bash
# 编译
JAVA_HOME=D:/devSoftWare/jdk17/jdk-17.0.19+10
mvn compile

# V2 Web 服务
mvn spring-boot:run -Dspring-boot.run.mainClass=com.nanobot.v2.NanobotApplication

# V3 CLI
java -cp "target/classes:$(mvn -q dependency:build-classpath -DincludeScope=compile -Dmdep.outputFile=/dev/stdout)" \
    com.nanobot.v3.NanobotCliApplication
```

---

## 五、配置

核心配置文件：`src/main/resources/config/config.yaml`

```yaml
agents:
  defaults:
    workspace: ".nanobot/workspace"   # 工作区
    model: "deepseek-chat"            # 模型
    contextWindowTokens: 200000       # 上下文窗口

providers:
  deepseek:
    apiKey: "sk-xxx"                  # API Key
    apiBase: "https://api.deepseek.com"
```

启动时可通过命令行覆盖：

```bash
# 覆盖工作区
--agents.defaults.workspace=/custom/path

# 覆盖端口
--server.port=9090
```
