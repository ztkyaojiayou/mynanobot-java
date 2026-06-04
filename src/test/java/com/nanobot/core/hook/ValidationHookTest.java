package com.nanobot.core.hook;

import com.nanobot.core.hook.impl.ValidationHook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValidationHook 测试类
 */
class ValidationHookTest {
    
    private ValidationHook hook;
    
    @BeforeEach
    void setUp() {
        hook = new ValidationHook();
    }
    
    @Test
    void testInitialState() {
        assertEquals("ValidationHook", hook.getName());
        assertEquals(4096, hook.getMaxContentLength());
        assertEquals(0, hook.getSensitiveWordCount());
    }
    
    @Test
    void testSensitiveWordFiltering() {
        hook.addSensitiveWords("敏感词1", "敏感词2");
        
        AgentHookContext context = createMockContext();
        String result = hook.finalizeContent(context, "这是敏感词1测试内容敏感词2");
        
        assertTrue(result.contains("***"));
        assertFalse(result.contains("敏感词1"));
        assertFalse(result.contains("敏感词2"));
    }
    
    @Test
    void testLengthLimit() {
        hook.setMaxContentLength(10);
        
        String longContent = "这是一段很长很长的测试内容";
        AgentHookContext context = createMockContext();
        String result = hook.finalizeContent(context, longContent);
        
        assertEquals(13, result.length()); // 10 + "..."
        assertTrue(result.endsWith("..."));
    }
    
    @Test
    void testDefaultConfiguration() {
        ValidationHook defaultHook = ValidationHook.createDefault();
        
        assertTrue(defaultHook.getSensitiveWordCount() > 0);
        assertEquals(8192, defaultHook.getMaxContentLength());
    }
    
    @Test
    void testStrictConfiguration() {
        ValidationHook strictHook = ValidationHook.createStrict();
        
        assertEquals(2048, strictHook.getMaxContentLength());
    }
    
    private AgentHookContext createMockContext() {
        return new AgentHookContext() {
            @Override
            public String getSessionKey() { return "test-session"; }
            @Override
            public int getIteration() { return 1; }
            @Override
            public java.util.List<java.util.Map<String, Object>> getMessages() { return java.util.List.of(); }
            @Override
            public boolean hasToolCalls() { return false; }
            @Override
            public java.util.List<?> getToolCalls() { return java.util.List.of(); }
            @Override
            public boolean hasError() { return false; }
            @Override
            public java.util.Map<String, Integer> getUsage() { return java.util.Map.of(); }
            @Override
            public int getTotalTokens() { return 0; }
        };
    }
}