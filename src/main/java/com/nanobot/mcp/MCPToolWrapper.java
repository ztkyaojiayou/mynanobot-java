package com.nanobot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.nanobot.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP 工具包装器
 * 
 * 将 MCP 工具包装为 Nanobot 的 Tool 接口实现，使得 MCP 工具可以无缝集成到工具系统中。
 * 
 * 工具命名规则：mcp_<server_name>_<tool_name>
 * 例如：mcp_git_status, mcp_weather_get
 * 
 * 使用示例：
 * 
 * ```java
 * // 创建 MCP 客户端
 * MCPClient client = new StdioMCPClient("git", config);
 * 
 * // 获取工具列表并包装
 * List<MCPToolInfo> tools = client.listTools().get();
 * for (MCPToolInfo info : tools) {
 *     Tool tool = new MCPToolWrapper(client, info);
 *     toolRegistry.register(tool);
 * }
 * ```
 */
public class MCPToolWrapper implements Tool {
    
    private static final Logger log = LoggerFactory.getLogger(MCPToolWrapper.class);
    
    /**
     * MCP 客户端
     */
    private final MCPClient client;
    
    /**
     * 工具信息
     */
    private final MCPToolInfo toolInfo;
    
    /**
     * 包装后的工具名称
     */
    private final String qualifiedName;
    
    // ==================== 构造函数 ====================
    
    public MCPToolWrapper(MCPClient client, MCPToolInfo toolInfo) {
        this.client = client;
        this.toolInfo = toolInfo;
        this.qualifiedName = toolInfo.getQualifiedName();
    }
    
    // ==================== Tool 接口实现 ====================
    
    @Override
    public String getName() {
        return qualifiedName;
    }
    
    @Override
    public String getDescription() {
        String serverName = client.getServerName();
        return String.format("[MCP:%s] %s", serverName, toolInfo.getDescription());
    }
    
    @Override
    public JsonNode getParameters() {
        return toolInfo.getParameters();
    }
    
    @Override
    public boolean isReadOnly() {
        return toolInfo.isReadOnly();
    }
    
    @Override
    public boolean isExclusive() {
        // MCP 工具默认不是独占的
        return false;
    }
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        // 移除任何 null 值
        Map<String, Object> filteredParams = new HashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() != null) {
                filteredParams.put(entry.getKey(), entry.getValue());
            }
        }
        
        log.debug("Calling MCP tool {} with params: {}", qualifiedName, filteredParams);
        
        return client.callTool(toolInfo.getName(), filteredParams)
                .thenApply(result -> {
                    if (result.isSuccess()) {
                        log.debug("MCP tool {} returned: {}", qualifiedName, result.toString());
                        return (Object) result.toString();
                    } else {
                        log.warn("MCP tool {} failed: {}", qualifiedName, result.getErrorMessage());
                        return (Object) ("Error: " + result.getErrorMessage());
                    }
                })
                .exceptionally(e -> {
                    log.error("MCP tool {} exception: {}", qualifiedName, e.getMessage());
                    return (Object) ("Error: " + e.getMessage());
                });
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 获取原始工具名称（不带服务器前缀）
     */
    public String getOriginalName() {
        return toolInfo.getName();
    }
    
    /**
     * 获取服务器名称
     */
    public String getServerName() {
        return client.getServerName();
    }
    
    /**
     * 获取工具信息
     */
    public MCPToolInfo getToolInfo() {
        return toolInfo;
    }
}
