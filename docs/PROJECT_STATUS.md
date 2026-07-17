# Nanobot-Java 项目状态

> 最后更新: 2026-07-17 | 117 源文件 · ~26,000 行

---

## 一、总览

```
com.nanobot (117 files, 26K lines)
├── v1/        非SpringBoot版 (5 files)
├── v2/        Spring Boot版 — HTTP/SSE/WS (8 files)
├── v3/        CLI版 — 类Claude Code (3 files)
├── bus/       消息总线 (3)
├── command/   命令系统 (7)
├── config/    配置 (2)
├── core/      核心引擎 (17)
├── tools/     工具系统 (23)
├── security/  安全模块 (16)
├── memory/    记忆系统 (4)
├── providers/ LLM提供商 (4)
├── mcp/       MCP协议 (8)
├── identity/  身份系统 (5)
├── skill/     技能系统 (5)
├── rules/     规则系统 (4)
├── session/   会话管理 (1)
├── cron/      定时任务 (1)
└── hooks/     钩子系统 (in core/)
```

---

## 二、核心引擎

| 组件 | 文件 | 说明 |
|------|------|------|
| AgentLoop | `core/AgentLoop.java` | 8状态状态机，异步消息处理，NANOBOT.md 自动加载 |
| AgentRunner | `core/AgentRunner.java` | LLM 调用循环，工具失败降级（连续3次→强制无工具） |
| TurnContext | `core/TurnContext.java` | 会话上下文 |
| TaskStore | `core/TaskStore.java` | 任务追踪，JSON 持久化 |
| Consolidator | `memory/Consolidator.java` | 历史压缩（token>90%budget→LLM总结） |
| Dream | `memory/Dream.java` | 长期记忆巩固 |
| MemoryStore | `memory/MemoryStore.java` | 记忆文件管理（MEMORY.md） |

---

## 三、工具系统（16个）

### 编程工具
| 工具 | 文件 | 对标 |
|------|------|------|
| `read_file` | ReadFileTool | offset+limit，默认2000行 |
| `write_file` | WriteFileTool | 自动创建父目录 |
| `edit_file` | EditFileTool | **唯一性校验**（oldText必须出现恰好1次） |
| `exec` | ExecTool | **stderr分离**，默认120s/最大600s |
| `grep` | GrepTool | **include过滤**（如`*.java`） |
| `glob` | GlobTool | **自动跳过.git/node_modules/target**等12目录 |
| `list_dir` | ListDirTool | 递归+文件大小 |

### Agent工具
| 工具 | 说明 |
|------|------|
| `spawn` | 子Agent分解任务，4种能力（search/summary/code/calc） |
| `ask_user` | LLM向用户提问确认，CLI交互式 |
| `task_create/list/update` | 任务分解追踪，JSON持久化 |

### 网络工具
| 工具 | 说明 |
|------|------|
| `web_search` | 4 providers: duckduckgo/baidu_web(免费)/baidu(API)/brave |
| `web_fetch` | URL内容抓取，Jsoup解析 |

### 辅助工具
| 工具 | 说明 |
|------|------|
| `get_current_time` | 精确日期时间（对抗模型训练数据偏差） |
| `BuiltinTools` | 9个@ToolDef注解方法(加减乘除/Base64/随机数等) |

---

## 四、安全模块

```
PreToolUse Hook → Guards × 3 → RuleEngine → PermissionMode → Execute
```

| 组件 | 说明 |
|------|------|
| PathGuard | 工作区路径隔离，toRealPath()防符号链接绕过 |
| CommandGuard | Shell命令过滤(deny+allow，allow优先)，14条默认deny |
| NetworkGuard | SSRF防护，CIDR匹配，12条默认blocked |
| PermissionMode | PLAN / DEFAULT / ACCEPT_EDITS / BYPASS |
| RuleEngine | deny > ask > allow 优先级链 |
| InteractivePermissionHandler | CLI `[y/N]` 交互确认 |

---

## 五、通道

| 通道 | 入口 | 模式 |
|------|------|------|
| HTTP REST | `POST /api/chat` | 异步阻塞等待 |
| SSE 流式 | `POST /api/chat/stream` | StreamResponseCallback |
| WebSocket | `ws://host/ws` | StreamResponseCallback |
| CLI | `nanobot` 命令 | 类Claude Code，当前目录即工作区 |

---

## 六、命令系统（CLI）

| 命令 | 说明 |
|------|------|
| `/exit` `/q` | 优雅关闭 |
| `/clear` | 清空上下文 |
| `/mode plan\|default\|accept_edits\|bypass` | 实时切换权限模式 |
| `/init` | 分析项目生成 NANOBOT.md（混合模式：Java收集元数据+LLM生成） |
| `/help` | 帮助 |

---

## 七、前端

| 页面 | 功能 |
|------|------|
| `index.html` | 聊天 UI + 流式对话 + 🔍联网查 + 会话管理入口 |
| `sessions.html` | 会话列表 + 弹窗查看详情 + 删除 |

---

## 八、部署

| 脚本 | 用途 |
|------|------|
| `scripts/nanobot` | CLI全局命令（类claude） |
| `scripts/start.sh` | V2 Web服务启动 |
| `scripts/stop.sh` | 停止 |
| `scripts/restart.sh` | 重启 |

```bash
# V3 CLI（类 Claude Code）
nanobot                       # 当前目录
nanobot -w /path/to/project   # 指定工作区

# V2 Web
./scripts/start.sh --port 8080
```

---

## 九、待办

| 优先级 | 功能 | 状态 |
|:--:|------|:--:|
| P0 | 搜索功能修复（baidu_web改Jsoup + duckduckgo备用） | ✅ 已完成 |
| P1 | Task追踪持久化 | ✅ 已完成 |
| P1 | NANOBOT.md init + 自动加载 | ✅ 已完成 |
| P1 | Token精确计数 | 未实现 |
| P2 | Worktree Git隔离 | 已评估，需Git |
| P2 | LSP集成 | 已评估，工作量大 |
| P3 | Telegram/Discord适配器 | 未实现 |