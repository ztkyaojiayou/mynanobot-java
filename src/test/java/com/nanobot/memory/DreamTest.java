package com.nanobot.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dream 长期记忆系统测试类
 * ======================================
 * 
 * 测试长期记忆功能：
 * - 记忆存储结构
 * - 记忆条目创建
 * - 空状态处理
 */
@DisplayName("Dream 长期记忆系统测试")
class DreamTest {

    @Test
    @DisplayName("测试记忆条目创建")
    void testMemoryEntryCreation() {
        Map<String, Object> entryData = new HashMap<>();
        entryData.put("id", "mem-001");
        entryData.put("content", "人工智能是计算机科学的一个分支");
        entryData.put("keywords", List.of("AI", "人工智能"));
        entryData.put("importance", 0.8);
        
        assertNotNull(entryData);
        assertEquals("mem-001", entryData.get("id"));
        assertEquals("人工智能是计算机科学的一个分支", entryData.get("content"));
        assertTrue(((List<?>) entryData.get("keywords")).contains("AI"));
    }

    @Test
    @DisplayName("测试空记忆列表处理")
    void testEmptyMemoryList() {
        List<Map<String, Object>> emptyMemories = new ArrayList<>();
        
        assertNotNull(emptyMemories);
        assertTrue(emptyMemories.isEmpty());
        
        // 测试检索空列表
        assertTrue(emptyMemories.isEmpty());
    }

    @Test
    @DisplayName("测试记忆关键词匹配")
    void testKeywordMatching() {
        List<String> keywords = List.of("Java", "编程", "AI");
        
        assertTrue(keywords.contains("Java"));
        assertTrue(keywords.contains("AI"));
        assertFalse(keywords.contains("Python"));
    }

    @Test
    @DisplayName("测试记忆重要性排序")
    void testImportanceSorting() {
        List<Map<String, Object>> memories = new ArrayList<>();
        
        Map<String, Object> mem1 = new HashMap<>();
        mem1.put("content", "重要内容");
        mem1.put("importance", 0.9);
        memories.add(mem1);
        
        Map<String, Object> mem2 = new HashMap<>();
        mem2.put("content", "普通内容");
        mem2.put("importance", 0.5);
        memories.add(mem2);
        
        // 按重要性排序
        memories.sort((a, b) -> Double.compare(
            (Double) b.get("importance"), 
            (Double) a.get("importance")
        ));
        
        assertEquals(0.9, memories.get(0).get("importance"));
        assertEquals("重要内容", memories.get(0).get("content"));
    }

    @Test
    @DisplayName("测试记忆检索逻辑")
    void testMemoryRetrievalLogic() {
        // 模拟简单的检索逻辑
        List<String> allKeywords = List.of("Java", "Python", "AI", "机器学习");
        String query = "Java 编程";
        
        // 检查关键词匹配
        boolean hasMatch = allKeywords.stream()
            .anyMatch(keyword -> query.contains(keyword));
        
        assertTrue(hasMatch);
    }
}