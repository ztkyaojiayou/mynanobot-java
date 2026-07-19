# Nanobot-Java 项目学习手册

---

## 一、项目概述

Nanobot-Java 是基于香港大学开源的 Nanobot（mini 版 OpenClaw）项目进行的 Java 重写。这是一个轻量级的 AI Agent 框架，遵循 **"Core stays small; extend at the edges"**（核心保持精简，通过边缘扩展）的设计理念。

**项目位置**：https://github.com/ztkyaojiayou/mynanobot-java

---

## 二、AI 开发演进与核心概念

> **阅读目标**：理解 AI 编程领域的发展脉络和关键术语，建立全局认知框架。

### 2.1 AI Native — 一个时代的范式转移

**AI Native** 指从设计之初就以 AI 为核心构建的应用，而非在现有产品上"嫁接" AI 功能。

| | AI-Wrapped（AI 包装） | AI-Native（AI 原生） |
|------|------|------|
| **AI 角色** | 辅助功能，可选模块 | 核心引擎，不可剥离 |
| **架构** | 传统三层 + LLM API 调用 | 以 Agent Loop 为中心的状态机 |
| **交互** | 表单/按钮为主，偶尔用 AI | 自然语言是主交互界面 |
| **开发方式** | 人写代码，AI 辅助补全 | AI 自主编码，人做 Code Review |
| **示例** | Notion AI、钉钉 AI 助手 | Cursor、Claude Code、Devin、本项目 |

**判断标准**：如果把 AI 组件从系统中移除，产品是否还能正常工作？能 → AI-Wrapped；不能 → AI-Native。

本项目的目标就是构建一个 AI-Native 的轻量级 Agent 框架。

---

### 2.2 AI 编程模式的演进：Vibe Coding → Spec Coding → Agent-Driven

```
2022             2023-2024           2024-2025          2025+
─────────────────┬───────────────────┬───────────────────┬──────────→
  Copilot 补全    │  Vibe Coding      │  Spec Coding      │ Agent-Driven
  (行级辅助)      │  (对话式编程)     │  (规约驱动)       │  (Agent 自主)
```

#### Vibe Coding（对话式编程）

由 Andrej Karpathy 在 2025 年初提出：开发者用自然语言描述需求，AI 生成代码，人只管"vibe"（感觉）和验证结果。

```
用户: "帮我写一个四则运算计算器，Vue3 + TypeScript"
AI: 生成完整组件代码 → 用户运行 → 不满意 → "加个历史记录功能" → AI 修改
```

**特点**：
- ✅ 上手零门槛，会说话就能编程
- ✅ 原型验证极快，几分钟出一个 Demo
- ❌ 缺乏结构化约束，复杂项目难以维护
- ❌ AI 理解偏差会累积，迭代多轮后代码质量下降

#### Spec Coding（规约驱动编程）

Vibe Coding 的下一阶段演进。在写代码之前，先与 AI 合作产出一份详尽的**规格说明书**（Specification），明确边界条件、数据结构、错误处理等，再让 AI 按 Spec 实现。

```
用户 + AI 讨论需求 → 产出 Spec.md（接口定义、数据模型、测试用例、边界条件）
                  → AI 按 Spec 逐项实现
                  → 自动化验证 vs Spec
```

**特点**：
- ✅ 有规约约束，减少 AI 理解偏差
- ✅ Spec 本身就是文档，可维护性强
- ✅ 支持"先出计划再执行"的工作流（本项目 Plan Mode 的灵感来源）
- ❌ 前期讨论成本较高

#### Agent-Driven（Agent 自主驱动）

Spec Coding 的再下一阶段。AI Agent 不再被动等待指令，而是**主动**探索、规划、执行、验证循环。

```
Agent 接到目标: "实现用户注销功能"
  → 自动探索项目结构 (list_dir / glob / read_file)
  → 自动制定 Plan (需要改哪些文件)
  → 自动逐个执行 (edit_file / write_file)
  → 自动验证 (mvn test → 修复 → 再验证)
  → 提交 PR
```

这正是本项目 Plan Mode + Agent Loop 的设计目标。

---

### 2.3 AI 工程的四大范式

```
┌─────────────────────────────────────────────────────────┐
│                  AI 工程四大范式                         │
│                                                         │
│  ┌─────────────┐   ┌─────────────┐                      │
│  │  提示词工程  │──▶│  上下文工程  │    ← 都在"对话窗口"内   │
│  │  (Prompt)   │   │  (Context)  │                      │
│  └─────────────┘   └─────────────┘                      │
│         │                  │                            │
│         ▼                  ▼                            │
│  ┌─────────────┐   ┌─────────────┐                      │
│  │  Harness工程 │──▶│  Loop 工程   │    ← 跳出"对话窗口"   │
│  │  (脚手架)    │   │  (自闭环)   │                      │
│  └─────────────┘   └─────────────┘                      │
└─────────────────────────────────────────────────────────┘
```

#### 第一层：提示词工程（Prompt Engineering）

**时间**：2022-2023 年，ChatGPT 时代早期。

**核心问题**：如何"问对问题"才能让 LLM 输出高质量结果？

| 技术 | 说明 | 示例 |
|------|------|------|
| **Zero-shot** | 直接提问，不给示例 | "翻译成英文：你好" |
| **Few-shot** | 给几个示例再提问 | "输入:你好→Hello；输入:再见→?" |
| **Chain of Thought** | 要求逐步推理 | "一步步思考后再回答" |
| **Role Prompting** | 设定角色 | "你是一个资深 Java 架构师..." |
| **Structured Output** | 约束输出格式 | "用 JSON 格式返回" |

**局限**：提示词再精妙，LLM 的知识也被训练数据的截止日期锁死，无法获取实时信息、无法执行操作。

#### 第二层：上下文工程（Context Engineering）

**时间**：2023-2024 年，RAG 和 Agent 兴起。

**核心问题**：如何把正确的信息在正确的时间喂给 LLM？

| 技术 | 说明 | 本项目的实现 |
|------|------|-------------|
| **RAG（检索增强生成）** | 从知识库检索相关文档注入上下文 | `web_search` / `web_fetch` |
| **System Prompt 设计** | 系统级指令控制 Agent 行为 | `BuildState` → SOUL + NANOBOT.md + Rules + Plan Mode |
| **Memory（记忆系统）** | 跨会话的信息持久化 | `MemoryStore` + `Dream`（长期记忆） |
| **会话历史压缩** | 上下文窗口有限，需要压缩旧消息 | `CompactState` + `Consolidator` |
| **项目记忆注入** | NANOBOT.md / CLAUDE.md 提供项目级上下文 | `/init` 命令 + `BuildState` 自动加载 |

**核心理念**：LLM 的能力 = 模型本身的能力 × 你喂给它的上下文质量。上下文工程的目标是把"对的上下文"在"对的时间"塞进有限的 Context Window。

#### 第三层：Harness 工程（脚手架工程）

**时间**：2024-2025 年，Agent 基础设施爆发。

**核心问题**：如何为 LLM 构建一个"可以做事"的运行时环境？

| 技术 | 说明 | 本项目的实现 |
|------|------|-------------|
| **Tool Use / Function Calling** | LLM 调用外部工具 | `Tool` 接口 + 17 个内置工具 |
| **MCP（Model Context Protocol）** | 标准化工具接入协议 | `MCPServer` → `MCPToolWrapper` |
| **Agent Loop** | LLM 调用的控制循环 | `AgentLoop` 状态机 + `AgentRunner` |
| **Safety Guards** | 工具执行前的安全检查 | `PathGuard` / `CommandGuard` / `NetworkGuard` |
| **Permission System** | 工具调用的权限管控 | `PermissionManager`（4 种模式） |
| **Multi-Channel** | 多入口接入（CLI/HTTP/WS） | V1 ChannelServer / V2 Spring Boot / V3 CliChannel |
| **Sandbox / Workspace** | 执行环境隔离 | 工作区路径约束 |

**核心理念**：LLM 是一个"大脑"，但不能"动手"。Harness 工程就是给这个大脑装上"手"（工具）、"眼睛"（检索）、"安全帽"（权限）、"方向盘"（Agent Loop）。

#### 第四层：Loop 工程（自闭环工程）★ 最新

**时间**：2025 年开始兴起。

**核心问题**：如何让 Agent 拥有自我纠正和自我改进的能力，形成"执行→验证→修复"的闭环？

```
传统 Agent (Harness 级):
  LLM 输出 → 执行 → 结束

Loop 工程 (Loop 级):
  LLM 输出 → 执行 → 观察结果 → 失败？→ 分析原因 → 重新执行
       ↑_______________________________________________|
```

| 模式 | 说明 | 示例 |
|------|------|------|
| **Self-Debugging** | Agent 运行自己的代码，捕获错误，自我修复 | "跑一下测试 → 失败了 → 分析 stack trace → 修改代码 → 再跑" |
| **Reflection** | Agent 定期反思自己的输出质量 | "我上次的回答遗漏了边界条件，这次补上" |
| **Adversarial Review** | 多个 Agent 互相审查 | Claude Code 的 /code-review 多维度交叉验证 |
| **Plan → Execute → Verify** | 三步闭环 | 本项目的 `/mode plan` + 执行 + 验证 |
| **Multi-Agent Loop** | 子 Agent 并行执行，主 Agent 汇总 | Subagent + SpawnTool |

**本项目的 Loop 工程体现**：
- `AgentRunner.runInternal()`：递归循环，工具失败重试 3 次
- Plan Mode：`/plan` → 探索 → 出计划 → `/plan approve` → 执行
- Task 工具：`task_create` → 分步执行 → `task_update` 标记完成
- 子 Agent 系统：`spawn` → 分发任务 → 收集结果 → 汇总

---

### 2.4 关键名词速查表

| 术语 | 英文 | 一句话解释 |
|------|------|-----------|
| **AI 原生** | AI Native | 以 AI 为核心引擎构建的应用，非嫁接 |
| **对话式编程** | Vibe Coding | 自然语言描述需求，AI 生成代码 |
| **规约驱动编程** | Spec Coding | 先产 Spec 再让 AI 按规约实现 |
| **Agent 驱动** | Agent-Driven | AI 主动探索→规划→执行→验证 |
| **提示词工程** | Prompt Engineering | 设计有效提问以获得高质量输出 |
| **上下文工程** | Context Engineering | 管理喂给 LLM 的信息（RAG、System Prompt、Memory） |
| **脚手架工程** | Harness Engineering | 构建 Agent 运行时（工具、安全、循环控制） |
| **自闭环工程** | Loop Engineering | Agent 自我验证、纠错、改进的闭环 |
| **大语言模型** | LLM | 驱动 Agent 的核心 AI 模型 |
| **检索增强生成** | RAG | 从知识库检索信息增强 LLM 回答 |
| **思维链** | Chain of Thought | 让 LLM 逐步推理而非直接给答案 |
| **函数调用** | Function Calling | LLM 调用外部 API/工具的能力 |
| **MCP 协议** | Model Context Protocol | AI-工具通信的标准化协议 |
| **Agent 循环** | Agent Loop | LLM 调用的控制循环（感知→思考→行动） |
| **流式输出** | Streaming / SSE | LLM 逐 token 实时输出内容 |
| **Token** | Token | LLM 处理文本的最小单位（约 0.75 个英文单词） |
| **嵌入向量** | Embedding | 文本的数值化表示，用于语义搜索 |
| **上下文窗口** | Context Window | LLM 一次能处理的最大信息量 |
| **系统提示词** | System Prompt | 控制 Agent 行为的最高优先级指令 |
| **幻觉** | Hallucination | LLM 生成似是而非的虚假信息 |

---

## 三、Claude Code 实战指南

> **为什么要学 Claude Code？** 本项目全程使用 Claude Code 构建。Claude Code 是 Anthropic 官方出品的 AI 编程 Agent CLI 工具，是目前 Agent-Driven Development 的标杆产品。掌握它，你就能理解本项目的设计目标——用 Java 复刻一个类 Claude Code 的 Agent 框架。

### 3.1 Claude Code 是什么

Claude Code 是 Anthropic 推出的**终端原生 AI 编程助手**，运行在命令行中，不是一个 IDE 插件。

```
$ claude
> 帮我实现用户登录功能
  → AI 自动探索项目（列出文件、读取代码、搜索关键模式）
  → 制定计划（改哪些文件、加什么依赖、注意什么安全问题）
  → 逐步实现（创建文件、编辑代码、跑测试验证）
  → 提交 git commit
```

**与 Copilot / Cursor 的关键区别**：

| | GitHub Copilot | Cursor | Claude Code |
|------|------|------|------|
| **运行环境** | IDE 插件 | 独立 IDE | 终端 CLI |
| **交互方式** | Tab 补全 + 聊天面板 | 编辑器内联 + 聊天面板 | 纯命令行对话 |
| **自主性** | 低（需人触发补全） | 中（可对话式编辑） | 高（自主探索→规划→执行→验证） |
| **工具调用** | 受限 | 部分支持 | 完整（文件读写、Shell、Web、Git） |
| **项目理解** | 当前文件上下文 | 项目级索引 | 自主探索（glob/grep/read） |
| **权限模型** | 无 | 部分 | 4 级权限（Plan/Default/AcceptEdits/Bypass） |

---

### 3.2 安装与启动

```bash
# 安装 Claude Code（需要 Node.js 18+）
npm install -g @anthropic-ai/claude-code

# 在任意项目目录启动
cd /your/project
claude

# 首次使用需要登录 Anthropic 账号
claude login
```

启动后界面：

```
>                        ← 直接输入需求，AI 开始工作
```

**没有繁琐的配置**，Claude Code 自动检测项目结构。

---

### 3.3 核心命令速查

| 命令 | 功能 |
|------|------|
| `/help` | 列出所有可用命令 |
| `/clear` | 清空当前对话上下文 |
| `/compact` | 压缩对话历史（释放 token 预算） |
| `/init` | 分析项目生成 CLAUDE.md（项目记忆文件） |
| `/doctor` | 诊断环境问题 |
| `/login` / `/logout` | 账号管理 |
| `/status` | 查看当前会话状态 |
| `/add-dir` | 添加额外的工作目录 |
| `/cost` | 查看本次会话的 API 费用 |
| `/context` | 查看当前上下文使用情况 |
| `/review` | 代码审查当前改动 |
| `/security-review` | 安全审查当前改动 |
| `/pr-comment` | 将审查结果发布为 PR 评论 |
| `Ctrl+C` | 中断当前回复 |

---

### 3.4 核心工作流

#### 工作流 1：Vibe Coding（对话式编程）

最直接的使用方式：

