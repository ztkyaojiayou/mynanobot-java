package com.nanobot.providers.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * DeepSeek 提供商实现
 * ====================
 * 
 * 支持 DeepSeek 系列模型：
 * - deepseek-chat
 * - deepseek-coder
 * - deepseek-r1-chat
 * 
 * API 文档：https://platform.deepseek.com/api-docs
 */
public class DeepSeekProvider implements LLMProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekProvider.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final String apiKey;
    private final String model;
    private final String apiBase;
    private final HttpClient httpClient;
    
    public DeepSeekProvider(String apiKey, String model) {
        this(apiKey, model, "https://api.deepseek.com");
    }
    
    public DeepSeekProvider(String apiKey, String model, String apiBase) {
        this.apiKey = apiKey;
        this.model = model;
        this.apiBase = apiBase;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        logger.info("DeepSeekProvider initialized for model: {}", model);
    }
    
    @Override
    public String getName() {
        return "deepseek";
    }
    
    @Override
    public String getDefaultModel() {
        return model;
    }
    
    @Override
    public int getMaxTokens() {
        return 8192;
    }
    
    @Override
    public boolean supportsTools() {
        return true;
    }
    
    @Override
    public boolean supportsStreaming() {
        return true;
    }
    
    @Override
    public CompletableFuture<LLMResponse> chat(List<Message> messages, List<JsonNode> tools) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String requestBody = buildRequestBody(messages, tools);
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();
                
                HttpResponse<String> response = httpClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    return parseResponse(response.body());
                } else {
                    logger.error("DeepSeek API error: {} - {}", response.statusCode(), response.body());
                    return LLMResponse.error("API request failed: " + response.statusCode(), "api_error");
                }
                
            } catch (Exception e) {
                logger.error("DeepSeek API request failed", e);
                return LLMResponse.error("Failed to call DeepSeek API: " + e.getMessage(), "network_error");
            }
        });
    }
    
    @Override
    public CompletableFuture<LLMResponse> chatStream(List<Message> messages, List<JsonNode> tools, 
                                                     Consumer<String> onDelta) {
        return chat(messages, tools).thenApply(response -> {
            if (response.getContent() != null && onDelta != null) {
                String content = response.getContent();
                // 模拟流式输出：每次发送一小段内容
                try {
                    int chunkSize = Math.max(1, content.length() / 50); // 分成约50块
                    for (int i = 0; i < content.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, content.length());
                        String chunk = content.substring(i, end);
                        onDelta.accept(chunk);
                        Thread.sleep(10); // 短暂延迟模拟流式效果
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return response;
        });
    }
    
    private String buildRequestBody(List<Message> messages, List<JsonNode> tools) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("max_tokens", 8192);
            body.put("temperature", 0.7);
            
            List<Map<String, Object>> messagesList = new ArrayList<>();
            for (Message msg : messages) {
                Map<String, Object> msgMap = new HashMap<>();
                msgMap.put("role", msg.getRole());
                
                if (msg.getContent() != null) {
                    msgMap.put("content", msg.getContent());
                }
                
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    List<Map<String, Object>> toolCalls = new ArrayList<>();
                    for (Message.ToolCallInfo tc : msg.getToolCalls()) {
                        Map<String, Object> toolCall = new HashMap<>();
                        toolCall.put("id", tc.getId());
                        toolCall.put("type", "function");
                        
                        Map<String, Object> function = new HashMap<>();
                        function.put("name", tc.getName());
                        // DeepSeek API 要求 arguments 必须是字符串格式（JSON 字符串）
                        Object args = tc.getArguments();
                        if (args instanceof String) {
                            function.put("arguments", args);
                        } else {
                            try {
                                String argsJson = objectMapper.writeValueAsString(args);
                                function.put("arguments", argsJson);
                            } catch (Exception e) {
                                function.put("arguments", "{}");
                            }
                        }
                        toolCall.put("function", function);
                        
                        toolCalls.add(toolCall);
                    }
                    msgMap.put("tool_calls", toolCalls);
                }
                
                if (msg.getToolCallId() != null) {
                    msgMap.put("tool_call_id", msg.getToolCallId());
                }
                
                messagesList.add(msgMap);
            }
            body.put("messages", messagesList);
            
            if (tools != null && !tools.isEmpty()) {
                body.put("tools", tools);
            }
            
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request body", e);
        }
    }
    
    private LLMResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode choice = choices.get(0);
                JsonNode message = choice.get("message");
                String finishReason = choice.has("finish_reason") ? choice.get("finish_reason").asText() : null;
                
                if (message != null) {
                    String content = message.has("content") ? message.get("content").asText() : null;
                    JsonNode toolCalls = message.get("tool_calls");
                    
                    List<LLMResponse.ToolCallRequest> toolCallRequests = new ArrayList<>();
                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            String id = tc.has("id") ? tc.get("id").asText() : null;
                            JsonNode function = tc.get("function");
                            
                            if (function != null) {
                                String name = function.has("name") ? function.get("name").asText() : null;
                                JsonNode args = function.get("arguments");
                                
                                if (name != null) {
                                    Map<String, Object> arguments = new HashMap<>();
                                    if (args != null) {
                                        if (args.isTextual()) {
                                            // 如果 arguments 是字符串格式，需要先解析
                                            try {
                                                String argsStr = args.asText();
                                                arguments = objectMapper.readValue(argsStr, new TypeReference<Map<String, Object>>() {});
                                            } catch (Exception e) {
                                                logger.warn("Failed to parse arguments string: {}", args.asText());
                                            }
                                        } else {
                                            arguments = objectMapper.convertValue(args, new TypeReference<Map<String, Object>>() {});
                                        }
                                    }
                                    
                                    toolCallRequests.add(new LLMResponse.ToolCallRequest(id, name, arguments));
                                }
                            }
                        }
                    }
                    
                    if (!toolCallRequests.isEmpty()) {
                        return LLMResponse.toolCalls(toolCallRequests, model);
                    }
                    
                    return LLMResponse.success(content, finishReason, null);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to parse response", e);
        }
        
        return LLMResponse.error("Failed to parse response", "api_error");
    }
}
