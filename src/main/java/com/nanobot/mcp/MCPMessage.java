package com.nanobot.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * MCP 协议消息类
 * 
 * MCP (Model Context Protocol) 消息格式定义：
 * 
 * 请求消息：
 * {
 *   "id": "uuid",
 *   "type": "invoke",
 *   "tool_name": "tool_name",
 *   "arguments": {...}
 * }
 * 
 * 响应消息：
 * {
 *   "id": "uuid",
 *   "type": "result",
 *   "content": {...},
 *   "error": null
 * }
 * 
 * 工具列表请求：
 * {
 *   "id": "uuid",
 *   "type": "list_tools"
 * }
 * 
 * 工具列表响应：
 * {
 *   "id": "uuid",
 *   "type": "result",
 *   "content": {"tools": [...]},
 *   "error": null
 * }
 */
public class MCPMessage {
    
    /**
     * 消息类型枚举
     */
    public enum Type {
        INVOKE("invoke"),
        LIST_TOOLS("list_tools"),
        RESULT("result"),
        ERROR("error");
        
        private final String value;
        
        Type(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public static Type fromValue(String value) {
            for (Type type : Type.values()) {
                if (type.value.equalsIgnoreCase(value)) {
                    return type;
                }
            }
            return null;
        }
    }
    
    /**
     * 消息 ID（UUID）
     */
    private String id;
    
    /**
     * 消息类型
     */
    private String type;
    
    /**
     * 工具名称（invoke 类型时使用）
     */
    @JsonProperty("tool_name")
    private String toolName;
    
    /**
     * 工具参数（invoke 类型时使用）
     */
    private JsonNode arguments;
    
    /**
     * 结果内容（result 类型时使用）
     */
    private JsonNode content;
    
    /**
     * 错误信息（error 类型时使用）
     */
    private String error;
    
    /**
     * 进度信息（streaming 时使用）
     */
    private String progress;
    
    // ==================== 构造函数 ====================
    
    public MCPMessage() {
        this.id = UUID.randomUUID().toString();
    }
    
    /**
     * 创建工具调用请求消息
     */
    public static MCPMessage createInvoke(String toolName, JsonNode arguments) {
        MCPMessage msg = new MCPMessage();
        msg.type = Type.INVOKE.getValue();
        msg.toolName = toolName;
        msg.arguments = arguments;
        return msg;
    }
    
    /**
     * 创建工具列表请求消息
     */
    public static MCPMessage createListTools() {
        MCPMessage msg = new MCPMessage();
        msg.type = Type.LIST_TOOLS.getValue();
        return msg;
    }
    
    /**
     * 创建成功结果消息
     */
    public static MCPMessage createResult(String id, JsonNode content) {
        MCPMessage msg = new MCPMessage();
        msg.id = id;
        msg.type = Type.RESULT.getValue();
        msg.content = content;
        return msg;
    }
    
    /**
     * 创建错误结果消息
     */
    public static MCPMessage createError(String id, String errorMessage) {
        MCPMessage msg = new MCPMessage();
        msg.id = id;
        msg.type = Type.ERROR.getValue();
        msg.error = errorMessage;
        return msg;
    }
    
    // ==================== Getter 和 Setter ====================
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public String getToolName() {
        return toolName;
    }
    
    public void setToolName(String toolName) {
        this.toolName = toolName;
    }
    
    public JsonNode getArguments() {
        return arguments;
    }
    
    public void setArguments(JsonNode arguments) {
        this.arguments = arguments;
    }
    
    public JsonNode getContent() {
        return content;
    }
    
    public void setContent(JsonNode content) {
        this.content = content;
    }
    
    public String getError() {
        return error;
    }
    
    public void setError(String error) {
        this.error = error;
    }
    
    public String getProgress() {
        return progress;
    }
    
    public void setProgress(String progress) {
        this.progress = progress;
    }
    
    /**
     * 获取消息类型枚举
     */
    public Type getMessageType() {
        return Type.fromValue(type);
    }
}