```
> 帮我写一个 Spring Boot REST API，GET /users 返回用户列表

Claude: 先读项目结构 → 确认是 Spring Boot 项目
       → 创建 UserController.java
       → 跑 mvn test 验证 → 通过 ✅
```

#### 工作流 2：Plan Mode（先计划后执行）

这是 Claude Code 最特色的工作流：

```
> /plan
→ 进入规划模式（只读）

> 实现用户注销功能，包括 JWT 黑名单和 Session 失效

Claude (只读模式):
  1. 探索项目: glob **/*Auth*.java → 找到 AuthController, JwtUtil
  2. 阅读代码: read_file AuthController.java, JwtUtil.java
  3. 搜索相关: grep "token\|jwt\|logout" → 确认没有现有实现
  4. 出计划:
     ## 实现计划
     - JwtUtil.java: 新增 invalidate() + 内存黑名单
     - AuthController.java: 新增 POST /logout
     - SecurityConfig.java: 放行 /logout
     - 测试: AuthControllerTest
  → 等待审批

> 继续
Claude: 切换到执行模式 → 按计划逐步实现 → 跑测试 → 全部通过 ✅
```

**对比本项目的 Plan Mode**：完全相同的工作流，`/plan` → 探索 → 出计划 → `/plan approve` → 执行。

#### 工作流 3：代码审查

```
> /review
Claude: 分析 staged changes → 从多个维度审查:
  - 正确性: 有没有 bug？
  - 安全: SQL 注入、敏感信息泄露？
  - 性能: N+1 查询、不必要的循环？
  - 可维护性: 命名清晰？有无重复代码？
→ 输出审查报告 + 修复建议
```

#### 工作流 4：多轮迭代

```
> 帮我写一个四则运算计算器

Claude: 生成 Calculator.vue → 完整的加减乘除功能

> 加个历史记录功能

Claude: 读 Calculator.vue → 在现有代码上添加 history 列表

> 把历史记录存到 localStorage，页面刷新不丢失

Claude: 再加持久化 → 验证 → 完成
```

---

### 3.5 CLAUDE.md — 项目的 AI 记忆

这是 Claude Code 最核心的功能之一，也是本项目 `NANOBOT.md` + `/init` 的灵感来源。

运行 `/init` 后，Claude Code 自动探索项目并生成 `CLAUDE.md`：

```markdown
# nanobot-java

A Java 17 AI Agent framework built with Spring Boot 3.2.

## Build & Run
- `mvn compile` - compile
- `mvn test` - run tests
- `./scripts/nanobot` - launch CLI mode

## Architecture
- `core/AgentLoop.java` - state machine engine
- `core/AgentRunner.java` - LLM call loop
- `tools/` - 17 built-in tools
...
```

**每次对话开始时**，Claude Code 自动加载 `CLAUDE.md` 作为系统提示词。你可以手动编辑补充：
- 编码规范（"用 4 空格缩进""禁止使用 Lombok @Builder"）
- 项目约定（"数据库迁移用 Flyway，SQL 文件放 db/migration/"）
- 常用命令（"启动：./mvnw spring-boot:run"）

---

### 3.6 权限系统

Claude Code 的 4 级权限模式是本项目 `PermissionManager` + `PermissionMode` 的对标对象：

| 模式 | Claude Code | nanobot |
|------|------|------|
| **Plan（只读）** | `/plan` — 只能读文件，不能改 | `/mode plan` — 完全相同 |
| **Default（默认）** | 读放行，写需确认 | `/mode default` — 完全相同 |
| **Accept Edits** | 读+文件编辑放行，Shell 需确认 | `/mode accept_edits` — 完全相同 |
| **Bypass** | 全部放行 | `/mode bypass` — 完全相同 |

**交互确认**：Claude Code 弹出 `y/N` 确认，nanobot 用 `1/2/3` 数字选择（多了"之后都放行"选项）。

---

### 3.7 Claude Code 的架构思想

Claude Code 的架构直接启发了本项目的核心设计：

| Claude Code 特性 | 本项目的对应实现 |
|------|------|
| CLI 终端原生交互 | V3 `CliChannel` + JLine + Markdown 渲染 |
| Agent Loop（LLM 循环调用） | `AgentLoop` + `AgentRunner` |
| 文件读写工具 | `read_file`, `write_file`, `edit_file` |
| 搜索工具 | `glob`, `grep` |
| Shell 执行 | `exec` |
| Web 搜索 | `web_search`, `web_fetch` |
| Task 系统（任务追踪） | `TaskStore` + `task_create/list/update` |
| MCP 工具扩展 | `MCPManager` + `MCPToolWrapper` |
| `/init` → CLAUDE.md | `/init` → NANOBOT.md |
| `/plan` 规划模式 | `/mode plan` + `/plan approve` |
| Permission 权限模式 | `PermissionManager` + `PermissionMode` |
| Stream 流式输出 | `StreamResponseCallback` + SSE |
| Session 会话管理 | `SessionManager` + `sessions.html` |

---

### 3.8 实战建议

**1. 先 `/init` 再干活**：进入新项目第一件事就是 `/init`，让 Claude Code 理解项目。

**2. 善用 Plan Mode**：
```bash
/plan           # 进入规划
（描述需求）     # Claude 探索 + 出计划
（审查计划）     # 不满意就调整
/plan approve   # 满意后执行
```

**3. 定期 `/compact`**：对话长了之后 token 占用上升，`/compact` 压缩历史释放预算。

**4. 用 `/cost` 关注费用**：每次对话结束看看花了多少钱，养成习惯。

**5. 利用 `CLAUDE.md` 积累项目知识**：把每次踩坑的经验写进去，后续对话自动生效。

**6. 复杂任务分步拆解**：不要一次性描述过于复杂的需求，拆成 2-3 步逐步推进。

**7. 命令行 + IDE 配合**：Claude Code 做"设计+编码"的脏活，你在 IDE 里 Code Review 和微调。

---

### 3.9 Harness 规范体系 — Claude Code 的结构化使用方式

Vibe Coding 的问题在于"随意"——没有规格约束、没有变更追踪、没有质量门禁。**Harness** 体系是在 Claude Code 之上叠加一套轻量级开发规范，让 AI 编码从"随意"走向"可控"。

> 本项目的 `.harness/` 目录就是这套规范的完整实现。它是本项目全程使用 Claude Code 构建的核心方法论。

#### 目录结构

```
.harness/
├── agents/               # Agent 角色定义
│   └── owner.md          # Owner Agent — 总编排者（身份+职责+流水线+决策边界）
│
├── rules/                # 约束规则（AI 的"护栏"）
│   ├── SDD-TDD模式.md    # Spec → Test → Code 三层关系
│   ├── 工程结构.md        # 目录组织 + 包结构 + 命名约定
│   ├── 开发流程规范.md    # 6 阶段流水线 + 门禁 + 变更状态机
│   ├── 编码规范.md        # Java 命名/日志/异常/提交规范
│   └── 运行时可靠性.md    # 工具超时重试/消息持久/优雅关闭
│
├── skills/               # 可复用技能（6 阶段流水线的执行者）
│   ├── request-analysis/  # ① 需求分析 → 产出 change.md
│   ├── coding-skill/      # ② 编码实现（TDD）
│   ├── unit-test-write/   # ③ 边界/降级测试
│   ├── expert-reviewer/   # ④ 多维度评审
│   ├── unit-test-ci/      # ⑤ CI 门禁
│   └── deploy-verify/     # ⑥ 部署验证
│
├── changes/              # 变更追踪（Spec Coding 核心）
│   ├── _TEMPLATE/         # 模板（change + review + verify）
│   └── C-001~C-018/       # 18 张已完成的变更卡片（三件套）
│
└── wiki/                 # 项目知识库（按需加载）
    ├── 业务模型.md / 接口协议.md / 数据模型.md
    └── 架构决策.md        # 10 个 ADR（Java17/State模式/ProviderFactory/…）
```

#### 核心理念

**三重约束**：
- `rules/` — 约束边界（AI 不能做什么）
- `skills/` — 执行手段（AI 怎么一步步做）
- `changes/` — 可追溯记录（每一步留档）

**6 阶段流水线**：
```
人类意图
  → ① request-analysis   需求分析 → change.md（规格真相源）
  → ② coding-skill       编码实现（TDD: Red→Green→Refactor）
  → ③ unit-test-write    边界/降级测试（覆盖率 ≥80%）
  → ④ expert-reviewer    专家评审（0 个严重问题）
  → ⑤ unit-test-ci       CI 全量通过（mvn test 59/59）
  → ⑥ deploy-verify      部署冒烟验证
  → 交付
```

**变更状态机**：`draft → analyzing → coding → testing → reviewing → ci → verifying → done`

#### 在 Claude Code 中如何使用

```bash
# 1. 新建变更
mkdir -p .harness/changes/C-019-new-feature
cp .harness/changes/_TEMPLATE/* .harness/changes/C-019-new-feature/

# 2. 对 Claude Code 说
> 按 .harness 流程，帮我完成 C-019：给 AgentLoop 加个新状态
  → Claude Code 自动读取 owner.md（角色定位）+ rules/（约束）
  → 按 ①→⑥ 流水线逐步推进
  → 每阶段产出写入 .harness/changes/C-019/

# 3. 人类只需在关键 Gate 审批
> ① 需求卡 OK，继续 ②
> ④ 评审通过，继续 ⑤
```

#### 与 Plan Mode 的关系

| Harness 流水线 | nanobot Plan Mode |
|------|------|
| ① request-analysis | `/plan` + 描述需求 → AI 探索 + 出计划 |
| ② coding-skill | `/plan approve` → AI 按计划逐步实现 |
| ③~⑥ 验证 | 人工审查 + `mvn test` |

> **核心价值**：Harness 把"AI 辅助开发"从不可控的 Vibe Coding 升级为可追溯、可验证、可断点续传的 Spec Coding 工程实践。你不只是在和 AI 聊天，你是在用 AI 做软件工程。

---

### 3.10 Harness 各模块详解

#### Owner Agent（`agents/owner.md`）

Owner Agent 是整个 Harness 体系的总指挥。它定义了 AI 的角色定位、工作流水线和决策边界。

| 模块 | 说明 |
|------|------|
| **身份与使命** | "我是 mynanobot-java 的 Owner Agent，精通 Java、Spring Boot、AI Agent 框架" |
| **核心职责** | 需求理解、技能编排、约束守护、上下文管理、变更追踪、质量兜底、人机协同 |
| **工作流水线** | 6 阶段严格按序，不可跳步（见上文） |
| **决策边界** | 可自主决定（命名/重构/测试设计） vs 必须请示人类（新增依赖/改 API/改架构） |
| **上下文加载策略** | 按需加载（地图模式），禁止全量灌入；任何任务先读 CLAUDE.md + rules/ |
| **技术铁律** | JDK 17、Spring Boot 3.2.5、Maven 3.9+、deepseek-chat、JLine 3 |

#### Rules 规则层详解

每个 `.harness/rules/` 文件都是对 AI 的强制约束：

| 文件 | 核心内容 | AI 行为约束 |
|------|---------|------------|
| **SDD-TDD模式.md** | SDD（change.md=真相源）+ TDD（Red→Green→Refactor）+ Harness（约束→验证→留档） | 核心逻辑必须先写失败测试，覆盖率 ≥80% |
| **工程结构.md** | 完整目录树、包结构约定、命名约定、新增模块/功能约定 | 单模块 Maven，`com.nanobot.*` 分包，禁止循环依赖 |
| **开发流程规范.md** | 6 阶段流水线 + 门禁 + 变更状态机 + 人机协同协议 + 提交规范 | 每个 Gate 不通过禁止进入下一阶段；状态变更即更新 change.md |
| **编码规范.md** | Java 命名（PascalCase/camelCase）、Lombok 策略（@Data 允许）、日志约定、异常处理、安全红线 | 禁止吞异常、禁止硬编码 Key、提交前 mvn test 全绿 |
| **运行时可靠性.md** | Agent 调用保护（超时/重试/降级）、消息总线可靠性、会话持久化、CLI 交互可靠性、优雅关闭 | 外部依赖必须有超时+重试+降级；消息队列有界防 OOM |

#### Skills 技能层详解

6 个技能对应开发流水线的 6 个阶段：

| 阶段 | 技能 | 输入 | 产出 | Gate |
|------|------|------|------|------|
| ① 需求分析 | `request-analysis` | 一句话意图 | change.md（用户故事+AC+边界+NFR） | AC 可测试、≥3 边界情况 |
| ② 编码实现 | `coding-skill` | 已确认的 change.md | 失败测试 + 最小实现 | 测试通过 + mvn compile |
| ③ 单测编写 | `unit-test-write` | 实现代码 | 边界/降级/回归测试 | 核心覆盖率 ≥80% |
| ④ 专家评审 | `expert-reviewer` | 代码 + 测试 | 评审报告（5 维度） | 0 个严重问题 |
| ⑤ CI 门禁 | `unit-test-ci` | 完整变更 | CI 报告 | mvn test 59/59 全绿 |
| ⑥ 部署验证 | `deploy-verify` | 通过 CI 的构建 | 验证报告 | 冒烟 + 健康检查 |

#### Changes 变更追踪详解

每个功能/模块 = 一张变更卡片，三件套：

```
.harness/changes/C-NNN-<slug>/
├── change.md    — 用户故事 + 验收标准 + 边界情况 + 非功能需求（规格真相源）
├── review.md    — 设计评审记录（5 维度：代码规范/工程结构/测试覆盖/边界处理/可扩展性）
└── verify.md    — 验证记录（mvn test 59/59 全绿 + 编译通过）
```

**本项目 18 张卡片速览**：

| 编号 | 模块 | 状态 |
|------|------|------|
| C-001 | 项目脚手架（Java 17 + Maven + Spring Boot） | done |
| C-002 | 消息总线（MessageBus + Inbound/Outbound） | done |
| C-003 | AgentLoop 引擎（State 模式七状态机） | done |
| C-004 | AgentRunner 执行循环（LLM→Tool→递归） | done |
| C-005 | 工具系统（Tool 接口 + 17 内置工具） | done |
| C-006 | LLM 提供商（ProviderFactory + OpenAI + DeepSeek） | done |
| C-007 | 记忆系统（Dream + Consolidator + NANOBOT.md） | done |
| C-008 | 会话管理（SessionManager + SessionStore + Web UI） | done |
| C-009 | 安全权限（PermissionManager + Guard + RuleEngine） | done |
| C-010 | 命令系统（/exit /help /init /mode /resume） | done |
| C-011 | 钩子系统（AgentHook + Metrics/Validation/Tracing） | done |
| C-012 | 身份系统（SOUL + IDENTITY + USER） | done |
| C-013 | V3 CLI（JLine + Markdown + Esc 中断） | done |
| C-014 | Plan Mode（/plan → /plan approve） | done |
| C-015 | V2 Spring Boot（REST + SSE + WebSocket + sessions.html） | done |
| C-016 | MCP 集成（StdioMCP + HttpMCP + MCPManager） | done |
| C-017 | 设计模式重构（State + Strategy + Repository） | done |
| C-018 | 文档体系（架构 14 章 + README + NANOBOT.md + .harness） | done |

