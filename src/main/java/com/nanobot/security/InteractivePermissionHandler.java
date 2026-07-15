package com.nanobot.security;

import com.nanobot.tools.Tool;
import java.util.Map;

/**
 * 交互式权限确认处理器 — 让用户在工具执行前确认是否允许。
 *
 * CLI 模式: 通过 stdin/stdout 交互
 * HTTP/WS 模式: 当前返回 false (拒绝)，后续可扩展为 WebSocket 弹窗确认
 */
@FunctionalInterface
public interface InteractivePermissionHandler {

    /**
     * 请求用户确认工具调用。
     *
     * @param tool   待执行的工具
     * @param params 工具参数
     * @param reason 需要确认的原因
     * @return true=允许执行, false=拒绝
     */
    boolean requestConfirmation(Tool tool, Map<String, Object> params, String reason);
}
