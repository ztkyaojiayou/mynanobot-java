package com.nanocode.hook;

/**
 * Hook 执行结果 — 记录单个 Hook 匹配后的执行情况.
 *
 * @param hookId 对应 Hook 的 id
 * @param output 动作执行的输出（COMMAND 的 stdout，PROMPT 返回 message 本身）
 * @param success 执行是否成功（COMMAND exitCode=0, PROMPT 始终 true）
 * @param reject 该 Hook 是否要求拦截操作（当 Hook.reject=true 且条件匹配时）
 */
public record HookResult(
        String hookId,
        String output,
        boolean success,
        boolean reject
) {
    /** 创建一个成功的、不拦截的结果 */
    public static HookResult ok(String hookId, String output) {
        return new HookResult(hookId, output, true, false);
    }

    /** 创建一个拦截的结果（PRE_TOOL_USE / TURN_START reject） */
    public static HookResult blocked(String hookId, String reason) {
        return new HookResult(hookId, reason, true, true);
    }
}