#### Wiki 知识库

| 文件 | 内容 |
|------|------|
| `业务模型.md` | Agent 框架核心概念（InboundMessage→AgentLoop→Tool→Provider 流转） |
| `接口协议.md` | REST API（6 端点）+ CLI 9 命令 + WebSocket + MCP 协议 |
| `数据模型.md` | 文件存储 6 层（history.jsonl/metadata.json/dream.json/NANOBOT.md/TaskStore/config.yaml） |
| `架构决策.md` | 10 个 ADR（Java 17/单模块/State模式/ProviderFactory/文件存储/JLine/Claude Code） |

#### 快速上手

```bash
# 1. 新需求：创建变更卡
cp -r .harness/changes/_TEMPLATE .harness/changes/C-019-my-feature
# 编辑 change.md 填写用户故事和 AC

# 2. 启动 Claude Code，一句话启动流水线
> 按 .harness 流程完成 C-019，先读 rules/ 和 wiki/

# 3. AI 按 6 阶段推进，关键 Gate 等你拍板
> ① OK 继续 ②
> ④ 通过 继续 ⑤

# 4. 收口
mvn test                    # 59/59 全绿
git commit -m "feat: xxx"
```

---

## 四、nanobot CLI 实战指南（对标 Claude Code）

> **阅读目标**：掌握 nanobot CLI 的日常使用方式、常用命令和最佳实践，像使用 Claude Code 一样高效。

### 4.1 启动与环境

```bash
# 安装：把 scripts/ 目录加到 PATH
export PATH="/path/to/nanobot-java/scripts:$PATH"

# 在任意项目目录下启动
cd /your/project
nanobot

# 指定工作目录
nanobot --workspace /path/to/project

# 恢复之前的会话
nanobot --resume cli-1234567890
```

启动后进入交互界面：

```
╔══════════════════════════════════╗
║       my-nanobot CLI 模式       ║
║  基于 Java 的 AI Agent助手      ║
╚══════════════════════════════════╝
输入消息开始对话，/exit 退出系统，/clear 清上下文，Esc 中断当前回复

>
```

---

### 4.2 命令速查

| 命令 | 别名 | 功能 | 示例 |
|------|------|------|------|
| `/help` | — | 列出所有可用命令 | `/help` |
| `/init` | — | 分析项目生成 NANOBOT.md | `/init` |
| `/mode plan` | `/plan` | 进入规划模式（只读分析+出计划） | `/plan` |
| `/plan approve` | — | 审批计划并切换到执行模式 | `/plan approve` |
| `/mode default` | — | 默认模式（读放行，写需确认） | `/mode default` |
| `/mode accept_edits` | — | 接受编辑模式（读+文件编辑放行） | `/mode accept_edits` |
| `/mode bypass` | — | 绕过模式（全部放行，谨慎使用） | `/mode bypass` |
| `/resume` | — | 列出最近 5 个会话 | `/resume` |
| `/resume <key>` | — | 恢复到指定会话 | `/resume cli-1234567890` |
| `/clear` | — | 清空当前会话上下文 | `/clear` |
| `/exit` | `/q`, `/quit` | 退出 CLI | `/exit` |
| `Esc` | — | 中断当前流式回复 | 按 `Esc` 键 |

---

### 4.3 核心工作流

#### 工作流 1：自由对话（Vibe Coding）

最直接的使用方式：描述需求 → AI 生成代码 → 验证结果 → 不满意就继续改。

```
> 帮我写一个用户登录接口，Spring Boot + JWT

AI: 分析项目结构 → 生成 LoginController + JwtUtil + 配置
    → 流式输出到终端

> 端口改成 9090

AI: 修改 application.yml 中的 server.port

> 帮我跑一下测试看看有没有问题

AI: 执行 mvn test → 分析结果 → 两个测试失败
    → 修复 → 再跑 → 全部通过
```

**适用场景**：原型开发、简单功能、一次性脚本。

#### 工作流 2：Plan Mode（Spec Coding 风格）

推荐的正规流程：先出计划 → 审批 → 执行。避免 AI 在理解不清时直接改代码。

```
> /plan
📋 已进入规划模式 — LLM 将只读分析并出计划，不会修改代码

> 实现用户注销功能，包括：清除 JWT、失效 Session、前端跳转

AI 使用只读工具探索项目:
  - list_dir 了解目录结构
  - read_file 查看现有认证相关代码
  - grep 搜索 JWT 和 Session 用法

AI 输出计划:
  ## 需求理解
  在现有认证系统上增加注销端点，清除 JWT token...

  ## 影响范围
  - AuthController.java — 新增 POST /logout
  - JwtUtil.java — 新增 invalidate 方法
  - SecurityConfig.java — 注册 logout 端点免认证

  ## 实现步骤
  1. JwtUtil 增加黑名单机制
  2. AuthController 新增 /logout
  3. SecurityConfig 放行 /logout
  4. 写测试验证

  ## 注意事项
  - JWT 无状态，需要内存黑名单或 Redis
  - 确认后请回复 /plan approve 开始执行

> /plan approve
✅ 计划已审批，进入执行模式...
（AI 开始按计划逐步实现）
```

**适用场景**：复杂功能、跨文件改动、需要审批的正式需求。

#### 工作流 3：探索式开发

先让 AI 了解项目，再逐步深入。

```
> /init                    # 第一步：生成项目记忆
🔍 正在分析项目...
🤖 正在通过大模型分析并生成 NANOBOT.md...
✅ 已生成: /Users/xxx/my-project/NANOBOT.md

> 这个项目的认证是怎么实现的？
                            # AI 读取 NANOBOT.md + grep 相关代码
                            # 给出清晰的架构说明

> 有没有安全漏洞？          # AI 根据上下文做安全审查

> 帮我把明文密码改成 BCrypt  # AI 已理解项目结构，直接精准修改
```

**适用场景**：接手新项目、代码审查、技术债务评估。

---

### 4.4 权限模式选择指南

```
信任度低 ←──────────────────────────────→ 信任度高

  PLAN        DEFAULT      ACCEPT_EDITS    BYPASS
  (只读)      (默认)       (编辑放行)      (全放行)

  /plan       /mode        /mode           /mode
              default      accept_edits    bypass
```

| 场景 | 推荐模式 | 原因 |
|------|---------|------|
| 刚接手不熟悉的项目 | `PLAN` | 先读后改，避免盲目修改 |
| 日常开发 | `DEFAULT` | 读自动放行，写操作需确认 |
| 信任的编码会话 | `ACCEPT_EDITS` | 跳过文件编辑确认，Shell 仍需确认 |
| CI/CD 自动化 | `BYPASS` | 完全信任，无人值守 |
| 不确定 AI 会做什么 | `PLAN` | 先让它出计划看看 |

**权限确认快捷键**（DEFAULT 模式下触发写操作时）：

```
[!] 工具调用需要确认:
  工具: exec
  参数: {command=mvn test}
  原因: Shell 命令执行需要您的确认
  1=允许  2=之后都放行  3=拒绝  [1/2/3]
```

- `1` — 只允许这一次
- `2` — 当前进程内信任，后续不再询问（相当于临时 `ACCEPT_EDITS`）
- `3` — 拒绝

---

### 4.5 会话管理

```
> /resume                  # 查看历史会话
最近会话（/resume <key> 恢复）:
  cli-1784128421194          12 条消息  07-16 14:30
  cli-1784207282340           5 条消息  07-15 09:12

> /resume cli-1784128421194  # 恢复指定会话
已切换到会话: cli-1784128421194

> /clear                    # 清空当前上下文
上下文已清空
```

**Web 界面**：启动 V2 Spring Boot 模式后访问 `http://localhost:8080/sessions.html`，可以：
- 查看所有会话的消息数和最后活动时间
- **点击会话名称可重命名**（输入后回车保存）
- 点击"查看"浏览完整消息历史
- 删除不需要的会话

---

### 4.6 NANOBOT.md — 项目的 AI 记忆文件

`/init` 命令会在项目根目录生成 `NANOBOT.md`，后续每次对话 AI 都会自动加载它。

```markdown
# nanobot-java 项目概述
这是一个基于 Java 17 的 AI Agent 框架...

## 技术栈
- Java 17, Maven, Spring Boot 3.2
- Jackson, SLF4J + Logback

## 构建和运行
mvn compile
mvn test
./scripts/nanobot
```

**手动编辑建议**：
- 补充编码规范（如"用 4 空格缩进""所有 public 方法要有 Javadoc"）
- 添加常用命令（如"数据库迁移：mvn flyway:migrate"）
- 写清楚目录用途（如"src/main/gen/ 是自动生成的，不要手动改"）

**效果**：AI 会严格按照 NANOBOT.md 中的规范工作，不需要每次都重复交代。

---

### 4.7 实用技巧

**1. 善用 Esc 中断**：AI 生成的内容跑偏了，按 `Esc` 立即停止再重新提问。

**2. 先 /init 再干活**：进入新项目第一件事就是 `/init`，让 AI 先理解项目。

**3. 复杂需求用 Plan Mode**：
```
/plan
  → AI 探索 + 出计划
  → 你审查计划
  → 不满意？直接说"第三步不对，应该先改接口再改实现"
  → AI 更新计划
/plan approve
  → AI 开始执行
```

**4. 分步执行**：不要一次性描述过于复杂的需求。拆成 2-3 步，每步验证后继续。

**5. 利用历史会话**：重要的工作用 `/resume` 恢复上下文，不怕断线。

**6. 信任加速**：频繁执行同类操作时，用 `2=之后都放行` 避免反复确认。

---

## 五、架构说明

### 5.1 整体架构分层

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         入口层 (Entry Points)                            │
│  ┌──────────────────┐  ┌────────────────────┐  ┌──────────────────┐     │
│  │ V3 CLI (终端)    │  │ V2 Spring Boot     │  │ V1 独立模式      │     │
│  │ nanobot 命令     │  │ HTTP/SSE/WebSocket │  │ ChannelServer    │     │
│  │ JLine+Markdown   │  │ sessions.html      │  │ 手动帧解析       │     │
│  └────────┬─────────┘  └────────┬───────────┘  └────────┬─────────┘     │
└───────────┼─────────────────────┼───────────────────────┼───────────────┘
            │                     │                       │
            └─────────────────────┼───────────────────────┘
                                  │  InboundMessage(sessionId, content, metadata)
                                  ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                      消息总线层 (MessageBus)                              │
│  inboundQueue (ArrayBlockingQueue)    sessionResponses (ConcurrentHashMap)│
└──────────────────────────────────┬───────────────────────────────────────┘
                                   │ consumeInbound()
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                        核心引擎层 (AgentLoop)                             │
│   ┌──────────────────────────────────────────────────────────────────┐   │
│   │  State 模式状态机:  RESTORE→COMPACT→COMMAND→BUILD→RUN→SAVE→RESPOND │   │
│   │  7 个 StateHandler:  Restore / Compact / Command / Build /       │   │
│   │                      Run / Save / Respond                        │   │
│   └──────────────────────────────────────────────────────────────────┘   │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                  │
│   │ AgentRunner  │  │  TurnContext │  │  CompositeHook│                  │
│   │ (LLM+工具循环)│  │  (会话上下文) │  │  (生命周期钩子)│                  │
│   └──────────────┘  └──────────────┘  └──────────────┘                  │
└──────────────────────────────────┬───────────────────────────────────────┘
                                   │
       ┌───────────────────────────┼───────────────────────────┐
       ▼                           ▼                           ▼
┌─────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│    工具层       │  │     提供商层          │  │    存储层            │
│  ToolRegistry   │  │  ProviderFactory     │  │  SessionStore (I/O)  │
│  + 17 内置工具  │  │  (策略工厂)          │  │  SessionManager(锁)  │
│  + MCP 工具     │  │  OpenAI / DeepSeek   │  │  MemoryStore/Dream   │
└────────┬────────┘  └──────────────────────┘  └──────────────────────┘
         │
         │  ╔══════════════════════════════════════╗
         └──╣       安全与扩展层                   ║
            ║  PermissionManager (Guard→Mode→Rule) ║
            ║  PathGuard / CommandGuard / Network  ║
            ║  RuleEngine (deny→ask→allow)         ║
            ║  PreToolUseHookManager (Hook 链)     ║
            ╚══════════════════════════════════════╝
