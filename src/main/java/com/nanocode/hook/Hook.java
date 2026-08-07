package com.nanocode.hook;

/**
 * Hook 规则定义 — ECA 模型的核心数据载体.
 *
 * <h2>一条 Hook = Event + Condition + Action</h2>
 * 用大白话说就是「什么时候」「什么情况下」「做什么」：
 * <ul>
 *   <li><b>什么时候</b> — {@code event}：9 种生命周期事件（SESSION_START, PRE_TOOL_USE ...）</li>
 *   <li><b>什么情况下</b> — {@code condition}：DSL 条件表达式，空字符串表示无条件</li>
 *   <li><b>做什么</b> — {@code action}：COMMAND / PROMPT / SCRIPT 三种动作</li>
 *   <li><b>要不要拦</b> — {@code reject}：true 时拦截操作（仅 TURN_START / PRE_TOOL_USE 有效）</li>
 * </ul>
 *
 * <h2>条件 DSL 语法</h2>
 * <pre>
 *   ""                         无条件匹配
 *   "tool==bash"               工具名精确匹配
 *   "tool=~mcp__.*"            工具名正则匹配
 *   "args.key==val"            工具参数值匹配
 *   "args.cmd=~rm.*"           工具参数正则匹配
 *   "cond1 && cond2"           多条件 AND 组合
 * </pre>
 *
 * <h2>完整生命周期：从定义到生效</h2>
 * <pre>
 *   ┌──────────┐   ┌───────────────┐   ┌────────────────┐   ┌──────────────┐
 *   │ 1. 定义  │──▶│ 2. 加载注册    │──▶│ 3. 事件触发     │──▶│ 4. 匹配执行   │
 *   │ YAML/代码│   │ HookManager   │   │ AgentLoop/     │   │ evalCondition│
 *   │          │   │ .loadFromConfig│   │ Runner/RunState│   │ → executeAct│
 *   └──────────┘   └───────────────┘   └────────────────┘   └──────────────┘
 * </pre>
 *
 * <h3>1. 定义（YAML 配置）</h3>
 * <pre>
 * hooks:
 *   list:
 *     - id: "block-rm"
 *       event: PRE_TOOL_USE
 *       condition: "tool==bash && args.cmd=~rm.*"
 *       action: { type: COMMAND, command: "echo blocked" }
 *       reject: true
 * </pre>
 *
 * <h3>2. 加载注册</h3>
 * Spring 启动 → NanoCodeConfig 创建 HookManager @Bean →
 * {@code hookManager.loadFromConfig(config.getHooks())} →
 * Jackson 反序列化 YAML → Hook record 列表 → 存入 HookManager.hooks.
 *
 * <h3>3. 事件触发</h3>
 * AgentLoop/AgentRunner/RunState 在关键位置调用：
 * {@code hookManager.runHooks(new HookContext(event, toolName, args, sessionId, msg, err))}
 *
 * <h3>4. 匹配执行</h3>
 * HookManager 遍历所有 Hook → 比对 event → 解析 condition DSL →
 * 执行 action（COMMAND/PROMPT/SCRIPT）→ 返回 HookResult.
 * 若 reject=true 且匹配，则拦截该操作.
 *
 * <h2>YAML 配置示例</h2>
 * <pre>
 * # 示例1：拦截危险 bash 命令
 * - id: "block-dangerous-bash"
 *   event: PRE_TOOL_USE
 *   condition: "tool==bash && args.cmd=~rm.*"
 *   action:
 *     type: COMMAND
 *     command: "echo '[HOOK] 危险命令已拦截'"
 *   reject: true
 *
 * # 示例2：每轮对话前注入项目上下文
 * - id: "inject-project-context"
 *   event: TURN_START
 *   condition: ""
 *   action:
 *     type: PROMPT
 *     message: "[项目上下文] 当前工作区: /path/to/project"
 *   reject: false
 *
 * # 示例3：文件写入后记录审计日志
 * - id: "audit-file-writes"
 *   event: POST_TOOL_USE
 *   condition: "tool==write_file || tool==edit_file"
 *   action:
 *     type: SCRIPT
 *     script_path: "${NANOBOT_DIR}/hooks/audit.sh"
 *   reject: false
 * </pre>
 */
public record Hook(
        String id,
        HookEvent event,
        String condition,
        HookAction action,
        boolean reject
) {}
