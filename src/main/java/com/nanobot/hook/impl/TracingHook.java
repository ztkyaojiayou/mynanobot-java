package com.nanobot.hook.impl;

import com.nanobot.hook.AgentHook;
import com.nanobot.hook.AgentHookContext;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 链路追踪钩子 - 记录请求链路
 * ==============================
 * 
 * 实现分布式链路追踪，记录每个请求的完整执行路径。
 * 
 * **追踪数据结构**：
 * 
 * ```json
 * {
 *   "traceId": "xxx",
 *   "spanId": "xxx",
 *   "parentSpanId": "xxx",
 *   "sessionKey": "xxx",
 *   "operation": "llm_call",
 *   "startTime": "2024-01-01T12:00:00",
 *   "durationMs": 1200,
 *   "status": "SUCCESS",
 *   "tags": {
 *     "model": "gpt-4o-mini",
 *     "tokens": 500
 *   }
 * }
 * ```
 * 
 * **支持的操作类型**：
 * - `session_start` - 会话开始
 * - `llm_call` - LLM 调用
 * - `tool_call` - 工具调用
 * - `iteration` - 迭代
 * - `session_end` - 会话结束
 * 
 * **使用方式**：
 * 
 * ```java
 * TracingHook tracing = new TracingHook();
 * compositeHook.add(tracing);
 * 
 * // 获取追踪信息
 * List<TraceSpan> spans = tracing.getSpans("sessionKey");
 * ```
 */
@Getter
public class TracingHook implements AgentHook {
    
    private static final Logger logger = LoggerFactory.getLogger(TracingHook.class);
    
    /** 会话追踪存储 */
    private final ConcurrentHashMap<String, List<TraceSpan>> sessionTraces = new ConcurrentHashMap<>();
    
    /** 当前追踪上下文 */
    private final ThreadLocal<TraceContext> currentContext = new ThreadLocal<>();
    
    /** 根 span 开始时间 */
    private final ThreadLocal<Instant> rootStartTime = new ThreadLocal<>();
    
    // ==================== 钩子方法 ====================
    
