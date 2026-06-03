package com.nanobot.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM 响应模型
 * ==============
 * 
 * 本类封装了 LLM API 返回的响应数据。
 * 设计为不可变对象，确保响应数据在传递过程中不会被意外修改。
 * 
 * **响应字段说明**：
 * 
 * 1. **内容相关**：
 *    - content: 主要文本响应
 *    - reasoningContent: 推理内容（如 Claude 的思考过程）
 *    - thinkingBlocks: 结构化的思考块
 * 
 * 2. **工具调用相关**：
 *    - toolCalls: 工具调用请求列表
 *    - shouldExecuteTools: 是否应该执行工具
 * 
 * 3. **元数据**：
 *    - finishReason: 响应结束原因
 *    - usage: Token 使用统计
 * 
 * 4. **错误相关**：
 *    - error: 错误信息
 *    - errorKind: 错误类型
 * 
 * **finishReason 取值**：
 * - "stop": 正常结束
 * - "tool_calls": 需要执行工具
 * - "length": 输出长度达到限制
 * - "error": 发生错误
 * - "content_filtered": 内容被过滤
 * 
 * **使用示例**：
 * 
 * ```java
 * // 调用 LLM
 * LLMResponse response = provider.chat(messages, tools);
 * 
 * // 检查响应
 * if (response.isError()) {
 *     System.err.println("Error: " + response.getError());
 *     return;
 * }
 * 
 * // 处理工具调用
 * if (response.shouldExecuteTools()) {
 *     for (ToolCallRequest tool : response.getToolCalls()) {
 *         System.out.println("Call " + tool.getName() + " with " + tool.getArguments());
 *     }
 *     return;
 * }
 * 
 * // 处理最终响应
 * System.out.println(response.getContent());
 * ```
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LLMResponse {
    
    // ==================== 内容字段 ====================
    
    /**
     * 主要文本响应内容
     */
    private final String content;
    
    /**
     * 推理内容
     * 
     * 部分模型支持在正式回答前进行推理（如 Claude 的 extended thinking）。
     * 这个字段包含推理过程。
     */
    private final String reasoningContent;
    
    /**
     * 结构化思考块
     * 
     * 部分 API 支持返回结构化的思考过程（如 OpenAI o1）。
     * 这是一个列表，每个元素代表一个思考步骤。
     */
    private final List<Map<String, Object>> thinkingBlocks;
    
    // ==================== 工具调用字段 ====================
    
    /**
     * 工具调用请求列表
     * 
     * 当 finishReason 为 "tool_calls" 时，这个列表不为空。
     * 包含 LLM 请求调用的所有工具及其参数。
     */
    private final List<ToolCallRequest> toolCalls;
    
    /**
     * 是否应该执行工具
     * 
     * 当模型决定需要调用工具时为 true。
     * 等价于检查 toolCalls 是否非空。
     */
    private final boolean shouldExecuteTools;
    
    // ==================== 元数据字段 ====================
    
    /**
     * 响应结束原因
     * 
     * 指示响应是如何结束的：
     * - "stop": 正常结束
     * - "tool_calls": 触发了工具调用
     * - "length": 达到最大长度
     * - "error": 发生错误
     */
    private final String finishReason;
    
    /**
     * Token 使用统计
     * 
     * 包含本次请求的 token 消耗：
     * - promptTokens/inputTokens: 输入 token 数
     * - completionTokens/outputTokens: 输出 token 数
     * - totalTokens: 总 token 数
     */
    private final Map<String, Integer> usage;
    
    /**
     * 模型名称
     * 
     * 实际使用的模型名称。
     */
    private final String model;
    
    // ==================== 错误字段 ====================
    
    /**
     * 错误信息
     * 
     * 当发生错误时，这个字段包含错误描述。
     */
    private final String error;
    
    /**
     * 错误类型
     * 
     * 错误的分类，用于程序化处理：
     * - "timeout": 请求超时
     * - "rate_limit": 速率限制
     * - "invalid_request": 请求参数错误
     * - "api_error": API 错误
     * - "network_error": 网络错误
     */
    private final String errorKind;
    
    // ==================== 构造函数 ====================
    
    /**
     * 完整构造函数
     */
    public LLMResponse(
            String content,
            String reasoningContent,
            List<Map<String, Object>> thinkingBlocks,
            List<ToolCallRequest> toolCalls,
            String finishReason,
            Map<String, Integer> usage,
            String model,
            String error,
            String errorKind) {
        this.content = content;
        this.reasoningContent = reasoningContent;
        this.thinkingBlocks = thinkingBlocks;
        this.toolCalls = toolCalls;
        this.shouldExecuteTools = toolCalls != null && !toolCalls.isEmpty();
        this.finishReason = finishReason;
        this.usage = usage;
        this.model = model;
        this.error = error;
        this.errorKind = errorKind;
    }
    
    /**
     * 创建成功响应
     */
    public static LLMResponse success(String content, String finishReason, Map<String, Integer> usage) {
        return new LLMResponse(
            content, null, null, null, finishReason, usage, null, null, null
        );
    }
    
    /**
     * 创建工具调用响应
     */
    public static LLMResponse toolCalls(List<ToolCallRequest> toolCalls, String model) {
        return new LLMResponse(
            null, null, null, toolCalls, "tool_calls", null, model, null, null
        );
    }
    
    /**
     * 创建错误响应
     */
    public static LLMResponse error(String errorMessage, String errorKind) {
        return new LLMResponse(
            null, null, null, null, "error", null, null, errorMessage, errorKind
        );
    }
    
    /**
     * 创建超时错误响应
     */
    public static LLMResponse timeout(double timeoutSeconds) {
        return error(
            String.format("Request timed out after %.1f seconds", timeoutSeconds),
            "timeout"
        );
    }
    
    // ==================== Getter 方法 ====================
    
    public String getContent() {
        return content;
    }
    
    public Optional<String> getReasoningContent() {
        return Optional.ofNullable(reasoningContent);
    }
    
    public Optional<List<Map<String, Object>>> getThinkingBlocks() {
        return Optional.ofNullable(thinkingBlocks);
    }
    
    public List<ToolCallRequest> getToolCalls() {
        return toolCalls != null ? toolCalls : List.of();
    }
    
    public boolean shouldExecuteTools() {
        return shouldExecuteTools;
    }
    
    public String getFinishReason() {
        return finishReason;
    }
    
    public Optional<String> getModel() {
        return Optional.ofNullable(model);
    }
    
    public String getError() {
        return error;
    }
    
    public String getErrorKind() {
        return errorKind;
    }
    
    /**
     * 获取使用统计
     */
    public Map<String, Integer> getUsage() {
        return usage != null ? usage : Map.of();
    }
    
    /**
     * 获取输入 Token 数
     */
    public int getPromptTokens() {
        if (usage == null) return 0;
        return usage.getOrDefault("promptTokens", 
               usage.getOrDefault("inputTokens", 0));
    }
    
    /**
     * 获取输出 Token 数
     */
    public int getCompletionTokens() {
        if (usage == null) return 0;
        return usage.getOrDefault("completionTokens",
               usage.getOrDefault("outputTokens", 0));
    }
    
    /**
     * 获取总 Token 数
     */
    public int getTotalTokens() {
        if (usage == null) return 0;
        return usage.getOrDefault("totalTokens", 
               getPromptTokens() + getCompletionTokens());
    }
    
    // ==================== 状态检查方法 ====================
    
    /**
     * 检查是否为错误响应
     */
    public boolean isError() {
        return error != null || "error".equals(finishReason);
    }
    
    /**
     * 检查是否成功
     */
    public boolean isSuccessful() {
        return !isError() && !shouldExecuteTools();
    }
    
    /**
     * 检查是否为超时错误
     */
    public boolean isTimeout() {
        return "timeout".equals(errorKind);
    }
    
    /**
     * 检查是否被速率限制
     */
    public boolean isRateLimited() {
        return "rate_limit".equals(errorKind);
    }
    
    // ==================== ToolCallRequest 内部类 ====================
    
    /**
     * 工具调用请求
     * 
     * 封装了 LLM 请求调用的工具信息。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCallRequest {
        
        /** 工具调用 ID */
        private final String id;
        
        /** 工具名称 */
        private final String name;
        
        /** 工具参数 */
        private final Map<String, Object> arguments;
        
        public ToolCallRequest(String id, String name, Map<String, Object> arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
        
        public String getId() {
            return id;
        }
        
        public String getName() {
            return name;
        }
        
        public Map<String, Object> getArguments() {
            return arguments;
        }
        
        /**
         * 获取指定参数值
         */
        @SuppressWarnings("unchecked")
        public <T> T getArgument(String key) {
            return arguments != null ? (T) arguments.get(key) : null;
        }
        
        /**
         * 检查是否包含指定参数
         */
        public boolean hasArgument(String key) {
            return arguments != null && arguments.containsKey(key);
        }
        
        @Override
        public String toString() {
            return "ToolCallRequest{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", arguments=" + arguments +
                '}';
        }
    }
    
    // ==================== Object 方法 ====================
    
    @Override
    public String toString() {
        if (isError()) {
            return "LLMResponse{error='" + error + "', kind='" + errorKind + "'}";
        }
        if (shouldExecuteTools()) {
            return "LLMResponse{toolCalls=" + toolCalls.size() + "}";
        }
        String preview = content != null && content.length() > 50 
            ? content.substring(0, 50) + "..." 
            : content;
        return "LLMResponse{content='" + preview + "', finishReason='" + finishReason + "'}";
    }
}
