package com.nanocode.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM 响应模型
 * ==============
 * 
 * 本类封装了 LLM API 返回的响应数据。
 * 设计为不可变对象，确保响应数据在传递过程中不会被意外修改。
 */
@Value
@Builder
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LLMResponse {
    
    // ==================== 内容字段 ====================
    private final String content;
    private final String reasoningContent;
    private final List<Map<String, Object>> thinkingBlocks;
    
    // ==================== 工具调用字段 ====================
    private final List<ToolCallRequest> toolCalls;
    
    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final boolean shouldExecuteTools = false;
    
    // ==================== 元数据字段 ====================
    private final String finishReason;
    private final Map<String, Integer> usage;
    private final String model;
    
    // ==================== 错误字段 ====================
    private final String error;
    private final String errorKind;
    
    // ==================== 构造函数 ====================
    
    /**
     * 创建成功响应
     */
    public static LLMResponse success(String content, String finishReason, Map<String, Integer> usage) {
        return LLMResponse.builder()
            .content(content)
            .finishReason(finishReason)
            .usage(usage)
            .build();
    }
    
    /**
     * 创建工具调用响应
     */
    public static LLMResponse toolCalls(List<ToolCallRequest> toolCalls, String model) {
        return LLMResponse.builder()
            .toolCalls(toolCalls)
            .shouldExecuteTools(true)
            .finishReason("tool_calls")
            .model(model)
            .build();
    }
    
    /**
     * 创建错误响应
     */
    public static LLMResponse error(String errorMessage, String errorKind) {
        return LLMResponse.builder()
            .error(errorMessage)
            .errorKind(errorKind)
            .finishReason("error")
            .build();
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

    /**
     * 将 HTTP 错误映射为用户友好的中文提示.
     */
    public static LLMResponse httpError(int statusCode, String responseBody) {
        return error(toUserFriendlyError(statusCode, responseBody), "http_error");
    }

    /**
     * LLM API 常见 HTTP 状态码枚举 — 统一管理状态码和用户提示.
     */
    public enum HttpStatus {
        OK(200, "请求成功"),
        BAD_REQUEST(400, "请求参数有误，请检查输入内容。"),
        UNAUTHORIZED(401, "API Key 无效或已过期，请检查配置文件中的 API Key。"),
        FORBIDDEN(403, "API Key 无效或已过期，请检查配置文件中的 API Key。"),
        NOT_FOUND(404, "API 端点不存在，请检查 API 地址配置。"),
        TOO_MANY_REQUESTS(429, "请求过于频繁，已被限流。请稍等片刻再试。"),
        INTERNAL_ERROR(500, "AI 服务端异常，请稍后重试。"),
        BAD_GATEWAY(502, "AI 服务网关异常，请稍后重试。"),
        SERVICE_UNAVAILABLE(503, "") {  // 动态消息，根据 body 判断子场景
            @Override
            String toUserMessage(String body) {
                if (body != null && body.contains("service_unavailable")) {
                    return "AI 服务当前繁忙，请稍后重试。\n\n💡 建议：等待几秒后重新发送消息，或临时切换到其他模型。";
                }
                return "服务暂时不可用，请稍后重试。";
            }
        },
        GATEWAY_TIMEOUT(504, "AI 服务响应超时，请稍后重试。");

        private final int code;
        private final String message;

        HttpStatus(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int code() { return code; }

        /** 正文不敏感时直接返回预设消息，子类重写以支持动态判断 */
        String toUserMessage(String body) {
            return message;
        }

        /** 根据状态码查找枚举，未知 5xx 归入 INTERNAL_ERROR */
        static HttpStatus of(int code) {
            for (HttpStatus s : values()) {
                if (s.code == code) return s;
            }
            return code >= 500 ? INTERNAL_ERROR : null;
        }
    }

    private static String toUserFriendlyError(int statusCode, String body) {
        HttpStatus status = HttpStatus.of(statusCode);
        if (status != null) {
            return status.toUserMessage(body);
        }
        return String.format("请求失败（HTTP %d）", statusCode);
    }

    // ==================== 自定义 Getter 方法 ====================
    
    public boolean shouldExecuteTools() {
        return shouldExecuteTools;
    }
    
    public Optional<String> getReasoningContent() {
        return Optional.ofNullable(reasoningContent);
    }
    
    public Optional<List<Map<String, Object>>> getThinkingBlocks() {
        return Optional.ofNullable(thinkingBlocks);
    }
    
    public Optional<String> getModel() {
        return Optional.ofNullable(model);
    }
    
    public List<ToolCallRequest> getToolCalls() {
        return toolCalls != null ? toolCalls : List.of();
    }
    
    public Map<String, Integer> getUsage() {
        return usage != null ? usage : Map.of();
    }
    
    public int getPromptTokens() {
        if (usage == null) return 0;
        return usage.getOrDefault("promptTokens", 
               usage.getOrDefault("inputTokens", 0));
    }
    
    public int getCompletionTokens() {
        if (usage == null) return 0;
        return usage.getOrDefault("completionTokens",
               usage.getOrDefault("outputTokens", 0));
    }
    
    public int getTotalTokens() {
        if (usage == null) return 0;
        return usage.getOrDefault("totalTokens", 
               getPromptTokens() + getCompletionTokens());
    }
    
    // ==================== 状态检查方法 ====================
    
    public boolean isError() {
        return error != null || "error".equals(finishReason);
    }
    
    public boolean isSuccessful() {
        return !isError() && !shouldExecuteTools();
    }
    
    public boolean isTimeout() {
        return "timeout".equals(errorKind);
    }
    
    public boolean isRateLimited() {
        return "rate_limit".equals(errorKind);
    }
    
    // ==================== ToolCallRequest 内部类 ====================
    
    @Value
    @Builder
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCallRequest {
        private final String id;
        private final String name;
        private final Map<String, Object> arguments;
        
        @SuppressWarnings("unchecked")
        public <T> T getArgument(String key) {
            return arguments != null ? (T) arguments.get(key) : null;
        }
        
        public boolean hasArgument(String key) {
            return arguments != null && arguments.containsKey(key);
        }
    }
    
    // ==================== Object 方法 ====================
    
    @Override
    public String toString() {
        if (isError()) {
            return "LLMResponse{error='" + error + "', kind='" + errorKind + "'}";
        }
        if (shouldExecuteTools()) {
            return "LLMResponse{toolCalls=" + (toolCalls != null ? toolCalls.size() : 0) + "}";
        }
        String preview = content != null && content.length() > 50 
            ? content.substring(0, 50) + "..." 
            : content;
        return "LLMResponse{content='" + preview + "', finishReason='" + finishReason + "'}";
    }
}
