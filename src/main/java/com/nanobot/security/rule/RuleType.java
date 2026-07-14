package com.nanobot.security.rule;

/**
 * 规则类型枚举
 */
public enum RuleType {
    /**
     * 拒绝 — 匹配的工具调用被禁止。
     * 优先级最高，deny 规则不可被 allow 规则覆写。
     */
    DENY,

    /**
     * 询问 — 匹配的工具调用需要用户交互确认。
     * 优先级中等。
     */
    ASK,

    /**
     * 允许 — 匹配的工具调用自动放行。
     * 优先级最低，仅在没有 deny 或 ask 规则匹配时生效。
     */
    ALLOW
}
