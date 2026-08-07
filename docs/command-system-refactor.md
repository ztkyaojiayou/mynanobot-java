# 命令系统统一重构设计文档

> 状态：已实施（2026-08-07）
> 日期：2026-08-07

---

## Context

nanobot 命令系统当前"三分天下"，管理混乱：
1. **CommandState 内置命令**（`core/state/CommandState.java`）：/stop /clear /compact /remember /skills /rules /stats，走 AgentLoop 状态机，仅 Web 通道可触发。
2. **CommandRegistry 注册命令**（`command/`）：/mode /help /init /resume，仅 CLI 用。
3. **CLI 硬编码**（`v3/cli/CliChannel.java` L335-343）：/clear /exit /history 在输入循环里 if 分支。

**已知 bug**：CLI 拦截所有 `/` 开头行，未注册命令直接报"未知命令"——所以 `/stop /compact /remember /skills /rules /stats` 和**技能 slash**在 CLI 下永远不可用。

**目标**（用户明确要求）：
1. 参考 Claude Code 统一注册中心：内置命令 + CLI 硬编码统一进 CommandRegistry。**新命令两种方式**：写一个 Java 类（`Command` 实现）或加一个技能文件（`.nanobot/skills/xxx/SKILL.md`，已有机制，**不新建命令文件机制**）。
2. 高复杂度项不做（/model 运行时换 provider、/undo 文件回退等）。
3. 跨平台适配往后放（! bash 直通、/config、Ctrl+R 等）。

## 核心设计

### 统一注册中心
`CommandRegistry` 成为 CLI 与 Web（CommandState）共同的命令入口。命令类不持有组件，依赖统一经 `CommandContext.agentLoop()` getter 获取（`AgentLoop` 新增 getSessionManager/getMessageBus/getSkillManager/getRuleManager/getConsolidator/getDream/getHookManager）；consolidator/dream 可能为 null 需防御。

### 命令执行优先级（CLI runInputLoop 与 CommandState 两处一致）
1. **registry 内置 Java 命令**（clear/stop/exit/...）— 系统级
2. **技能 slash**（`SkillManager.parseSlashCommand` → executeSkill）— 复用现有技能机制
3. 都不匹配：Web → BUILD（LLM 当普通消息）；CLI → 打印"未知命令"

净效果：CLI 首次能调 `/commit-generator` 等技能（修 bug）；新命令 = 加类 或 加技能文件。

### 接口变更（最小化）
- **`Command.execute(CommandContext, String)` 签名不变**（仍返回 `boolean`，true=退出）。修 `CommandRegistry.execute`（L48-66）**丢弃返回值**的问题：改为返回 `Optional<Boolean>`——empty=未注册（调用方 fallback 技能）、present(true)=已执行且请求退出（/exit）、present(false)=已执行正常继续。
- **`CommandContext` record 增加三个字段**：
  - `sessionKey`：完整存储 key（CLI 为 `cli:`+sessionId，Web 为裸 sessionId）。命令一律用它读写 SessionManager，禁止自己拼前缀（否则 /clear 误清 key，正是 CLI 旧 bug）。
  - `channel`：消息来源通道，/clear 发布 `_session_cleared` 事件时使用（计划最初以为不需要，实际需要）。
  - `out`（`PrintStream`）：命令输出目标。CLI 传 System.out；Web 的 CommandState 传收集 buffer，执行完作为最终响应返回前端（保留原内置命令 setFinalContent 行为）。

## 改动清单

### 新建（command/impl/，逻辑从 CommandState / CliChannel 迁出）
| 文件 | 说明 |
|------|------|
| `ExitCommand.java` | /exit /q /quit → 返回 true（CLI 据此退出）；Web 下调用 `ctx.shutdown()` |
| `ClearCommand.java` | /clear → `getSessionManager().clearSession(ctx.sessionKey())` + 发布 `_session_cleared` 出站事件（从 CommandState L250-263 迁出） |
| `CompactCommand.java` | /compact → `loadHistory` → `consolidator.consolidate()` → **`SessionStore.replaceHistory`** 覆盖写；consolidator null 给提示 |
| `RememberCommand.java` | /remember → `loadHistory` → `dream.extractAndStore()`；dream null 给提示 |
| `SkillsCommand.java` | /skills → `getSkillManager().getRegistry().getHelp()` |
| `RulesCommand.java` | /rules → `ruleManager.getRulesSummary()` |
| `StatsCommand.java` | /stats → 会话+全局+Hook 统计（从 CommandState handleStats 迁出） |
| `StopCommand.java` | /stop → `ctx.agentLoop().cancelCurrentTurn(ctx.sessionKey())` |
| `HistoryCommand.java` | /history → 显示输入历史（CLI 专用，构造注入共享 history 列表） |
| `CostCommand.java` | /cost → 会话历史 token 估算 + 按模型单价表折算成本（当前会话 + 全部会话）；单价表内置常量可调 |
| `PermissionsCommand.java` | /permissions → 权限状态总览（模式/交互确认/守卫/规则/Hook）+ `/permissions <mode>` 切换；与 /mode 互补（/mode 管 plan 工作流） |

