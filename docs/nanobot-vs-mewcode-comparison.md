# Nanobot-Java vs MewCode-Java 对比分析

> nanobot-java: 通用 AI Agent 框架，对标港大 nanobot
> mewcode-java: 编程专用 Agent，对标 Claude Code CLI

---

## 一、概览

| 维度 | nanobot-java | mewcode-java |
|------|:--:|:--:|
| 定位 | 通用 AI 助手 | 编程助手 (Claude Code 复刻) |
| 文件数 | 101 | 103 |
| 代码量 | ~24,400 行 | ~17,000 行 |
| 交互方式 | HTTP/SSE/WS + CLI | CLI (TUI 终端界面) |
| 版本 | 3 个 (v1/v2/v3) | 1 个 |
| 作者 | 港大开源 + 自扩展 | 小林coding |

---

## 二、功能对比

### 🔧 工具系统

| 功能 | nanobot | mewcode | 说明 |
|------|:--:|:--:|------|
| 文件读写 | ✅ | ✅ | 双方都有 |
| Shell 执行 | ✅ exec | ✅ BashTool | |
| 文件编辑 | ✅ edit_file | ✅ EditFileTool | |
| Web 搜索 | ✅ | ❌ | nanobot 独有 |
| Web 抓取 | ✅ | ❌ | nanobot 独有 |
| 时间获取 | ✅ | ❌ | nanobot 独有 |
| 子 Agent (spawn) | ✅ | ✅ AgentTool | 双方都有 |
| Worktree 隔离 | ❌ | ✅ EnterWorktreeTool | mewcode 独有 |
| 退出 Plan 模式 | ❌ | ✅ ExitPlanModeTool | mewcode 独有 |
| 工具搜索 | ❌ | ✅ ToolSearchTool | mewcode 独有 |
| @ToolDef 注解注册 | ✅ | ❌ | nanobot 独有 |
| BuiltinTools | ✅ | ❌ | nanobot 独有 |

### 🛡️ 安全/权限

| 功能 | nanobot | mewcode |
|------|:--:|:--:|
| 权限模式 | ✅ 4种(PLAN/DEFAULT/ACCEPT_EDITS/BYPASS) | ✅ 3种 |
| 路径守卫 | ✅ PathGuard | ❌ |
| 命令守卫 | ✅ CommandGuard(deny+allow) | ❌ |
| 网络守卫 | ✅ NetworkGuard(SSRF) | ❌ |
| 规则引擎 | ✅ deny>ask>allow | ❌ |
| PreToolUse Hook | ✅ | ✅ HookEngine |
| 交互式确认 | ✅ CLI [y/N] | ❌ |
| 子Agent权限过滤 | ❌ | ✅ ToolFilter |

### 🤖 子 Agent/多 Agent

| 功能 | nanobot | mewcode |
|------|:--:|:--:|
| 单子Agent spawn | ✅ AgentCoordinator + SimpleSubagent | ✅ AgentTool + SubAgentTaskManager |
| 多Agent协作 (Team) | ❌ | ✅ TeamManager + Coordinator + Tmux |
| 子Agent间通信 | ❌ 未启用 | ✅ FileMailBox + SharedTaskStore |
| 子Agent进度追踪 | ❌ | ✅ TeammateProgress |
| Git Worktree 隔离 | ❌ | ✅ |

### 📝 编码专用功能

| 功能 | nanobot | mewcode |
|------|:--:|:--:|
| Plan 模式 | ✅ 通过 PermissionMode | ✅ 专用 PlanFile + PlanModePrompt |
| 内容替换追踪 | ❌ | ✅ ContentReplacementState |
| 工具结果生命周期 | ❌ | ✅ ApplyResult + ReplacementRecordsIO |
| 工具结果预算管理 | ❌ | ✅ ToolResultBudget |
| Git Worktree | ❌ | ✅ 完整支持 |

### 🎨 用户界面

| 功能 | nanobot | mewcode |
|------|:--:|:--:|
| HTTP REST API | ✅ | ❌ |
| SSE 流式 | ✅ | ❌ |
| WebSocket | ✅ | ❌ |
| CLI 命令行 | ✅ V3 | ✅ MewCode.main() |
| TUI 终端界面 | ❌ | ✅ MarkdownRenderer + Styles |
| Web UI | ✅ index.html | ❌ |

### 🧠 记忆/存储

| 功能 | nanobot | mewcode |
|------|:--:|:--:|
| 会话持久化 | ✅ history.jsonl | ✅ FileHistory |
| 长期记忆 | ✅ MemoryStore + Dream | ✅ HistoryStore |
| 对话压缩 | ✅ Consolidator | ✅ ContextCompactor + RecoveryState |
| 身份系统 | ✅ Soul/Identity/User | ❌ |

### 🔌 扩展性

| 功能 | nanobot | mewcode |
|------|:--:|:--:|
| MCP 协议 | ✅ 完整(8文件) | ✅ 基础(1文件) |
| Skill 系统 | ✅ | ✅ |
| Rule 系统 | ✅ | ❌ |
| Hook 系统 | ✅ 7钩子点 | ✅ HookEngine |
| Cron 定时 | ✅ | ❌ |
| 拦截器链 | ❌ | ❌ 双方都无 |

