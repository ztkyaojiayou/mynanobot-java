package com.nanobot.core;

import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import lombok.Getter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话上下文 - 保存一轮对话的状态信息
 * =====================================
 * 
 * TurnContext 保存 Agent 处理单条消息时的完整上下文。
 * 它在整个处理流程中传递，积累状态信息。
 * 
 * **包含的信息**：
 * 
 * 1. **输入信息**：
 *    - 原始消息
 *    - 会话键
 *    - 时间戳
 * 
 * 2. **中间状态**：
 *    - 当前状态
 *    - LLM 响应
 *    - 工具调用结果
 *    - 最终内容
 * 
 * 3. **统计信息**：
 *    - 迭代次数
 *    - Token 使用
 *    - 错误信息
 * 
 * **设计思想**：
 * 
 * 1. **单次使用**：
 *    - 每个 TurnContext 只用于处理一条消息
 *    - 处理完成后可以保存为历史记录
 * 
 * 2. **线程安全**：
 *    - 使用不可变或线程安全的数据结构
 *    - 支持并发处理多条消息
 * 
 * 3. **易于调试**：
 *    - 保存完整的处理过程
 *    - 便于问题追踪
 * 
 * **使用示例**：
 * 
 * ```java
 * // 创建上下文
 * TurnContext ctx = TurnContext.create(inboundMessage, config, provider, registry);
 * 
 * // 处理消息
 * while (ctx.getState() != TurnState.DONE) {
 *     ctx = processState(ctx);
 * }
 * 
 * // 获取结果
 * String response = ctx.getFinalContent();
 * int tokens = ctx.getTotalTokens();
 * ```
 */
@Getter
public class TurnContext {
    
    // ==================== 输入信息 ====================
    
    /** 原始入站消息 */
    private final InboundMessage message;
    
    /** 会话键 */
    private final String sessionKey;
    
    /** 创建时间 */
    private final Instant createdAt;
    
    // ==================== 配置 ====================
    
    /** 模型名称 */
    private final String model;
    
    /** 最大输出 token */
    private final int maxTokens;
    
    /** 温度参数 */
    private final float temperature;
    
    /** 最大迭代次数 */
    private final int maxIterations;
    
    // ==================== 运行时状态 ====================
    
    /** 当前状态 */
    private TurnState state;
    
    /** 迭代计数 */
    private final AtomicInteger iteration;
    
    /** 消息历史 */
    private final List<Map<String, Object>> messages;
    
    /** 工具定义 */
    private final List<com.fasterxml.jackson.databind.JsonNode> toolDefinitions;
    
    /** LLM 响应 */
    private volatile LLMResponse response;
    
    /** 工具调用列表 */
    private volatile List<LLMResponse.ToolCallRequest> toolCalls;
    
    /** 工具结果列表 */
    private final List<Map<String, Object>> toolResults;
    
    /** 最终内容 */
    private volatile String finalContent;
    
    /** 错误信息 */
    private volatile String error;
    
    /** 是否已取消 */
    private volatile boolean cancelled;
    
    // ==================== 统计信息 ====================
    
    /** Token 使用统计 */
    private final Map<String, Integer> usage;
    
    /** 输出标记 */
    private volatile String stopReason;
    
    /** 是否流式输出 */
    private volatile boolean streamed;
    
    /** 工具执行开始时间 */
    private volatile long toolStartTime;
    
    // ==================== 构造函数 ====================
    
    /**
     * 私有构造函数 - 使用 Builder 创建
     */
    private TurnContext(Builder builder) {
        this.message = builder.message;
        this.sessionKey = builder.sessionKey;
        this.createdAt = Instant.now();
        this.model = builder.model;
        this.maxTokens = builder.maxTokens;
        this.temperature = builder.temperature;
        this.maxIterations = builder.maxIterations;
        this.state = TurnState.RESTORE;
        this.iteration = new AtomicInteger(0);
        this.messages = new ArrayList<>();
        this.toolDefinitions = builder.toolDefinitions != null 
            ? new ArrayList<>(builder.toolDefinitions) 
            : new ArrayList<>();
        this.toolResults = new ArrayList<>();
        this.usage = new HashMap<>();
    }
    
    // ==================== 工厂方法 ====================
    
    /**
     * 创建新的上下文
     */
    public static TurnContext create(
            InboundMessage message,
            String model,
            int maxTokens,
            float temperature,
            int maxIterations,
            List<com.fasterxml.jackson.databind.JsonNode> toolDefinitions) {
        
        String sessionKey = message.getSessionKey();
        
        return TurnContext.builder()
            .message(message)
            .sessionKey(sessionKey)
            .model(model)
            .maxTokens(maxTokens)
            .temperature(temperature)
            .maxIterations(maxIterations)
            .toolDefinitions(toolDefinitions)
            .build();
    }
    
    /**
     * 创建 Builder
     */
    public static Builder builder() {
        return new Builder();
    }
    
    // ==================== 状态管理 ====================
    
    /**
     * 进入下一状态
     */
    public TurnState nextState() {
        return nextState("ok");
    }
    
    /**
     * 进入下一状态（带事件）
     */
    public TurnState nextState(String event) {
        TurnState next = state.next(event);
        state = next;
        return next;
    }
    
    /**
     * 递增迭代计数
     */
    public int incrementIteration() {
        return iteration.incrementAndGet();
    }
    
    /**
     * 检查是否超过最大迭代
     */
    public boolean isOverMaxIterations() {
        return iteration.get() >= maxIterations;
    }
    
    /**
     * 标记取消
     */
    public void cancel() {
        this.cancelled = true;
    }
    
    // ==================== 消息管理 ====================
    
