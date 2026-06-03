package com.nanobot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 基于 HTTP 的 MCP 客户端实现
 * 
 * 支持两种 HTTP 传输方式：
 * - streamableHttp: HTTP 流式传输
 * - sse: Server-Sent Events
 * 
 * 工作原理：
 * 1. 使用 Java 11+ HttpClient 发送 HTTP 请求
 * 2. 支持自定义请求头
 * 3. 使用 JSON 格式进行消息交换
 */
public class HttpMCPClient implements MCPClient {
    
    private static final Logger log = LoggerFactory.getLogger(HttpMCPClient.class);
    
    private final Config.MCPServerConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    
    /**
     * 服务器名称
     */
    private final String serverName;
    
    // ==================== 构造函数 ====================
    
    public HttpMCPClient(String serverName, Config.MCPServerConfig config) {
        this.serverName = serverName;
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newHttpClient();
    }
    
    // ==================== MCPClient 接口实现 ====================
    
    @Override
    public CompletableFuture<MCPResult> callTool(String toolName, Map<String, Object> arguments) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode argsNode = objectMapper.valueToTree(arguments);
                MCPMessage request = MCPMessage.createInvoke(toolName, argsNode);
                
                String json = objectMapper.writeValueAsString(request);
                
                HttpRequest httpRequest = buildRequest(json);
                
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    return MCPResult.error("HTTP error: " + response.statusCode());
                }
                
                return parseResponse(response.body());
                
            } catch (Exception e) {
                log.error("Failed to call tool {}: {}", toolName, e.getMessage());
                return MCPResult.error(e.getMessage());
            }
        });
    }
    
    @Override
    public CompletableFuture<List<MCPToolInfo>> listTools() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                MCPMessage request = MCPMessage.createListTools();
                String json = objectMapper.writeValueAsString(request);
                
                HttpRequest httpRequest = buildRequest(json);
                
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    log.warn("Failed to list tools: HTTP error {}", response.statusCode());
                    return new ArrayList<>();
                }
                
                MCPResult result = parseResponse(response.body());
                if (!result.isSuccess()) {
                    log.warn("Failed to list tools: {}", result.getErrorMessage());
                    return new ArrayList<>();
                }
                
                return parseToolList(result);
                
            } catch (Exception e) {
                log.error("Failed to list tools: {}", e.getMessage());
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 构建 HTTP 请求
     */
    private HttpRequest buildRequest(String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.getUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        
        // 添加自定义请求头
        if (config.getHeaders() != null) {
            config.getHeaders().forEach(builder::header);
        }
        
        return builder.build();
    }
    
    /**
     * 解析 HTTP 响应
     */
    private MCPResult parseResponse(String body) throws Exception {
        MCPMessage message = objectMapper.readValue(body, MCPMessage.class);
        
        if ("error".equalsIgnoreCase(message.getType())) {
            return MCPResult.error(message.getError());
        }
        
        JsonNode content = message.getContent();
        if (content == null) {
            return MCPResult.error("Empty response");
        }
        
        // 检查是否有错误字段
        JsonNode errorNode = content.get("error");
        if (errorNode != null && !errorNode.isNull()) {
            return MCPResult.error(errorNode.asText());
        }
        
        // 获取结果类型和内容
        JsonNode typeNode = content.get("type");
        JsonNode valueNode = content.get("value");
        
        if (typeNode == null || valueNode == null) {
            return MCPResult.text(content.asText());
        }
        
        String type = typeNode.asText();
        if ("text".equalsIgnoreCase(type)) {
            return MCPResult.text(valueNode.asText());
        } else if ("json".equalsIgnoreCase(type)) {
            return MCPResult.json(valueNode);
        } else {
            return MCPResult.text(valueNode.asText());
        }
    }
    
    /**
     * 解析工具列表
     */
    private List<MCPToolInfo> parseToolList(MCPResult result) throws Exception {
        List<MCPToolInfo> tools = new ArrayList<>();
        
        JsonNode content = objectMapper.readTree(result.getTextContent());
        JsonNode toolsNode = content.get("content");
        
        if (toolsNode == null) {
            toolsNode = content;
        }
        
        JsonNode toolsArray = toolsNode.get("tools");
        if (toolsArray == null || !toolsArray.isArray()) {
            return tools;
        }
        
        for (JsonNode toolNode : toolsArray) {
            MCPToolInfo toolInfo = new MCPToolInfo();
            toolInfo.setName(toolNode.get("name").asText());
            toolInfo.setDescription(toolNode.get("description").asText());
            toolInfo.setParameters(toolNode.get("parameters"));
            toolInfo.setReturnType(toolNode.has("return_type") ? toolNode.get("return_type").asText() : "text");
            toolInfo.setReadOnly(toolNode.has("read_only") && toolNode.get("read_only").asBoolean());
            toolInfo.setServerName(serverName);
            tools.add(toolInfo);
        }
        
        return tools;
    }
    
    @Override
    public CompletableFuture<MCPResult> readResource(String uri) {
        return callTool("read_resource", Map.of("uri", uri));
    }
    
    @Override
    public CompletableFuture<MCPResult> getPrompt(String promptName, Map<String, Object> arguments) {
        return callTool("get_prompt", arguments);
    }
    
    @Override
    public void close() {
        // HTTP 客户端不需要显式关闭
        log.debug("HTTP MCP client {} closed", serverName);
    }
    
    @Override
    public boolean isConnected() {
        // HTTP 是无状态的，始终返回 true
        return true;
    }
    
    @Override
    public String getServerName() {
        return serverName;
    }
}
