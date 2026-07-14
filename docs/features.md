# Nanobot-Java 功能文档

> 本文档统一维护所有已实现功能的架构、使用方式和配置说明。新增功能请按模板追加到文末。

---

## 目录

1. [权限控制模块](#1-权限控制模块)

---

## 1. 权限控制模块

**实现日期**: 2026-07-14
**参考来源**: Nanobot (HKUDS/nanobot) 为主，Claude Code 为辅
**状态**: ✅ 已完成

### 1.1 概述

为 nanobot-java 构建完整的纵深防御（Defense-in-Depth）权限控制体系。所有 Agent 工具调用在 `ToolRegistry.execute()` 中通过统一的检查管道进行安全校验。

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
| 3 | **Rules** | deny → ask → allow 优先级链 | ✅ 无匹配时跳过 |
| 4 | **Mode** | PLAN / DEFAULT / ACCEPT_EDITS / BYPASS | — |

### 1.3 包结构

```
com.nanobot.security
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

**参考**: Nanobot `_resolve_path()`

统一所有 File Tool 的路径解析，确保不越出工作区。

```java
PathGuard guard = new PathGuard("/workspace");
guard.addAllowedDir("/tmp");
Path safe = guard.resolvePath("src/main/App.java");  // 相对路径基于workspace
Path safe = guard.resolvePath("/tmp/data.json");      // extraAllowedDirs中路径放行
Path safe = guard.resolvePath("../../../etc/passwd"); // → SecurityException
```

- `toRealPath()` 解析符号链接防绕过
- `extraAllowedDirs` 支持多工作区
- `strictMode = false` 可降级为仅日志不拦截

#### CommandGuard — Shell 命令守卫

**参考**: Nanobot `_guard_command()`

**设计要点**: allowPatterns 优先于 denyPatterns（同 Nanobot PR #3594）。

```java
CommandGuard guard = CommandGuard.withDefaults();
guard.addAllowPattern("git\\s+status");
guard.guard("git status");    // → 白名单放行
guard.guard("rm -rf /");      // → SecurityException
```

内置 12 条默认 deny 规则（sudo, rm -rf /, mkfs, fork bomb, pipe-to-shell 等）。

#### NetworkGuard — 网络/SSRF 守卫

**参考**: Nanobot `validate_url_target()`

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
| `PLAN` | ✅ | ❌ | ❌ | 代码探索/审查 |
| `DEFAULT` | ✅ | ❌ | ❌ | 日常开发 |
| `ACCEPT_EDITS` | ✅ | ✅ | ❌ | 信任编码 |
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

```yaml
nanobot:
  security:
    mode: default     # plan | default | accept_edits | bypass

    path-guard:
      workspace: "."
      extra-allowed-dirs: []

    command-guard:
      deny-patterns:
        - "rm\\s+-rf\\s+/"
        - "sudo\\b"
        - "mkfs\\."
        - "diskpart"
        - "dd\\s+if="
        - "shutdown|reboot|halt"
      allow-patterns:
        - "^(ls|dir|cat|type|echo|find)\\s"
        - "^git\\s+(status|diff|log|branch|show)"
        - "^(mvn|gradle|npm|node|python|java|javac)\\s"

    network-guard:
      blocked-cidrs:
        - "10.0.0.0/8"
        - "172.16.0.0/12"
        - "192.168.0.0/16"
        - "127.0.0.0/8"
        - "169.254.0.0/16"
        - "100.64.0.0/10"
        - "172.17.0.0/16"
      allowed-domains: []
      blocked-domains: []

    rules:
      - type: deny
        tool-pattern: "exec"
        param-name: "command"
        value-pattern: "rm -rf.*"
        reason: "递归删除被安全策略禁止"
      - type: allow
        tool-pattern: "exec"
        param-name: "command"
        value-pattern: "git status"
        reason: null

  channels:
    acl:
      websocket:
        enabled: true
        allow-from: []    # 空列表 = 拒绝所有
```

### 1.6 集成点

| 集成点 | 文件 | 说明 |
|--------|------|------|
| **唯一切入点** | `ToolRegistry.execute()` | 所有工具调用必经之路，在此注入权限检查 |
| **Spring Bean** | `NanobotConfig.java` | PathGuard/CommandGuard/NetworkGuard/PermissionManager 的 Bean 创建 |
| **WebSocket ACL** | `NanobotWebSocketEndpoint.java` | 通道级访问控制检查 |
| **Config** | `Config.java` | ChannelAclConfig 及安全相关配置类 |

### 1.7 如何扩展

**添加新的守卫**:
1. 在 `security/guard/` 下创建新守卫类
2. 在 `PermissionManager.checkGuards()` 中注册
3. 在 `NanobotConfig` 中创建 Bean

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
