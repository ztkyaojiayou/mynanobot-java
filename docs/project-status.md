# NanoCode 项目状态

> 最后更新: 2026-08-06 | 134 源文件 · ~29,000 行

---

## 一、总览

```
com.nanocode (134 files, ~29K lines)
├── v1/              独立模式 (5 files)
├── v2/              Spring Boot — HTTP/SSE/WS (9 files)
├── v3/              CLI 模式 — 类 Claude Code (4 files)
│   ├── cli/         CliChannel — 输入/消费/确认/渲染
│   └── tui/         TerminalStyle + MarkdownRenderer
├── bus/             消息总线 (3)
├── command/         命令系统 (7)
├── config/          配置加载 (2)
├── core/            核心引擎 + 8 状态处理器 (12)
├── tools/           工具系统 + 注解 (25)
├── security/        安全模块 — Guard/Rules/Hook (16)
├── memory/          记忆系统 (3)
├── providers/       LLM 提供商 (6)
├── mcp/             MCP 协议 (9)
├── hook/            ECA 声明式 Hook (9)
├── identity/        身份系统 (5)
├── skill/           技能系统 (5)
├── rules/           规则系统 (4)
├── session/         会话管理 (2)
└── subagent/        子 Agent (7)
```

---

## 二、核心引擎

| 组件 | 文件 | 说明 |
|------|------|------|
| AgentLoop | `core/AgentLoop.java` | 8 状态状态机，异步消息处理，Hook 注入 |
| AgentRunner | `core/AgentRunner.java` | LLM+Tool 循环，并行工具执行，最大 100 轮迭代 |
| TurnContext | `core/TurnContext.java` | 会话上下文，requestId 提取 |
| State 处理器 | `core/state/*.java` (8 files) | RESTORE→COMPACT→BUILD→THINK→ACT→SAVE→RESPOND→DONE |
| Consolidator | `memory/Consolidator.java` | 上下文压缩（token > 90% budget → LLM 总结） |
| Dream | `memory/Dream.java` | 长期记忆提取，JSON 格式校验 + 纯文本降级 |
| TaskStore | `core/TaskStore.java` | 任务追踪，JSON 持久化 |

---

## 三、工具系统（18 个）

### 编程工具
| 工具 | 特性 |
|------|------|
| `read_file` | offset+limit，默认 2000 行 |
| `write_file` | 自动创建父目录 |
| `edit_file` | 唯一性校验（oldText 必须恰好出现一次） |
| `exec` | **后台模式** `background=true`，Unix→Win 命令转换 (pwd→cd)，stderr 分离 |
| `grep` | include 过滤（如 `*.java`） |
| `glob` | 自动跳过 .git/node_modules/target 等 12 目录 |
| `list_dir` | 递归 + 文件大小 |

### Agent 工具
| 工具 | 说明 |
|------|------|
| `spawn` / `spawn_check` | 子 Agent 分解任务 |
| `ask_user` | LLM 向用户提问确认 |
| `task_create/list/update` | 任务分解追踪 |
| `use_skill` | 技能元工具 |

### 网络工具
| 工具 | 说明 |
|------|------|
| `web_search` | 4 providers: baidu_web(免费) / baidu(API) / brave / bing |
| `web_fetch` | URL 抓取，Jsoup 解析 |

### 辅助工具
| 工具 | 说明 |
|------|------|
| `get_current_time` | 精确日期时间 |
| 内置工具 | 9 个 @ToolDef 注解（加减乘除/Base64/随机数等） |

---

## 四、安全模块

```
PreToolUse Hook → Guards ×3 → RuleEngine（ASK 触发 CLI 确认框）→ PermissionMode → Execute
```

| 组件 | 说明 |
|------|------|
| PathGuard | 工作区隔离，toRealPath() 防符号链接，支持 restrictToWorkspace 开关 |
| CommandGuard | Shell 命令过滤，14 条默认 deny |
| NetworkGuard | SSRF 防护，CIDR 匹配，12 条默认 blocked |
| PermissionMode | PLAN / DEFAULT* / ACCEPT_EDITS / BYPASS |
| RuleEngine | deny > ask > allow 优先级链 |
| 交互确认 | CLI 彩色弹框 `[1]`绿 `[2]`青 `[3]`红，confirmLock 防并发穿插 |
| PreToolUseHook | 工具执行前钩子链 |

