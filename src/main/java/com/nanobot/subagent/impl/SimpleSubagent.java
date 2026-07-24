package com.nanobot.subagent.impl;

import com.nanobot.core.TurnContext;
import com.nanobot.subagent.Subagent;
import com.nanobot.providers.LLMProvider;
import com.nanobot.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SimpleSubagent - 简单子 Agent 实现
 * ================================
 * 
 * 基于 LLM Provider 和工具注册表实现的子 Agent。
 * 支持：
 * - 独立的 LLM 调用
 * - 工具调用
 * - 自定义系统提示词
 * 
 * **使用示例**：
 * 
 * ```java
 * // 创建子 Agent
 * SimpleSubagent agent = new SimpleSubagent(
 *     "search-assistant",
 *     provider,
 *     toolRegistry
 * );
 * 
 * // 设置能力
 * agent.setCapability("web_search", true);
 * agent.setCapability("summarization", true);
 * 
 * // 设置系统提示词
 * agent.setSystemPrompt("你是一个专业的搜索助手...");
 * 
 * // 执行任务
 * CompletableFuture<String> result = agent.execute(
 *     "搜索人工智能最新进展",
 *     Map.of("useSearch", true)
 * );
 * ```
 */
public class SimpleSubagent implements Subagent {
    
    private static final Logger logger = LoggerFactory.getLogger(SimpleSubagent.class);
    
    /** Agent ID */
    private final String id;
    
    /** Agent 名称 */
    private String name;
    
    /** Agent 描述 */
    private String description;
    
    /** LLM 提供商 */
    private final LLMProvider provider;
    
    /** 工具注册表 */
    private final ToolRegistry toolRegistry;
    
    /** 能力集合 */
    private final Map<String, Boolean> capabilities = new ConcurrentHashMap<>();
    
    /** 系统提示词 */
    private String systemPrompt;
    
    /** 状态 */
    private volatile SubagentStatus status = SubagentStatus.CREATED;
    
    /** 执行器 */
    private ExecutorService executor;
    
    /** 执行统计 */
    private final SubagentStatsImpl stats = new SubagentStatsImpl();
    
    /** 是否启用工具调用 */
    private boolean toolCallsEnabled = true;
    
    /** 最大迭代次数 */
    private int maxIterations = 3;
    
    // ==================== 构造函数 ====================
    
    public SimpleSubagent(String id, LLMProvider provider, ToolRegistry toolRegistry) {
        this.id = id;
        this.name = id;
        this.provider = provider;
        this.toolRegistry = toolRegistry;
    }
    
    public SimpleSubagent(String id, String name, LLMProvider provider, ToolRegistry toolRegistry) {
        this.id = id;
        this.name = name;
        this.provider = provider;
        this.toolRegistry = toolRegistry;
    }
    
    // ==================== 配置方法 ====================
    
    /**
     * 设置系统提示词
     */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
    
    /**
     * 获取系统提示词
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }
    
    /**
     * 启用/禁用工具调用
     */
    public void setToolCallsEnabled(boolean enabled) {
        this.toolCallsEnabled = enabled;
    }
    
