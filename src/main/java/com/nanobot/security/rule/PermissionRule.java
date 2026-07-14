package com.nanobot.security.rule;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 权限规则 — 匹配工具名和参数值
 * ================================
 *
 * 一条规则定义了对特定工具（或工具类）的执行策略。
 * 规则使用正则表达式匹配工具名，并可选的匹配参数值。
 *
 * 规则示例：
 * - 禁止删除命令: {@code new PermissionRule(RuleType.DENY, "exec", "command", "rm -rf.*", "危险操作")}
 * - 允许 git status: {@code new PermissionRule(RuleType.ALLOW, "exec", "command", "git status", null)}
 * - 禁止所有 exec: {@code new PermissionRule(RuleType.DENY, "exec", null, null, "Shell 被禁用")}
 *
 * @param type         规则类型：DENY/ASK/ALLOW
 * @param toolPattern  工具名正则（如 "exec", "write_file|edit_file", "mcp_.*"）
 * @param paramName    参数名（可选，如 "command"、"path"）
 * @param valuePattern 参数值正则（可选，如 "rm -rf.*"）
 * @param reason       拦截或提示原因
 */
public record PermissionRule(
        RuleType type,
        String toolPattern,
        String paramName,
        String valuePattern,
        String reason
) {
    private static final Map<String, Pattern> PATTERN_CACHE = new HashMap<>();

    /**
     * 检查此规则是否匹配给定的工具调用
     *
     * @param toolName 工具名称
     * @param params   工具参数
     * @return true 表示此规则匹配
     */
    public boolean matches(String toolName, Map<String, Object> params) {
        // 1. 匹配工具名
        Pattern toolRegex = compilePattern(toolPattern);
        if (!toolRegex.matcher(toolName).matches()) {
            return false;
        }

        // 2. 如果没有参数约束，直接匹配
        if (paramName == null || valuePattern == null) {
            return true;
        }

        // 3. 匹配参数值
        Object value = params.get(paramName);
        if (value == null) {
            return false;
        }

        Pattern valueRegex = compilePattern(valuePattern);
        return valueRegex.matcher(String.valueOf(value)).matches();
    }

    /**
     * 检查是否匹配（仅按工具名，不检查参数）
     */
    public boolean matchesTool(String toolName) {
        return compilePattern(toolPattern).matcher(toolName).matches();
    }

    private static Pattern compilePattern(String regex) {
        return PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(type.name());
        sb.append(": ").append(toolPattern);
        if (paramName != null) {
            sb.append("(").append(paramName);
            if (valuePattern != null) {
                sb.append("=").append(valuePattern);
            }
            sb.append(")");
        }
        if (reason != null) {
            sb.append(" [").append(reason).append("]");
        }
        return sb.toString();
    }
}
