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
        return new AgentHookContext.Builder()
            .sessionKey("test-session")
            .iteration(1)
            .messages(java.util.List.of())
            .toolCalls(java.util.List.of())
            .usage(java.util.Map.of())
            .build();
    }
}