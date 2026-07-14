package com.nanobot.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.UUID;

/**
 * MCP 协议消息类
 *
 * MCP (Model Context Protocol) 消息格式定义。
 */
@Data
public class MCPMessage {

    /** 消息类型枚举 */
    public enum Type {
        INVOKE("invoke"),
        LIST_TOOLS("list_tools"),
        RESULT("result"),
        ERROR("error");

        @lombok.Getter
        private final String value;

        Type(String value) { this.value = value; }

        public static Type fromValue(String value) {
            for (Type type : Type.values()) {
                if (type.value.equalsIgnoreCase(value)) return type;
            }
            return null;
        }
    }

    private String id = UUID.randomUUID().toString();
    private String type;
    @JsonProperty("tool_name") private String toolName;
    private JsonNode arguments;
    private JsonNode content;
    private String error;
    private String progress;

    public static MCPMessage createInvoke(String toolName, JsonNode arguments) {
        MCPMessage msg = new MCPMessage();
        msg.type = Type.INVOKE.getValue();
        msg.toolName = toolName;
        msg.arguments = arguments;
        return msg;
    }

    public static MCPMessage createListTools() {
        MCPMessage msg = new MCPMessage();
        msg.type = Type.LIST_TOOLS.getValue();
        return msg;
    }

    public static MCPMessage createResult(String id, JsonNode content) {
        MCPMessage msg = new MCPMessage();
        msg.id = id;
        msg.type = Type.RESULT.getValue();
        msg.content = content;
        return msg;
    }

    public static MCPMessage createError(String id, String errorMessage) {
        MCPMessage msg = new MCPMessage();
        msg.id = id;
        msg.type = Type.ERROR.getValue();
        msg.error = errorMessage;
        return msg;
    }

    /** 获取消息类型枚举 */
    public Type getMessageType() { return Type.fromValue(type); }
}
