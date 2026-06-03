package com.nanobot.mcp;

import com.nanobot.config.Config;
import com.nanobot.tools.Tool;
import com.nanobot.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 管理器
 * 
 * 负责管理所有 MCP 服务器连接和工具注册。
 * 
 * 使用示例：
 * 
 * ```java
 * MCPManager manager = new MCPManager();
 * manager.initialize(config, toolRegistry);
 * 
 * // 后续可以动态添加服务器
 * manager.addServer("custom", config);
 * 
 * // 关闭时清理资源
 * manager.close();
 * ```
 */
public class MCPManager {
    
    private static final Logger log = LoggerFactory.getLogger(MCPManager.class);
    
    /**
     * MCP 客户端映射（服务器名称 -> 客户端）
     */
    private final Map<String, MCPClient> clients = new ConcurrentHashMap<>();
    
    /**
     * 是否已初始化
     */
    private boolean initialized = false;
    
    // ==================== 初始化方法 ====================
    
    /**
     * 初始化 MCP 管理器
     * 
     * @param config 配置对象
     * @param toolRegistry 工具注册中心
     */
    public void initialize(Config config, ToolRegistry toolRegistry) {
        if (initialized) {
            log.warn("MCPManager already initialized");
            return;
        }
        
        Config.ToolsConfig toolsConfig = config.getTools();
        if (toolsConfig == null) {
            log.debug("No tools config found, skipping MCP initialization");
            return;
        }
        
        Map<String, Config.MCPServerConfig> servers = toolsConfig.getMcpServers();
        if (servers == null || servers.isEmpty()) {
            log.debug("No MCP servers configured");
            return;
        }
        
        log.info("Initializing MCP servers...");
        
        for (Map.Entry<String, Config.MCPServerConfig> entry : servers.entrySet()) {
            String serverName = entry.getKey();
            Config.MCPServerConfig serverConfig = entry.getValue();
            
            if (!serverConfig.isEnable()) {
                log.debug("MCP server {} is disabled", serverName);
                continue;
            }
            
            try {
                addServer(serverName, serverConfig, toolRegistry);
            } catch (Exception e) {
                log.error("Failed to initialize MCP server {}: {}", serverName, e.getMessage());
            }
        }
        
        initialized = true;
        log.info("MCP initialization complete");
    }
    
    /**
     * 添加 MCP 服务器
     * 
     * @param serverName 服务器名称
     * @param config 服务器配置
     * @param toolRegistry 工具注册中心
     */
    public void addServer(String serverName, Config.MCPServerConfig config, ToolRegistry toolRegistry) {
        // 创建客户端
        MCPClient client = createClient(serverName, config);
        if (client == null) {
            log.error("Failed to create MCP client for {}", serverName);
            return;
        }
        
        // 保存客户端
        clients.put(serverName, client);
        
        try {
            // 获取工具列表并注册
            List<MCPToolInfo> tools = client.listTools().get();
            log.info("Discovered {} tools from MCP server {}", tools.size(), serverName);
            
            for (MCPToolInfo toolInfo : tools) {
                // 检查是否在启用列表中
                if (!isToolEnabled(config.getEnabledTools(), toolInfo.getName())) {
                    log.debug("Tool {} is not enabled", toolInfo.getName());
                    continue;
                }
                
                MCPToolWrapper wrapper = new MCPToolWrapper(client, toolInfo);
                toolRegistry.register(wrapper);
                log.debug("Registered MCP tool: {}", wrapper.getName());
            }
            
        } catch (Exception e) {
            log.error("Failed to register tools from MCP server {}: {}", serverName, e.getMessage());
            clients.remove(serverName);
            client.close();
        }
    }
    
    /**
     * 创建 MCP 客户端
     */
    private MCPClient createClient(String serverName, Config.MCPServerConfig config) {
        String type = config.getType();
        
        if ("stdio".equalsIgnoreCase(type)) {
            return new StdioMCPClient(serverName, config);
        } else if ("sse".equalsIgnoreCase(type) || "streamableHttp".equalsIgnoreCase(type)) {
            return new HttpMCPClient(serverName, config);
        } else {
            log.error("Unknown MCP transport type: {}", type);
            return null;
        }
    }
    
    /**
     * 检查工具是否启用
     */
    private boolean isToolEnabled(List<String> enabledTools, String toolName) {
        if (enabledTools == null || enabledTools.isEmpty()) {
            return true;
        }
        
        // 检查是否包含 "*"（启用所有工具）
        if (enabledTools.contains("*")) {
            return true;
        }
        
        // 检查工具名称是否在列表中
        return enabledTools.contains(toolName);
    }
    
    /**
     * 获取已注册的服务器列表
     */
    public List<String> getServerNames() {
        return new ArrayList<>(clients.keySet());
    }
    
    /**
     * 获取指定服务器的客户端
     */
    public MCPClient getClient(String serverName) {
        return clients.get(serverName);
    }
    
    /**
     * 关闭所有 MCP 客户端
     */
    public void close() {
        log.info("Closing all MCP clients...");
        
        for (Map.Entry<String, MCPClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
                log.debug("Closed MCP client: {}", entry.getKey());
            } catch (Exception e) {
                log.error("Failed to close MCP client {}: {}", entry.getKey(), e.getMessage());
            }
        }
        
        clients.clear();
        initialized = false;
        
        log.info("All MCP clients closed");
    }
    
    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 获取已注册的工具数量
     */
    public int getClientCount() {
        return clients.size();
    }
}
