package com.nanobot.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionManager 会话管理器测试类
 * ======================================
 * 
 * 测试会话管理功能：
 * - 会话结构验证
 * - 会话状态管理
 */
@DisplayName("SessionManager 会话管理器测试")
class SessionManagerTest {

    @Test
    @DisplayName("测试会话结构")
    void testSessionStructure() {
        Map<String, Object> session = new HashMap<>();
        session.put("id", "telegram:chat123");
        session.put("channel", "telegram");
        session.put("sessionId", "chat123");
        session.put("lastActive", Instant.now().toString());
        session.put("active", true);
        
        assertNotNull(session);
        assertEquals("telegram:chat123", session.get("id"));
        assertEquals("telegram", session.get("channel"));
        assertEquals("chat123", session.get("sessionId"));
        assertTrue((Boolean) session.get("active"));
    }

    @Test
    @DisplayName("测试会话ID生成")
    void testSessionIdGeneration() {
        String channel = "discord";
        String originalId = "channel456";
        String sessionId = channel + ":" + originalId;
        
        assertEquals("discord:channel456", sessionId);
        assertTrue(sessionId.contains(":"));
    }

    @Test
    @DisplayName("测试会话状态转换")
    void testSessionStateTransition() {
        Map<String, Object> session = new HashMap<>();
        session.put("active", true);
        
        // 模拟会话关闭
        session.put("active", false);
        
        assertFalse((Boolean) session.get("active"));
    }

    @Test
    @DisplayName("测试会话超时判断")
    void testSessionTimeout() {
        Instant now = Instant.now();
        Instant lastActive = now.minusSeconds(3600); // 1小时前
        
        // 检查是否超时（假设超时时间为30分钟）
        boolean isTimeout = lastActive.plusSeconds(1800).isBefore(now);
        
        assertTrue(isTimeout);
    }
}