```
### 5.2 核心组件职责

| 组件 | 职责 | 文件位置 |
|------|------|----------|
| **NanobotRunner** | Spring Boot 启动器，组件初始化编排 | `NanobotRunner.java` |
| **NanobotApplication** | V2 Spring Boot 入口（HTTP/SSE/WS） | `v2/NanobotApplication.java` |
| **NanobotCliApplication** | V3 CLI 入口（类 Claude Code 体验） | `v3/NanobotCliApplication.java` |
| **CliChannel** | CLI 交互通道，JLine 终端 + Markdown 渲染 | `v3/cli/CliChannel.java` |
| **AgentLoop** | 状态机引擎（State 模式），委托 7 个 StateHandler | `core/AgentLoop.java` |
| **AgentRunner** | LLM 调用循环，处理工具调用 | `core/AgentRunner.java` |
| **TurnContext** | 会话上下文，存储消息和状态 | `core/TurnContext.java` |
| **TurnState** | 状态枚举，定义状态机节点 | `core/TurnState.java` |
| **AgentState** | State 模式接口（8 个实现类） | `core/state/` |
| **TaskStore** | 会话级任务追踪，持久化 JSON | `core/TaskStore.java` |
| **MessageBus** | 消息总线，异步队列通信 | `bus/MessageBus.java` |
| **ToolRegistry** | 工具注册中心，支持只读过滤 | `tools/ToolRegistry.java` |
| **Tool** | 工具接口，定义工具契约 | `tools/Tool.java` |
| **LLMProvider** | LLM 提供商接口 | `providers/LLMProvider.java` |
| **ProviderFactory** | 策略工厂，按模型名匹配 Provider | `providers/ProviderFactory.java` |
| **SessionManager** | 会话业务层（锁管理+协调） | `session/SessionManager.java` |
| **SessionStore** | 会话存储层（纯文件 I/O） | `session/SessionStore.java` |
| **MemoryStore** | 内存持久化存储 | `memory/MemoryStore.java` |
| **Config / ConfigLoader** | 配置加载和管理 | `config/` |
| **AgentHook / CompositeHook** | 钩子系统（Chain of Responsibility） | `core/hook/` |
| **Command / CommandRegistry** | 命令系统（Command 模式） | `command/` |
| **MCPManager** | MCP 服务器管理和工具注册 | `mcp/MCPManager.java` |
| **MCPClient / StdioMCPClient / HttpMCPClient** | MCP 客户端实现（Template Method） | `mcp/` |
| **CronScheduler** | 定时任务调度器 | `cron/CronScheduler.java` |
| **Consolidator** | 记忆压缩器 | `memory/Consolidator.java` |
| **Dream** | 长期记忆系统 | `memory/Dream.java` |
| **PermissionManager** | 权限编排器（Guard→Mode→Rule 管道） | `security/PermissionManager.java` |
| **PathGuard / CommandGuard / NetworkGuard** | 守卫层（Strategy 模式） | `security/guard/` |
| **RuleEngine** | 规则引擎，deny→ask→allow 优先级链 | `security/rule/RuleEngine.java` |
| **MarkdownRenderer** | CLI 终端 Markdown 渲染 | `v3/tui/MarkdownRenderer.java` |

> 📖 完整安全模块文档: [docs/features.md](docs/features.md)
### 5.3 Agent Loop — 核心循环 ★

> Agent Loop 是 AI Agent 的心脏。理解它就理解了 Agent 是如何"活着"的。

#### 什么是 Agent Loop

Agent Loop 是 AI Agent 的核心控制循环，遵循 **感知→思考→行动→观察** 的基本模式：

```
┌──────────────────────────────────────────────────────┐
│                  Agent Loop 核心循环                  │
│                                                      │
│   ┌──────────┐    ┌──────────┐    ┌──────────┐       │
│   │ PERCEIVE │───▶│  THINK   │───▶│   ACT    │       │
│   │ (感知)   │    │ (思考)   │    │ (行动)   │       │
│   └──────────┘    └──────────┘    └──────────┘       │
│        ▲                                 │           │
│        │         ┌──────────┐           │            │
│        └─────────│ OBSERVE  │◀──────────┘            │
│                  │ (观察)   │                        │
│                  └──────────┘                        │
└──────────────────────────────────────────────────────┘
```

**一次循环的完整过程**：

| 阶段 | 对应组件 | 做什么 |
|------|---------|--------|
| **PERCEIVE** | MessageBus.consumeInbound() | 从消息队列取出用户输入 |
| **THINK** | AgentRunner.run() | 调用 LLM，解析响应（文本 or 工具调用） |
| **ACT** | Tool.execute() | 并行执行 LLM 请求的工具（读文件/搜索/Shell…） |
| **OBSERVE** | executeTools() → messages.add() | 工具结果注入消息历史，准备下一轮 |
| **LOOP** | runInternal(iteration+1) | 递归回到 THINK，直到 LLM 不再请求工具 |

#### 本项目实现：双层架构

```
AgentLoop（状态机引擎 — 管理"做什么"）
    │  RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND
    │
    └── RUN 状态内部嵌套 AgentRunner（执行引擎 — 管理"怎么做"）
         │
         └── runInternal() 递归循环
              ├── LLM 调用
              ├── 工具执行
              └── 结果注入 → 递归
```

**AgentLoop 职责**（状态机层）：
- 恢复会话历史、压缩过长的上下文
- 构建 System Prompt（身份+NANOBOT.md+PlanMode+Rules）
- 调度 RUN 状态，保存结果，发送响应
- 管理生命周期钩子和流式回调

**AgentRunner 职责**（执行引擎层）：
- 调用 LLM API（chat/chatStream）
- 解析 tool_calls，执行工具（并行），收集结果
- 递归循环直到 LLM 返回纯文本
- 防护：迭代上限、费用上限、降级兜底

#### 关键设计决策

| 决策 | 模式 | 理由 |
|------|------|------|
| **双层分离** | AgentLoop(状态机) + AgentRunner(循环) | 状态管理和 LLM 调用独立演进 |
| **递归非循环** | runInternal() 递归 | 每轮自然携带更新后的 messages |
| **State 模式** | 7 个独立 StateHandler | 新增状态只需实现接口+注册 |
| **工具并行执行** | CompletableFuture.allOf | 同轮多工具同时跑，减少延迟 |
| **流式双路径** | SSE直推 + MessageBus | 覆盖 HTTP 同步和 WebSocket 两种场景 |
| **单线程消费** | 单 daemon 线程 | 保证同一会话消息串行，防并发冲突 |

#### 循环终止条件

AgentRunner 在以下任一条件满足时停止循环：

1. **LLM 返回纯文本**（无 tool_calls）→ 正常结束
2. **达到 maxToolIterations**（默认 100）→ 配额耗尽
3. **达到 maxTurns** → 轮次上限
4. **费用超 maxCost** → 预算用尽
5. **用户取消**（Esc 中断）→ 手动停止
6. **连续 3 次工具失败** → 降级兜底，强制不带工具回答

#### 一个完整的循环示例

```
用户: "帮我查今天天气，然后保存到 weather.txt"
    │
    ▼ PERCEIVE: MessageBus 收到消息
    │
    ▼ THINK [iteration=0]: 调用 LLM
    │   LLM 返回: tool_calls=[web_search("今天天气"), write_file("weather.txt", ...)]
    │
    ▼ ACT: 并行执行 web_search + write_file
    │   web_search → "北京今天晴，25°C"
    │   write_file → 写入成功
    │
    ▼ OBSERVE: 工具结果注入 messages
    │
    ▼ THINK [iteration=1]: 再次调用 LLM（messages 含搜索结果+写入结果）
    │   LLM 返回: "已查询天气并保存到 weather.txt，北京今天晴，25°C"
    │   （无 tool_calls → 循环结束）
    │
    ▼ RESPOND: 发送响应给用户
```

---
### 5.4 状态机流程（已重构为 State 模式）

`AgentLoop` 采用 **State 模式** 管理消息处理，每个状态对应一个独立的 `AgentState` 实现类：

```
RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE
    │          │         │        │       │       │         │
    ▼          ▼         ▼        ▼       ▼       ▼         ▼
 恢复会话   压缩历史   命令分发  构建上下文 LLM调用 保存状态 发送响应
    │          │         │        │       │       │         │