    /**
     * 设置最大迭代次数
     */
    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }
    
    // ==================== Subagent 接口实现 ====================
    
    @Override
    public String getId() {
        return id;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    @Override
    public void setCapability(String capability, boolean enabled) {
        capabilities.put(capability, enabled);
    }
    
    @Override
    public boolean hasCapability(String capability) {
        return capabilities.getOrDefault(capability, false);
    }
    
    @Override
    public Map<String, Boolean> getCapabilities() {
        return Map.copyOf(capabilities);
    }
    
    @Override
    public CompletableFuture<String> execute(String task, Map<String, Object> context) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Subagent {} executing task: {}", id, task);
            
            long startTime = System.currentTimeMillis();
            stats.lastExecutionTime = Instant.now();
            
            try {
                status = SubagentStatus.EXECUTING;
                
                // 构建消息
                List<LLMProvider.Message> messages = new ArrayList<>();
                
                // 添加系统提示词
                if (systemPrompt != null && !systemPrompt.isEmpty()) {
                    messages.add(LLMProvider.Message.ofSystem(systemPrompt));
                } else {
                    messages.add(LLMProvider.Message.ofSystem(
                        "你是一个专业的 AI 助手，请直接回答问题。"));
                }
                
                // 添加用户消息
                messages.add(LLMProvider.Message.ofUser(task));
                
                // 检查是否需要工具调用
                boolean useTools = toolCallsEnabled;
                Object useSearchObj = context != null ? context.get("useSearch") : null;
                if (useSearchObj instanceof Boolean) {
                    useTools = (Boolean) useSearchObj;
                }
                
                // 调用 LLM
                String result;
                if (useTools && toolRegistry != null && !toolRegistry.getDefinitions().isEmpty()) {
                    result = executeWithTools(messages, context);
                } else {
                    result = provider.chat(messages, Collections.emptyList()).join().getContent();
                }
                
                stats.successCount++;
                stats.totalDurationMs += System.currentTimeMillis() - startTime;
                
                logger.info("Subagent {} completed task in {}ms", id, 
                          System.currentTimeMillis() - startTime);
                
                return result;
                
            } catch (Exception e) {
                stats.failureCount++;
                logger.error("Subagent {} failed to execute task: {}", id, e.getMessage(), e);
                throw new RuntimeException("Subagent execution failed: " + e.getMessage(), e);
            } finally {
                status = SubagentStatus.RUNNING;
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<String> executeWithContext(String task, TurnContext turnContext) {
        // 从 TurnContext 提取必要信息
        Map<String, Object> context = new HashMap<>();
        
        if (turnContext.getMessage() != null && turnContext.getMessage().getMetadata() != null) {
            context.putAll(turnContext.getMessage().getMetadata());
        }
        
        return execute(task, context);
    }
    
    /**
     * 使用工具执行
     */
    private String executeWithTools(List<LLMProvider.Message> messages, Map<String, Object> context) {
        // 简化实现：直接调用 LLM 并处理工具调用
        // 完整实现应该包含工具调用循环
        
        var definitions = toolRegistry.getDefinitions();
        var response = provider.chat(messages, definitions).join();
        
        // 检查是否有工具调用
        var toolCalls = response.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // 执行工具调用
            for (var toolCall : toolCalls) {
                try {
                    var result = toolRegistry.execute(
                        toolCall.getName(),
                        toolCall.getArguments()
                    );
                    
                    // 添加工具结果消息
                    messages.add(LLMProvider.Message.ofTool(
                        result != null ? result.toString() : "null",
                        toolCall.getId()
                    ));
                } catch (Exception e) {
                    messages.add(LLMProvider.Message.ofTool(
                        "Error: " + e.getMessage(),
                        toolCall.getId()
                    ));
                }
            }
            
            // 再次调用 LLM 获取最终回答
            return provider.chat(messages, Collections.emptyList()).join().getContent();
        }
        
        return response.getContent();
    }
    
    @Override
    public SubagentStatus getStatus() {
        return status;
    }
    
    @Override
    public void start() {
        if (status == SubagentStatus.RUNNING) {
            return;
        }
        
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Subagent-" + id);
            t.setDaemon(true);
            return t;
        });
        
        status = SubagentStatus.RUNNING;
        logger.info("Subagent {} started", id);
    }
    
    @Override
    public void stop() {
        if (status == SubagentStatus.STOPPED) {
            return;
        }
        
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        status = SubagentStatus.STOPPED;
        logger.info("Subagent {} stopped", id);
    }
    
    @Override
    public boolean isRunning() {
        return status == SubagentStatus.RUNNING || status == SubagentStatus.EXECUTING;
    }
    
    @Override
    public SubagentStats getStats() {
        return stats;
    }
    
    @Override
    public void resetStats() {
        stats.reset();
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 统计实现
     */
    private static class SubagentStatsImpl implements SubagentStats {
        private int successCount;
        private int failureCount;
        private long totalDurationMs;
        private Instant lastExecutionTime;
        
        @Override
        public int getSuccessCount() {
            return successCount;
        }
        
        @Override
        public int getFailureCount() {
            return failureCount;
        }
        
        @Override
        public long getTotalDurationMs() {
            return totalDurationMs;
        }
        
        @Override
        public long getAverageDurationMs() {
            int total = successCount + failureCount;
            return total > 0 ? totalDurationMs / total : 0;
        }
        
        @Override
        public Instant getLastExecutionTime() {
            return lastExecutionTime;
        }
        
        void reset() {
            successCount = 0;
            failureCount = 0;
            totalDurationMs = 0;
            lastExecutionTime = null;
        }
    }
    
    @Override
    public String toString() {
        return "SimpleSubagent{" +
            "id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", status=" + status +
            ", capabilities=" + capabilities.size() +
            '}';
    }
}