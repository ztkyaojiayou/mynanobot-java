package com.nanobot.providers;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * LLM 提供商接口
 * ================
 * 
 * 本接口定义了与各种 LLM API 交互的统一契约。
 * 通过实现这个接口，可以支持不同的 LLM 提供商（OpenAI、Anthropic、Ollama 等）。
 * 
 * **设计思想**：
 * 
 * 1. **统一接口**：
 *    - 所有提供商使用相同的方法调用
 *    - 屏蔽不同 API 的差异
 *    - 便于切换和扩展
 * 
 * 2. **异步优先**：
 *    - 所有网络操作都是异步的
 *    - 支持流式输出
 *    - 使用 CompletableFuture
 * 
 * 3. **灵活配置**：
 *    - 支持自定义参数
 *    - 支持多种模型
 *    - 支持代理和自定义端点
 * 
 * **核心方法**：
 * 
 * | 方法 | 说明 |
 * |------|------|
 * | chat | 发送聊天请求（非流式） |
 * | chatStream | 发送聊天请求（流式） |
 * | chatWithRetry | 带重试的聊天请求 |
 * | chatStreamWithRetry | 带重试的流式请求 |
 * 
 * **使用示例**：
 * 
 * ```java
 * // 1. 创建提供商实例
 * LLMProvider provider = new OpenAIProvider(apiKey, model);
 * 
 * // 2. 准备消息
 * List<Message> messages = List.of(
 *     Message.ofSystem("You are a helpful assistant."),
 *     Message.ofUser("Hello!")
 * );
 * 
 * // 3. 非流式调用
 * LLMResponse response = provider.chat(messages, null).join();
 * System.out.println(response.getContent());
 * 
 * // 4. 流式调用
 * provider.chatStream(messages, null, delta -> {
 *     System.out.print(delta);  // 实时输出
 * }).join();
 * ```
 * 
 * **实现注意事项**：
 * 
 * 1. 所有网络操作必须在异步线程中执行
 * 2. 需要处理各种错误情况（超时、速率限制等）
 * 3. 应该实现重试逻辑
 * 4. 需要正确处理工具调用的格式差异
 */
public interface LLMProvider {
    
    // ==================== 配置方法 ====================
    
    /**
     * 获取提供商名称
     * 
     * 用于日志和调试。
     * 
     * @return 提供商名称，如 "openai", "anthropic"
     */
    String getName();
    
    /**
     * 获取默认模型
     * 
     * @return 默认模型名称
     */
    String getDefaultModel();
    
    /**
     * 获取最大支持 Token 数
     * 
     * 包括输入和输出。
     * 
     * @return 最大 Token 数
     */
    int getMaxTokens();
    
    /**
     * 检查是否支持工具调用
     * 
     * @return 如果支持返回 true
     */
    boolean supportsTools();
    
    /**
     * 检查是否支持流式输出
     * 
     * @return 如果支持返回 true
     */
    boolean supportsStreaming();
    
    // ==================== 核心聊天方法 ====================
    
    /**
     * 发送聊天请求（非流式）
     * 
     * 这是最基础的聊天方法。
     * 所有其他聊天方法都可以基于这个实现。
     * 
     * @param messages 消息列表
     * @param tools 工具定义列表（可为 null）
     * @return 异步响应
     */
    CompletableFuture<LLMResponse> chat(List<Message> messages, List<JsonNode> tools);
    
    /**
     * 发送聊天请求（流式）
     * 
     * 响应通过回调函数实时传递。
     * 适用于需要实时展示生成内容的场景。
     * 
     * @param messages 消息列表
     * @param tools 工具定义列表（可为 null）
     * @param onDelta 内容增量回调（每个字符/片段调用一次）
     * @return 异步响应（包含完整内容）
     */
    CompletableFuture<LLMResponse> chatStream(
        List<Message> messages, 
        List<JsonNode> tools,
        Consumer<String> onDelta
    );
    
    /**
     * 发送聊天请求（带重试）
     * 
     * 自动处理临时错误和速率限制。
     * 
     * @param messages 消息列表
     * @param tools 工具定义列表
     * @param maxRetries 最大重试次数
     * @return 异步响应
     */
    default CompletableFuture<LLMResponse> chatWithRetry(
            List<Message> messages, 
            List<JsonNode> tools,
            int maxRetries) {
        return chatWithRetry(messages, tools, maxRetries, null);
    }
    
