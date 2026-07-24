package com.nanobot.hook;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 组合钩子 - 支持多个钩子组合使用
 * =================================
 * 
 * 本类实现了组合模式，允许同时使用多个钩子。
 * 钩子按添加顺序执行。
 * 
 * **设计思想**：
 * 
 * 1. **组合模式**：
 *    - 多个钩子作为一个整体
 *    - 统一接口
 * 
 * 2. **容错**：
 *    - 单个钩子失败不影响其他
 *    - 记录失败但不抛出
 * 
 * 3. **短路**：
 *    - 支持短路操作
 *    - 可以停止后续钩子
 * 
 * **使用示例**：
 * 
 * ```java
 * CompositeHook hooks = new CompositeHook();
 * 
 * // 添加日志钩子
 * hooks.add(new LoggingHook());
 * 
 * // 添加指标钩子
 * hooks.add(new MetricsHook());
 * 
 * // 添加自定义钩子
 * hooks.add(new MyCustomHook());
 * 
 * // 在 Agent 中使用
 * hooks.beforeIteration(context);
 * ```
 */
public class CompositeHook implements AgentHook {
    
    /** 钩子列表 */
    private final List<AgentHook> hooks = new ArrayList<>();
    
    /** 钩子名称映射 */
    private final Map<String, AgentHook> hookMap = new LinkedHashMap<>();
    
    // ==================== 钩子管理 ====================
    
    /**
     * 添加钩子
     */
    public void add(AgentHook hook) {
        if (hook == null) {
            return;
        }
        
        hooks.add(hook);
        hookMap.put(hook.getName(), hook);
    }
    
    /**
     * 移除钩子
     */
    public void remove(AgentHook hook) {
        hooks.remove(hook);
        hookMap.remove(hook.getName());
    }
    
    /**
     * 移除钩子（按名称）
     */
    public AgentHook remove(String name) {
        AgentHook hook = hookMap.remove(name);
        if (hook != null) {
            hooks.remove(hook);
        }
        return hook;
    }
    
    /**
     * 清空所有钩子
     */
    public void clear() {
        hooks.clear();
        hookMap.clear();
    }
    
    /**
     * 获取钩子数量
     */
    public int size() {
        return hooks.size();
    }
    
    /**
     * 检查是否为空
     */
    public boolean isEmpty() {
        return hooks.isEmpty();
    }
    
    /**
     * 获取钩子（按名称）
     */
    public AgentHook get(String name) {
        return hookMap.get(name);
    }
    
    // ==================== 钩子方法实现 ====================
    
    @Override
    public boolean wantsStreaming() {
        for (AgentHook hook : hooks) {
            if (hook.wantsStreaming()) {
                return true;
            }
        }
        return false;
    }
    
    @Override
    public CompletableFuture<Void> beforeIteration(AgentHookContext context) {
        return executeChain(hook -> hook.beforeIteration(context));
    }
    
    @Override
    public CompletableFuture<Void> onStream(AgentHookContext context, String delta) {
        context.incrementStreamDelta();
        return executeChain(hook -> hook.onStream(context, delta));
    }
    
    @Override
    public CompletableFuture<Void> onStreamEnd(AgentHookContext context, boolean resuming) {
        return executeChain(hook -> hook.onStreamEnd(context, resuming));
    }
    
    @Override
    public CompletableFuture<Void> beforeExecuteTools(AgentHookContext context) {
        return executeChain(hook -> hook.beforeExecuteTools(context));
    }
    
    @Override
    public CompletableFuture<Void> afterIteration(AgentHookContext context) {
        return executeChain(hook -> hook.afterIteration(context));
    }
    
    @Override
    public String finalizeContent(AgentHookContext context, String content) {
        for (AgentHook hook : hooks) {
            try {
                content = hook.finalizeContent(context, content);
            } catch (Exception e) {
                // 记录但继续
            }
        }
        return content;
    }
    
    @Override
    public String getName() {
        return "CompositeHook[" + hooks.size() + "]";
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 执行钩子链
     */
    private CompletableFuture<Void> executeChain(
            java.util.function.Function<AgentHook, CompletableFuture<Void>> action) {
        
        if (hooks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
        
        for (AgentHook hook : hooks) {
            result = result.thenCompose(v -> {
                try {
                    return action.apply(hook);
                } catch (Exception e) {
                    // 记录错误但继续执行下一个钩子
                    return CompletableFuture.completedFuture(null);
                }
            });
        }
        
        return result;
    }
    
    // ==================== 便捷方法 ====================
    
    /**
     * 创建并添加日志钩子
     */
    public static CompositeHook withLogging() {
        CompositeHook hooks = new CompositeHook();
        hooks.add(new LoggingHook());
        return hooks;
    }
    
    // ==================== 内置钩子 ====================
    
    /**
     * 日志钩子 - 记录处理过程
     */
    public static class LoggingHook implements AgentHook {
        
        private static final org.slf4j.Logger logger = 
            org.slf4j.LoggerFactory.getLogger(LoggingHook.class);
        
        @Override
        public CompletableFuture<Void> beforeIteration(AgentHookContext context) {
            logger.debug("Before iteration {} for session {}", 
                        context.getIteration(), context.getSessionKey());
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public CompletableFuture<Void> afterIteration(AgentHookContext context) {
            if (context.hasError()) {
                logger.warn("Iteration {} completed with error: {}", 
                          context.getIteration(), context.getError());
            } else {
                logger.debug("Iteration {} completed, tokens: {}", 
                           context.getIteration(), context.getTotalTokens());
            }
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public String finalizeContent(AgentHookContext context, String content) {
            logger.debug("Finalizing content for session {}", context.getSessionKey());
            return content;
        }
        
        @Override
        public String getName() {
            return "LoggingHook";
        }
    }
    
    // ==================== 调试 ====================
    
    @Override
    public String toString() {
        return "CompositeHook{" +
            "hooks=" + hooks.stream()
                .map(AgentHook::getName)
                .collect(java.util.stream.Collectors.joining(", ")) +
            '}';
    }
}