    @Override
    public CompletableFuture<Void> beforeIteration(AgentHookContext context) {
        String sessionKey = context.getSessionKey();
        
        // 初始化会话追踪
        sessionTraces.computeIfAbsent(sessionKey, k -> new ArrayList<>());
        
        // 创建根 span（第一次迭代时）
        if (context.getIteration() == 1) {
            String traceId = generateTraceId();
            TraceContext ctx = new TraceContext(traceId, null);
            currentContext.set(ctx);
            rootStartTime.set(Instant.now());
            
            TraceSpan span = new TraceSpan(
                traceId,
                traceId,
                null,
                sessionKey,
                "session_start",
                Instant.now()
            );
            addSpan(sessionKey, span);
            
            logger.info("Started trace {} for session {}", traceId, sessionKey);
        }
        
        // 创建迭代 span
        TraceContext ctx = currentContext.get();
        if (ctx != null) {
            String spanId = generateSpanId();
            ctx.setCurrentSpanId(spanId);
            
            TraceSpan span = new TraceSpan(
                ctx.getTraceId(),
                spanId,
                ctx.getParentSpanId(),
                sessionKey,
                "iteration",
                Instant.now()
            );
            span.addTag("iteration", String.valueOf(context.getIteration()));
            addSpan(sessionKey, span);
            
            ctx.setParentSpanId(spanId);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> beforeExecuteTools(AgentHookContext context) {
        String sessionKey = context.getSessionKey();
        TraceContext ctx = currentContext.get();
        
        if (ctx != null && context.hasToolCalls()) {
            String spanId = generateSpanId();
            
            TraceSpan span = new TraceSpan(
                ctx.getTraceId(),
                spanId,
                ctx.getParentSpanId(),
                sessionKey,
                "tool_call",
                Instant.now()
            );
            
            // 添加工具调用信息
            List<String> toolNames = context.getToolCalls().stream()
                .map(call -> call.getName())
                .toList();
            span.addTag("tools", String.join(",", toolNames));
            span.addTag("toolCount", String.valueOf(context.getToolCalls().size()));
            
            addSpan(sessionKey, span);
            
            // 更新父 span ID
            String oldParent = ctx.getParentSpanId();
            ctx.setParentSpanId(spanId);
            ctx.setLastToolSpanId(oldParent);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> afterIteration(AgentHookContext context) {
        String sessionKey = context.getSessionKey();
        TraceContext ctx = currentContext.get();
        
        if (ctx != null) {
            // 完成当前迭代 span
            completeSpan(sessionKey, ctx.getCurrentSpanId(), context.hasError() ? "ERROR" : "SUCCESS");
            
            // 更新 Token 使用
            int tokens = context.getTotalTokens();
            if (tokens > 0) {
                updateSpanTag(sessionKey, ctx.getCurrentSpanId(), "tokens", String.valueOf(tokens));
            }
            
            // 恢复父 span ID
            ctx.setParentSpanId(ctx.getLastToolSpanId());
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public String finalizeContent(AgentHookContext context, String content) {
        String sessionKey = context.getSessionKey();
        TraceContext ctx = currentContext.get();
        
        if (ctx != null) {
            // 完成根 span
            completeSpan(sessionKey, ctx.getTraceId(), context.hasError() ? "ERROR" : "SUCCESS");
            
            // 添加会话结束 span
            TraceSpan endSpan = new TraceSpan(
                ctx.getTraceId(),
                generateSpanId(),
                ctx.getParentSpanId(),
                sessionKey,
                "session_end",
                Instant.now()
            );
            
            // 计算总耗时
            if (rootStartTime.get() != null) {
                long duration = java.time.Duration.between(rootStartTime.get(), Instant.now()).toMillis();
                endSpan.addTag("totalDurationMs", String.valueOf(duration));
            }
            
            endSpan.addTag("contentLength", String.valueOf(content != null ? content.length() : 0));
            endSpan.complete("SUCCESS", 0);
            
            addSpan(sessionKey, endSpan);
            
            logger.info("Completed trace {} for session {}", ctx.getTraceId(), sessionKey);
            
            // 清理上下文
            currentContext.remove();
            rootStartTime.remove();
        }
        
        return content;
    }
    
    // ==================== 追踪查询 ====================
    
    /**
     * 获取指定会话的所有 span
     */
    public List<TraceSpan> getSpans(String sessionKey) {
        List<TraceSpan> spans = sessionTraces.get(sessionKey);
        return spans != null ? List.copyOf(spans) : List.of();
    }
    
    /**
     * 获取指定会话的追踪摘要
     */
    public Map<String, Object> getTraceSummary(String sessionKey) {
        List<TraceSpan> spans = sessionTraces.get(sessionKey);
        if (spans == null || spans.isEmpty()) {
            return Map.of();
        }
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("sessionKey", sessionKey);
        summary.put("spanCount", spans.size());
        
        Optional<TraceSpan> root = spans.stream()
            .filter(s -> "session_start".equals(s.getOperation()))
            .findFirst();
        
        Optional<TraceSpan> end = spans.stream()
            .filter(s -> "session_end".equals(s.getOperation()))
            .findFirst();
        
        if (root.isPresent() && end.isPresent()) {
            long duration = java.time.Duration.between(root.get().getStartTime(), end.get().getStartTime()).toMillis();
            summary.put("totalDurationMs", duration);
        }
        
        long llmCalls = spans.stream().filter(s -> "llm_call".equals(s.getOperation())).count();
        long toolCalls = spans.stream().filter(s -> "tool_call".equals(s.getOperation())).count();
        summary.put("llmCalls", llmCalls);
        summary.put("toolCalls", toolCalls);
        
        return summary;
    }
    
    /**
     * 清除指定会话的追踪数据
     */
    public void clearTraces(String sessionKey) {
        sessionTraces.remove(sessionKey);
    }
    
    /**
     * 清除所有追踪数据
     */
    public void clearAllTraces() {
        sessionTraces.clear();
    }
    
    @Override
    public String getName() {
        return "TracingHook";
    }
    
    // ==================== 辅助方法 ====================
    
    private void addSpan(String sessionKey, TraceSpan span) {
        sessionTraces.computeIfPresent(sessionKey, (k, spans) -> {
            spans.add(span);
            return spans;
        });
    }
    
    private void completeSpan(String sessionKey, String spanId, String status) {
        sessionTraces.computeIfPresent(sessionKey, (k, spans) -> {
            for (TraceSpan span : spans) {
                if (spanId.equals(span.getSpanId()) && !span.isCompleted()) {
                    span.complete(status);
                    break;
                }
            }
            return spans;
        });
    }
    
    private void updateSpanTag(String sessionKey, String spanId, String key, String value) {
        sessionTraces.computeIfPresent(sessionKey, (k, spans) -> {
            for (TraceSpan span : spans) {
                if (spanId.equals(span.getSpanId())) {
                    span.addTag(key, value);
                    break;
                }
            }
            return spans;
        });
    }
    
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    
    private String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 追踪上下文
     */
    @Getter
    private static class TraceContext {
        private final String traceId;
        @Setter
        private String parentSpanId;
        @Setter
        private String currentSpanId;
        @Setter
        private String lastToolSpanId;

        TraceContext(String traceId, String parentSpanId) {
            this.traceId = traceId;
            this.parentSpanId = parentSpanId;
        }
    }
    
    /**
     * 追踪 Span
     */
    @Getter
    public static class TraceSpan {
        private final String traceId;
        private final String spanId;
        private final String parentSpanId;
        private final String sessionKey;
        private final String operation;
        private final Instant startTime;
        private final Map<String, String> tags = new HashMap<>();
        private String status = "RUNNING";
        private long durationMs;
        
        TraceSpan(String traceId, String spanId, String parentSpanId, 
                  String sessionKey, String operation, Instant startTime) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.sessionKey = sessionKey;
            this.operation = operation;
            this.startTime = startTime;
        }
        
        void addTag(String key, String value) {
            tags.put(key, value);
        }
        
        void complete(String status) {
            this.status = status;
            this.durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis();
        }
        
        void complete(String status, long durationMs) {
            this.status = status;
            this.durationMs = durationMs;
        }
        
        boolean isCompleted() {
            return !"RUNNING".equals(status);
        }
        
        public Map<String, String> getTags() { return Map.copyOf(tags); }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("traceId", traceId);
            map.put("spanId", spanId);
            map.put("parentSpanId", parentSpanId);
            map.put("sessionKey", sessionKey);
            map.put("operation", operation);
            map.put("startTime", startTime.toString());
            map.put("status", status);
            map.put("durationMs", durationMs);
            map.put("tags", tags);
            return map;
        }
        
        @Override
        public String toString() {
            return String.format(
                "TraceSpan{trace=%s, span=%s, op=%s, status=%s, duration=%dms}",
                traceId.substring(0, 8), spanId.substring(0, 8), operation, status, durationMs);
        }
    }
}