---
id: C-009
slug: security-system
status: done
created: 2025-06-15
owner: Owner Agent
---

# C-009 安全权限系统

## 用户故事

作为系统管理员，我需要一套完整的权限管控体系，包括文件路径守卫、命令执行白名单/黑名单、网络 SSRF 防护、4 级权限模式、规则引擎和交互式确认，确保 Agent 的工具调用在安全边界内执行。

## 验收标准

- AC-1: `PermissionManager` 四步管道：PreToolUseHook → Guards → RuleEngine → Mode
- AC-2: `PermissionMode` 四种：PLAN(只读)/DEFAULT(默认)/ACCEPT_EDITS(编辑放行)/BYPASS(全放行)
- AC-3: `PathGuard` 限制文件操作在工作区范围内
- AC-4: `CommandGuard` Shell 命令黑/白名单过滤
- AC-5: `NetworkGuard` URL SSRF 防护 + IP 范围过滤
- AC-6: `RuleEngine` deny → ask → allow 优先级链
- AC-7: `PreToolUseHookManager` Hook 链（deny/allow/modify/passthrough）
- AC-8: CLI 交互式确认：1=允许 2=之后都放行 3=拒绝

## 边界情况

- 当无交互处理器时，ask 规则自动降级为 deny
- 当 Guard 拦截时，返回明确的安全原因
- 当模式切换时，logger 记录变更

## 非功能需求

| 维度 | 指标 |
|------|------|
| 检查顺序 | Hook → Guards(永远执行) → Rules → Mode |
| 性能 | Guard 和 Mode 判定 O(1) |
| 可扩展 | 新增 Guard/Rule 实现接口即可注册 |
