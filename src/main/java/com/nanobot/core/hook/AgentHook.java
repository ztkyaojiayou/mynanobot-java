package com.nanobot.core.hook;

import com.nanobot.core.TurnContext;
import com.nanobot.providers.LLMResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 钩子接口 - 生命周期事件扩展点
 * ======================================
 * 
 * 钩子允许在 Agent 处理消息的不同阶段插入自定义逻辑。
 * 用于实现监控、日志、指标收集等功能。
 * 
 * **支持的钩子点**：
 * 
 * | 钩子方法 | 时机 | 用途 |
 * |----------|------|------|
 * | beforeIteration | 每次 LLM 调用前 | 修改上下文、添加消息 |
 * | onStream | 流式输出每个片段 | 实时显示、处理 |
 * | onStreamEnd | 流式输出结束 | 最终处理、清理 |
 * | beforeExecuteTools | 执行工具前 | 记录、验证 |
 * | afterIteration | 每次迭代后 | 记录结果、收集指标 |
 * | finalizeContent | 最终内容确定 | 内容后处理 |
 * 
 * **设计思想**：
 * 
 * 1. **非侵入**：
 *    - 不修改核心逻辑
 *    - 通过钩子扩展功能
 * 
 * 2. **异步**：
 *    - 所有钩子都是异步的
 *    - 不阻塞主流程
 * 
 * 3. **组合**：
 *    - 支持多个钩子组合
 *    - 按顺序执行
 * 
 * **使用示例**：
 * 
 * ```java
 * // 1. 创建自定义钩子
 * public class MyHook implements AgentHook {
 *     @Override
 *     public CompletableFuture<Void> beforeIteration(AgentHookContext ctx) {
 *         System.out.println("Starting iteration " + ctx.getIteration());
 *         return CompletableFuture.completedFuture(null);
 *     }
 *     
 *     @Override
 *     public CompletableFuture<Void> afterIteration(AgentHookContext ctx) {
 *         System.out.println("Completed iteration " + ctx.getIteration());
 *         return CompletableFuture.completedFuture(null);
 *     }
 * }
 * 
 * // 2. 注册钩子
 * CompositeHook hooks = new CompositeHook();
 * hooks.add(new MyHook());
 * hooks.add(new MetricsHook());
 * 
 * // 3. 在 AgentLoop 中使用
 * context.setHooks(hooks);
 * ```
 */
public interface AgentHook {
    
    // ==================== 钩子方法 ====================
    
    /**
     * 是否需要流式输出
     * 
     * 如果返回 true，将启用流式 LLM 调用。
     */
    default boolean wantsStreaming() {
        return false;
    }
    
    /**
     * 每次 LLM 迭代前调用
     * 
     * 可用于：
     * - 修改消息内容
     * - 添加额外上下文
     * - 记录开始状态
     * 
     * @param context 钩子上下文
     * @return 异步完成
     */
    default CompletableFuture<Void> beforeIteration(AgentHookContext context) {
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * 流式输出片段回调
     * 
     * 每个输出片段调用一次。
     * 可用于实时显示、增量保存等。
     * 
     * @param context 钩子上下文
     * @param delta 新增的内容片段
     * @return 异步完成
     */
    default CompletableFuture<Void> onStream(AgentHookContext context, String delta) {
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * 流式输出结束
     * 
     * 流式输出全部完成后调用。
     * 
     * @param context 钩子上下文
     * @param resuming 是否从之前的流恢复
     * @return 异步完成
     */
    default CompletableFuture<Void> onStreamEnd(AgentHookContext context, boolean resuming) {
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * 执行工具前调用
     * 
     * @param context 钩子上下文
     * @return 异步完成
     */
    default CompletableFuture<Void> beforeExecuteTools(AgentHookContext context) {
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * 每次迭代后调用
     * 
     * 可用于：
     * - 记录结果
     * - 收集指标
     * - 检查错误
     * 
     * @param context 钩子上下文
     * @return 异步完成
     */
    default CompletableFuture<Void> afterIteration(AgentHookContext context) {
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * 最终内容确定
     * 
     * 在返回最终响应前调用。
     * 可用于：
     * - 内容后处理
     * - 敏感词过滤
     * - 格式化
     * 
     * @param context 钩子上下文
     * @param content 原始内容
     * @return 处理后的内容
     */
    default String finalizeContent(AgentHookContext context, String content) {
        return content;
    }
    
    /**
     * 获取钩子名称
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}
