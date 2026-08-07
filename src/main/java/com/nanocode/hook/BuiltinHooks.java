package com.nanocode.hook;

import java.util.List;

/**
 * 内置 Hook 定义 — 开箱即用的默认钩子规则.
 *
 * <h2>设计意图</h2>
 * 即使没有在 config.yaml 中配置任何 hook，系统也应该有基本的可观测性.
 * 这些内置 Hook 在 {@link HookManager#loadFromConfig} 检测到配置列表为空时自动加载.
 *
 * <h2>与旧系统的区别</h2>
 * 旧系统默认加载 LoggingHook + MetricsHook（Java 类，硬编码逻辑）.
 * 新系统默认加载以下 ECA 声明的 Hook 规则（全部用 PROMPT 类型，零开销，
 * 仅输出标记文本供日志记录，不执行外部进程）.
 *
 * <h2>默认规则一览</h2>
 * <table>
 *   <tr><th>ID</th><th>事件</th><th>作用</th></tr>
 *   <tr><td>builtin-session-lifecycle</td><td>SESSION_START / SESSION_END</td><td>记录应用生命周期</td></tr>
 *   <tr><td>builtin-turn-boundary</td><td>TURN_START / TURN_END</td><td>标记每轮对话边界</td></tr>
 *   <tr><td>builtin-tool-audit</td><td>POST_TOOL_USE</td><td>记录工具调用</td></tr>
 *   <tr><td>builtin-error-observe</td><td>ON_ERROR</td><td>记录异常</td></tr>
 * </table>
 *
 * <h2>为什么用 PROMPT 而不是 COMMAND？</h2>
 * PROMPT 类型的 Hook 不执行外部进程（零开销），其 message 文本被 HookManager
 * 记录到统计缓冲（recentResults），可在 /stats 中查看。对于内置的可观测性 Hook，
 * 日志输出由 HookManager 内置计数器提供，不需要 shell 命令.
 *
 * <h2>覆盖内置 Hook</h2>
 * 如果用户在 config.yaml 中配置了自己的 Hook 列表，内置 Hook 不会被加载.
 * 如需同时使用内置 + 自定义 Hook，可以在 YAML 中显式引用内置 Hook 的 id.
 */
public final class BuiltinHooks {

    private BuiltinHooks() { /* 纯静态工具 */ }

    /**
     * 内置 Hook 列表 — 开箱即用.
     *
     * <h3>加载时机</h3>
     * {@link HookManager#loadFromConfig} 在 config.getList() 为空时调用此方法.
     */
    public static List<Hook> defaults() {
        return List.of(
                // ── ① 会话生命周期日志 ──
                new Hook(
                        "builtin-session-start",
                        HookEvent.SESSION_START,
                        "",  // 无条件
                        new HookAction(ActionType.PROMPT, "", "[SESSION] NanoCode 已启动", ""),
                        false
                ),
                new Hook(
                        "builtin-session-end",
                        HookEvent.SESSION_END,
                        "",
                        new HookAction(ActionType.PROMPT, "", "[SESSION] NanoCode 正在关闭", ""),
                        false
                ),

                // ── ② 对话轮次标记 ──
                new Hook(
                        "builtin-turn-start",
                        HookEvent.TURN_START,
                        "",
                        new HookAction(ActionType.PROMPT, "", "[TURN] 新消息开始处理", ""),
                        false
                ),
                new Hook(
                        "builtin-turn-end",
                        HookEvent.TURN_END,
                        "",
                        new HookAction(ActionType.PROMPT, "", "[TURN] 消息处理完成", ""),
                        false
                ),

                // ── ③ 工具调用审计 ──
                new Hook(
                        "builtin-tool-audit",
                        HookEvent.POST_TOOL_USE,
                        "",  // 无条件 — 记录所有工具调用
                        new HookAction(ActionType.PROMPT, "", "[TOOL] 工具执行完成", ""),
                        false
                ),

                // ── ④ 错误监控 ──
                new Hook(
                        "builtin-error-observe",
                        HookEvent.ON_ERROR,
                        "",
                        new HookAction(ActionType.PROMPT, "", "[ERROR] 发生异常", ""),
                        false
                )
        );
    }
}
