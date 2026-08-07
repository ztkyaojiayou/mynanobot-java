package com.nanocode.hook;

import java.util.Collections;
import java.util.Map;

/**
 * Hook 运行时上下文 — 事件触发时传递给 Hook 的信息快照.
 *
 * <h2>不可变设计</h2>
 * 本 record 是事件触发时刻的只读快照，Hook 不应该修改它.
 * Hook 的执行结果通过 {@link HookResult} 返回，不修改上下文.
 *
 * <h2>字段说明</h2>
 * 不同事件会填充不同字段，未填充的字段为 null 或空 Map：
 * <ul>
 *   <li>{@code event} — 必填，触发该上下文的事件类型</li>
 *   <li>{@code toolName} — PRE_TOOL_USE / POST_TOOL_USE 时填充</li>
 *   <li>{@code toolArgs} — PRE_TOOL_USE / POST_TOOL_USE 时填充（工具参数）</li>
 *   <li>{@code sessionId} — 当前会话标识</li>
 *   <li>{@code message} — TURN_START(用户消息) / ON_STREAM(delta) / ON_ERROR(错误信息)</li>
 *   <li>{@code error} — ON_ERROR 时填充</li>
 * </ul>
 *
 * <h2>便捷工厂方法</h2>
 * <pre>
 * // 简单事件（SESSION_START, SESSION_END, TURN_END, STREAM_END）
 * HookContext.of(HookEvent.SESSION_START, sessionId)
 *
 * // 消息事件（TURN_START, ON_STREAM）
 * HookContext.message(HookEvent.TURN_START, sessionId, userMessage)
 *
 * // 工具事件（PRE_TOOL_USE, POST_TOOL_USE）
 * HookContext.tool(HookEvent.PRE_TOOL_USE, "bash", args, sessionId)
 *
 * // 错误事件
 * HookContext.error(sessionId, exception.getMessage())
 * </pre>
 */
public record HookContext(
        HookEvent event,
        String toolName,
        Map<String, Object> toolArgs,
        String sessionId,
        String message,
        String error
) {
    /** 构造后防御性复制可变参数 */
    public HookContext {
        toolArgs = toolArgs != null ? Collections.unmodifiableMap(toolArgs) : Collections.emptyMap();
    }

    // ═══════════ 便捷工厂 ═══════════

    /** 简单事件（无额外信息） */
    public static HookContext of(HookEvent event, String sessionId) {
        return new HookContext(event, null, null, sessionId, null, null);
    }

    /** 消息事件 */
    public static HookContext message(HookEvent event, String sessionId, String message) {
        return new HookContext(event, null, null, sessionId, message, null);
    }

    /** 工具事件 */
    public static HookContext tool(HookEvent event, String toolName,
                                    Map<String, Object> args, String sessionId) {
        return new HookContext(event, toolName, args, sessionId, null, null);
    }

    /** 工具事件 + 结果消息 */
    public static HookContext toolResult(HookEvent event, String toolName,
                                          Map<String, Object> args, String sessionId, String result) {
        return new HookContext(event, toolName, args, sessionId, result, null);
    }

    /** 错误事件 */
    public static HookContext error(String sessionId, String error) {
        return new HookContext(HookEvent.ON_ERROR, null, null, sessionId, null, error);
    }
}