### 修改
| 文件 | 变更 |
|------|------|
| `command/CommandContext.java` | record 加 `sessionKey` + `channel` + `out` 三个字段。sessionKey=完整存储 key（CLI `cli:`+id / Web 裸 id）；channel 供 /clear 发布 `_session_cleared` 事件；out 为命令输出目标（CLI=System.out，Web=收集 buffer 作为响应返回） |
| `command/Command.java` | 新增 `default String usage()`（默认空串）：每个命令类以多行文本描述详细用法，供 `/help <命令>` 查询（15 个命令类全部 @Override） |
| `command/CommandRegistry.java` | `execute()` 改返回 `Optional<Boolean>`（修复原丢弃返回值）：empty=未注册（调用方 fallback 技能）、present(true)=已执行且请求退出（/exit）、present(false)=已执行正常继续；`helpText()` 修复 + `listUnique()` 去重供 /help 用；`buildBase()` 工厂注册全部内置命令（每通道新建实例，避免 CLI 追加 /history 污染 Web）；新增 `find(name)` 按名称/别名（可带 / 前缀）查找命令供 /help 参数查询 |
| `core/state/CommandState.java` | 重写：删内置命令 switch（逻辑迁命令类）；持有 `AgentLoop` + `SkillManager` + `CommandRegistry.buildBase()`；`/xxx` → 构建 CommandContext（sessionKey=ctx.getSessionKey()，out=收集 buffer，toolRegistry/permissionManager/shutdown 传 null——内置命令不用）→ registry.execute；执行后把 buffer 内容 setFinalContent 返回给 Web；优先级 registry > 技能 > BUILD |
| `core/AgentLoop.java` | 加 `ConcurrentMap<String, TurnContext> currentTurns` + `cancelCurrentTurn(sessionKey)`（putIfAbsent 同会话只跟踪首个 turn）；processMessage 建 turn 时 put、结束 remove |
| `session/SessionStore.java` | 新增 `replaceHistory(sessionKey, messages)` 覆盖写。**必须**：现有 saveHistory 是追加式（只追加新行，`SessionStore.java` L44-54），/compact 压缩后行数变少会静默不生效 |
| `v3/cli/CliChannel.java` | 删 runInputLoop L335-343 硬编码 if 分支；注册全部内置 + /history（`CommandRegistry.buildBase()`）；registry miss 时**新增技能 fallback**（`SkillManager.parseSlashCommand`）；handleClear/handleExit/showHistory 删除；**引入 JLine 行编辑器**（见下） |
| `command/impl/HelpCommand.java` | 用 registry 全量列出 + 技能列表，去 ANSI（上色交 CLI 后置）；**支持参数**：`/help <命令名>`（支持别名）→ 显示 name/aliases/description/usage；未知命令给提示；无参数保持全量列表 |

### JLine 行编辑器（CLI 输入增强）
| 文件 | 变更 |
|------|------|
| `pom.xml` | 新增 `jline-reader`（LineReader 行编辑/补全）+ `jline-terminal-jansi`（Windows 原生终端支持，Windows Terminal 也能获得真终端） |
| `v3/cli/CliChannel.java` | 新增 `lineReader` 字段 + `buildLineReader()` + `readCliLine()`：Unix / Windows Terminal 用 LineReader，Windows CMD 降级 Scanner（`TerminalBuilder.system(true)` 失败即降级）。`readCliLine` 捕获 EndOfFileException（Ctrl+D→退出）/ UserInterruptException（Ctrl+C→忽略）/ 其他异常（降级 Scanner）；读取后补换行分隔 prompt 与后续输出 |

