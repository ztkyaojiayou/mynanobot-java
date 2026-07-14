package com.nanobot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 工具调用结果类 — 表示 MCP 工具调用的返回结果。
 */
@Data
@NoArgsConstructor
public class MCPResult {

    private String type;
    private String textContent;
    private JsonNode jsonContent;
    private String errorMessage;
    private boolean success;
    private String toolName;

    public static MCPResult text(String content) {
        MCPResult result = new MCPResult();
        result.type = "text";
        result.textContent = content;
        result.success = true;
        return result;
    }

    public static MCPResult json(JsonNode content) {
        MCPResult result = new MCPResult();
        result.type = "json";
        result.jsonContent = content;
        result.success = true;
        return result;
    }

    public static MCPResult error(String message) {
        MCPResult result = new MCPResult();
        result.type = "error";
        result.errorMessage = message;
        result.success = false;
        return result;
    }

    @Override
    public String toString() {
        if ("text".equals(type)) return textContent;
        if ("json".equals(type)) return jsonContent != null ? jsonContent.toString() : "null";
        if ("error".equals(type)) return "Error: " + errorMessage;
        return "";
    }
}