    /**
     * 添加消息
     */
    public void addMessage(String role, String content) {
        messages.add(Map.of("role", role, "content", content != null ? content : ""));
    }
    
    /**
     * 添加消息（从Map，用于加载历史）
     */
    public void addMessage(Map<String, Object> message) {
        if (message != null && message.containsKey("role") && message.containsKey("content")) {
            messages.add(new HashMap<>(message));
        }
    }
    
    /**
     * 添加用户消息
     */
    public void addUserMessage(String content) {
        addMessage("user", content);
    }
    
    /**
     * 添加助手消息
     */
    public void addAssistantMessage(String content, List<LLMResponse.ToolCallRequest> toolCalls) {
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<Map<String, Object>> tcList = toolCalls.stream()
                .map(tc -> {
                    Map<String, Object> tcMap = new HashMap<>();
                    tcMap.put("id", tc.getId());
                    tcMap.put("type", "function");
                    Map<String, Object> func = Map.of(
                        "name", tc.getName(),
                        "arguments", tc.getArguments()
                    );
                    tcMap.put("function", func);
                    return tcMap;
                })
                .toList();
            messages.add(Map.of("role", "assistant", "tool_calls", tcList));
        } else {
            addMessage("assistant", content);
        }
    }
    
    /**
     * 添加工具结果消息
     */
    public void addToolResult(String toolCallId, String name, Object result) {
        messages.add(Map.of(
            "role", "tool",
            "tool_call_id", toolCallId,
            "name", name,
            "content", result != null ? result.toString() : ""
        ));
    }

    // ==================== 工具管理 ====================
    
    /**
     * 添加工具结果
     */
    public void addToolResult(LLMResponse.ToolCallRequest toolCall, Object result) {
        toolResults.add(Map.of(
            "id", toolCall.getId(),
            "name", toolCall.getName(),
            "result", result != null ? result.toString() : ""
        ));
    }
    
    // ==================== Token 统计 ====================
    
    /**
     * 添加 Token 使用
     */
    public void addUsage(int promptTokens, int completionTokens) {
        usage.merge("promptTokens", promptTokens, Integer::sum);
        usage.merge("completionTokens", completionTokens, Integer::sum);
        usage.merge("totalTokens", promptTokens + completionTokens, Integer::sum);
    }
    
    // ==================== Getter / Setter ====================
    // Simple getters are generated by @Getter; this section keeps only non-trivial ones.

    /** Unwrap AtomicInteger for convenience. */
    public int getIteration() { return iteration.get(); }

    // --- Mutable field setters (not generated; @Getter generates getters) ---
    public void setResponse(LLMResponse response) { this.response = response; }
    public void setToolCalls(List<LLMResponse.ToolCallRequest> toolCalls) { this.toolCalls = toolCalls; }
    public void setFinalContent(String content) { this.finalContent = content; }
    public void setError(String error) { this.error = error; }
    public void setStopReason(String reason) { this.stopReason = reason; }
    public void setStreamed(boolean streamed) { this.streamed = streamed; }

    // --- Derived / defensive-copy getters (override Lombok defaults) ---
    public int getPromptTokens() { return usage.getOrDefault("promptTokens", 0); }
    public int getCompletionTokens() { return usage.getOrDefault("completionTokens", 0); }
    public int getTotalTokens() { return usage.getOrDefault("totalTokens", 0); }

    /** Defensive copy. */
    public List<Map<String, Object>> getMessages() { return new ArrayList<>(messages); }

    /** Defensive copy. */
    public List<Map<String, Object>> getToolResults() { return new ArrayList<>(toolResults); }

    /** Defensive copy. */
    public Map<String, Integer> getUsage() { return new HashMap<>(usage); }

    /** Defensive copy. */
    public List<com.fasterxml.jackson.databind.JsonNode> getToolDefinitions() {
        return new ArrayList<>(toolDefinitions);
    }
    
    // ==================== Builder ====================
    
    public static class Builder {
        private InboundMessage message;
        private String sessionKey;
        private String model;
        private int maxTokens = 8192;
        private float temperature = 0.7f;
        private int maxIterations = 100;
        private List<com.fasterxml.jackson.databind.JsonNode> toolDefinitions;
        
        public Builder message(InboundMessage message) {
            this.message = message;
            return this;
        }
        
        public Builder sessionKey(String sessionKey) {
            this.sessionKey = sessionKey;
            return this;
        }
        
        public Builder model(String model) {
            this.model = model;
            return this;
        }
        
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }
        
        public Builder temperature(float temperature) {
            this.temperature = temperature;
            return this;
        }
        
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }
        
        public Builder toolDefinitions(List<com.fasterxml.jackson.databind.JsonNode> definitions) {
            this.toolDefinitions = definitions;
            return this;
        }
        
        public TurnContext build() {
            if (message == null) {
                throw new IllegalStateException("message is required");
            }
            if (model == null) {
                throw new IllegalStateException("model is required");
            }
            return new TurnContext(this);
        }
    }
    
    // ==================== 调试方法 ====================
    
    /**
     * 获取简要摘要
     */
    public String getSummary() {
        return String.format(
            "TurnContext{session=%s, state=%s, iter=%d, tokens=%d}",
            sessionKey, state, iteration.get(), getTotalTokens()
        );
    }
    
    @Override
    public String toString() {
        return "TurnContext{" +
            "sessionKey='" + sessionKey + '\'' +
            ", state=" + state +
            ", iteration=" + iteration.get() +
            ", messages=" + messages.size() +
            ", toolResults=" + toolResults.size() +
            ", totalTokens=" + getTotalTokens() +
            '}';
    }
}
