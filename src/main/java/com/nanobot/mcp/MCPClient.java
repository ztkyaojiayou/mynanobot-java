package com.nanobot.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP 客户端接口
 * 
 * MCP (Model Context Protocol) 客户端用于与 MCP 服务器通信。
 * 支持三种传输方式：
 * - stdio: 通过标准输入输出与进程通信
 * - sse: Server-Sent Events
 * - streamableHttp: HTTP 流式传输
 * 
 * 使用示例：
 * 
 * ```java
 * // 创建客户端
 * MCPClient client = new StdioMCPClient(config);
 * 
 * // 列出工具
 * List<MCPToolInfo> tools = client.listTools().get();
 * 
 * // 调用工具
 * MCPResult result = client.callTool("git_status", Map.of()).get();
 * 
 * // 关闭客户端
 * client.close();
 * ```
 */
public interface MCPClient {
    
    /**
     * 调用工具
     * 
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 工具调用结果
     */
    CompletableFuture<MCPResult> callTool(String toolName, Map<String, Object> arguments);
    
    /**
     * 获取工具列表
     * 
     * @return 工具信息列表
     */
    CompletableFuture<List<MCPToolInfo>> listTools();
    
    /**
     * 读取资源（MCP 资源协议）
     * 
     * @param uri 资源 URI
     * @return 资源内容
     */
    CompletableFuture<MCPResult> readResource(String uri);
    
    /**
     * 获取提示模板（MCP 提示协议）
     * 
     * @param promptName 提示名称
     * @param arguments 提示参数
     * @return 提示内容
     */
    CompletableFuture<MCPResult> getPrompt(String promptName, Map<String, Object> arguments);
    
    /**
     * 关闭客户端，释放资源
     */
    void close();
    
    /**
     * 检查连接是否活跃
     * 
     * @return true 如果连接活跃
     */
    boolean isConnected();
    
    /**
     * 获取服务器名称
     * 
     * @return 服务器名称
     */
    String getServerName();
}
