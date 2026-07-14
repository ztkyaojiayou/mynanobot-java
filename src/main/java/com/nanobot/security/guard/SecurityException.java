package com.nanobot.security.guard;

import lombok.Getter;

/**
 * 安全异常 — 守卫拦截时抛出
 * ==============================
 *
 * 当 PathGuard、CommandGuard、NetworkGuard 检测到违规操作时抛出此异常。
 * 携带被哪个守卫拦截以及拦截原因，便于日志和调试。
 *
 * 设计思想：
 * - 继承 RuntimeException，不强制调用方声明 throws
 * - 在 ToolRegistry.execute() 中被统一捕获并返回友好错误信息
 * - guard 字段标识拦截来源（用于日志和审计）
 */
@Getter
public class SecurityException extends RuntimeException {

    /** 拦截来源守卫名称，如 "PathGuard"、"CommandGuard"、"NetworkGuard" */
    private final String guard;

    /** 拦截原因，如 "Path outside workspace: /etc/passwd" */
    private final String reason;

    public SecurityException(String guard, String reason) {
        super("[" + guard + "] " + reason);
        this.guard = guard;
        this.reason = reason;
    }

    public SecurityException(String guard, String reason, Throwable cause) {
        super("[" + guard + "] " + reason, cause);
        this.guard = guard;
        this.reason = reason;
    }
}
