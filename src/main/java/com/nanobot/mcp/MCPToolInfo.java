package com.nanobot.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 工具信息类
 * 
 * 表示从 MCP 服务器获取的工具元数据信息。
 * 
 * MCP 工具定义包含：
 * - name: 工具名称
 * - description: 工具描述（用于 LLM 理解）
 * - parameters: 参数 JSON Schema
 * - returnType: 返回类型
 */
public class MCPToolInfo {
    
    /**
     * 工具名称
     */
    private String name;
    
    /**
     * 工具描述
     */
    private String description;
    
    /**
     * 参数 JSON Schema
     */
    private JsonNode parameters;
    
    /**
     * 返回类型
     */
    private String returnType;
    
    /**
     * 是否为只读工具
     */
    private boolean readOnly;
    
    /**
     * 所属服务器名称
     */
    private String serverName;
    
    // ==================== 构造函数 ====================
    
    public MCPToolInfo() {
    }
    
    public MCPToolInfo(String name, String description, JsonNode parameters, String returnType) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.returnType = returnType;
        this.readOnly = false;
    }
    
    // ==================== Getter 和 Setter ====================
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public JsonNode getParameters() {
        return parameters;
    }
    
    public void setParameters(JsonNode parameters) {
        this.parameters = parameters;
    }
    
    public String getReturnType() {
        return returnType;
    }
    
    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }
    
    public boolean isReadOnly() {
        return readOnly;
    }
    
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }
    
    public String getServerName() {
        return serverName;
    }
    
    public void setServerName(String serverName) {
        this.serverName = serverName;
    }
    
    /**
     * 获取包装后的工具名称（格式：mcp_<server>_<tool>）
     */
    public String getQualifiedName() {
        if (serverName == null || serverName.isBlank()) {
            return name;
        }
        return "mcp_" + serverName + "_" + name;
    }
}
