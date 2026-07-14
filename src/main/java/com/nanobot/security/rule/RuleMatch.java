package com.nanobot.security.rule;

/**
 * 规则匹配结果
 * ================
 *
 * 表示 RuleEngine.evaluate() 的返回值。
 */
public final class RuleMatch {

    /** 无匹配规则时的哨兵值 */
    public static final RuleMatch NO_MATCH = new RuleMatch(null, null);

    private final RuleType type;
    private final PermissionRule rule;

    private RuleMatch(RuleType type, PermissionRule rule) {
        this.type = type;
        this.rule = rule;
    }

    static RuleMatch deny(PermissionRule rule) {
        return new RuleMatch(RuleType.DENY, rule);
    }

    static RuleMatch ask(PermissionRule rule) {
        return new RuleMatch(RuleType.ASK, rule);
    }

    static RuleMatch allow(PermissionRule rule) {
        return new RuleMatch(RuleType.ALLOW, rule);
    }

    // ==================== 查询方法 ====================

    public boolean isDeny() {
        return type == RuleType.DENY;
    }

    public boolean isAsk() {
        return type == RuleType.ASK;
    }

    public boolean isAllow() {
        return type == RuleType.ALLOW;
    }

    public boolean isNoMatch() {
        return type == null;
    }

    public String getReason() {
        return rule != null ? rule.reason() : null;
    }

    public RuleType getType() {
        return type;
    }

    public PermissionRule getRule() {
        return rule;
    }

    @Override
    public String toString() {
        if (isNoMatch()) return "NO_MATCH";
        return type + ": " + (rule != null ? rule.reason() : "");
    }
}
