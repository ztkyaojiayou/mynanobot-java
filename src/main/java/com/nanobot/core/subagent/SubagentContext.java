package com.nanobot.core.subagent;

import com.nanobot.core.TurnContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SubagentContext - 子 Agent 上下文
 * ================================
 * 
 * 管理子 Agent 执行所需的上下文信息，支持：
 * - 任务数据传递
 * - 共享状态管理
 * - 父子 Agent 通信
 * 
 * **上下文层次**：
 * 
 * ```
 * Parent Context (主 Agent)
 *     │
 *     ├── Subagent Context 1 (子 Agent 1)
 *     │       └── Shared Data
 *     │
 *     ├── Subagent Context 2 (子 Agent 2)
 *     │       └── Shared Data
 *     │
 *     └── Shared Context (共享上下文)
 *             └── Cross-Agent Data
 * ```
 */
public class SubagentContext {
    
    /** 上下文 ID */
    private final String contextId;
    
    /** 父 TurnContext（可选） */
    private final TurnContext parentContext;
    
    /** 子 Agent 专用数据 */
    private final Map<String, Object> privateData = new ConcurrentHashMap<>();
    
    /** 共享数据（可在多个子 Agent 之间共享） */
    private final Map<String, Object> sharedData = new ConcurrentHashMap<>();
    
    /** 任务参数 */
    private final Map<String, Object> taskParams = new ConcurrentHashMap<>();
    
    /** 执行结果 */
    private volatile String result;
    
    /** 错误信息 */
    private volatile String error;
    
    /** 创建时间 */
    private final java.time.Instant createdAt = java.time.Instant.now();
    
    /** 完成时间 */
    private volatile java.time.Instant completedAt;
    
    // ==================== 构造函数 ====================
    
    public SubagentContext(String contextId) {
        this(contextId, null);
    }
    
    public SubagentContext(String contextId, TurnContext parentContext) {
        this.contextId = contextId;
        this.parentContext = parentContext;
    }
    
    /**
     * 创建子上下文（用于嵌套子 Agent）
     */
    public SubagentContext createChildContext(String childId) {
        SubagentContext child = new SubagentContext(childId, parentContext);
        // 继承共享数据
        child.sharedData.putAll(this.sharedData);
        return child;
    }
    
    // ==================== 私有数据操作 ====================
    
    /**
     * 设置私有数据（仅当前子 Agent 可见）
     */
    public void setPrivate(String key, Object value) {
        privateData.put(key, value);
    }
    
    /**
     * 获取私有数据
     */
    @SuppressWarnings("unchecked")
    public <T> T getPrivate(String key) {
        return (T) privateData.get(key);
    }
    
    /**
     * 获取私有数据（带默认值）
     */
    @SuppressWarnings("unchecked")
    public <T> T getPrivate(String key, T defaultValue) {
        return (T) privateData.getOrDefault(key, defaultValue);
    }
    
    /**
     * 删除私有数据
     */
    public void removePrivate(String key) {
        privateData.remove(key);
    }
    
    /**
     * 获取所有私有数据
     */
    public Map<String, Object> getPrivateData() {
        return Map.copyOf(privateData);
    }
    
    // ==================== 共享数据操作 ====================
    
    /**
     * 设置共享数据（所有子 Agent 可见）
     */
    public void setShared(String key, Object value) {
        sharedData.put(key, value);
    }
    
    /**
     * 获取共享数据
     */
    @SuppressWarnings("unchecked")
    public <T> T getShared(String key) {
        return (T) sharedData.get(key);
    }
    
    /**
     * 获取共享数据（带默认值）
     */
    @SuppressWarnings("unchecked")
    public <T> T getShared(String key, T defaultValue) {
        return (T) sharedData.getOrDefault(key, defaultValue);
    }
    
    /**
     * 删除共享数据
     */
    public void removeShared(String key) {
        sharedData.remove(key);
    }
    
    /**
     * 获取所有共享数据
     */
    public Map<String, Object> getSharedData() {
        return Map.copyOf(sharedData);
    }
    
    // ==================== 任务参数操作 ====================
    
    /**
     * 设置任务参数
     */
    public void setTaskParam(String key, Object value) {
        taskParams.put(key, value);
    }
    
    /**
     * 获取任务参数
     */
    @SuppressWarnings("unchecked")
    public <T> T getTaskParam(String key) {
        return (T) taskParams.get(key);
    }
    
    /**
     * 获取任务参数（带默认值）
     */
    @SuppressWarnings("unchecked")
    public <T> T getTaskParam(String key, T defaultValue) {
        return (T) taskParams.getOrDefault(key, defaultValue);
    }
    
    /**
     * 获取所有任务参数
     */
    public Map<String, Object> getTaskParams() {
        return Map.copyOf(taskParams);
    }
    
    /**
     * 批量设置任务参数
     */
    public void setTaskParams(Map<String, Object> params) {
        if (params != null) {
            taskParams.putAll(params);
        }
    }
    
    // ==================== 结果操作 ====================
    
    /**
     * 设置执行结果
     */
    public void setResult(String result) {
        this.result = result;
        this.completedAt = java.time.Instant.now();
    }
    
    /**
     * 获取执行结果
     */
    public String getResult() {
        return result;
    }
    
    /**
     * 设置错误信息
     */
    public void setError(String error) {
        this.error = error;
        this.completedAt = java.time.Instant.now();
    }
    
    /**
     * 获取错误信息
     */
    public String getError() {
        return error;
    }
    
    /**
     * 是否有错误
     */
    public boolean hasError() {
        return error != null && !error.isEmpty();
    }
    
    /**
     * 是否已完成
     */
    public boolean isCompleted() {
        return completedAt != null;
    }
    
    // ==================== 父上下文操作 ====================
    
    /**
     * 获取父 TurnContext
     */
    public Optional<TurnContext> getParentContext() {
        return Optional.ofNullable(parentContext);
    }
    
    /**
     * 检查是否有父上下文
     */
    public boolean hasParentContext() {
        return parentContext != null;
    }
    
    // ==================== Getter ====================
    
    public String getContextId() {
        return contextId;
    }
    
    public java.time.Instant getCreatedAt() {
        return createdAt;
    }
    
    public java.time.Instant getCompletedAt() {
        return completedAt;
    }
    
    /**
     * 获取执行耗时（毫秒）
     */
    public long getDurationMs() {
        if (completedAt == null) {
            return java.time.Duration.between(createdAt, java.time.Instant.now()).toMillis();
        }
        return java.time.Duration.between(createdAt, completedAt).toMillis();
    }
    
    // ==================== 便捷方法 ====================
    
    /**
     * 创建默认上下文
     */
    public static SubagentContext create() {
        return new SubagentContext(generateId());
    }
    
    /**
     * 从 TurnContext 创建
     */
    public static SubagentContext from(TurnContext turnContext) {
        SubagentContext context = new SubagentContext(generateId(), turnContext);
        
        // 复制关键信息
        if (turnContext.getMessage() != null) {
            context.setShared("sessionKey", turnContext.getSessionKey());
            context.setShared("chatId", turnContext.getMessage().getChatId());
            context.setShared("channel", turnContext.getMessage().getChannel());
        }
        
        return context;
    }
    
    private static String generateId() {
        return "ctx-" + System.currentTimeMillis() + "-" + 
               java.util.UUID.randomUUID().toString().substring(0, 8);
    }
    
    @Override
    public String toString() {
        return "SubagentContext{" +
            "contextId='" + contextId + '\'' +
            ", completed=" + isCompleted() +
            ", hasError=" + hasError() +
            ", duration=" + getDurationMs() + "ms" +
            '}';
    }
}