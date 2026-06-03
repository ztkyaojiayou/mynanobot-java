package com.nanobot.bus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MessageBus 消息总线测试类
 * ===================================
 * 
 * 测试消息总线的核心功能：
 * - 入站消息发布与消费
 * - 出站消息发布与消费
 * - 异步发布功能
 * - 消息流转正确性
 */
@DisplayName("MessageBus 消息总线测试")
class MessageBusTest {

    private MessageBus messageBus;

    @BeforeEach
    void setUp() {
        messageBus = new MessageBus(10);
        messageBus.start();
    }

    @AfterEach
    void tearDown() {
        messageBus.shutdown(1, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("测试入站消息发布与消费")
    void testPublishAndConsumeInbound() throws InterruptedException {
        // 创建测试消息
        InboundMessage message = InboundMessage.builder()
                .channel("test")
                .senderId("user123")
                .chatId("chat456")
                .content("Hello, World!")
                .build();

        // 发布消息
        messageBus.publishInbound(message);

        // 消费消息
        InboundMessage consumed = messageBus.consumeInbound(1, TimeUnit.SECONDS);

        // 验证消息内容
        assertNotNull(consumed);
        assertEquals("test", consumed.getChannel());
        assertEquals("user123", consumed.getSenderId());
        assertEquals("chat456", consumed.getChatId());
        assertEquals("Hello, World!", consumed.getContent());
    }

    @Test
    @DisplayName("测试出站消息发布与消费")
    void testPublishAndConsumeOutbound() throws InterruptedException {
        // 创建测试消息
        OutboundMessage message = OutboundMessage.builder()
                .channel("test")
                .chatId("chat456")
                .content("Response message")
                .build();

        // 发布消息
        messageBus.publishOutbound(message);

        // 消费消息
        OutboundMessage consumed = messageBus.consumeOutbound(1, TimeUnit.SECONDS);

        // 验证消息内容
        assertNotNull(consumed);
        assertEquals("test", consumed.getChannel());
        assertEquals("chat456", consumed.getChatId());
        assertEquals("Response message", consumed.getContent());
    }

    @Test
    @DisplayName("测试入站消息异步发布")
    void testPublishInboundAsync() throws InterruptedException {
        InboundMessage message = InboundMessage.builder()
                .channel("async")
                .senderId("asyncUser")
                .chatId("asyncChat")
                .content("Async message")
                .build();

        // 异步发布
        messageBus.publishInboundAsync(message).join();

        // 消费消息
        InboundMessage consumed = messageBus.consumeInbound(1, TimeUnit.SECONDS);
        assertNotNull(consumed);
        assertEquals("Async message", consumed.getContent());
    }

    @Test
    @DisplayName("测试出站消息异步发布")
    void testPublishOutboundAsync() throws InterruptedException {
        OutboundMessage message = OutboundMessage.builder()
                .channel("async")
                .chatId("asyncChat")
                .content("Async response")
                .build();

        // 异步发布
        messageBus.publishOutboundAsync(message).join();

        // 消费消息
        OutboundMessage consumed = messageBus.consumeOutbound(1, TimeUnit.SECONDS);
        assertNotNull(consumed);
        assertEquals("Async response", consumed.getContent());
    }

    @Test
    @DisplayName("测试会话密钥生成")
    void testSessionKey() {
        InboundMessage message1 = InboundMessage.builder()
                .channel("telegram")
                .senderId("user1")
                .chatId("chat1")
                .content("test")
                .build();

        InboundMessage message2 = InboundMessage.builder()
                .channel("telegram")
                .senderId("user1")
                .chatId("chat1")
                .content("test")
                .sessionKeyOverride("custom-key")
                .build();

        assertEquals("telegram:chat1", message1.getSessionKey());
        assertEquals("custom-key", message2.getSessionKey());
    }

    @Test
    @DisplayName("测试消息超时获取")
    void testConsumeWithTimeout() throws InterruptedException {
        // 队列为空时，应该在超时后返回 null
        InboundMessage message = messageBus.consumeInbound(100, TimeUnit.MILLISECONDS);
        assertNull(message);
    }

    @Test
    @DisplayName("测试消息属性检查")
    void testMessageProperties() {
        InboundMessage message = InboundMessage.builder()
                .channel("test")
                .senderId("user")
                .chatId("chat")
                .content("")
                .build();

        assertTrue(message.isContentEmpty());
        assertFalse(message.hasMedia());

        InboundMessage withContent = InboundMessage.builder()
                .channel("test")
                .senderId("user")
                .chatId("chat")
                .content("Hello")
                .build();

        assertFalse(withContent.isContentEmpty());
    }
}