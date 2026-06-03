package com.nanobot.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 工具调用结果类
 * 
 * 表示 MCP 工具调用的返回结果。
 * 
 * MCP 协议定义了多种返回类型：
 * - text: 纯文本结果
 * - json: JSON 结果
 * - error: 错误结果
 */
public class MCPResult {
    
    /**
     * 结果类型：text、json、error
     */
    private String type;
    
    /**
     * 文本内容（当 type 为 text 时）
     */
    private String textContent;
    
    /**
     * JSON 内容（当 type 为 json 时）
     */
    private JsonNode jsonContent;
    
    /**
     * 错误信息（当 type 为 error 时）
     */
    private String errorMessage;
    
    /**
     * 是否成功
     */
    private boolean success;
    
    /**
     * 工具名称
     */
    private String toolName;
    
    // ==================== 构造函数 ====================
    
    public MCPResult() {
    }
    
    /**
     * 创建成功的文本结果
     */
    public static MCPResult text(String content) {
        MCPResult result = new MCPResult();
        result.type = "text";
        result.textContent = content;
        result.success = true;
        return result;
    }
    
    /**
     * 创建成功的 JSON 结果
     */
    public static MCPResult json(JsonNode content) {
        MCPResult result = new MCPResult();
        result.type = "json";
        result.jsonContent = content;
        result.success = true;
        return result;
    }
    
    /**
     * 创建错误结果
     */
    public static MCPResult error(String message) {
        MCPResult result = new MCPResult();
        result.type = "error";
        result.errorMessage = message;
        result.success = false;
        return result;
    }
    
    // ==================== Getter 和 Setter ====================
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public String getTextContent() {
        return textContent;
    }
    
    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }
    
    public JsonNode getJsonContent() {
        return jsonContent;
    }
    
    public void setJsonContent(JsonNode jsonContent) {
        this.jsonContent = jsonContent;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public String getToolName() {
        return toolName;
    }
    
    public void setToolName(String toolName) {
        this.toolName = toolName;
    }
    
    /**
     * 获取结果的字符串表示
     */
    public String toString() {
        if ("text".equals(type)) {
            return textContent;
        } else if ("json".equals(type)) {
            return jsonContent != null ? jsonContent.toString() : "null";
        } else if ("error".equals(type)) {
            return "Error: " + errorMessage;
        }
        return "";
    }
}
