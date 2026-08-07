# NanoCode 启动与部署

---

## 环境准备

- **JDK 17+**（Windows: `D:/devSoftWare/jdk17/jdk-17.0.19+10`）
- Maven 3.6+（仅开发/打包需要，使用者不需要）
- Git Bash / Windows Terminal（推荐）/ CMD（纯文本模式）

```bash
java -version   # openjdk 17.0.19+
```

---

## 一、开发者部署（源码环境）

### 1.1 安装

```bash
# 把 scripts 目录加入 PATH
export PATH="/d/IdeaProjects/个人项目/ai-vibe-coding/nanobot-java/scripts:$PATH"

# Windows CMD
set PATH=D:\IdeaProjects\个人项目\ai-vibe-coding\nanobot-java\scripts;%PATH%
```

### 1.2 V3 CLI 模式（类 Claude Code）

```bash
# 在任意目录直接对话
cd /my-project
nanocode

# 指定工作区
nanocode -w /another-project

# 恢复历史会话
nanocode --resume cli_1784097347013
```

**CLI 命令**：

| 命令 | 说明 |
|------|------|
| `/exit` `/q` | 退出 |
| `/clear` | 清上下文 |
| `/help` | 查看所有命令 |
| `/history` | 查看输入历史 |
| `!!` | 重复上一条命令 |
| `!N` | 重复第 N 条历史命令 |
| `/mode plan\|default\|accept_edits\|bypass` | 切换权限模式 |
| `/init` | 分析项目生成 NANOCODE.md |
| `/resume` | 列出/恢复历史会话 |

**终端支持**：

| 终端 | 渲染模式 | JLine |
|------|---------|-------|
| Linux / macOS | 彩色 ANSI + Unicode 框线 | ✅ |
| Windows Terminal | 彩色 ANSI + Unicode 框线 | ❌（跳过，避免 dumb 警告） |
| Git Bash | 彩色 ANSI + Unicode 框线 | ❌（同 WT） |
| CMD | 纯文本 ASCII，无颜色 | ❌ |

> Windows 全系跳过 JLine 原生库（避免 `Unable to create a system terminal` 警告），WT/Git Bash 原生支持 ANSI，CMD 自动降级为纯文本。

### 1.3 V2 Web 服务模式

```bash
# 启动
./scripts/start.sh --port 8080
# 停止/重启
./scripts/stop.sh
./scripts/restart.sh
```

Web 端为通用 AI 助手身份，CLI 端为编程 Agent 身份。系统提示词通过 `IdentityManager` 自动切换：检测 `spring.profiles.active=cli` → 硬编码编程 Agent prompt，非 CLI → 从 SOUL.md 文件加载。

---

## 二、分发给同事（无需源码、无需 Maven）

### 2.1 打包

```bash
bash scripts/build-dist.sh
```

生成 `dist/nanocode/`：

```
dist/nanocode/
├── nanocode.jar     25MB  (fat JAR，自包含所有依赖)
├── nanocode.bat          (Windows CMD 启动)
├── nanobot              (Linux/Mac/Git Bash 启动)
├── config.yaml          配置模板（API Key 为空）
└── README.txt           使用说明
```

### 2.2 同事部署

**只需 3 步**：

1. **JDK 17+**

2. **配 API Key**（三种方式任选）：
   ```bash
   # 方式一：环境变量（推荐，最方便）
   set DEEPSEEK_API_KEY=sk-your-key-here
   
   # 方式二：全局配置文件
   mkdir %USERPROFILE%\.nanocode
   echo providers: > %USERPROFILE%\.nanocode\secret.yaml
   echo   deepseek: >> %USERPROFILE%\.nanocode\secret.yaml
   echo     apiKey: "sk-your-key-here" >> %USERPROFILE%\.nanocode\secret.yaml
   
   # 方式三：项目本地配置
   # 在 dist/nanocode/config.yaml 中填 apiKey
   ```

3. **加 PATH**，把 `nanobot/` 目录加入系统 PATH

```bash
cd /any-project
nanocode
```

---

## 三、多实例

```bash
# CLI 模式（无端口，无限制）
nanocode              # 当前目录
nanocode -w /proj-a   # 指定目录

# Web 模式（需不同端口）
./scripts/start.sh --port 8080
./scripts/start.sh --port 8081
```

---

## 四、配置架构

### 4.1 统一加载链

所有配置遵循统一优先级（参考 Claude Code）：

```
高  CLI 参数 (--workspace, --model)
↑   环境变量 (DEEPSEEK_API_KEY)
↑   workspace/.nanocode/        ← 项目专属
↑   ~/.nanocode/                ← 用户全局
↑   classpath:config/           ← jar 出厂默认
低
```

### 4.2 config.yaml（业务配置）

```yaml
# 所在位置（优先级从高到低）：
#   ① {workspace}/.nanocode/config.yaml  项目专属
#   ② ~/.nanocode/config.yaml            用户全局
#   ③ ./config.yaml (cwd)              开发兼容
#   ④ classpath:config/config.yaml      jar 内置（兜底）

agents:
  defaults:
    workspace: "."
    model: "deepseek-chat"
    maxTurns: 100

providers:
  deepseek:
    apiKey: ""          # 留空，走 secret.yaml 或环境变量
    apiBase: "https://api.deepseek.com"

tools:
  exec:
    enable: true
    timeout: 60
  web:
    enable: true
```

### 4.3 secret.yaml（密钥，不入 git）

```yaml
# 查找链：环境变量 > workspace/.nanocode/ > ~/.nanocode/ > config同目录 > classpath
providers:
  deepseek:
    apiKey: "sk-xxx"   # 或设 DEEPSEEK_API_KEY 环境变量
```

环境变量支持：`DEEPSEEK_API_KEY` / `OPENAI_API_KEY` / `NANOCODE_API_KEY`

### 4.4 身份文件

```
SOUL.md / IDENTITY.md / USER.md
  加载链：workspace/.nanocode/ > ~/.nanocode/ > classpath:config/ > 默认模板
  CLI 模式硬编码编程 Agent prompt，不依赖文件
```

### 4.5 application.yml（仅 Spring Boot）

`application.yml` 只管 Spring Boot 层（端口、日志、MVC 超时），不包含任何 nanobot 业务配置。所有业务配置在 `config.yaml`。

### 4.6 运行时数据

| 数据 | 存储位置 |
|------|---------|
| 会话历史 | `{workspace}/.nanocode/sessions/` |
| 长期记忆 | `{workspace}/.nanocode/memory/MEMORY.md` |
| Hook 配置 | `{workspace}/.nanocode/hooks/`（或 config.yaml 中配置） |
| Skills | `{workspace}/.nanocode/skills/` |
| Rules | `{workspace}/.nanocode/rules/` + `{workspace}/NANOCODE.md` |

---

## 五、配置对比：开发 vs 分发

| | 开发环境 | 分发环境 |
|------|---------|------|
| API Key | `~/.nanocode/secret.yaml` 或环境变量 | 环境变量 `DEEPSEEK_API_KEY` |
| config.yaml | cwd 或 classpath | classpath 内置 |
| 身份 | classpath SOUL.md | classpath 内置 |
| terminal | 自动检测降级 | 自动检测降级 |
| workspace | `--workspace` 或启动目录 | `--workspace` 或启动目录 |
