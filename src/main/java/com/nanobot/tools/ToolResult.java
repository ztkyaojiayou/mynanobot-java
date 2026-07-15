package com.nanobot.tools;

/**
 * 工具执行结果 — 统一包装格式，帮助 LLM 明确判断成功/失败。
 *
 * 发送给 LLM 的 content 格式:
 *   成功: [TOOL_OK] {实际结果}
 *   失败: [TOOL_ERR] {错误信息}
 *
 * LLM 可以通过前缀直接识别，无需解析自然语言中的 "Error:" 字符串。
 */
public record ToolResult(String content, boolean isError) {

    /** 包装成功结果 */
    public static String ok(Object raw) {
        String text = raw != null ? raw.toString() : "";
        return "[TOOL_OK] " + text;
    }

    /** 包装失败结果（附带错误信息） */
    public static String err(String error) {
        return "[TOOL_ERR] " + (error != null ? error : "Unknown error");
    }

    /** 根据 raw 结果自动判断：以 "Error:" 或 "Security blocked:" 开头视为失败 */
    public static String wrap(Object raw) {
        if (raw == null) return ok("");
        String text = raw.toString();
        if (text.startsWith("Error:") || text.startsWith("Security blocked:")) {
            return err(text);
        }
        return ok(text);
    }
}