RestoreState CompactSt CommandSt BuildSt  RunSt   SaveSt  RespondSt
```

**State 模式优势**：
- 每个状态独立文件（`core/state/*.java`），职责清晰
- 新增状态只需实现 `AgentState` 接口并注册
- AgentLoop 从 973 行精简到 579 行

**状态转换表**：

| 当前状态 | 事件 | 下一状态 | 说明 |
|---------|------|---------|------|
| RESTORE | ok | COMPACT | 加载会话历史 |
| COMPACT | ok | COMMAND | 压缩历史消息 |
| COMMAND | dispatch | BUILD | 分发普通消息 |
| COMMAND | shortcut | DONE | 处理快捷命令（如 `/stop`） |
| BUILD | ok | RUN | 构建 LLM 上下文 |
| RUN | ok | SAVE | 执行 LLM 调用和工具 |
| SAVE | ok | RESPOND | 保存会话状态 |
| RESPOND | ok | DONE | 发送响应 |
### 5.5 核心消息处理全链路详解

> **本节目标**：完整梳理一条用户消息从进入系统到生成响应的全链路，理解每一层的职责、数据流转和关键代码路径。

#### 2.8.1 端到端流程图

```
                        【消息入口层】—— 三种渠道
┌─────────────────┐  ┌──────────────────┐  ┌──────────────────────┐
│ V3 CLI (终端)   │  │ V2 Spring Boot   │  │ V1 ChannelServer     │
│ CliChannel      │  │ ChatController   │  │ (内嵌 HTTP/WS)       │
│ JLine+Markdown  │  │ + SSE + WS       │  │                      │
└────────┬────────┘  └────────┬─────────┘  └──────────┬───────────┘
         │                    │                        │
         │    构建 InboundMessage(sessionId, content, metadata...)
         │                    │                        │
         └────────────────────┼────────────────────────┘
                              │
                              ▼   publishInbound()
              ┌───────────────────────────────────┐
              │           MessageBus              │  ◄── 有界队列 + 响应匹配
              │  inboundQueue (ArrayBlockingQueue) │
              │  sessionResponses (ConcurrentMap)  │
              └───────────────┬───────────────────┘
                              │
                              ▼   consumeInbound() — AgentLoop 单线程消费
              ┌───────────────────────────────────────────────────────────┐
              │                      AgentLoop                            │
              │              七状态 State 模式引擎                         │
              │  RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE
              │  RestoreSt CompactSt CommandSt BuildSt RunSt SaveSt RespondSt │
              └───────────────────────────────────────────────────────────┘
                              │                        ▲
                              │   RUN 状态              │ 递归循环（工具调用）
                              ▼                        │
              ┌───────────────────────────────────────────────────────────┐
              │                      AgentRunner                          │
              │                 LLM 调用 + 工具执行循环                     │
              │                                                           │
              │  ① dropOrphanToolResults(messages)    清理孤立 tool 结果   │
              │  ② sanitizeToolCallHistory(messages)  清理不完整 tool_calls│
              │  ③ convertToLLMMessages → LLMProvider.Message             │
              │  ④ 调用 LLM API (chat / chatStream)                      │
              │  ⑤ 流式 tool_calls 拼接 (ToolCallAccumulator)             │
              │  ⑥ tool_calls → 并行执行工具 → 结果追加 → 回到③           │
              │  ⑦ 无 tool_calls → 返回最终文本内容                        │
              └─────────────────┬─────────────────────────────────────────┘
                                │
                                ▼ 最终响应 (doRespond)
              ┌───────────────────────────────────┐
              │           MessageBus              │
              │  publishOutbound → sessionResp    │  ← 响应写入 Map
              └───────────────┬───────────────────┘
                              │
       ┌──────────────────────┼──────────────────────────┐
       │                      │                          │
       ▼                      ▼                          ▼
  HTTP 同步响应          SSE 流式推送               WebSocket 推送
  waitForSessionResp   StreamRespCallback         StreamRespCallback
  (按 requestId 匹配)   (onStreamData 直推)        (onStreamData 直推)
```

#### 2.8.2 阶段一：消息入口 —— 三种渠道

系统支持三种渠道接收用户消息，最终都构建 `InboundMessage` 发布到 `MessageBus`。

**渠道 A：Spring Boot REST API**（`controller/ChatController.java`）

| 端点 | 模式 | 机制 |
|------|------|------|
| `POST /api/chat` | 同步 | 发布消息后 `waitForSessionResponse()` 阻塞等待（默认 120s），按 `requestId` 精确匹配 |
| `POST /api/chat/stream` | 流式 SSE | 注册 `StreamResponseCallback` 到 AgentLoop，通过 `SseEmitter` 实时推送每个 token |

**渠道 B：Jakarta WebSocket**（`websocket/NanobotWebSocketEndpoint.java`）

- 端点：`ws://host:port/ws`
- `@OnMessage` 解析 JSON → type 为 `"chat"` → 构建 InboundMessage
- WebSocket 默认启用流式模式（`streamMode: true`）
- 流式响应通过 `StreamResponseCallback` → `session.getBasicRemote().sendText()`

**渠道 C：内嵌 HTTP 服务器**（`channels/ChannelServer.java`）

- 独立模式下的内嵌 HTTP 服务器（`com.sun.net.httpserver.HttpServer`）
- `ChatHandler` 处理 `POST /api/chat`，支持流式 SSE 和同步两种模式
- `WebSocketHandler` 处理 `/ws` 的 WebSocket 升级握手，`WebSocketConnection` 管理帧读写

**InboundMessage 核心字段**：

| 字段 | 说明 |
|------|------|
| `channel` | 来源通道：`"api"`, `"websocket"`, `"http"` 等 |
| `senderId` | 发送者 ID |
| `sessionId` | 会话标识，用于会话隔离 |
| `content` | 用户文本内容 |
| `metadata` | 元数据 Map，包含 `requestId`, `streamMode`, `useSearch`, `connectionId` 等 |
| `sessionKey` | 会话键，格式 `"{channel}:{chatId}"`，支持 `sessionKeyOverride` 覆盖 |

#### 2.8.3 阶段二：MessageBus —— 异步消息总线

文件：`bus/MessageBus.java`

MessageBus 是系统的**中枢神经**，采用双队列 + 生产者-消费者模式实现模块解耦：

```
Channel Adapters ──(publishInbound)──→ inboundQueue ──(consumeInbound)──→ AgentLoop
AgentLoop ────────(publishOutbound)──→ sessionResponses (ConcurrentHashMap, requestId 匹配)
                                       ↑ 已移除 outboundQueue，响应通过 Map 匹配或回调直推
```

| 方法 | 说明 |
|------|------|
| `publishInbound(msg)` | 阻塞写入入站队列（`put`），队列满时阻塞等待 |
| `consumeInbound(timeout)` | 带超时的阻塞消费，AgentLoop 主循环使用 |
| `publishOutbound(msg)` | 阻塞写入出站队列 + 同步存入 `sessionResponses` Map |
| `offerOutbound(msg)` | 非阻塞写入出站队列（`offer`），用于流式进度消息，队列满时静默丢弃 |
| `waitForSessionResponse(sessionId, requestId, timeout)` | HTTP 同步模式专用：轮询 `sessionResponses` Map，按 `requestId` 精确匹配 |

**设计要点**：
- 使用 `ArrayBlockingQueue`（有界队列），默认容量 100，防止内存溢出
- 入站单线程消费（AgentLoop 的 daemon 线程），保证消息串行处理
- `sessionResponses` 用 `ConcurrentHashMap<String, Queue<OutboundMessage>>` 存储，支持按 sessionId + requestId 精确匹配响应

#### 2.8.4 阶段三：AgentLoop —— 七状态状态机引擎

文件：`core/AgentLoop.java`

AgentLoop 是整个系统的**核心引擎**，在单 daemon 线程中运行主循环：

```java
// AgentLoop.runLoop() — 主循环
while (running.get()) {
    InboundMessage message = messageBus.consumeInbound(1, TimeUnit.SECONDS);
    if (message != null) {
        processMessage(message);  // 创建 TurnContext → 驱动状态机 → 发送响应
    }
}
```

**状态机七阶段详解**：

| # | 状态 | 职责 | 核心操作 | 下一状态 |
|---|------|------|---------|---------|
| 1 | **RESTORE** | 恢复会话 | `sessionManager.loadHistory(sessionKey)` → 从 JSONL 文件加载历史消息；将当前用户消息追加到消息列表 | COMPACT |
| 2 | **COMPACT** | 压缩历史 | 检查 token 预算，超限时调用 LLM 生成摘要压缩旧消息（当前为 TODO，直接跳过） | COMMAND |
| 3 | **COMMAND** | 命令分发 | 解析 `/` 前缀：匹配 Skills（`skillManager.parseSlashCommand()`）、`/stop`、`/clear`、`/skills`、`/rules`；命中命令则直接返回，否则继续 | BUILD 或 DONE |
| 4 | **BUILD** | 构建上下文 | 组装 System Prompt：注入身份信息 + NANOBOT.md + Plan Mode 提示 + Rules；根据 `useSearch` 元数据决定搜索工具；Plan Mode 时只暴露只读工具 | RUN |
| 5 | **RUN** | 运行 LLM | 设置流式回调 `onDelta` → 调用 `AgentRunner.run(context, messages, onDelta)` → 等待结果 → 发送流式结束标记 | SAVE |
| 6 | **SAVE** | 保存状态 | 将助手响应追加到消息历史 → `sessionManager.saveHistory(sessionKey, messages)` → 写入 JSONL 文件 | RESPOND |
| 7 | **RESPOND** | 发送响应 | 构建 `OutboundMessage` → `messageBus.publishOutbound(response)` → 发布到出站队列 | DONE |

**流式回调机制**（在 `doRun()` 中设置）：

```java
onDelta = delta -> {
    // 路径1：发布进度消息到 MessageBus（供 ChannelServer WebSocket 消费）
    publishProgress(OutboundMessage.progress(channel, chatId, delta));

    // 路径2：调用 StreamResponseCallback（供 SSE/WebSocket 直接推送）
    if (streamResponseCallback != null) {
        streamResponseCallback.onStreamData(sessionId, requestId, delta);
    }
};
```

**System Prompt 构建过程**（`BuildState`）：

```
System Prompt =
    IdentityManager.getSystemPrompt(currentDate)    // SOUL.md + IDENTITY.md + USER.md
    + (useSearch ? "" : "请直接回答，不要调用工具")
    + NANOBOT.md（项目根目录，/init 生成）            // 项目记忆
    + Plan Mode 提示词（如果激活）                   // 只读限制 + 结构化计划要求
    + RuleManager.getRulesPrompt()                   // .nanobot/rules/*.md
```

#### 2.8.5 阶段四：AgentRunner —— LLM 调用 + 工具执行循环

文件：`core/AgentRunner.java`

AgentRunner 是**最核心的执行引擎**，采用递归模式实现"LLM 调用 → 工具执行 → 再调用"的多轮循环。

**递归循环流程**：

```
runInternal(context, messages, onDelta, iteration=0)
    │
    ├─ 1. 终止条件检查
    │     - iteration >= maxTurns / maxIterations → 停止
    │     - context.isCancelled() → 停止
    │     - 连续工具失败 >= 3 次 → 降级兜底（不带工具调用 LLM）
    │
    ├─ 2. dropOrphanToolResults(messages)      清理孤立 tool 结果
    ├─ 3. sanitizeToolCallHistory(messages)     清理不完整 tool_calls (DeepSeek 兼容)
    │
    ├─ 4. convertToLLMMessages → Map→LLMProvider.Message
    │
    ├─ 5. 调用 LLM API
    │     provider.chatStream() 或 provider.chat()
    │
    ├─ 6. 解析 LLMResponse
    │     ├─ isError() → 返回错误
    │     ├─ shouldExecuteTools() → 有工具调用
    │     │   ├─ Plan Mode 时只暴露只读工具 (getDefinitions(true))
    │     │   ├─ 创建 assistant 消息（含 tool_calls）→ messages
    │     │   ├─ executeTools() — CompletableFuture.allOf 并行
    │     │   │   └─ executeToolWithRetry() 重试3次，结果截断 16000 字符
    │     │   └─ return runInternal(..., iteration+1)  ← 递归
    │     └─ 最终文本 → 追加 assistant 消息 → 返回 content
    │
    └─ 7. 返回 CompletableFuture<String>
```

**工具执行细节**：

```java
// executeTools() — 并行工具执行
for (ToolCallRequest call : toolCalls) {
    CompletableFuture.runAsync(() -> {
        Object result = executeToolWithRetry(toolName, params, callId);
        // 截断过长结果 (>16000字符)
        // 追加 tool 角色消息: {role: "tool", tool_call_id: id, content: result}
    }, toolExecutor);
}
return CompletableFuture.allOf(futures);  // 等待全部完成

// executeToolWithRetry() — 带重试机制
// - 最大重试3次，重试间隔1秒
// - 仅对网络错误 (ConnectException, IOException) 和超时重试
// - 其他异常直接返回错误信息
// - 每次执行有30秒超时保护
```

#### 2.8.6 阶段五：LLM Provider 调用

接口：`providers/LLMProvider.java`
实现：`providers/impl/DeepSeekProvider.java`、`providers/impl/OpenAIProvider.java`

**DeepSeekProvider 调用流程**（当前主要使用的 provider）：

```
chatStream(messages, tools, onDelta)
    │
    ├─ buildRequestBody → POST https://api.deepseek.com/chat/completions
    │
    ├─ 逐行解析 SSE 响应流 (Java HttpClient, 超时 300s)
    │     data: {"choices":[{"delta":{"content":"..."}}]}
    │     data: {"choices":[{"delta":{"tool_calls":[{"index":0,...}]}}]}
    │     data: [DONE]
    │
    ├─ content delta → onDelta.accept(content) → 流式推送
    │
    ├─ tool_calls delta → parseToolCallsFromDelta → ToolCallAccumulator
    │     DeepSeek 逐字符流式返回 arguments，需要跨 delta 拼接
    │     Delta 1: {name:"read_file", arguments:"{"}
    │     Delta 2: {arguments:"\"path\""}
    │     Delta 3: {arguments:":\"."}
    │     Delta 4: {arguments:"\"}"}
    │     → buildToolCalls() 拼接后解析完整 arguments JSON
    │
    └─ 返回 LLMResponse
          ├─ 有 tool_calls → LLMResponse.toolCalls(...)
          └─ 纯文本     → LLMResponse.success(content, finishReason)
```

**LLMResponse 三种类型**：

| 类型 | `shouldExecuteTools()` | `isError()` | 含义 |
|------|----------------------|-------------|------|
| `success(content, ...)` | false | false | 最终文本响应 |
| `toolCalls(list, model)` | true | false | LLM 请求执行工具 |
| `error(msg, kind)` | false | true | API 调用失败 |

#### 2.8.7 阶段六：响应返回

响应通过 `OutboundMessage` 封装，按请求渠道和模式分三种路径返回：

| 模式 | 机制 | 代码路径 | 适用场景 |
|------|------|---------|---------|
| **HTTP 同步** | `MessageBus.waitForSessionResponse(sessionId, requestId, 120s)` 轮询匹配 OutboundMessage | ChatController.chat() / ChannelServer.ChatHandler | `POST /api/chat` |
| **SSE 流式** | `AgentLoop.StreamResponseCallback.onStreamData()` → `SseEmitter.send()` | ChatController.streamChat() → AgentLoop.doRun() → onDelta | `POST /api/chat/stream` |
| **WebSocket** | `StreamResponseCallback.onStreamData()` → `Session.getBasicRemote().sendText()` | NanobotWebSocketEndpoint.onMessage() → AgentLoop.doRun() → onDelta | `ws://host:port/ws` |

**OutboundMessage 特殊标记**（metadata 中以 `_` 开头的系统标记）：

| 标记 | 含义 |
|------|------|
| `_progress` | 流式输出的进度消息（中间结果） |
| `_stream_delta` | 流式消息片段 |
| `_stream_end` | 流式消息结束标记 |
| `_streamed` | 消息已完整流式发送 |
| `_tool_hint` | 工具调用提示 |
| `_wants_stream` | 请求启用流式输出 |

#### 2.8.8 完整数据流示例

以用户发送 `"帮我搜索最新AI新闻"` 为例：

```
用户输入 "帮我搜索最新AI新闻"
    │
    ▼
[ChatController.streamChat()] 
    构建 InboundMessage {channel:"api", chatId:"xxx", content:"帮我搜索最新AI新闻",
                         metadata:{requestId:"uuid", streamMode:true, useSearch:true}}
    │
    ▼
[MessageBus.publishInbound()] → inboundQueue.put(msg)
    │
    ▼
[AgentLoop.consumeInbound()] → processMessage(msg)
    │
    ├─ [RESTORE] SessionManager.loadHistory() → 加载历史消息 (JSONL)
    │     messages = [{role:"system",...}, {role:"user",...}, ...]
    │
    ├─ [COMPACT] (skip)
    │
    ├─ [COMMAND] 不是 / 命令 → 继续
    │
    ├─ [BUILD] 构建 System Prompt:
    │     IdentityManager.getSystemPrompt()   ← SOUL.md: "你是 my-nanobot..."
    │     + "你可以调用工具...包括网页搜索"       ← useSearch=true
    │     + RuleManager.getRulesPrompt()       ← 项目规则
    │
    ├─ [RUN] AgentRunner.run(context, messages, onDelta)
    │     │
    │     ├─ 第1轮 LLM 调用 (iteration=0)
    │     │   POST DeepSeek API: messages + tools[{web_search,...}]
    │     │   LLM 返回: tool_calls:[{name:"web_search", args:{query:"最新AI新闻"}}]
    │     │
    │     ├─ executeTools() 并行执行
    │     │   WebSearchTool.execute({query:"最新AI新闻"})
    │     │   → HTTP 请求搜索引擎 → 返回 [{title:"...", url:"...", snippet:"..."}, ...]
    │     │
    │     ├─ 第2轮 LLM 调用 (iteration=1) — 递归
    │     │   messages 追加了 tool_calls assistant消息 + tool结果消息
    │     │   LLM 基于搜索结果生成回答:
    │     │   "根据最新搜索，以下是近期AI领域的重大新闻：1. ... 2. ..."
    │     │   → 纯文本响应，无 tool_calls
    │     │
    │     └─ 返回最终内容
    │
    ├─ [SAVE] SessionManager.saveHistory() → 写入 JSONL
    │
    └─ [RESPOND] OutboundMessage → MessageBus.outboundQueue
                    │
                    ▼
              SSE 流式推送给客户端 (实时展示)
```

#### 2.8.9 三种运行模式对比

| 维度 | V1 独立模式 | V2 Spring Boot | V3 CLI 模式 |
|------|-----------|---------------|------------|
| 入口类 | `Nanobot.java` | `NanobotApplication.java` | `NanobotCliApplication.java` |
| 启动方式 | `mvn exec:java` | `mvn spring-boot:run` | `./scripts/nanobot` 或 `java -jar nanobot-cli.jar` |
| HTTP 服务器 | `ChannelServer`（内嵌） | Spring MVC + Tomcat | 无（纯终端） |
| 交互方式 | API/SSE/WebSocket | API/SSE/WebSocket | 命令行终端（JLine + Markdown） |
| 核心流程 | AgentLoop + AgentRunner | AgentLoop + AgentRunner | AgentLoop + AgentRunner |
| 特性 | 基础功能 | 会话管理前端、REST API | Plan Mode、Esc 中断、/ 命令系统 |
| 命令 | 无 | 无 | /exit, /help, /init, /mode, /plan, /resume, /clear |

#### 2.8.10 关键设计决策与要点

| 设计决策 | 说明 |
|---------|------|
| **State 模式** | AgentLoop 状态机拆分为 8 个独立 `AgentState` 实现类，AgentLoop 从 973 行精简到 579 行 |
| **策略工厂** | `ProviderFactory` 按模型名自动匹配 Provider，新增厂商只需注册策略 |
| **Repository 分离** | `SessionStore` 负责文件 I/O，`SessionManager` 负责锁和业务逻辑，可独立测试 |
| **单线程消费** | AgentLoop 单 daemon 线程保证同一会话串行处理 |
| **有界队列** | `ArrayBlockingQueue`（默认 100），防 OOM |
| **递归 + 工具并行** | AgentRunner 递归实现多轮，同轮 tool_calls 用 `CompletableFuture.allOf` 并行 |
| **流式工具参数拼接** | DeepSeek 流式返回 arguments 逐字符发送，用 `ToolCallAccumulator` 拼接后解析 |
| **sanitizeToolCallHistory** | 每次 LLM 调用前清理不完整的 tool_calls，兼容 DeepSeek 严格校验 |
| **增量保存** | `saveHistory()` 只追加新增消息，不全量覆写 |

---
### 5.6 核心接口设计

#### Tool 接口

工具是 Agent 与外部世界交互的核心方式：

```java
public interface Tool {
    String getName();                    // 工具名称
    String getDescription();             // 工具描述
    JsonNode getParameters();            // 参数 JSON Schema
    boolean isReadOnly();                // 是否只读
    boolean isExclusive();               // 是否独占执行
    CompletableFuture<Object> execute(Map<String, Object> params);  // 执行方法
}
```

#### LLMProvider 接口

统一的 LLM API 调用接口：

```java
public interface LLMProvider {
    String getName();
    CompletableFuture<LLMResponse> chat(List<Message> messages, List<JsonNode> tools);
    CompletableFuture<LLMResponse> chatStream(List<Message> messages, 
                                               List<JsonNode> tools, 
                                               Consumer<String> onDelta);
}
```

#### AgentHook 接口

生命周期钩子扩展点（Chain of Responsibility 模式）：

```java
public interface AgentHook {
    default CompletableFuture<Void> beforeIteration(AgentHookContext ctx) {...}
    default CompletableFuture<Void> beforeExecuteTools(AgentHookContext ctx) {...}
    default CompletableFuture<Void> afterIteration(AgentHookContext ctx) {...}
    default String finalizeContent(AgentHookContext ctx, String content) { return content; }
    String getName();
}
```

#### AgentState 接口（State 模式）

```java
public interface AgentState {
    TurnState execute(TurnContext ctx);
}
// 实现类: RestoreState, CompactState, CommandState, BuildState, RunState, SaveState, RespondState
```

#### Command 接口（Command 模式）

```java
public interface Command {
    String name();
    default List<String> aliases() { return List.of(); }
    String description();
    boolean execute(CommandContext ctx, String input);
}
// 实现类: ExitCommand, HelpCommand, InitCommand, ModeCommand, ResumeCommand
```

#### ProviderStrategy 接口（Strategy 模式）

```java
public interface ProviderStrategy {
    boolean supports(String model);
    LLMProvider create(Config config, String model);
}
// ProviderFactory 遍历注册的策略，首个 match 的创建 Provider
```

#### MCP 相关接口

**MCPClient 接口** - MCP 客户端接口：

```java
public interface MCPClient {
    CompletableFuture<MCPResult> callTool(String toolName, Map<String, Object> arguments);
    CompletableFuture<List<MCPToolInfo>> listTools();
    CompletableFuture<MCPResult> readResource(String uri);
    CompletableFuture<MCPResult> getPrompt(String promptName, Map<String, Object> arguments);
    void close();
    boolean isConnected();
    String getServerName();
}
```

**MCPToolWrapper 类** - 将 MCP 工具包装为 Nanobot Tool：

```java
public class MCPToolWrapper implements Tool {
    private final MCPClient client;
    private final MCPToolInfo toolInfo;
    private final String qualifiedName;  // mcp_<server>_<tool>
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return client.callTool(toolInfo.getName(), params)
            .thenApply(result -> result.toString());
    }
}
```

---
### 5.7 模块结构

```
nanobot-java/
├── scripts/                   # 启动脚本
│   ├── nanobot                # Mac/Linux 启动脚本
│   ├── nanobot.bat            # Windows 启动脚本
│   └── start.sh / stop.sh     # 服务启停脚本
├── src/main/java/com/nanobot/
│   ├── NanobotRunner.java     # Spring Boot 组件初始化
│   ├── bus/                   # 消息总线
│   ├── command/               # 命令系统 (Command 模式)
│   │   ├── Command.java
│   │   ├── CommandContext.java
│   │   ├── CommandRegistry.java
│   │   └── impl/              # /exit, /help, /init, /mode, /resume
│   ├── config/                # 配置系统
│   ├── core/                  # 核心引擎
│   │   ├── AgentLoop.java     # 状态机引擎 (State 模式)
│   │   ├── AgentRunner.java   # LLM 调用循环
│   │   ├── TaskStore.java     # 任务追踪
│   │   ├── TurnContext.java
│   │   ├── TurnState.java
│   │   ├── state/             # State 处理器 (NEW)
│   │   │   ├── AgentState.java
│   │   │   ├── RestoreState.java / CompactState.java / CommandState.java
│   │   │   ├── BuildState.java / RunState.java
│   │   │   └── SaveState.java / RespondState.java
│   │   ├── hook/              # 钩子系统
│   │   └── subagent/          # 子 Agent
│   ├── cron/                  # 定时任务
│   ├── identity/              # 身份系统 (SOUL/IDENTITY/USER)
│   ├── mcp/                   # MCP 协议
│   ├── memory/                # 记忆存储
│   ├── providers/             # LLM 提供商
│   │   ├── LLMProvider.java
│   │   ├── ProviderFactory.java   # 策略工厂 (NEW)
│   │   └── impl/              # OpenAI, DeepSeek
│   ├── rules/                 # 规则系统
│   ├── security/              # 安全系统
│   │   ├── PermissionManager.java
│   │   ├── PermissionMode.java   # PLAN/DEFAULT/ACCEPT_EDITS/BYPASS
│   │   ├── guard/             # PathGuard/CommandGuard/NetworkGuard
│   │   ├── hook/              # PreToolUseHook
│   │   └── rule/              # RuleEngine
│   ├── session/               # 会话管理
│   │   ├── SessionManager.java    # 业务层
│   │   └── SessionStore.java      # 存储层 (NEW)
│   ├── skill/                 # 技能系统
│   ├── tools/                 # 工具系统 (17+ 内置工具)
│   │   ├── Tool.java / ToolRegistry.java
│   │   └── impl/              # 文件、搜索、Shell、Web、Task、AskUser
│   ├── v1/                    # V1 独立模式 (Nanobot.java + ChannelServer)
│   ├── v2/                    # V2 Spring Boot (NanobotApplication + REST + WS)
│   │   ├── controller/        # ChatController, SessionController, HealthController
│   │   └── websocket/         # NanobotWebSocketEndpoint
│   └── v3/                    # V3 CLI 模式 (NanobotCliApplication)
│       ├── cli/               # CliChannel (JLine 终端 + Markdown 渲染)
│       └── tui/               # MarkdownRenderer
├── src/main/resources/
│   ├── config/config.yaml
│   ├── logback.xml / logback-cli.xml
│   └── static/                # sessions.html (会话管理前端)
└── pom.xml
```
### 5.8 内置工具一览

**文件工具**：

| 工具 | 只读 | 说明 |
|------|------|------|
| `read_file` | ✅ | 读取文件内容（支持行范围） |
| `write_file` | ❌ | 创建或覆盖文件 |
| `edit_file` | ❌ | 精确字符串替换编辑 |
| `list_dir` | ✅ | 列目录（支持递归，path 默认 `.`） |
| `glob` | ✅ | 通配符文件搜索（pattern 默认 `*`） |
| `grep` | ✅ | 文件内容搜索（path 默认 `.`） |

**Shell 工具**：

| 工具 | 只读 | 说明 |
|------|------|------|
| `exec` | ❌ | 执行 shell 命令。自动检测 PowerShell/pwsh，避免 cmd /c 吃掉转义符 |

**Web 工具**：

| 工具 | 只读 | 说明 |
|------|------|------|
| `web_search` | ✅ | 网页搜索（支持百度/Brave/Bing） |
| `web_fetch` | ✅ | 抓取网页内容转 Markdown |

**任务工具**：

| 工具 | 只读 | 说明 |
|------|------|------|
| `task_create` | ❌ | 创建任务（对标 Claude Code） |
| `task_list` | ✅ | 列出任务，支持状态过滤 |
| `task_update` | ❌ | 更新任务状态/依赖 |

**交互工具**：

| 工具 | 只读 | 说明 |
|------|------|------|
| `ask_user` | ✅ | LLM 在关键决策点向用户提问 |
| `get_current_time` | ✅ | 获取当前时间 |

**子 Agent 工具**：

| 工具 | 只读 | 说明 |
|------|------|------|
| `spawn` | ❌ | 动态创建子 Agent 执行任务 |
| `spawn_check` | ✅ | 检查子 Agent 任务状态 |

---

### 5.9 身份系统 (Identity)

**三件套**：`.nanobot/` 目录下的三个 Markdown 文件，控制 Agent 的行为和个性。

| 文件 | 说明 | 示例 |
|------|------|------|
| `SOUL.md` | Agent 的核心身份定义（名字、使命、底线） | "你是 my-nanobot，永远不说自己是 Claude" |
| `IDENTITY.md` | 个性特征（语气、风格、偏好） | "回答简洁，用中文，代码块标注语言" |
| `USER.md` | 用户信息（称呼、偏好、上下文） | "用户叫小王，偏好 Java 17，Mac 环境" |

**System Prompt 注入**（首位+近因效应）：
```
getSystemPrompt(currentDate) =
    【开头 — 首位效应】
    名字声明 + 日期覆盖 + 身份信息
    ↓
    （中间是 NANOBOT.md + Plan Mode + Rules）
    ↓
    【结尾 — 近因效应】
    再次强调身份 + 工具结果格式说明
```

组件：`IdentityManager` → `IdentityLoader`（静态工厂方法，从 Markdown 文件加载）→ `Soul` / `Identity` / `UserProfile`（前端模型类）

---
### 5.10 记忆压缩系统 (Consolidator)

**功能说明**：当对话历史超过 token 预算时，自动压缩历史消息，保留关键信息。

**压缩策略**：
1. 计算当前对话的 token 使用量
2. 如果超过预算，选择最不重要的消息进行压缩
3. 使用 LLM 对选中的消息进行总结
4. 用总结替换原始消息

**优先级规则**：
- 系统消息：最高优先级，不压缩
- 工具调用结果：高优先级，保留关键结果
- 用户消息：中等优先级，可适当压缩
- 助手消息：低优先级，优先压缩

**核心方法**：
```java
// 压缩消息列表
CompletableFuture<List<Map<String, Object>>> consolidate(List<Map<String, Object>> messages)

// 检查是否需要压缩
boolean needsConsolidation(List<Map<String, Object>> messages)

// 获取当前 token 使用量
int getCurrentUsage(List<Map<String, Object>> messages)
```
### 5.11 长期记忆系统 (Dream)

**功能说明**：实现 AI Agent 的长期记忆功能，允许 Agent 存储和检索长期信息。

**核心功能**：
1. **记忆存储** - 将对话中的关键信息保存到长期记忆
2. **记忆检索** - 根据当前上下文检索相关记忆
3. **记忆整合** - 将新信息与现有记忆融合
4. **记忆清理** - 移除过时或重复的记忆

**记忆结构**：
| 字段 | 说明 |
|------|------|
| id | 唯一标识 |
| content | 记忆内容 |
| keywords | 关键词标签 |
| timestamp | 创建时间 |
| importance | 重要性分数 (0-1) |
| source | 来源会话 ID |

**核心方法**：
```java
// 从对话中提取并存储记忆
CompletableFuture<List<MemoryEntry>> extractAndStore(String sessionId, List<Map<String, Object>> messages)

// 检索相关记忆
CompletableFuture<List<MemoryEntry>> retrieve(String query, int limit)

// 整合新信息到现有记忆
CompletableFuture<MemoryEntry> consolidate(MemoryEntry newMemory)

// 清理过时记忆
void cleanup()
```
### 5.12 安全系统详解

**PermissionMode 四种模式**：

| 模式 | 只读工具 | 文件编辑 | Shell | 场景 |
|------|---------|---------|-------|------|
| `PLAN` | ✅ 放行 | ❌ 拒绝 | ❌ 拒绝 | 代码审查、出计划 |
| `DEFAULT` | ✅ 放行 | 🔶 需确认 | 🔶 需确认 | 日常使用 |
| `ACCEPT_EDITS` | ✅ 放行 | ✅ 放行 | 🔶 需确认 | 信任的编码会话 |
| `BYPASS` | ✅ 放行 | ✅ 放行 | ✅ 放行 | 完全信任的自动化 |

**权限检查管道**（4 步顺序执行）：

```
Tool 调用
  → 1. PreToolUseHook 链 (deny/allow/modify/passthrough)
  → 2. Guards 守卫 (PathGuard → CommandGuard → NetworkGuard，永远执行)
  → 3. RuleEngine 规则 (deny → ask → allow 优先级链)
  → 4. PermissionMode 判定 (PLAN/DEFAULT/ACCEPT_EDITS/BYPASS)
  → execute() 或 deny
```

**Guard 守卫层**（Strategy 模式）：

| Guard | 检查内容 | 适用工具 |
|-------|---------|---------|
| `PathGuard` | 文件路径必须在工作区内 | read_file, write_file, glob, grep 等 |
| `CommandGuard` | Shell 命令黑/白名单过滤 | exec |
| `NetworkGuard` | URL SSRF 防护，IP 范围过滤 | web_fetch, web_search |

**RuleEngine**：`deny规则` > `ask规则`（需交互确认） > `allow规则` > `默认(模式判定)`

**交互式权限确认**（CLI 模式）：
- `1=允许` 执行本次
- `2=之后都放行` 当前进程内信任，不再询问
- `3=拒绝`

---
### 5.13 命令系统 (Command)

**设计模式**：Command 模式。所有 CLI/WebSocket/HTTP 命令通过 `CommandRegistry` 统一分发。

```
┌─────────────────────────────────────────────┐
│               CommandRegistry               │
│  /exit  /help  /init  /mode  /resume       │
│          /plan  /clear                      │
└────────────────────┬────────────────────────┘
                     │ execute(ctx, input)
                     ▼
┌─────────────────────────────────────────────┐
│              Command 接口                    │
│  name() + aliases() + description()         │
│  execute(CommandContext, String) → boolean  │
└─────────────────────────────────────────────┘
```

**CommandContext**：record 类型，注入组件依赖（ToolRegistry, PermissionManager, AgentLoop, sessionId, shutdown）

**命令清单**：

| 命令 | 别名 | 说明 |
|------|------|------|
| `/exit` | `/q`, `/quit` | 退出 CLI 进程 |
| `/help` | — | 显示所有可用命令 |
| `/init` | — | 分析项目生成 NANOBOT.md（混合：Java收集元数据+LLM生成内容） |
| `/mode` | `/plan` | 切换权限模式。`/mode plan` 进入规划模式，`/plan approve` 审批执行 |
| `/resume` | — | 列出最近会话，`/resume <key>` 恢复指定会话 |
| `/clear` | — | 清空当前会话上下文（inline 处理，不走 Command 接口） |

**Plan Mode 工作流**（`/mode plan` → `/plan approve`）：
```
/mode plan  → AgentLoop.planMode = true
            → PermissionMode.PLAN (只读)
            → 工具列表过滤为只读 (getDefinitions(true))
            → System Prompt 注入规划模式指令
            → LLM 只能探索 + 出计划，不能写代码

/plan approve  → planMode = false
              → PermissionMode.ACCEPT_EDITS
              → 自动发送执行指令到 AgentLoop
```

---
### 5.14 MCP (Model Context Protocol) 系统

**MCP**（Model Context Protocol）是由 Cursor 编辑器提出的标准化协议，用于连接 AI Agent 与外部工具/服务。Nanobot 通过 MCP 支持，可以动态加载和使用第三方工具，而无需修改核心代码。

#### MCP 架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                        权限管道 (Permission Pipeline)                │
│                                                                      │
│  Tool 调用 → PreToolUseHook → Guards → Rules → Mode → execute / deny │
│                                                                      │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐    │
│  │ PreToolUseHook   │  │     Guards       │  │   Rules + Mode   │    │
│  │ (Hook 链)        │  │ (永远执行)       │  │ (按优先级判定)   │    │
│  │                  │  │                  │  │                  │    │
│  │ DENY/ALLOW/      │  │ PathGuard        │  │ RuleEngine       │    │
│  │ MODIFY/          │  │ CommandGuard     │  │ deny→ask→allow   │    │
│  │ PASSTHROUGH      │  │ NetworkGuard     │  │                  │    │
│  └──────────────────┘  └──────────────────┘  │ PermissionMode   │    │
│                                               │ PLAN/DEFAULT/    │    │
│                                               │ ACCEPT_EDITS/    │    │
│                                               │ BYPASS           │    │
│                                               └──────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

#### MCP 架构

```
┌─────────────────────────────────────────────────────────────┐
│                      MCP 服务器层                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐      │
│  │ Git MCP  │  │ 天气 MCP │  │  其他 MCP 服务...     │      │
│  └────┬─────┘  └────┬─────┘  └──────────┬───────────┘      │
└───────┼─────────────┼────────────────────┼─────────────────┘
        │             │                    │
        ▼             ▼                    ▼
┌─────────────────────────────────────────────────────────────┐
│                    MCP 客户端层                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │   StdioMCPClient    │    HttpMCPClient               │   │
│  │   (进程间通信)       │    (HTTP/SSE 通信)            │   │
│  └──────────────────────────────────────────────────────┘   │
│                          │                                  │
│                          ▼                                  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                  MCPManager                           │   │
│  │  - 管理多服务器连接 + 自动发现注册工具 + 生命周期       │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    ToolRegistry                            │
│        MCP 工具包装为 MCPToolWrapper → 统一注册             │
└─────────────────────────────────────────────────────────────┘
```

#### 支持的传输方式

| 传输类型 | 说明 | 适用场景 |
|---------|------|---------|
| **stdio** | 通过标准输入输出与进程通信 | 本地 MCP 服务器（如 git-mcp） |
| **sse** | Server-Sent Events | 实时推送场景 |
| **streamableHttp** | HTTP 流式传输 | 远程 MCP 服务 |

#### MCP 工具命名规则

注册后的工具名称格式：`mcp_<server_name>_<tool_name>`

例如：
- 服务器名 `git` + 工具名 `status` → `mcp_git_status`
- 服务器名 `weather` + 工具名 `get` → `mcp_weather_get`

---
### 5.15 Skills 技能系统

**Skills** 是参考 Claude Code 设计的可复用技能系统，允许用户定义可复用的工作流、指令集和领域知识。Skills 可以通过斜杠命令手动调用，也可以根据对话场景自动触发。

#### Skills 架构

```
┌─────────────────────────────────────────────────────────────┐
│                     技能存储层                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ 项目级技能   │  │  用户级技能  │  │  内置技能    │      │
│  │ .nanobot/    │  │ ~/.nanobot/  │  │  (内置)     │      │
│  │ skills/      │  │ skills/      │  │             │      │
│  └────┬────────┘  └────┬────────┘  └────┬─────────┘      │
│       │                 │                │                  │
└───────┼────────────────┼────────────────┼─────────────────┘
        │                 │                │
        ▼                 ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                    SkillLoader                             │
│        加载 SKILL.md 文件，解析元数据和内容                   │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   SkillRegistry                             │
│        管理所有已注册的技能，支持按名称查找和场景匹配          │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   SkillManager                              │
│        技能管理器：加载、匹配、执行技能                       │
└─────────────────────────────────────────────────────────────┘
```

#### 技能目录结构

```
.nanobot/skills/my-skill/
├── SKILL.md          # 必需：技能定义文件
├── templates/        # 可选：模板文件
├── scripts/          # 可选：辅助脚本
└── resources/        # 可选：资源文件
```

#### SKILL.md 格式

```markdown
---
name: code-review
description: 代码审查：检查代码质量、安全性和最佳实践
argument-hint: "[file-path]"
auto-trigger: true
---
# 代码审查指南

## 审查要点

1. **代码风格**
   - 检查命名规范
   - 检查代码格式
   - 检查注释完整性

2. **安全性**
   - SQL 注入风险
   - 敏感信息泄露
   - 输入验证

3. **性能**
   - 不必要的循环
   - 重复计算
   - 资源泄漏
```

#### 技能类型

| 类型 | 说明 | 示例 |
|------|------|------|
| **参考型** | API/Library 使用参考 | 内部库使用说明 |
| **验证型** | 产品验证和测试 | 注册流程验证 |
| **数据型** | 数据获取和分析 | 指标查询 |
| **自动化型** | 业务流程自动化 | 部署流程 |
| **审查型** | 代码审查和安全检查 | 代码审查、安全扫描 |

#### 调用方式

**1. 手动调用（斜杠命令）**
```
/code-review src/main/java/
/doc-generator
/refactor
```

**2. 自动触发**
当对话内容匹配技能描述中的关键词时，技能会自动触发。

**3. 带参数调用**
```
/code-review src/main/java/MyClass.java
```

#### 核心组件

| 组件 | 职责 | 文件位置 |
|------|------|----------|
| **Skill** | 技能接口定义 | `skill/Skill.java` |
| **SkillMetadata** | 技能元数据 | `skill/SkillMetadata.java` |
| **SkillLoader** | 技能加载器 | `skill/SkillLoader.java` |
| **SkillRegistry** | 技能注册中心 | `skill/SkillRegistry.java` |
| **SkillManager** | 技能管理器 | `skill/SkillManager.java` |

#### 使用示例

```java
// 创建技能管理器
SkillManager skillManager = new SkillManager(config);

// 加载技能
skillManager.loadSkills();

// 手动调用技能
String result = skillManager.executeSkill("code-review", context, "src/main/java/");

// 解析斜杠命令
SkillManager.SkillCall call = skillManager.parseSlashCommand("/review src/main/");
if (call != null) {
    String skillResult = skillManager.executeSkill(call.skillName(), context, call.args());
}

// 查找匹配的技能（自动触发）
List<Skill> matches = skillManager.findMatchingSkills("帮我审查代码");
```

---
### 5.16 Rules 规则系统

**Rules** 是参考 Claude Code 的设计理念实现的全局规则系统，通过自然语言指令定义 Agent 的行为规范。规则告诉模型"在这个项目中你应该遵循什么规范"。

#### Rules 架构

```
┌─────────────────────────────────────────────────────────────┐
│                     规则存储层                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   CLAUDE.md  │  │ .nanobot/    │  │ ~/.nanobot/  │      │
│  │ (项目根目录)  │  │ rules/*.md   │  │ rules/*.md   │      │
│  └────┬────────┘  └────┬────────┘  └────┬─────────┘      │
│       │                 │                │                  │
└───────┼────────────────┼────────────────┼─────────────────┘
        │                 │                │
        ▼                 ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                    RuleLoader                              │
│        加载规则文件，解析元数据和内容                        │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   RuleRegistry                             │
│        管理所有已注册的规则，支持按优先级排序和筛选            │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   RuleManager                              │
│        规则管理器：加载、合并、生成提示词                     │
└─────────────────────────────────────────────────────────────┘
```

#### 规则文件位置

| 级别 | 路径 | 优先级 | 说明 |
|------|------|--------|------|
| **项目级** | `CLAUDE.md` | 高 | 项目根目录下的规则文件 |
| **项目级** | `.nanobot/rules/*.md` | 中 | 项目专属规则目录 |
| **用户级** | `~/.nanobot/rules/*.md` | 低 | 当前用户全局规则 |
| **内置** | Built-in | 最低 | 系统默认规则 |

#### 规则文件格式

```markdown
---
name: coding-style
description: Java 代码风格规范
priority: 10
enabled: true
tags: [java, coding]
---
# Java 代码风格规范

## 命名规范
- 类名：使用 PascalCase
- 方法名：使用 camelCase
- 变量名：使用 camelCase

## 格式规范
- 每行代码不超过 120 字符
- 使用 4 空格缩进
```

#### 规则类型

| 类型 | 说明 | 示例 |
|------|------|------|
| **代码风格** | 代码命名和格式规范 | 命名规范、缩进规则 |
| **安全规则** | 安全编码规范 | 输入验证、敏感信息保护 |
| **响应规则** | 回复格式规范 | 语言要求、输出格式 |
| **业务规则** | 特定业务规则 | 项目特定规范 |

#### 优先级机制

规则按优先级排序后合并到系统提示词中：
- 数字越小，优先级越高
- 高优先级规则会在提示词中靠前显示
- 内置规则默认优先级为 100-150

#### 核心组件

| 组件 | 职责 | 文件位置 |
|------|------|----------|
| **Rule** | 规则接口定义 | `rules/Rule.java` |
| **RuleLoader** | 规则加载器 | `rules/RuleLoader.java` |
| **RuleRegistry** | 规则注册中心 | `rules/RuleRegistry.java` |
| **RuleManager** | 规则管理器 | `rules/RuleManager.java` |

#### 使用示例

**1. 创建项目级规则文件 CLAUDE.md**：
```markdown
---
name: project-guide
description: 项目指南
priority: 5
---
# 项目指南

## 关于此项目
这是一个 Nanobot-Java AI Agent 项目。

## 开发规范
- 使用 Java 17
- 遵循 Spring Boot 代码风格
- 编写单元测试
```

**2. 创建规则目录**：
```bash
mkdir -p .nanobot/rules
touch .nanobot/rules/coding-style.md
```

**3. 代码中使用**：
```java
RuleManager ruleManager = new RuleManager(config);
ruleManager.loadRules();

// 获取合并后的规则提示词
String rulesPrompt = ruleManager.getRulesPrompt();

// 将规则注入到系统提示词中
String systemPrompt = "你是一个 AI Agent。\n\n" + rulesPrompt;
```

---
### 5.17 多通道接入系统 (ChannelServer)

**功能说明**：提供 HTTP 和 WebSocket 通道的统一管理，允许客户端通过多种方式与 Agent 交互。

**支持的通道类型**：

| 通道类型 | 说明 | 端点 |
|---------|------|------|
| HTTP REST | 同步请求/响应模式 | `/api/chat` |
| WebSocket | 异步双向通信 | `/ws` |

**HTTP API 端点**：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/chat` | POST | 同步发送消息 |
| `/api/chat/stream` | POST | SSE 流式发送消息 |
| `/api/sessions` | GET | 获取会话列表（含名称、消息数） |
| `/api/sessions/{key}` | GET | 获取会话详情（消息历史） |
| `/api/sessions/{key}` | PATCH | 重命名会话 `{"name":"..."}` |
| `/api/sessions/{key}` | DELETE | 删除会话 |
| `/api/health` | GET | 健康检查 |

**消息参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `content` | String | 消息内容（必填） |
| `chat_id` | String | 会话 ID（可选） |
| `useSearch` | Boolean | 是否启用联网搜索（可选，默认 true） |

**useSearch 参数说明**：
- `true`（默认）：允许 LLM 调用 web_search 工具进行联网搜索
- `false`：禁用所有工具调用，LLM 将直接回答问题，不进行联网搜索

**使用示例**：
```json
POST /api/chat
{
  "content": "今天天气怎么样？",
  "useSearch": true
}
```

**使用示例**：
```java
ChannelServer server = new ChannelServer(messageBus, 8080);
server.start();
```
### 5.18 定时任务系统 (CronScheduler)

> 非核心功能，按"从能跑到跑得好"原则后置。

**功能说明**：基于 cron 表达式的定时任务调度器，支持在指定时间执行任务。

**支持的 cron 表达式格式**：
```
┌───────────── 分钟 (0 - 59)
│ ┌───────────── 小时 (0 - 23)
│ │ ┌───────────── 日期 (1 - 31)
│ │ │ ┌───────────── 月份 (1 - 12)
│ │ │ │ ┌───────────── 星期 (0 - 6) (周日=0)
│ │ │ │ │
* * * * *
```

**使用示例**：
```java
CronScheduler scheduler = new CronScheduler(messageBus);
scheduler.schedule("0 * * * *", () -> {
    messageBus.publish(OutboundMessage.builder()
        .channel("system")
        .content("每分钟执行一次")
        .build());
});
```

**支持的特殊字符**：
- `*` : 匹配任何值
- `?` : 不指定值（用于日期和星期）
- `,` : 列出多个值
- `-` : 指定范围
- `/` : 步长
### 5.19 V3 CLI 模式 (CliChannel)

**入口**：`NanobotCliApplication` → Spring Boot 容器 → `CliChannel.start()`

```
┌───────────────────────────────────────────────┐
│                 CliChannel                     │
│  ┌──────────┐  ┌──────────┐  ┌─────────────┐  │
│  │ Scanner  │  │ JLine    │  │ Markdown     │  │
│  │ (行输入) │  │ Terminal │  │ Renderer     │  │
│  │          │  │ (Esc检测)│  │ (流式渲染)   │  │
│  └──────────┘  └──────────┘  └─────────────┘  │
│  ┌──────────────────────────────────────────┐  │
│  │  交互式权限确认 (1/2/3 数字选择)          │  │
│  │  AskUser 工具注入 (CLI stdin)            │  │
│  │  流式响应回调 (MarkdownRenderer)         │  │
│  └──────────────────────────────────────────┘  │
└───────────────────────────────────────────────┘
```

**关键特性**：
- **JLine Terminal**：跨平台 Esc 键检测，独立于 Scanner 的原始按键流
- **MarkdownRenderer**：流式输出 Markdown 内容（代码块、标题、列表）
- **交互式权限**：`1=允许 2=之后都放行 3=拒绝`
- **流式取消**：`Esc` 中断当前回复
- **命令系统**：`/` 前缀自动路由到 CommandRegistry
- **会话恢复**：`--resume <key>` 启动参数

**消息发送流程**：
```
用户输入 → scanner.nextLine()
    → 命令? → CommandRegistry.execute()
    → 消息? → InboundMessage(sessionId, content, channel="cli")
            → MessageBus.publishInbound()
            → 等待流式完成 (currentRequestId)
```

---
### 5.20 V2 Spring Boot 模式

**入口**：`NanobotApplication` → 内嵌 Tomcat + Spring MVC

**REST API**：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/chat` | POST | 同步聊天，`waitForSessionResponse` 阻塞等待 |
| `/api/chat/stream` | POST | SSE 流式聊天，`SseEmitter` 实时推送 |
| `/api/sessions` | GET | 列出所有会话 |
| `/api/sessions/{key}` | GET | 获取会话消息历史 |
| `/api/sessions/{key}` | PATCH | 重命名会话 `{"name":"..."}` |
| `/api/sessions/{key}` | DELETE | 删除会话 |
| `/api/health` | GET | 健康检查 |

**前端页面**：
- `/` — `index.html`（聊天界面）
- `/sessions.html` — 会话管理（列表、查看详情、重命名、删除）

**WebSocket**：`NanobotWebSocketEndpoint`（`/ws`），Jakarta WebSocket 注解，流式响应通过 `StreamResponseCallback` → `session.sendText()`

**配置类**：`NanobotConfig` 创建 Spring Bean（MessageBus, ToolRegistry, AgentLoop, SessionManager 等），注入到 `NanobotRunner` 静态字段

---
### 5.21 V1 独立模式

**入口**：`Nanobot.java`，手动组装所有组件，无需 Spring。

**ChannelServer**：内嵌 HTTP 服务器（`com.sun.net.httpserver.HttpServer`）
- `ChatHandler` — 处理 `POST /api/chat`，支持同步和 SSE 流式
- `WebSocketHandler` — WebSocket 升级握手
- `WebSocketConnection` — 原始 WebSocket 帧读写（`WebSocketFrame` 手动解析/组装）

**WebSocketFrame**：WebSocket 协议帧的完整实现
- `text(msg)` / `close()` / `ping()` / `pong()` 工厂方法
- `send(OutputStream)` 序列化帧
- `parse(InputStream)` 反序列化帧（含掩码处理）

---
### 5.22 子 Agent 系统 (Subagent)

**核心组件**：

| 组件 | 说明 |
|------|------|
| `Subagent` | 子 Agent 接口：`execute(task)` 异步执行任务 |
| `SubagentContext` | 子 Agent 上下文：任务数据 + 共享状态 + 父子通信 |
| `SubagentCommunication` | 通信管理器：消息队列、事件发布/订阅、共享状态存储 |
| `AgentCoordinator` | 协调器：任务分配、能力匹配、并行执行、结果汇总 |
| `FileInbox` | 文件邮箱：基于文件系统的异步通信（`.nanobot/inbox/`） |
| `SimpleSubagent` | 简单实现：使用独立 LLM 调用执行子任务 |

**通信模式**：
```
主 Agent (Lead)                子 Agent 1              子 Agent 2
     │                             │                       │
     ├─ send(task) ──────────────→ │                       │
     │                             ├─ execute(task)        │
     │                             └─ sendToParent(result) →│
     ├─ broadcast(msg) ───────────┼──────────────────────→ │
     │                             │                       │
     ├─ receive() ←───────────────┤                       │
     └─ 汇总结果                                          │
```

**SubagentCommunication 核心方法**：
- `send(from, to, msg)` — 点对点消息
- `broadcast(from, msg)` — 广播给所有子 Agent
- `sendToParent(childId, msg)` — 子→父汇报
- `receive(agentId)` — 非阻塞接收
- `setSharedState(key, value)` / `getSharedState(key)` — 跨 Agent 共享状态
- `registerParentChild(parent, child)` — 注册父子关系

**SpawnTool**：主 Agent 通过 `spawn` 工具动态创建子 Agent 并分配任务。

---
## 六、新手学习路线

### 6.1 学习阶段规划

| 阶段 | 目标 | 时间 | 关键知识点 |
|------|------|------|-----------|
| **Phase 1** | 环境搭建与入门 | 1-2 天 | Java 17, Maven, 项目结构 |
| **Phase 2** | 核心组件理解 | 3-5 天 | 状态机、消息总线、工具系统 |
| **Phase 3** | 深入核心机制 | 3-5 天 | LLM 调用、会话管理、钩子系统 |
| **Phase 4** | 扩展开发 | 3-5 天 | 自定义工具、新提供商、通道扩展 |

### 6.2 Phase 1：环境搭建与入门

**目标**：搭建开发环境，了解项目结构，成功运行项目

#### 步骤 1：环境准备

- 安装 JDK 17+
- 安装 Maven 3.9+
- 配置 IDE（IntelliJ IDEA 推荐）

#### 步骤 2：项目结构探索

查看目录结构，理解各模块职责：
- `src/main/java/com/nanobot/` - 源代码
- `src/main/resources/` - 配置文件
- `pom.xml` - Maven 依赖管理
- `scripts/` - 启动脚本

#### 步骤 3：配置运行

1. 配置 API Key（DeepSeek 或 OpenAI）：
   - 创建 `~/.nanobot/config.yaml`
   - 或设置环境变量 `DEEPSEEK_API_KEY` / `OPENAI_API_KEY`

2. 编译项目：
```bash
mvn clean compile
```

3. CLI 模式运行（推荐）：
```bash
./scripts/nanobot                    # Mac/Linux
scripts\nanobot.bat                  # Windows
```

4. 或 Spring Boot 模式：
```bash
mvn spring-boot:run
```

### 6.3 Phase 2：核心组件理解

**目标**：理解状态机、消息总线、工具系统的设计

#### 学习路径：

1. **Nanobot.java** - 主入口
   - 组件初始化流程
   - 生命周期管理

2. **AgentLoop.java** - 状态机引擎
   - 状态转换逻辑
   - 消息处理流程

3. **MessageBus.java** - 消息总线
   - 异步队列机制
   - 入站/出站消息模型

4. **Tool.java + ToolRegistry.java** - 工具系统
   - 工具接口设计
   - 工具注册与执行

#### 实践练习：

1. 跟踪一条消息从进入到响应的完整流程
2. 添加一个简单的自定义工具
3. 调试状态机转换过程

### 6.4 Phase 3：深入核心机制

**目标**：理解 LLM 调用、会话管理、钩子系统

#### 学习路径：

1. **AgentRunner.java** - LLM 调用循环
   - 多轮工具调用
   - 上下文治理

2. **LLMProvider.java** - 提供商接口
   - 流式与非流式调用
   - 工具调用处理

3. **SessionManager.java** - 会话管理
   - 历史存储与加载
   - 会话隔离

4. **AgentHook.java** - 钩子系统
   - 生命周期扩展点
   - CompositeHook 组合模式

#### 实践练习：

1. 实现一个自定义 LLMProvider
2. 添加会话压缩逻辑
3. 实现一个监控钩子

### 6.5 Phase 4：扩展开发

**目标**：能够扩展新功能

#### 学习路径：

1. **添加新工具**
   - 实现 Tool 接口
   - 注册到 ToolRegistry

2. **添加新提供商**
   - 实现 LLMProvider 接口
   - 处理不同 API 格式

3. **添加新通道**（未来扩展）
   - 实现通道接口
   - 集成到消息总线

#### 实践练习：

1. 创建一个天气查询工具
2. 实现 Anthropic 提供商
3. 添加 WebSocket 通道

---

## 七、关键技术选型

| 功能 | 技术方案 | 理由 |
|------|---------|------|
| **运行环境** | Java 17 LTS | 生产环境主流版本 |
| **异步编程** | CompletableFuture + ExecutorService | 标准异步编程模型 |
| **JSON 处理** | Jackson | 成熟稳定，支持 Schema 验证 |
| **HTTP 客户端** | HttpClient (Java 11+) | 内置，支持异步和流式 |
| **终端输入** | JLine 3 | 跨平台原始按键读取（Esc 中断） |
| **配置管理** | Jackson + YAML | 支持复杂嵌套配置 |
| **日志** | SLF4J + Logback | Java 标准日志框架 |
| **Web 框架** | Spring Boot 3.2 | V2 模式 HTTP/SSE/WS |
| **构建部署** | Maven + Fat JAR + shell/bat 脚本 | 跨平台一键启动 |
| **文件 I/O** | NIO.2 (Files/Paths) | 现代文件 API |

---

## 八、配置说明

### 7.1 配置文件位置

```
src/main/resources/config/config.yaml  ← 项目默认配置（内置）
~/.nanobot/config.yaml                 ← 用户自定义覆盖
```

CLI 启动时通过 `--workspace` 指定工作目录，Spring Boot 模式从 classpath 加载。

### 7.2 配置结构

```yaml
agents:
  defaults:
    model: "deepseek-chat"          # 默认模型（ProviderFactory 自动匹配）
    workspace: ".nanobot/workspace"
    maxTokens: 8192
    contextWindowTokens: 200000
    temperature: 0.7
    maxToolIterations: 100
    maxTurns: 0                    # 0=不限制
    maxCost: 0                     # 0=不限制，单位美元
    timezone: "UTC"

providers:
  openai:
    apiKey: ""                     # 优先读环境变量 OPENAI_API_KEY
    apiBase: "https://api.openai.com/v1"
  deepseek:
    apiKey: ""                     # 优先读环境变量 DEEPSEEK_API_KEY
    apiBase: "https://api.deepseek.com"

tools:
  exec:
    enable: true                   # Shell 命令执行（生产环境建议 false）
  web:
    enable: true
    search:
      provider: "baidu_web"
      maxResults: 5
      timeout: 30

mcp_servers:                       # MCP 服务器（可选）
  git:
    command: "npx"
    args: ["-y", "git-mcp@latest"]
    tool_timeout: 30

channels:
  send_progress: true              # 发送进度消息

memory:
  dream:
    maxMemories: 100               # 长期记忆最大条数
```

### 7.3 环境变量

| 变量名 | 说明 | 适用 Provider |
|--------|------|--------------|
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥 | deepseek-chat |
| `OPENAI_API_KEY` | OpenAI API 密钥 | gpt-4o, o1, o3 等 |
| `JAVA_HOME` | JDK 路径 | 启动脚本使用 |

> `ProviderFactory` 根据 `model` 字段自动匹配 Provider：`deepseek*` → DeepSeekProvider，`gpt-*/o1/o3/o4` → OpenAIProvider，其他 → OpenAIProvider（兜底）。API Key 优先读配置文件，没有则读环境变量。

---

## 九、编译、启动与部署

### 8.1 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| Java | 17+ | 核心运行环境 |
| Maven | 3.9+ | 构建工具 |
| Git | 2.x | 版本控制（可选） |

### 8.2 编译项目

```bash
# 编译项目
mvn clean compile

