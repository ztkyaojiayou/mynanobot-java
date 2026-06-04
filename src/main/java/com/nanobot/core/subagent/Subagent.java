package com.nanobot.core.subagent;

import com.nanobot.core.TurnContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Subagent 接口 - 子 Agent 契约
 * ==============================
 * 
 * 定义子 Agent 的基本行为。子 Agent 是主 Agent 的助手，
 * 可以独立执行特定任务并返回结果。
 * 
 * **设计思想**：
 * 
 * 1. **职责分离**：
 *    - 主 Agent 负责任务分发和结果汇总
 *    - 子 Agent 负责执行具体任务
 * 
 * 2. **异步执行**：
 *    - 所有方法都是异步的
 *    - 支持并行执行多个子 Agent
 * 
 * 3. **上下文共享**：
 *    - 子 Agent 可以访问主 Agent 的上下文
 *    - 但有独立的执行空间
 * 
 * **使用示例**：
 * 
 * ```java
 * Subagent searchAgent = new SimpleSubagent("search-agent");
 * searchAgent.setCapability("web_search", true);
 * searchAgent.setCapability("summarization", false);
 * 
 * CompletableFuture<String> result = searchAgent.execute(
 *     "搜索人工智能最新进展",
 *     Map.of("useSearch", true)
 * );
 * ```
 */
public interface Subagent {
    
    /**
     * 获取 Agent ID
     */
    String getId();
    
    /**
     * 获取 Agent 名称
     */
    String getName();
    
    /**
     * 获取 Agent 描述
     */
    String getDescription();
    
    /**
     * 设置能力
     * 
     * @param capability 能力名称
     * @param enabled 是否启用
     */
    void setCapability(String capability, boolean enabled);
    
    /**
     * 检查能力
     * 
     * @param capability 能力名称
     * @return 是否具备该能力
     */
    boolean hasCapability(String capability);
    
    /**
     * 获取所有能力
     */
    Map<String, Boolean> getCapabilities();
    
    /**
     * 执行任务
     * 
     * @param task 任务描述
     * @param context 执行上下文
     * @return 执行结果
     */
    CompletableFuture<String> execute(String task, Map<String, Object> context);
    
    /**
     * 执行任务（带 TurnContext）
     * 
     * @param task 任务描述
     * @param turnContext TurnContext
     * @return 执行结果
     */
    CompletableFuture<String> executeWithContext(String task, TurnContext turnContext);
    
    /**
     * 获取状态
     * 
     * @return 当前状态
     */
    SubagentStatus getStatus();
    
    /**
     * 启动 Agent
     */
    void start();
    
    /**
     * 停止 Agent
     */
    void stop();
    
    /**
     * 是否正在运行
     */
    boolean isRunning();
    
    /**
     * 获取执行统计
     */
    SubagentStats getStats();
    
    /**
     * 重置统计
     */
    void resetStats();
    
    // ==================== 内部类 ====================
    
    /**
     * Agent 状态
     */
    enum SubagentStatus {
        /** 已创建但未启动 */
        CREATED,
        /** 正在运行 */
        RUNNING,
        /** 正在执行任务 */
        EXECUTING,
        /** 已停止 */
        STOPPED,
        /** 出错 */
        ERROR
    }
    
    /**
     * 执行统计
     */
    interface SubagentStats {
        /** 成功执行次数 */
        int getSuccessCount();
        
        /** 失败执行次数 */
        int getFailureCount();
        
        /** 总执行时间（毫秒） */
        long getTotalDurationMs();
        
        /** 平均执行时间（毫秒） */
        long getAverageDurationMs();
        
        /** 最后执行时间 */
        java.time.Instant getLastExecutionTime();
    }
}