    /**
     * 发送聊天请求（带重试和等待回调）
     * 
     * @param messages 消息列表
     * @param tools 工具定义列表
     * @param maxRetries 最大重试次数
     * @param onRetryWait 等待回调（用于显示等待状态）
     * @return 异步响应
     */
    default CompletableFuture<LLMResponse> chatWithRetry(
            List<Message> messages, 
            List<JsonNode> tools,
            int maxRetries,
            Consumer<String> onRetryWait) {
        return chat(messages, tools)
            .exceptionallyCompose(error -> {
                if (maxRetries <= 0) {
                    return CompletableFuture.failedFuture(error);
                }
                
                // 检查是否是可重试的错误
                if (!isRetryable(error)) {
                    return CompletableFuture.failedFuture(error);
                }
                
                // 等待后重试
                if (onRetryWait != null) {
                    onRetryWait.accept("Retrying...");
                }
                
                return CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(1000);  // 简单等待
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).thenCompose(v -> chatWithRetry(messages, tools, maxRetries - 1, onRetryWait));
            });
    }
    
    /**
     * 流式聊天（带重试）
     */
    default CompletableFuture<LLMResponse> chatStreamWithRetry(
            List<Message> messages, 
            List<JsonNode> tools,
            Consumer<String> onDelta,
            int maxRetries) {
        return chatStream(messages, tools, onDelta)
            .exceptionallyCompose(error -> {
                if (maxRetries <= 0) {
                    return CompletableFuture.failedFuture(error);
                }
                if (!isRetryable(error)) {
                    return CompletableFuture.failedFuture(error);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return chatStreamWithRetry(messages, tools, onDelta, maxRetries - 1);
            });
    }
    
    /**
     * 判断错误是否可重试
     * 
     * @param error 错误
     * @return 如果可重试返回 true
     */
    default boolean isRetryable(Throwable error) {
        // 默认认为大多数错误都可能可重试
        // 具体实现可以根据错误类型判断
        return true;
    }
    
    // ==================== 工具调用相关 ====================
    
    /**
     * 获取工具结果的 content 字段格式
     * 
     * 不同 API 对工具结果的格式要求不同：
     * - OpenAI: 字符串
     * - Anthropic: 对象列表
     * 
     * @param toolName 工具名称
     * @param result 工具结果
     * @return 格式化后的结果
     */
    default Object formatToolResult(String toolName, Object result) {
        // 默认返回字符串
        return result != null ? result.toString() : "";
    }
    
    /**
     * 获取工具调用的格式
     * 
     * @return "openai" 或 "anthropic"
     */
    default String getToolCallFormat() {
        return "openai";
    }
    
    // ==================== 消息类 ====================
    
    /**
     * 聊天消息
     * 
     * 封装了单条消息的数据。
     */
    class Message {
        
        /** 消息角色：system, user, assistant */
        private final String role;
        
        /** 消息内容 */
        private final String content;
        
        /** 工具调用（仅 assistant 消息） */
        private final List<ToolCallInfo> toolCalls;
        
        /** 工具调用结果 ID（仅 tool 消息） */
        private final String toolCallId;
        
        private Message(String role, String content, 
                       List<ToolCallInfo> toolCalls, String toolCallId) {
            this.role = role;
            this.content = content;
            this.toolCalls = toolCalls;
            this.toolCallId = toolCallId;
        }
        
        public String getRole() { return role; }
        public String getContent() { return content; }
        public List<ToolCallInfo> getToolCalls() { return toolCalls; }
        public String getToolCallId() { return toolCallId; }
        
        /**
         * 创建系统消息
         */
        public static Message ofSystem(String content) {
            return new Message("system", content, null, null);
        }
        
        /**
         * 创建用户消息
         */
        public static Message ofUser(String content) {
            return new Message("user", content, null, null);
        }
        
        /**
         * 创建助手消息
         */
        public static Message ofAssistant(String content) {
            return new Message("assistant", content, null, null);
        }
        
        /**
         * 创建助手消息（带工具调用）
         */
        public static Message ofAssistant(String content, List<ToolCallInfo> toolCalls) {
            return new Message("assistant", content, toolCalls, null);
        }
        
        /**
         * 创建工具结果消息
         */
        public static Message ofTool(String content, String toolCallId) {
            return new Message("tool", content, null, toolCallId);
        }
        
        /**
         * 转换为 Map（用于 JSON 序列化）
         */
        public Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("role", role);
            
            if (content != null) {
                map.put("content", content);
            }
            
            if (toolCalls != null && !toolCalls.isEmpty()) {
                map.put("tool_calls", toolCalls.stream()
                    .map(tc -> tc.toMap())
                    .collect(java.util.stream.Collectors.toList()));
            }
            
            if (toolCallId != null) {
                map.put("tool_call_id", toolCallId);
            }
            
            return map;
        }
        
        /**
         * 工具调用信息
         */
        public static class ToolCallInfo {
            private final String id;
            private final String name;
            private final Map<String, Object> arguments;
            
            public ToolCallInfo(String id, String name, Map<String, Object> arguments) {
                this.id = id;
                this.name = name;
                this.arguments = arguments;
            }
            
            public String getId() { return id; }
            public String getName() { return name; }
            public Map<String, Object> getArguments() { return arguments; }
            
            public Map<String, Object> toMap() {
                java.util.Map<String, Object> map = new java.util.HashMap<>();
                map.put("id", id);
                map.put("type", "function");
                
                java.util.Map<String, Object> function = new java.util.HashMap<>();
                function.put("name", name);
                function.put("arguments", arguments != null ? arguments : new java.util.HashMap<>());
                map.put("function", function);
                
                return map;
            }
        }
    }
    
    // ==================== 提供商配置类 ====================
    
    /**
     * 提供商配置
     */
    class ProviderConfig {
        private String apiKey;
        private String apiBase;
        private String model;
        private int maxTokens = 8192;
        private double temperature = 0.7;
        private Map<String, String> extraHeaders;
        private Map<String, Object> extraBody;
        
        // Getter 和 Setter
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        
        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }
        
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        
        public Map<String, String> getExtraHeaders() { return extraHeaders; }
        public void setExtraHeaders(Map<String, String> extraHeaders) { this.extraHeaders = extraHeaders; }
        
        public Map<String, Object> getExtraBody() { return extraBody; }
        public void setExtraBody(Map<String, Object> extraBody) { this.extraBody = extraBody; }
    }
}
