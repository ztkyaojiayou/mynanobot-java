package com.nanocode.mcp;

import com.nanocode.config.Config;
import com.nanocode.tools.Tool;
import com.nanocode.tools.ToolRegistry;
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
    
    private static final Logger logger = LoggerFactory.getLogger(MCPManager.class);
    
    /**
     * MCP 客户端映射（服务器名称 -> 客户端）
     */
    private final Map<String, MCPClient> clients = new ConcurrentHashMap<>();
    
    /**
     * 是否已初始化
     */
    private volatile boolean initialized = false;
    
    // ==================== 初始化方法 ====================
    
    /**
     * 初始化 MCP 管理器
     * 
     * @param config 配置对象
     * @param toolRegistry 工具注册中心
     */
    public void initialize(Config config, ToolRegistry toolRegistry) {
        if (initialized) {
            logger.warn("MCPManager already initialized");
            return;
        }
        
        Config.ToolsConfig toolsConfig = config.getTools();
        Map<String, Config.MCPServerConfig> servers = null;

        if (toolsConfig != null) {
            servers = toolsConfig.getMcpServers();
        }
        // 兜底：检查顶层 mcpServers（兼容旧配置格式）
        if ((servers == null || servers.isEmpty()) && config.getMcpServers() != null) {
            servers = config.getMcpServers();
            logger.info("MCP: using top-level mcpServers ({} entries)", servers.size());
        }

        logger.info("MCP config: tools={}, servers={}",
                toolsConfig != null ? toolsConfig.getClass().getSimpleName() : "null",
                servers != null ? servers.keySet() : "null");
        if (servers == null || servers.isEmpty()) {
            logger.info("No MCP servers configured");
            return;
        }
        
        logger.info("Initializing MCP servers...");
        
        for (Map.Entry<String, Config.MCPServerConfig> entry : servers.entrySet()) {
            String serverName = entry.getKey();
            Config.MCPServerConfig serverConfig = entry.getValue();
            
            if (!serverConfig.isEnable()) {
                logger.debug("MCP server {} is disabled", serverName);
                continue;
            }
            
            try {
                addServer(serverName, serverConfig, toolRegistry);
            } catch (Exception e) {
                logger.error("Failed to initialize MCP server {}: {}", serverName, e.getMessage());
            }
        }
        
        initialized = true;
        logger.info("MCP initialization complete");
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
            logger.error("Failed to create MCP client for {}", serverName);
            return;
        }
        
        // 保存客户端
        clients.put(serverName, client);
        
        try {
            // 获取工具列表并注册
            List<MCPToolInfo> tools = client.listTools().get();
            logger.info("Discovered {} tools from MCP server {}", tools.size(), serverName);
            
            for (MCPToolInfo toolInfo : tools) {
                // 检查是否在启用列表中
                if (!isToolEnabled(config.getEnabledTools(), toolInfo.getName())) {
                    logger.debug("Tool {} is not enabled", toolInfo.getName());
                    continue;
                }
                
                MCPToolWrapper wrapper = new MCPToolWrapper(client, toolInfo);
                toolRegistry.register(wrapper);
                logger.debug("Registered MCP tool: {}", wrapper.getName());
            }
            
        } catch (Exception e) {
            // npx 未安装等环境问题 → 仅警告，不打断启动
            logger.warn("MCP server '{}' 跳过: {} ({})",
                    serverName, e.getMessage(),
                    e.getCause() != null ? e.getCause().getClass().getSimpleName() : "no_cause");
            clients.remove(serverName);
            try { client.close(); } catch (Exception ignored) {}
        }
    }
    
    /**
     * 创建 MCP 客户端
     */
    private MCPClient createClient(String serverName, Config.MCPServerConfig config) {
        String type = config.getType();
        if ("stdio".equalsIgnoreCase(type)) {
            return new StdioMCPClient(serverName, config);
        } else if ("streamableHttp".equalsIgnoreCase(type) || "http".equalsIgnoreCase(type)) {
            return new StreamableHttpMCPClient(serverName, config);
        } else if ("sse".equalsIgnoreCase(type)) {
            return new SseMCPClient(serverName, config);
        } else {
            logger.error("Unknown MCP transport type: {}", type);
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
        logger.info("Closing all MCP clients...");
        
        for (Map.Entry<String, MCPClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
                logger.debug("Closed MCP client: {}", entry.getKey());
            } catch (Exception e) {
                logger.error("Failed to close MCP client {}: {}", entry.getKey(), e.getMessage());
            }
        }
        
        clients.clear();
        initialized = false;
        
        logger.info("All MCP clients closed");
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