# 运行测试
mvn test

# 打包项目（包含依赖）
mvn clean package

# 打包跳过测试
mvn clean package -DskipTests
```

### 8.3 启动运行

#### V3 CLI 模式（推荐）

```bash
# 全局安装后，在任意目录下运行
nanobot

# 或直接启动 JAR
java -jar target/nanobot-cli.jar

# 指定工作目录
nanobot --workspace /path/to/project
```

**CLI 交互命令**：
| 命令 | 说明 |
|------|------|
| `/help` | 列出所有命令 |
| `/init` | 分析项目生成 NANOBOT.md |
| `/mode plan` 或 `/plan` | 进入规划模式（只读分析出计划） |
| `/plan approve` | 审批计划并开始执行 |
| `/resume` | 列出/恢复历史会话 |
| `/clear` | 清空会话上下文 |
| `Esc` | 中断当前流式回复 |

#### V2 Spring Boot 模式

```bash
mvn spring-boot:run
# 访问 http://localhost:8080 (聊天) /sessions.html (会话管理)
```

#### V1 独立模式

```bash
mvn exec:java -Dexec.mainClass="com.nanobot.v1.Nanobot"
```

### 8.4 配置环境变量

```bash
# Linux / Mac — 加到 ~/.bashrc 或 ~/.zshrc
export DEEPSEEK_API_KEY=your-api-key
export JAVA_HOME=/path/to/jdk17

