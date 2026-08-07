# NanoCode 功能文档

> 本文档统一维护所有已实现功能的架构、使用方式和配置说明。新增功能请按模板追加到文末。

---

## 目录

1. [权限控制模块](#1-权限控制模块)
2. [CLI 交互体验模块](#2-cli-交互体验模块)

---

## 1. 权限控制模块

**实现日期**: 2026-07-14
**参考来源**: NanoCode (HKUDS/nanobot) 为主，Claude Code 为辅
**状态**: ✅ 已完成

### 1.1 概述

为 nanocode 构建完整的纵深防御（Defense-in-Depth）权限控制体系。所有 Agent 工具调用在 `ToolRegistry.execute()` 中通过统一的检查管道进行安全校验。

### 1.2 架构

```
PreToolUse Hook → Guards → Rules → Mode → Execute
      ↓              ↓        ↓       ↓
   deny/allow     throw    deny/   allow/
   /modify        SecEx   ask/    deny
  /passthrough           allow
```

**四步检查管道**:
| 步骤 | 层 | 说明 | 可跳过? |
|:--:|------|------|:--:|
| 1 | **Hook** | PreToolUse 钩子链，可 deny/allow/modify/passthrough | — |
| 2 | **Guards** | PathGuard / CommandGuard / NetworkGuard | ❌ 永远执行 |
| 3 | **Rules** | deny → ask → allow 优先级链。ASK 触发 CLI 确认弹框 | ✅ 无匹配时跳过 |
| 4 | **Mode** | PLAN / DEFAULT* / ACCEPT_EDITS / BYPASS | — |

> DEFAULT 模式：有交互处理器时，非只读工具走确认流程（非直接拒绝），解决了"写工具永远被拒"的问题。确认框输出受 `confirmLock` 保护，防止并行工具调用时弹框穿插。

### 1.3 包结构

```
com.nanocode.security
├── PermissionMode.java          # 权限模式枚举
├── PermissionResult.java        # 检查结果
├── PermissionManager.java       # 权限编排器 (Builder模式)
├── guard/
│   ├── SecurityException.java   # 安全异常
│   ├── PathGuard.java           # 文件路径守卫
│   ├── CommandGuard.java        # Shell命令守卫
│   └── NetworkGuard.java        # 网络/SSRF守卫
├── rule/
│   ├── RuleType.java            # 规则类型 (DENY/ASK/ALLOW)
│   ├── PermissionRule.java      # 权限规则 record
│   ├── RuleEngine.java          # 规则引擎
│   └── RuleMatch.java           # 规则匹配结果
└── hook/
    ├── PreToolUseHook.java      # Hook接口 (@FunctionalInterface)
    ├── PreToolUseContext.java   # Hook上下文
    ├── PreToolUseResult.java    # Hook返回值
    └── PreToolUseHookManager.java # Hook链管理器
```

### 1.4 组件详解

#### PathGuard — 文件路径守卫

**参考**: NanoCode `_resolve_path()`

统一所有 File Tool 的路径解析，确保不越出工作区。

```java
PathGuard guard = new PathGuard("/");
guard.addAllowedDir("/tmp");
Path safe = guard.resolvePath("src/main/App.java");  // 相对路径基于workspace
Path safe = guard.resolvePath("/tmp/data.json");      // extraAllowedDirs中路径放行
Path safe = guard.resolvePath("../../../etc/passwd"); // → SecurityException
```

- `toRealPath()` 解析符号链接防绕过
- `extraAllowedDirs` 支持多工作区
- `strictMode = false` 可降级为仅日志不拦截

#### CommandGuard — Shell 命令守卫

**参考**: NanoCode `_guard_command()`

**设计要点**: allowPatterns 优先于 denyPatterns（同 NanoCode PR #3594）。

```java
CommandGuard guard = CommandGuard.withDefaults();
guard.addAllowPattern("git\\s+status");
guard.guard("git status");    // → 白名单放行
guard.guard("rm -rf /");      // → SecurityException
```

内置 12 条默认 deny 规则（sudo, rm -rf /, mkfs, fork bomb, pipe-to-shell 等）。

#### NetworkGuard — 网络/SSRF 守卫

**参考**: NanoCode `validate_url_target()`

```java
NetworkGuard guard = NetworkGuard.withDefaults();
guard.validateUrl("https://api.github.com");          // → OK
guard.validateUrl("http://169.254.169.254/meta-data");// → SecurityException (云元数据)
guard.validateUrl("http://192.168.1.1/admin");        // → SecurityException (内网)
```

内置 11 条默认 blocked IP ranges（RFC1918, loopback, link-local, Docker, CGNAT 等）。

#### PermissionMode — 权限模式

| 模式 | 读工具 | 文件编辑 | Shell | 用途 |
|------|:--:|:--:|:--:|------|
| `PLAN` | ✅ | ❌ | ❌ | 代码探索/出计划 |
| `DEFAULT` | ✅ | 确认后放行 | 确认后放行 | 日常开发（CLI 弹框确认） |
| `ACCEPT_EDITS` | ✅ | ✅ | 确认后放行 | 信任编码 |
| `BYPASS` | ✅ | ✅ | ✅ | 自动化工作流 |

#### RuleEngine — 规则引擎

**优先级**: DENY > ASK > ALLOW。Deny 不可被 Allow 覆写。

```java
RuleEngine engine = new RuleEngine();
engine.addRule(RuleType.DENY, "exec", "command", "rm -rf.*", "危险命令");
engine.addRule(RuleType.ALLOW, "exec", "command", "git status", null);

RuleMatch match = engine.evaluate("exec", Map.of("command", "rm -rf /"));
// → DENY (即使后面有 allow 规则，deny 优先)
```

#### PreToolUseHook — 工具执行前钩子

**参考**: Claude Code PreToolUse Hook

```java
PreToolUseHookManager hooks = new PreToolUseHookManager();
hooks.register(ctx -> {
    if ("exec".equals(ctx.getToolName())) {
        return PreToolUseResult.deny("Shell disabled in this session");
    }
    return PreToolUseResult.passthrough();
});
```

### 1.5 配置参考

所有安全组件通过 `NanoCodeConfig.java` 以 Spring Bean 方式创建，不可通过外部配置文件覆盖（守卫规则为硬编码，确保安全基线）。权限模式通过 `/mode` CLI 命令实时切换。

```java
// NanoCodeConfig.java — 安全 Bean 创建
@Bean public PathGuard pathGuard(Config config) {
    PathGuard guard = new PathGuard(config.getWorkspacePath());
    guard.setRestrictToWorkspace(config.getTools().isRestrictToWorkspace());
    return guard;
}
@Bean public CommandGuard commandGuard() { return CommandGuard.withDefaults(); }
@Bean public NetworkGuard networkGuard() { return NetworkGuard.withDefaults(); }
@Bean public PermissionManager permissionManager(...) {
    RuleEngine ruleEngine = new RuleEngine();
    ruleEngine.addRule(RuleType.ASK, "exec", null, null, "Shell 命令执行需要您的确认");
    return PermissionManager.builder()
        .mode(PermissionMode.DEFAULT).pathGuard(...).commandGuard(...)
        .ruleEngine(ruleEngine).build();
}
```

权限模式通过 `/mode` CLI 命令实时切换，或通过 `PermissionManager.setMode()` 编程切换。

> 注：`application.yml` 中曾有一份 `nanocode.security` 配置块（2026-08 已删除），因为无 Java 代码绑定，为死代码。安全配置的正确入口是 `NanoCodeConfig.java` 的 Bean 定义。

### 1.6 集成点

| 集成点 | 文件 | 说明 |
|--------|------|------|
| **唯一切入点** | `ToolRegistry.execute()` | 所有工具调用必经之路，在此注入权限检查 |
| **Spring Bean** | `NanoCodeConfig.java` | PathGuard/CommandGuard/NetworkGuard/PermissionManager 的 Bean 创建 |
| **WebSocket ACL** | `NanoCodeWebSocketEndpoint.java` | 通道级访问控制检查 |
| **Config** | `Config.java` | ChannelAclConfig 及安全相关配置类 |

### 1.7 如何扩展

**添加新的守卫**:
1. 在 `security/guard/` 下创建新守卫类
2. 在 `PermissionManager.checkGuards()` 中注册
3. 在 `NanoCodeConfig` 中创建 Bean

**添加新的权限模式**:
1. 在 `PermissionMode` 枚举中添加新值
2. 实现 `allowsTool(Tool)` 方法

**添加自定义 Hook**:
```java
hooks.register(ctx -> {
    // 自定义逻辑
    return PreToolUseResult.passthrough();
});
```

---

## 2. CLI 交互体验模块

**实现日期**: 2026-08-05 ~ 2026-08-06
**参考来源**: Claude Code CLI
**状态**: ✅ 已完成

### 2.1 概述

V3 CLI 模式（类 Claude Code）的终端交互层，包括彩色渲染、命令支持、权限确认、终端适配。核心设计：**双分支渲染**（ANSI 彩色 vs CMD 纯文本），共享同一套业务逻辑。

### 2.2 包结构

```
com.nanocode.v3.tui
├── TerminalStyle.java       # ANSI 颜色常量 + 降级过滤器
└── MarkdownRenderer.java    # Markdown → ANSI 渲染（标题/粗体/代码高亮）
```

### 2.3 组件详解

#### TerminalStyle — 统一样式入口

所有 CLI 输出颜色的**唯一来源**。`disable()` 将所有 ANSI 常量置空 → CMD 下自动降级纯文本。

```java
// 正常终端
System.out.println(TerminalStyle.RED + "✗" + TerminalStyle.R + " 错误");
// CMD (disable后) → "✗ 错误"  (无颜色)

TerminalStyle.error("失败");   // "✗ 失败" (红色/CMD纯文本)
TerminalStyle.success("成功"); // "✓ 成功"
TerminalStyle.warn("注意");    // "! 注意"
```

内置：`filter()`（ANSI 去除 + Unicode 框线→ASCII）、`toolColor()`（工具类别→颜色映射）。

#### MarkdownRenderer — 终端 Markdown 渲染

支持：`# 标题`（紫/青/绿）、`**粗体**`、`*斜体*`、`` `行内代码` ``、`` ```代码块``` ``（灰色背景 + 语法高亮）。

语法高亮覆盖：Java、JSON、YAML、Bash/Shell、SQL、XML/HTML。CMD 下全部降级为灰色背景无颜色。

### 2.4 CLI 命令

| 命令 | 说明 | 实现 |
|------|------|------|
| `/exit` `/q` | 退出 | 内置 |
| `/clear` | 清上下文 | 内置 |
| `/help` | 所有命令 | `HelpCommand` (彩色格式化) |
| `/history` | 输入历史 | 内置（含 `!!` `!N`） |
| `/mode` `/plan` | 权限模式 | `ModeCommand` |
| `/init` | 生成 NANOCODE.md | `InitCommand` |
| `/resume` | 历史会话 | `ResumeCommand` |

### 2.5 终端适配

```
os.name + WT_SESSION
  ├─ 非 Windows    → ANSI + JLine + 彩色双栏 banner
  ├─ WT_SESSION=有  → ANSI + 无JLine + 彩色双栏 banner（Windows Terminal）
  └─ WT_SESSION=无  → 纯文本 + 无JLine + ASCII banner（CMD）
```

终端检测在 `CliChannel` 构造函数中完成，`TerminalStyle.disable()` 一次性切换全局面板。CMD 下所有输出经 `filter()` 兜底：去 ANSI + Unicode框线→ASCII + 特殊符号→纯文本。

### 2.6 其他交互特性

| 特性 | 说明 |
|------|------|
| Thinking spinner | `| / - \` ASCII 帧动画，delta 到达时停止 |
| 工具调用着色 | exec=红, read=绿, write=橙, web=蓝, mcp=紫 |
| Token 用量 | prompt 显示 `deepseek-chat 1.2Kt >` |
| 模式指示 | `[PLAN]` `[EDIT]` `[BYPASS]` 标记在 prompt |
| 确认框 | `confirmLock` 防并发穿插，`[1]`绿 `[2]`青 `[3]`红 |
| 后台命令 | `exec background=true` 启动长期服务 |
| Unix 命令转换 | `pwd→cd` `ls→dir` `cat→type` 等 Windows 自动翻译 |

---

## 附录: 功能添加模板

新增功能请复制以下模板追加到本文档：

```markdown
## N. 功能名称

**实现日期**: YYYY-MM-DD
**参考来源**: 
**状态**: 🚧 开发中 / ✅ 已完成

### N.1 概述

### N.2 包结构

### N.3 组件详解

### N.4 配置参考

### N.5 集成点

### N.6 如何扩展
```
