package com.nanobot.hook.impl;

import com.nanobot.hook.AgentHook;
import com.nanobot.hook.AgentHookContext;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标收集钩子 - 收集 Agent 运行时指标
 * =====================================
 * 
 * 收集以下指标：
 * - Token 使用量（每次调用、累计）
 * - 耗时（总耗时、LLM调用耗时、工具执行耗时）
 * - 工具调用次数
 * - 迭代次数
 * - 错误次数
 * 
 * **指标数据结构**：
 * 
 * ```json
 * {
 *   "sessionKey": "xxx",
 *   "totalTokens": 1234,
 *   "promptTokens": 500,
 *   "completionTokens": 734,
 *   "totalDurationMs": 1500,
 *   "llmDurationMs": 1200,
 *   "toolDurationMs": 300,
 *   "iterations": 3,
 *   "toolCalls": 2,
 *   "errors": 0
 * }
 * ```
 * 
 * **使用方式**：
 * 
 * ```java
 * MetricsHook metrics = new MetricsHook();
 * compositeHook.add(metrics);
 * 
 * // 获取指标
 * Map<String, Object> stats = metrics.getMetrics("sessionKey");
 * ```
 */
@Getter
public class MetricsHook implements AgentHook {
    
    private static final Logger logger = LoggerFactory.getLogger(MetricsHook.class);
    
    /** 会话指标存储 */
    private final ConcurrentHashMap<String, SessionMetrics> sessionMetrics = new ConcurrentHashMap<>();
    
    /** 全局指标 */
    private final GlobalMetrics globalMetrics = new GlobalMetrics();
    
    /** 当前会话开始时间 */
    private final ThreadLocal<Instant> startTime = new ThreadLocal<>();
    
    /** 最后一次 LLM 调用开始时间 */
    private final ThreadLocal<Instant> llmStartTime = new ThreadLocal<>();
    
    /** 工具执行累计时间 */
    private final ThreadLocal<Long> toolDurationAccumulator = ThreadLocal.withInitial(() -> 0L);
    
    // ==================== 钩子方法 ====================
    