**补全候选**：Tab 触发，动态实时查询（lambda 捕获 `this.commands`，构造完成后每次补全重查）——内置命令 + 别名 + 技能名，`/` 前缀过滤 + 去重排序。**关键选项**：`DISABLE_EVENT_EXPANSION`（否则 `!ls` 被当历史事件扩展）、`AUTO_LIST`/`AUTO_MENU`（自动列出候选）。

**并发安全**：主线程 `readLine` 期间 `currentRequestId == null` → CancelMonitor 的 while 条件为 false，不读 `terminal.reader()`，不与 LineReader 竞争按键；dialog（`readInteractiveLine`）与 CancelMonitor 共用 `terminalLock`，且只在流式期间（LineReader 空闲）活跃。

## 行为变更点（需验证确认）
- **CLI `/clear` 修 bug**：旧 handleClear 用裸 sessionId 清错 key；统一后 ClearCommand 用 `cli:` 前缀的 sessionKey，真正清对，且多发 `_session_cleared` 事件。
- **CLI 技能 slash 可用**：`/commit-generator` 等从"未知命令"变为真执行（修复原 bug）。
- **/stop 生效链路**：`cancelCurrentTurn` → `TurnContext.cancel()` → AgentRunner 在下一 LLM 迭代边界返回"处理已取消"。单次流式调用不会中途打断（与 Esc 中断是两套机制）。CLI 下主线程阻塞等待流式，实际难输入 /stop，主要为 Web 服务。
- **/skills 命令**与技能 slash 并存：`/skills` 列出技能目录，`/commit-generator` 直接调用某个技能，互不冲突。
- **CLI `!` bash 直通**：`!命令`（如 `!git status`）复用 ExecTool 直接执行 shell（含 Unix→Windows 转换），在 workspace 目录同步运行；与 `!!`（重复上条）/ `!N`（序号）区分，不冲突。
- **/permissions 与 /mode 分工**：/mode 负责模式切换 + plan 工作流（/plan approve）；/permissions 查看权限状态总览 + 快速切换，不碰 plan 状态。
- **/help <命令> 参数查询**：`/help mode` / `/help plan`（别名）→ 显示 name/aliases/description/usage；未知命令提示；无参数仍列出全部命令 + 技能。
- **CLI Tab 斜杠补全**：Unix / Windows Terminal 下输入 `/` 后按 Tab，AUTO_LIST 自动列出所有命令（含别名与技能名），继续输入过滤后再 Tab 精确补全；上下键浏览历史。Windows CMD（无 JLine）降级 Scanner，保持原交互。

## 验证

1. `mvn compile -q` 通过（JDK17）。
2. **冒烟测试** `CommandRegistrySmokeTest`（11 项）：buildBase 注册完整性、execute 三态语义（empty/present/exit）、/clear 用完整 sessionKey（修复裸 sessionId 清错 key）、/stop 无活动轮次不崩溃、/cost 估算、/permissions 状态展示 + 切换 + 无效模式防御、**/help <命令> 显示 usage（含别名）/ 别名解析 / 未知命令提示 / 全命令 usage() 非空**。全量 `mvn test` 仅 McpHttpClientTest 因 Windows 缺 python3 失败（环境无关）。
4. **CLI**：`/help` 列出全部命令（内置 + 注册 + 提示技能）；`/help mode` / `/help plan` 显示单命令用法；`/stats /skills /rules /compact /remember` 不再"未知命令"；`/clear /exit(/q /quit) /history /mode /init /resume` 正常；`!!` `!N` 保留。
5. **CLI 技能**：`/commit-generator` 首次能执行（原来报未知命令）。
6. **/compact**：造长会话后 `/compact`，`.nanobot/sessions/cli_*/history.jsonl` 行数显著减少（验证 replaceHistory）。
7. **Web**：`/stats` 正常；`/clear` 清空前端列表。
8. **回归**：普通对话、工具调用、`/plan`→`/plan approve`、`@file` 引用、Esc 中断均正常。
9. **CLI 行编辑（Unix/Windows Terminal）**：输入 `/` + Tab 自动列出命令候选，过滤后补全；上下键历史；`!ls` 不再被当作历史事件扩展；`/exit` / `Ctrl+D` 退出、`Ctrl+C` 忽略重提示；Windows CMD 降级 Scanner 交互不变。

## 不做（用户明确排除）
/model（运行时换 provider）、/undo、/rewind（文件回退）、> bash 模式、/config、Ctrl+R、/mcp 管理命令、独立命令文件机制（改用技能）。
~~! bash 直通~~ 已做（CLI `!命令` 复用 ExecTool）。
