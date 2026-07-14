package com.nanobot.security;

import lombok.Getter;

/**
 * 权限检查结果
 * ================
 *
 * 表示 PermissionManager.check() 的返回值。
 * 四种可能结果：ALLOWED、DENIED、NEEDS_APPROVAL、GUARD_BLOCKED。
 *
 * 使用示例：
 * ```java
 * PermissionResult result = permissionManager.check(tool, params);
 * if (result.isDenied()) {
 *     return "Permission denied: " + result.reason();
 * }
 * ```
 */
@Getter
public final class PermissionResult {

    private final Decision decision;
    private final String reason;
    private final String source; // 来源：mode/rule/guard/hook

    private PermissionResult(Decision decision, String reason, String source) {
        this.decision = decision;
        this.reason = reason;
        this.source = source;
    }

    // ==================== 工厂方法 ====================

    /** 工具被允许执行 */
    public static PermissionResult allowed() {
        return new PermissionResult(Decision.ALLOWED, null, null);
    }

    /** 工具被拒绝（模式/规则决定） */
    public static PermissionResult denied(String reason, String source) {
        return new PermissionResult(Decision.DENIED, reason, source);
    }

    /** 需要用户交互确认 */
    public static PermissionResult needsApproval(String reason) {
        return new PermissionResult(Decision.NEEDS_APPROVAL, reason, "mode");
    }

    /** 被守卫拦截（永远拒绝） */
    public static PermissionResult guardBlocked(String reason, String source) {
        return new PermissionResult(Decision.DENIED, reason, source);
    }

    // ==================== 查询方法 ====================

    public boolean isAllowed() {
        return decision == Decision.ALLOWED;
    }

    public boolean isDenied() {
        return decision == Decision.DENIED;
    }

    public boolean needsApproval() {
        return decision == Decision.NEEDS_APPROVAL;
    }

    @Override
    public String toString() {
        return decision + (reason != null ? ": " + reason : "");
    }

    // ==================== 内部枚举 ====================

    public enum Decision {
        /** 允许执行 */
        ALLOWED,
        /** 拒绝执行 */
        DENIED,
        /** 需要用户交互确认 */
        NEEDS_APPROVAL
    }
}