    @Override
    public CompletableFuture<Void> beforeIteration(AgentHookContext context) {
        String sessionKey = context.getSessionKey();
        
        // 初始化会话指标
        sessionMetrics.computeIfAbsent(sessionKey, k -> new SessionMetrics());
        
        // 记录开始时间
        if (context.getIteration() == 1) {
            startTime.set(Instant.now());
            globalMetrics.incrementTotalRequests();
        }
        
        // 记录 LLM 调用开始
        llmStartTime.set(Instant.now());
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> beforeExecuteTools(AgentHookContext context) {
        // 记录 LLM 调用结束，计算耗时
        if (llmStartTime.get() != null) {
            long duration = Duration.between(llmStartTime.get(), Instant.now()).toMillis();
            String sessionKey = context.getSessionKey();
            sessionMetrics.computeIfPresent(sessionKey, (k, m) -> {
                m.addLlmDuration(duration);
                return m;
            });
        }
        
        // 记录工具执行开始
        llmStartTime.set(Instant.now());
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> afterIteration(AgentHookContext context) {
        String sessionKey = context.getSessionKey();
        
        // 计算工具执行耗时
        if (llmStartTime.get() != null) {
            long duration = Duration.between(llmStartTime.get(), Instant.now()).toMillis();
            toolDurationAccumulator.set(toolDurationAccumulator.get() + duration);
        }
        
        SessionMetrics metrics = sessionMetrics.computeIfPresent(sessionKey, (k, m) -> {
            // 更新迭代次数
            m.incrementIterations();
            
            // 更新工具调用次数
            if (context.hasToolCalls()) {
                m.addToolCalls(context.getToolCalls().size());
            }
            
            // 更新 Token 使用
            Map<String, Integer> usage = context.getUsage();
            if (usage != null) {
                m.addTokens(
                    usage.getOrDefault("promptTokens", 0),
                    usage.getOrDefault("completionTokens", 0)
                );
            }
            
            // 更新错误计数
            if (context.hasError()) {
                m.incrementErrors();
                globalMetrics.incrementErrors();
            }
            
            return m;
        });
        
        // 记录日志
        if (metrics != null) {
            logger.debug("Metrics updated for session {}: {}", sessionKey, metrics);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public String finalizeContent(AgentHookContext context, String content) {
        String sessionKey = context.getSessionKey();
        
        // 计算总耗时
        if (startTime.get() != null) {
            long totalDuration = Duration.between(startTime.get(), Instant.now()).toMillis();
            
            sessionMetrics.computeIfPresent(sessionKey, (k, m) -> {
                m.setTotalDuration(totalDuration);
                return m;
            });
            
            // 更新全局指标
            globalMetrics.addTotalDuration(totalDuration);
            globalMetrics.addTotalTokens(context.getTotalTokens());
        }
        
        // 输出最终指标
        SessionMetrics metrics = sessionMetrics.get(sessionKey);
        if (metrics != null) {
            logger.info("Session {} completed: tokens={}, duration={}ms, iterations={}, toolCalls={}",
                sessionKey, metrics.getTotalTokens(), metrics.getTotalDurationMs(),
                metrics.getIterations(), metrics.getToolCalls());
        }
        
        return content;
    }
    
    // ==================== 指标查询 ====================
    
    /**
     * 获取指定会话的指标
     */
    public Map<String, Object> getMetrics(String sessionKey) {
        SessionMetrics metrics = sessionMetrics.get(sessionKey);
        if (metrics == null) {
            return Map.of();
        }
        return metrics.toMap();
    }
    
    /**
     * 获取全局指标
     */
    public Map<String, Object> getGlobalMetrics() {
        return globalMetrics.toMap();
    }
    
    /**
     * 清除指定会话的指标
     */
    public void clearMetrics(String sessionKey) {
        sessionMetrics.remove(sessionKey);
    }
    
    /**
     * 清除所有会话指标
     */
    public void clearAllMetrics() {
        sessionMetrics.clear();
    }
    
    @Override
    public String getName() {
        return "MetricsHook";
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 会话级指标
     */
    @Getter
    private static class SessionMetrics {
        private int promptTokens;
        private int completionTokens;
        private int iterations;
        private int toolCalls;
        private int errors;
        private long totalDurationMs;
        private long llmDurationMs;
        
        synchronized void addTokens(int prompt, int completion) {
            this.promptTokens += prompt;
            this.completionTokens += completion;
        }
        
        synchronized void incrementIterations() {
            this.iterations++;
        }
        
        synchronized void addToolCalls(int count) {
            this.toolCalls += count;
        }
        
        synchronized void incrementErrors() {
            this.errors++;
        }
        
        synchronized void setTotalDuration(long duration) {
            this.totalDurationMs = duration;
        }
        
        synchronized void addLlmDuration(long duration) {
            this.llmDurationMs += duration;
        }
        
        synchronized int getTotalTokens() {
            return promptTokens + completionTokens;
        }
        
        synchronized int getIterations() {
            return iterations;
        }
        
        synchronized int getToolCalls() {
            return toolCalls;
        }
        
        synchronized long getTotalDurationMs() {
            return totalDurationMs;
        }
        
        synchronized Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("promptTokens", promptTokens);
            map.put("completionTokens", completionTokens);
            map.put("totalTokens", getTotalTokens());
            map.put("iterations", iterations);
            map.put("toolCalls", toolCalls);
            map.put("errors", errors);
            map.put("totalDurationMs", totalDurationMs);
            map.put("llmDurationMs", llmDurationMs);
            return map;
        }
        
        @Override
        public String toString() {
            return String.format(
                "SessionMetrics{tokens=%d, iterations=%d, toolCalls=%d, errors=%d, duration=%dms}",
                getTotalTokens(), iterations, toolCalls, errors, totalDurationMs);
        }
    }
    
    /**
     * 全局指标
     */
    @Getter
    private static class GlobalMetrics {
        private long totalRequests;
        private long totalErrors;
        private long totalDurationMs;
        private long totalTokens;
        private long startTime = System.currentTimeMillis();
        
        synchronized void incrementTotalRequests() {
            totalRequests++;
        }
        
        synchronized void incrementErrors() {
            totalErrors++;
        }
        
        synchronized void addTotalDuration(long duration) {
            totalDurationMs += duration;
        }
        
        synchronized void addTotalTokens(int tokens) {
            totalTokens += tokens;
        }
        
        synchronized Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("totalRequests", totalRequests);
            map.put("totalErrors", totalErrors);
            map.put("totalDurationMs", totalDurationMs);
            map.put("totalTokens", totalTokens);
            map.put("uptimeMs", System.currentTimeMillis() - startTime);
            if (totalRequests > 0) {
                map.put("avgDurationMs", totalDurationMs / totalRequests);
                map.put("avgTokens", totalTokens / totalRequests);
                map.put("errorRate", String.format("%.2f%%", (double) totalErrors / totalRequests * 100));
            }
            return map;
        }
    }
}