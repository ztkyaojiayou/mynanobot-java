package com.nanocode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Getter;

/**
 * MCP 工具调用结果类 — 表示 MCP 工具调用的返回结果。
 * @author zoutongkun
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

    /** 从标准 JSON-RPC 2.0 响应解析 MCP 结果 */
    public static MCPResult fromJsonRpcResponse(JsonRpcMessage.Response resp) {
        if (!resp.isSuccess()) {
            return error(resp.error().toString());
        }
        JsonNode result = resp.result();
        if (result == null) return error("Empty response");

        // MCP 标准：result.content 是内容数组
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) {
                String type = item.has("type") ? item.get("type").asText() : "text";
                if ("text".equals(type) && item.has("text")) {
                    sb.append(item.get("text").asText());
                } else if ("resource".equals(type) && item.has("resource")) {
                    JsonNode resource = item.get("resource");
                    if (resource.has("text")) sb.append(resource.get("text").asText());
                    else if (resource.has("uri")) sb.append("[resource:").append(resource.get("uri").asText()).append("]");
                }
            }
            return sb.isEmpty() ? text(content.toString()) : text(sb.toString());
        }

        // 兜底：整个 result 作为文本
        return text(result.toString());
    }

    @Override
    public String toString() {
        if ("text".equals(type)) return textContent;
        if ("json".equals(type)) return jsonContent != null ? jsonContent.toString() : "null";
        if ("error".equals(type)) return "Error: " + errorMessage;
        return "";
    }
}
