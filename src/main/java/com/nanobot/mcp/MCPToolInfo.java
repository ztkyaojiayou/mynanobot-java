package com.nanobot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 工具信息类 — 表示从 MCP 服务器获取的工具元数据信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MCPToolInfo {

    private String name;
    private String description;
    private JsonNode parameters;
    private String returnType;
    private boolean readOnly;
    private String serverName;

    public MCPToolInfo(String name, String description, JsonNode parameters, String returnType) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.returnType = returnType;
        this.readOnly = false;
    }

    /** 获取包装后的工具名称（格式：mcp_<server>_<tool>） */
    public String getQualifiedName() {
        if (serverName == null || serverName.isBlank()) return name;
        return "mcp_" + serverName + "_" + name;
    }
}