> DEFAULT 模式：有交互处理器时，非只读工具走确认流程（而非直接拒绝）

---

## 五、CLI 交互体验

| 特性 | 说明 |
|------|------|
| 终端自适应 | CMD → 纯文本 ASCII / Windows Terminal + Linux + macOS → 彩色双栏 |
| Banner | 双栏布局（≥80 列），模型+目录+会话+命令入口 |
| Thinking spinner | `\|/-\` ASCII 帧动画，首个 delta 到达时停止 |
| 工具着色 | exec=红、read=绿、write=橙、web=蓝、task=青、mcp=紫 |
| Token 用量 | 每轮结束统计，prompt 显示 `deepseek-chat 1.2Kt >` |
| 代码块高亮 | Java/JSON/YAML/Bash/SQL/XML 关键词着色 |
| 模式指示 | prompt 显示 `[PLAN]` / `[EDIT]` / `[BYPASS]` |
| Markdown 渲染 | 标题、粗体、斜体、行内代码、代码块、链接 |
| 历史回放 | `!!` 上条 / `!N` 第 N 条 / `/history` 列表 |

---

## 六、命令系统

| 命令 | 说明 |
|------|------|
| `/exit` `/q` | 退出 |
| `/clear` | 清空上下文 |
| `/help` | 彩色命令列表 |
| `/history` | 输入历史 |
| `!!` | 重复上条 |
| `!N` | 重复第 N 条 |
| `/mode plan\|default\|accept_edits\|bypass` | 切换权限模式 |
| `/plan approve` | 审批计划，开始执行 |
| `/init` | 分析项目生成 NANOCODE.md |
| `/resume` | 列出/恢复历史会话 |
| `Esc` | 中断流式回复 |

---

## 七、配置架构

所有配置统一优先级链：`环境变量 > CLI参数 > workspace/.nanocode/ > ~/.nanocode/ > classpath`

| 配置 | 加载链 |
|------|--------|
| config.yaml | workspace → ~ → cwd → classpath |
| secret.yaml (Key) | 环境变量 → workspace → ~ → 文件目录 → classpath |
| SOUL/IDENTITY/USER | workspace → ~ → classpath → 默认模板 |
| NANOCODE.md | workspace 根目录 |
| Rules/Skills | workspace/.nanocode/ → ~/.nanocode/ |
| Sessions/Memory | workspace/.nanocode/ (runtime) |

环境变量：`DEEPSEEK_API_KEY` / `OPENAI_API_KEY` / `NANOCODE_API_KEY`

---

## 八、通道

| 通道 | 入口 | 模式 |
|------|------|------|
| CLI | `nanocode` 命令 | 类 Claude Code，workspace 即工作目录 |
| HTTP REST | `POST /api/chat` | 同步等待（60s 超时） |
| SSE 流式 | `POST /api/chat/stream` | StreamResponseCallback |
| WebSocket | `ws://host/ws` | StreamResponseCallback |

---

## 九、前端（Web 模式）

| 页面 | 功能 |
|------|------|
| `index.html` | 聊天 UI + 流式对话 + 联网搜索 + 会话管理 |
| `sessions.html` | 会话列表 + 查看详情 + 删除 |

左侧 260px 侧边栏，右侧 768px 主聊天区，ChatGPT 风格。

---

## 十、部署

| 脚本 | 用途 |
|------|------|
| `scripts/nanocode` | CLI 全局命令（自动检测源码变更 + 重建） |
| `scripts/nanocode.bat` | Windows CMD 版 |
| `scripts/start.sh` | V2 Web 启动 |
| `scripts/stop.sh` | 停止 |
| `scripts/restart.sh` | 重启 |
| `scripts/build-dist.sh` | 一键打包分发包 |

---

## 十一、待办

| 优先级 | 功能 | 状态 |
|:--:|------|:--:|
| P1 | Token 精确计数 | 未实现 |
| P1 | Tab 补全 + ↑↓ 历史 | 未实现 |
| P2 | JLine arrow key 历史浏览 | 未实现 |
| P2 | 权限跨会话记忆 | 未实现 |
| P2 | diff 渲染（ANSI 红绿） | 未实现 |
| P2 | Git worktree 隔离 | 已评估 |
| P2 | LSP 集成 | 已评估，工作量大 |
| P3 | Telegram/Discord 适配 | 未实现 |