# Windows PowerShell
$env:DEEPSEEK_API_KEY="your-api-key"
```

### 8.5 自动重建（scripts/nanobot）

`scripts/nanobot` 脚本会自动检测源码变更并重新打包：

```bash
#!/bin/bash
JAR="$SCRIPT_DIR/target/nanobot-cli.jar"

# 源码有更新？自动 mvn package
if [[ ! -f "$JAR" ]] || [[ -n "$(find src/main/java -name '*.java' -newer "$JAR")" ]]; then
    echo "(source changed, rebuilding...)"
    (cd "$SCRIPT_DIR" && mvn package -DskipTests -q)
fi

java -jar "$JAR" "$@"
```

### 8.6 Docker 部署（可选）

```dockerfile
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY target/nanobot-cli.jar app.jar
ENV DEEPSEEK_API_KEY=your-api-key
CMD ["java", "-jar", "app.jar"]
```

### 8.7 常见启动问题

| 问题 | 解决方案 |
|------|----------|
| API Key 未设置 | 检查环境变量 `OPENAI_API_KEY` |
| 端口被占用 | 修改配置中的端口或停止占用进程 |
| 依赖下载失败 | 使用 `mvn clean compile -U` 强制更新 |
| 内存不足 | 增加 JVM 堆内存 `-Xmx2g` |

---

## 十、调试与日志

### 10.1 日志配置

日志配置文件：`src/main/resources/logback.xml`

日志级别：
- `DEBUG` - 详细调试信息
- `INFO` - 一般信息
- `WARN` - 警告
- `ERROR` - 错误

### 10.2 调试技巧

1. 设置断点跟踪状态机转换
2. 查看消息队列的入队出队
3. 监控 LLM 调用的请求/响应
4. 使用钩子记录执行时间

---

## 十一、扩展建议

### 11.1 添加新工具

```java
public class WeatherTool implements Tool {
    @Override
    public String getName() { return "get_weather"; }
    
