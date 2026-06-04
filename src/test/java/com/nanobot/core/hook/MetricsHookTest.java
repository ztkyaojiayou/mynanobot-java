package com.nanobot.core.hook;

import com.nanobot.core.hook.impl.MetricsHook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MetricsHook 测试类
 */
class MetricsHookTest {
    
    private MetricsHook hook;
    
    @BeforeEach
    void setUp() {
        hook = new MetricsHook();
    }
    
    @Test
    void testInitialState() {
        assertEquals("MetricsHook", hook.getName());
        assertTrue(hook.getMetrics("test-session").isEmpty());
    }
    
    @Test
    void testMetricsCollection() {
        String sessionKey = "test-session";
        
        // 模拟多次迭代
        AgentHookContext context = createMockContext(sessionKey, 1);
        hook.beforeIteration(context).join();
        
        // 模拟 LLM 调用
        context = createMockContext(sessionKey, 1);
        hook.afterIteration(context).join();
        
        // 模拟最终化
        hook.finalizeContent(context, "test content");
        
        // 检查指标
        Map<String, Object> metrics = hook.getMetrics(sessionKey);
        assertNotNull(metrics);
        assertTrue((Integer) metrics.get("iterations") >= 1);
    }
    
    @Test
    void testGlobalMetrics() {
        // 模拟多次请求
        for (int i = 0; i < 5; i++) {
            String sessionKey = "session-" + i;
            AgentHookContext context = createMockContext(sessionKey, 1);
            hook.beforeIteration(context).join();
            hook.afterIteration(context).join();
            hook.finalizeContent(context, "content");
        }
        
        Map<String, Object> global = hook.getGlobalMetrics();
        assertEquals(5, global.get("totalRequests"));
    }
    
    @Test
    void testClearMetrics() {
        String sessionKey = "test-session";
        AgentHookContext context = createMockContext(sessionKey, 1);
        hook.beforeIteration(context).join();
        hook.afterIteration(context).join();
        hook.finalizeContent(context, "content");
        
        assertFalse(hook.getMetrics(sessionKey).isEmpty());
        hook.clearMetrics(sessionKey);
        assertTrue(hook.getMetrics(sessionKey).isEmpty());
    }
    
    private AgentHookContext createMockContext(String sessionKey, int iteration) {
        return new AgentHookContext() {
            @Override
            public String getSessionKey() { return sessionKey; }
            @Override
            public int getIteration() { return iteration; }
            @Override
            public java.util.List<java.util.Map<String, Object>> getMessages() { return java.util.List.of(); }
            @Override
            public boolean hasToolCalls() { return false; }
            @Override
            public java.util.List<?> getToolCalls() { return java.util.List.of(); }
            @Override
            public boolean hasError() { return false; }
            @Override
            public java.util.Map<String, Integer> getUsage() { return java.util.Map.of("promptTokens", 100, "completionTokens", 50); }
            @Override
            public int getTotalTokens() { return 150; }
        };
    }
}