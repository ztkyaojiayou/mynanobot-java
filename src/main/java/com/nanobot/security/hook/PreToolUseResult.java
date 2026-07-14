package com.nanobot.security.hook;

import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/**
 * PreToolUse 钩子返回值
 * =======================
 *
 * 表示钩子对工具调用的决策：
 * - ALLOW: 允许执行（跳过后续权限检查）
 * - DENY:  拒绝执行（携带原因）
 * - MODIFY: 允许但修改参数后执行
 * - PASSTHROUGH: 不干预，继续正常权限检查流程
 *
 * 使用示例：
 * ```java
 * PreToolUseResult result = PreToolUseResult.deny("User not authorized");
 * if (result.isDeny()) { ... }
 * ```
 */
@Getter
public final class PreToolUseResult {

    private final Decision decision;
    private final String reason;
    private final Map<String, Object> modifiedParams;

    private PreToolUseResult(Decision decision, String reason, Map<String, Object> modifiedParams) {
        this.decision = decision;
        this.reason = reason;
        this.modifiedParams = modifiedParams != null
                ? Collections.unmodifiableMap(modifiedParams) : null;
    }

    // ==================== 工厂方法 ====================

    public static PreToolUseResult allow() {
        return new PreToolUseResult(Decision.ALLOW, null, null);
    }

    public static PreToolUseResult deny(String reason) {
        return new PreToolUseResult(Decision.DENY, reason, null);
    }

    public static PreToolUseResult modify(Map<String, Object> params) {
        return new PreToolUseResult(Decision.MODIFY, null, params);
    }

    public static PreToolUseResult passthrough() {
        return new PreToolUseResult(Decision.PASSTHROUGH, null, null);
    }

    // ==================== 查询方法 ====================

    public boolean isAllow()        { return decision == Decision.ALLOW; }
    public boolean isDeny()         { return decision == Decision.DENY; }
    public boolean isModify()       { return decision == Decision.MODIFY; }
    public boolean isPassthrough()  { return decision == Decision.PASSTHROUGH; }

    @Override
    public String toString() {
        return decision + (reason != null ? ": " + reason : "");
    }

    // ==================== 枚举 ====================

    public enum Decision {
        /** 允许执行（跳过后续检查） */
        ALLOW,
        /** 拒绝执行 */
        DENY,
        /** 允许但修改参数 */
        MODIFY,
        /** 不干预，继续正常流程 */
        PASSTHROUGH
    }
}
