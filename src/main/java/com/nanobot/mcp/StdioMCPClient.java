package com.nanobot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 基于标准输入输出的 MCP 客户端实现
 * 
 * 通过启动外部进程并使用标准输入输出进行通信。
 * 适用于本地 MCP 服务器（如 git-mcp、file-mcp 等）。
 * 
 * 工作原理：
 * 1. 通过 ProcessBuilder 启动外部进程
 * 2. 监听进程的 stdout 获取响应
 * 3. 通过 stdin 发送请求
 * 4. 使用 JSON 格式进行消息交换
 */
public class StdioMCPClient implements MCPClient {
    
    private static final Logger log = LoggerFactory.getLogger(StdioMCPClient.class);
    
    private final Config.MCPServerConfig config;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService executorService;
    
    private Process process;
    private BufferedReader reader;
    private PrintWriter writer;
    
    /**
     * 待处理的请求（按消息 ID 映射）
     */
    private final Map<String, CompletableFuture<MCPResult>> pendingRequests = new ConcurrentHashMap<>();
    
    /**
     * 是否已关闭
     */
    private volatile boolean closed = false;
    
    /**
     * 服务器名称
     */
    private final String serverName;
    
    // ==================== 构造函数 ====================
    
    public StdioMCPClient(String serverName, Config.MCPServerConfig config) {
        this.serverName = serverName;
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newScheduledThreadPool(2);
    }
    
    /**
     * 启动连接
     */
    public void connect() throws IOException {
        if (process != null && process.isAlive()) {
            log.debug("MCP server {} is already running", serverName);
            return;
        }
        
        String command = config.getCommand();
        List<String> args = config.getArgs();
        
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(buildCommand(command, args));
        
        // 设置工作目录（可选）
        String workspace = System.getProperty("user.home") + "/.nanobot/workspace";
        pb.directory(new java.io.File(workspace));
        
        // 合并错误输出到标准输出
        pb.redirectErrorStream(true);
        
        log.info("Starting MCP server {}: {} {}", serverName, command, args);
        
        process = pb.start();
        
        // 设置输入输出流
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        writer = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
        
        // 启动响应监听线程
        startResponseListener();
        
        log.info("MCP server {} started successfully", serverName);
    }
    
    /**
     * 构建命令列表
     */
    private List<String> buildCommand(String command, List<String> args) {
        List<String> cmdList = new ArrayList<>();
        cmdList.add(command);
        if (args != null) {
            cmdList.addAll(args);
        }
        return cmdList;
    }
    
    /**
     * 启动响应监听线程
     */
    private void startResponseListener() {
        executorService.submit(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null && !closed) {
                    try {
                        processResponse(line);
                    } catch (Exception e) {
                        log.error("Failed to process MCP response: {}", e.getMessage());
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    log.error("Error reading from MCP server {}: {}", serverName, e.getMessage());
                }
            }
        });
    }
    
    /**
     * 处理响应消息
     */
    private void processResponse(String line) throws IOException {
        if (line.isBlank()) {
            return;
        }
        
        MCPMessage message = objectMapper.readValue(line, MCPMessage.class);
        
        CompletableFuture<MCPResult> pending = pendingRequests.remove(message.getId());
        if (pending != null) {
            MCPResult result = parseResult(message);
            pending.complete(result);
        }
    }
    
    /**
     * 解析结果消息
     */
    private MCPResult parseResult(MCPMessage message) {
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
            // 尝试直接获取文本内容
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
     * 发送消息
     */
    private void sendMessage(MCPMessage message) throws IOException {
        String json = objectMapper.writeValueAsString(message);
        writer.println(json);
        writer.flush();
        log.debug("Sent MCP message: {}", json);
    }
    
    // ==================== MCPClient 接口实现 ====================
    
    @Override
    public CompletableFuture<MCPResult> callTool(String toolName, Map<String, Object> arguments) {
        CompletableFuture<MCPResult> future = new CompletableFuture<>();
        
        executorService.submit(() -> {
            try {
                // 确保连接已建立
                if (!isConnected()) {
                    connect();
                }
                
                JsonNode argsNode = objectMapper.valueToTree(arguments);
                MCPMessage request = MCPMessage.createInvoke(toolName, argsNode);
                
                pendingRequests.put(request.getId(), future);
                sendMessage(request);
                
                // 设置超时
                executorService.schedule(() -> {
                    CompletableFuture<MCPResult> pending = pendingRequests.remove(request.getId());
                    if (pending != null && !pending.isDone()) {
                        pending.complete(MCPResult.error("Tool call timeout"));
                    }
                }, config.getToolTimeout(), java.util.concurrent.TimeUnit.SECONDS);
                
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }
    
    @Override
    public CompletableFuture<List<MCPToolInfo>> listTools() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 确保连接已建立
                if (!isConnected()) {
                    connect();
                }
                
                MCPMessage request = MCPMessage.createListTools();
                CompletableFuture<MCPResult> future = new CompletableFuture<>();
                
                pendingRequests.put(request.getId(), future);
                sendMessage(request);
                
                MCPResult result = future.get(config.getToolTimeout(), java.util.concurrent.TimeUnit.SECONDS);
                
                if (!result.isSuccess()) {
                    log.warn("Failed to list tools: {}", result.getErrorMessage());
                    return new ArrayList<>();
                }
                
                return parseToolList(result);
                
            } catch (Exception e) {
                log.error("Failed to list tools: {}", e.getMessage());
                return new ArrayList<>();
            }
        }, executorService);
    }
    
    /**
     * 解析工具列表
     */
    private List<MCPToolInfo> parseToolList(MCPResult result) throws IOException {
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
        Map<String, Object> params = new HashMap<>(arguments);
        params.put("prompt_name", promptName);
        return callTool("get_prompt", params);
    }
    
    @Override
    public void close() {
        closed = true;
        
        if (writer != null) {
            writer.close();
        }
        
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                // ignore
            }
        }
        
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        
        executorService.shutdown();
        
        log.info("MCP client {} closed", serverName);
    }
    
    @Override
    public boolean isConnected() {
        return process != null && process.isAlive() && !closed;
    }
    
    @Override
    public String getServerName() {
        return serverName;
    }
}
