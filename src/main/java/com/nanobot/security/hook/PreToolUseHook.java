package com.nanobot.security.hook;

/**
 * PreToolUse 钩子接口
 * =====================
 *
 * 参考 Claude Code 的 PreToolUse Hook 设计。
 * 钩子在工具执行前被调用，可以允许、拒绝、修改参数或透传。
 *
 * 钩子在权限检查管道的最前面执行：
 * {@code Hook → Guards → Rules → Mode → Execute}
 *
 * 实现示例：
 * ```java
 * public class MySecurityHook implements PreToolUseHook {
 *     public PreToolUseResult beforeToolUse(PreToolUseContext ctx) {
 *         if (ctx.getToolName().equals("exec")) {
 *             return PreToolUseResult.deny("Shell disabled");
 *         }
 *         return PreToolUseResult.passthrough();
 *     }
 * }
 * ```
 */
@FunctionalInterface
public interface PreToolUseHook {

    /**
     * 在工具执行前调用
     *
     * @param context 包含工具名、参数、会话信息的上下文
     * @return 决策：allow/deny/modify/passthrough
     */
    PreToolUseResult beforeToolUse(PreToolUseContext context);
}