    @Override
    public String getDescription() { 
        return "获取指定城市的天气信息"; 
    }
    
    @Override
    public JsonNode getParameters() { /* JSON Schema */ }
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        String city = (String) params.get("city");
        // 调用天气 API
        return CompletableFuture.completedFuture("天气结果...");
    }
}

// 注册
toolRegistry.register(new WeatherTool());
```

### 11.2 添加新提供商

```java
public class AnthropicProvider implements LLMProvider {
    @Override
    public String getName() { return "anthropic"; }
    
    @Override
    public CompletableFuture<LLMResponse> chat(List<Message> messages, List<JsonNode> tools) {
        // 调用 Anthropic API
    }
}
```

### 11.3 配置 MCP 服务器

MCP 允许动态加载第三方工具，无需修改代码：

```yaml
mcp_servers:
  git:
    command: "npx"
    args: ["-y", "git-mcp@latest"]
    tool_timeout: 30
```

### 11.4 添加自定义 MCP 客户端

```java
public class CustomMCPClient implements MCPClient {
    @Override
    public CompletableFuture<MCPResult> callTool(String toolName, Map<String, Object> arguments) {
        // 实现自定义通信逻辑
    }
}
```

---

## 十二、常见问题

### Q1：如何配置 API Key？

A：可以通过配置文件或环境变量：
```yaml
providers:
  openai:
    api_key: your-api-key
```
或
```bash
export OPENAI_API_KEY=your-api-key
```

### Q2：如何添加自定义工具？

A：实现 `Tool` 接口，然后在 `Nanobot.initialize()` 中注册。

### Q3：如何扩展支持其他 LLM？

A：实现 `LLMProvider` 接口，处理特定 API 的格式差异。

### Q4：项目依赖哪些库？

A：主要依赖：
- Jackson（JSON 处理）
- SLF4J + Logback（日志）
- OkHttp（HTTP 客户端）

---

## 十三、学习资源

1. **官方文档**：`docs/` 目录下的文档
2. **架构分析**：`nanobot_architecture_analysis.md`
3. **测试代码**：参考现有工具和提供商实现
4. **原始项目**：`../nanobot/` 目录下的 Python 源码

---

## 十四、总结

Nanobot-Java 的核心价值在于：

1. **清晰的分层架构**：State 模式状态机 + 策略工厂 + Repository 分离
2. **灵活的扩展能力**：插件化工具系统、可注册 Provider 策略、Command 模式命令
3. **多种设计模式**：State、Strategy、Command、Chain of Responsibility、Template Method、Repository
4. **类 Claude Code 的 CLI 体验**：JLine 终端、Markdown 渲染、Plan Mode、Esc 中断
5. **新手友好**：清晰的模块划分和详细的注释

**建议学习顺序**：
1. 从 `NanobotRunner.java` 入口开始，理解组件初始化
2. 深入 `AgentLoop.java` + `core/state/`，理解 State 模式状态机
3. 学习 `AgentRunner.java`，理解 LLM 调用 + 工具执行循环
4. 研究 `ProviderFactory.java`，理解策略工厂模式
5. 研究 `Tool.java` 和 `Command.java`，了解扩展机制

祝你学习愉快！🚀