---

## 三、设计思路对比

### 架构哲学

| 维度 | nanobot | mewcode |
|------|------|------|
| **消息模型** | 异步消息总线 (MessageBus + AgentLoop) | 同步直接调用 (Agent.run()) |
| **状态管理** | 8 状态状态机 (RESTORE→...→DONE) | 循环迭代 (while loop) |
| **通道架构** | 多通道适配器 (HTTP/WS/CLI) | 单一 CLI 入口 |
| **Spring Boot** | ✅ 完全 Spring 管理 | ❌ 纯 Java 手动装配 |
| **配置管理** | YAML + Spring Config | Java Config 类 |
| **LLM 调用** | Provider 抽象层 (DeepSeek/OpenAI) | Client 抽象层 (Anthropic/OpenAI/OpenAiCompat) |

### 核心循环差异

```
nanobot:                          mewcode:
                                  
MessageBus.publishInbound()       Agent.run(task)
     │                                 │
     ▼                                 ▼
AgentLoop.runLoop()                Plan 模式: 写 plan → 确认
     │                                 │
     ▼                                 ▼
processStates()                    循环: LLM → tool_calls → 执行 → 结果
  RESTORE → COMPACT →                   │
  COMMAND → BUILD →                     ▼
  RUN → SAVE → RESPOND             返回最终结果
```

### 工具执行差异

```
nanobot:                          mewcode:
                                  
ToolRegistry.execute()            ToolRegistry.execute()
  → PermissionManager.check()       → PermissionChecker.check()
  → Guards (3层)                    → (无 guards)
  → Rules                           → (无 rules)
  → Mode                            → Mode
  → tool.execute()                  → tool.execute()
```

---

## 四、架构对比

```
nanobot-java                           mewcode-java
═══════════════                        ═══════════════

┌──────────────┐                       ┌──────────────┐
│   Channels   │  HTTP/WS/CLI          │     TUI      │  终端界面
└──────┬───────┘                       └──────┬───────┘
       │                                      │
┌──────┴───────┐                       ┌──────┴───────┐
│  MessageBus  │  异步队列              │    Agent     │  同步调用
└──────┬───────┘                       └──────┬───────┘
       │                                      │
┌──────┴───────┐                       ┌──────┴───────┐
│  AgentLoop   │  状态机               │  Agent.run() │  循环迭代
└──────┬───────┘                       └──────┬───────┘
       │                                      │
┌──────┴───────┐                       ┌──────┴───────┐
│ ToolRegistry │  工具                 │ ToolRegistry │  工具
│ + Permission │  权限                 │ + Permission │  权限
│ + Guards     │  守卫                 │ + HookEngine │  钩子
│ + Rules      │  规则                 └──────┬───────┘
└──────┬───────┘                              │
       │                           ┌──────────┴──────────┐
┌──────┴───────┐                   │                      │
│  Memory      │  记忆             │  Teams (多Agent)     │  独有
│  + Sessions  │  会话             │  Worktree (Git隔离)  │  独有
│  + Dream     │  长期             │  Plan (规划模式)     │  独有
│  + Identity  │  身份             │  TUI (终端界面)      │  独有
└──────────────┘                   └─────────────────────┘
```

---

## 五、总结清单

### mewcode 有而 nanobot 没有

| 功能 | 优先级 | 说明 |
|------|:--:|------|
| TUI 终端界面 (Markdown渲染) | 🔴 高 | CLI 体验质的提升 |
| Git Worktree 隔离 | 🟡 中 | 编码 Agent 核心需求 |
| Plan 模式专用实现 | 🟡 中 | PlanFile + PlanModePrompt |
| Teams 多Agent协作 | 🟡 中 | FileMailBox + Tmux + Coordinator |
| 子Agent 权限过滤 (ToolFilter) | 🟢 低 | nanobot 说了暂不需要 |
| ContentReplacementState | 🟢 低 | 编码场景的结构化编辑追踪 |
| ToolResultBudget | 🟢 低 | 工具结果 token 预算 |

### nanobot 有而 mewcode 没有

| 功能 | 说明 |
|------|------|
| 完整安全模块 (Guard×3 + RuleEngine) | mewcode 只有基础 Mode |
| 多通道 (HTTP/SSE/WS + CLI) | mewcode 只有 CLI |
| MCP 完整协议栈 (8文件) | mewcode 只有 1 文件 |
| @ToolDef 注解自动注册 | mewcode 手动注册 |
| 身份系统 (Soul/Identity/User) | |
| 记忆压缩 (Consolidator) | mewcode 有 ContextCompactor |
| Spring Boot 全 DI | mewcode 手动装配 |
| 三版本架构 (v1/v2/v3) | |

### 双方都值得向对方学习

| nanobot → mewcode | mewcode → nanobot |
|------|------|
| 安全模块 (Guards + Rules) | TUI 终端界面 |
| @ToolDef 注解扫描 | Git Worktree 隔离 |
| SSE/WS 多通道 | Teams 多Agent协作 |
| MCP 完整支持 | Plan 模式专用实现 |
| Spring Boot DI | 子Agent ToolFilter |
