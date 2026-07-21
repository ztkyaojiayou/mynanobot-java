# Nanobot 启动与部署

---

## 环境准备

- **JDK 17+**（Windows: `D:/devSoftWare/jdk17/jdk-17.0.19+10`）
- Maven 3.6+（仅开发/打包需要，使用者不需要）
- Git Bash（Windows 下推荐，CMD 也支持）

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
nanobot

# 指定工作区
nanobot -w /another-project

# 恢复历史会话
nanobot --resume cli_cli-1784097347013
```

**CLI 命令**：

| 命令 | 说明 |
|------|------|
| `/exit` `/q` | 退出 |
| `/clear` | 清上下文 |
| `/mode plan\|default\|accept_edits\|bypass` | 切换权限模式 |
| `/init` | 分析项目生成 NANOBOT.md |
| `/resume` | 列出/恢复历史会话 |
| `/help` | 帮助 |

### 1.3 V2 Web 服务模式

```bash
# 启动
./scripts/start.sh --port 8080
# 停止/重启
./scripts/stop.sh
./scripts/restart.sh
```

---

## 二、分发给同事（无需源码、无需 Maven）

### 2.1 打包

```bash
bash scripts/build-dist.sh
```

生成 `dist/nanobot/`：

```
dist/nanobot/
├── nanobot.jar     25MB  (fat JAR，自包含所有依赖)
├── nanobot.bat          (Windows CMD 启动)
├── nanobot              (Linux/Mac/Git Bash 启动)
├── config.yaml          配置模板
└── README.txt           使用说明
```

### 2.2 同事部署

**只需 3 步**：

1. **JDK 17+**

2. **填 API Key** — 编辑 `config.yaml`：
   ```yaml
   providers:
     deepseek:
       apiKey: "sk-your-key-here"
   ```

3. **加 PATH**，把 `nanobot/` 目录加入系统 PATH

```bash
# 使用
cd /any-project
nanobot
```

---

## 三、多实例

```bash
# CLI 模式（无端口，无限制）
nanobot              # 当前目录
nanobot -w /proj-a   # 指定目录

# Web 模式（需不同端口）
./scripts/start.sh --port 8080
./scripts/start.sh --port 8081
```

---

## 四、配置

```yaml
# config.yaml（或 ~/.nanobot/config.yaml）
agents:
  defaults:
    workspace: ".nanobot/"
    model: "deepseek-chat"
    # 预算控制（0=不限）
    maxTurns: 0
    maxCost: 0

providers:
  deepseek:
    apiKey: "sk-xxx"
    apiBase: "https://api.deepseek.com"
```

命令行覆盖（优先级最高）：

```bash
nanobot -w /custom/
nanobot --maxTurns=20 --maxCost=0.01
```
