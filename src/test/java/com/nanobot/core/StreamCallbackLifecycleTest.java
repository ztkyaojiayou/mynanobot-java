package com.nanobot.core;

import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.config.Config;
import com.nanobot.config.ConfigLoader;
import com.nanobot.providers.LLMProvider;
import com.nanobot.session.SessionManager;
import com.nanobot.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试流式输出的 Pub-Sub 生命周期 —— 适配新 Fan-Out 架构.
 *
 * <h2>架构变更</h2>
 * 旧: Runner → StreamResponseCallback 回调 → 遍历广播
 * 新: RunState → outboundQueue → Dispatcher 扇出 → subscriberQueue (各通道独立 poll)
 *
 * <h2>测试场景</h2>
 * <ol>
 *   <li>多 subscription 并发消费 —— 模拟 SSE+WS 同时订阅，各自收到完整数据</li>
 *   <li>requestId 精确匹配 —— 每轮过滤自己关心的消息</li>
 *   <li>unsubscribe 后异步清理 —— 不影响下一轮</li>
 * </ol>
 */
class StreamCallbackLifecycleTest {

    private MessageBus messageBus;
    private AgentLoop agentLoop;

    @BeforeEach
    void setUp() {
        messageBus = new MessageBus();
        messageBus.start();

        Config config = ConfigLoader.load();
        ToolRegistry registry = new ToolRegistry();
        SessionManager sessionManager = new SessionManager(config);

        // Mock LLM Provider — 同步调用 onDelta，测试流式 token 发布
        LLMProvider mockProvider = new LLMProvider() {
            @Override public String getName() { return "mock"; }
            @Override public String getDefaultModel() { return "mock-model"; }
            @Override public int getMaxTokens() { return 4096; }
            @Override public boolean supportsTools() { return false; }
            @Override public boolean supportsStreaming() { return true; }

            @Override
            public java.util.concurrent.CompletableFuture<com.nanobot.providers.LLMResponse> chat(
                    List<com.nanobot.providers.LLMProvider.Message> msgs,
                    List<com.fasterxml.jackson.databind.JsonNode> tools) {
                return chatStream(msgs, tools, null);
            }

            @Override
            public java.util.concurrent.CompletableFuture<com.nanobot.providers.LLMResponse> chatStream(
                    List<com.nanobot.providers.LLMProvider.Message> msgs,
                    List<com.fasterxml.jackson.databind.JsonNode> tools,
                    java.util.function.Consumer<String> onDelta) {
                if (onDelta != null) {
                    onDelta.accept("Hello ");
                    onDelta.accept("from ");
                    onDelta.accept("nanobot");
                }
                var resp = com.nanobot.providers.LLMResponse.success(
                        "Hello from nanobot", "stop", null);
                return java.util.concurrent.CompletableFuture.completedFuture(resp);
            }
        };

        agentLoop = new AgentLoop(messageBus, mockProvider, registry, sessionManager, config);
        agentLoop.start();
    }

    /**
     * 轮询 subscriberQueue 直到收到 _stream_end 或超时.
     *
     * @return 收集到的所有匹配的 OutboundMessage（含 deltas + stream_end）
     */
    private List<OutboundMessage> collectStreamEvents(BlockingQueue<OutboundMessage> subQueue,
                                                       String sessionId, String requestId,
                                                       long timeoutMs) throws InterruptedException {
        List<OutboundMessage> events = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            OutboundMessage msg = subQueue.poll(200, TimeUnit.MILLISECONDS);
            if (msg == null) continue;
            // 过滤：只保留匹配 sessionId+requestId 的消息
            if (!sessionId.equals(msg.getSessionId())) continue;
            if (!requestId.equals(msg.getRequestId())) continue;
            events.add(msg);
            if (msg.isStreamEnd()) break;
        }
        return events;
    }

    @Test
    @DisplayName("连续3轮流式对话 — 每轮订阅→发消息→收流→取消订阅")
    void testThreeConsecutiveStreamingRounds() throws Exception {
        String sessionId = "test-chat-" + System.currentTimeMillis();

        for (int round = 1; round <= 3; round++) {
            String requestId = "req-" + round;
            System.out.println("\n=== Round " + round + " (requestId=" + requestId + ") ===");

            // ① 订阅 outbound 扇出队列
            BlockingQueue<OutboundMessage> subQueue = messageBus.subscribeToOutbound();

            // ② 发送消息到 AgentLoop
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("requestId", requestId);
            metadata.put("streamMode", true);

            InboundMessage message = InboundMessage.builder()
                    .sessionId(sessionId)
                    .senderId(sessionId)
                    .content("Test message " + round)
                    .channel("api")
                    .metadata(metadata)
                    .build();
            messageBus.publishInbound(message);

            // ③ 收集流式事件
            List<OutboundMessage> events = collectStreamEvents(subQueue, sessionId, requestId, 10_000);
            System.out.println("  Events received: " + events.size());
            events.forEach(e -> System.out.println("    delta=" + e.isStreamDelta()
                    + " end=" + e.isStreamEnd() + " content="
                    + (e.getContent() != null ? e.getContent() : "null")));

            // ④ 验证
            assertFalse(events.isEmpty(),
                    "Round " + round + ": should have stream events");
            assertTrue(events.stream().anyMatch(OutboundMessage::isStreamDelta),
                    "Round " + round + ": should have stream deltas");
            assertTrue(events.stream().anyMatch(OutboundMessage::isStreamEnd),
                    "Round " + round + ": should have stream end event");

            // ⑤ 取消订阅
            messageBus.unsubscribeFromOutbound(subQueue);
        }
    }

    @Test
    @DisplayName("双订阅者并发 — SSE+WS 同时订阅，各自收到完整流")
    void testTwoSubscribersReceiveSameStream() throws Exception {
        String sessionId = "dual-sub-" + System.currentTimeMillis();
        String requestId = "dual-req-1";

        // 模拟 SSE 和 WS 两个订阅者
        BlockingQueue<OutboundMessage> sseQueue = messageBus.subscribeToOutbound();
        BlockingQueue<OutboundMessage> wsQueue = messageBus.subscribeToOutbound();

        // 发送消息
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("requestId", requestId);
        metadata.put("streamMode", true);
        messageBus.publishInbound(InboundMessage.builder()
                .sessionId(sessionId).senderId(sessionId).content("dual test")
                .channel("api").metadata(metadata).build());

        // 各自收集
        List<OutboundMessage> sseEvents = collectStreamEvents(sseQueue, sessionId, requestId, 10_000);
        List<OutboundMessage> wsEvents = collectStreamEvents(wsQueue, sessionId, requestId, 10_000);

        // 验证：两个订阅者都收到了完整的数据
        assertFalse(sseEvents.isEmpty(), "SSE subscriber should receive events");
        assertFalse(wsEvents.isEmpty(), "WS subscriber should receive events");
        assertEquals(sseEvents.size(), wsEvents.size(),
                "Both subscribers should receive same number of events");

        messageBus.unsubscribeFromOutbound(sseQueue);
        messageBus.unsubscribeFromOutbound(wsQueue);
    }

    @Test
    @DisplayName("requestId 精确匹配 — 验证多轮不会互相干扰")
    void testRequestIdMatching() throws Exception {
        String sessionId = "match-test-" + System.currentTimeMillis();

        // 订阅者持续监听
        BlockingQueue<OutboundMessage> subQueue = messageBus.subscribeToOutbound();

        // 连续发3轮，用不同 requestId
        for (int round = 1; round <= 3; round++) {
            String requestId = "match-req-" + round;
            System.out.println("\n=== Match test Round " + round + " ===");

            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("requestId", requestId);
            metadata.put("streamMode", true);

            messageBus.publishInbound(InboundMessage.builder()
                    .sessionId(sessionId).senderId(sessionId).content("test " + round)
                    .channel("api").metadata(metadata).build());

            List<OutboundMessage> events = collectStreamEvents(subQueue, sessionId, requestId, 10_000);
            System.out.println("  Events: " + events.size());
            events.forEach(e -> System.out.println("    rid=" + e.getRequestId()
                    + " delta=" + e.isStreamDelta() + " end=" + e.isStreamEnd()));

            // 验证每条消息的 requestId 都匹配
            assertFalse(events.isEmpty(), "Round " + round + ": should have events");
            for (OutboundMessage ev : events) {
                assertEquals(requestId, ev.getRequestId(),
                        "Round " + round + ": every event should match requestId");
            }
        }

        messageBus.unsubscribeFromOutbound(subQueue);
    }

    @Test
    @DisplayName("unsubscribe 后异步清理 — 下一轮订阅不受影响")
    void testSubscriptionCleanupBetweenRounds() throws Exception {
        String sessionId = "cleanup-test-" + System.currentTimeMillis();

        // Round 1: 订阅 → 发消息 → 收流 → 取消订阅
        BlockingQueue<OutboundMessage> subQueue1 = messageBus.subscribeToOutbound();
        assertEquals(1, messageBus.getSubscriberCount());

        Map<String, Object> meta1 = new java.util.HashMap<>();
        meta1.put("requestId", "clean-1");
        meta1.put("streamMode", true);
        messageBus.publishInbound(InboundMessage.builder()
                .sessionId(sessionId).senderId(sessionId).content("r1")
                .channel("api").metadata(meta1).build());

        List<OutboundMessage> events1 = collectStreamEvents(subQueue1, sessionId, "clean-1", 10_000);
        assertFalse(events1.isEmpty(), "Round 1 should receive events");

        messageBus.unsubscribeFromOutbound(subQueue1);
        assertEquals(0, messageBus.getSubscriberCount(), "No subscribers after unsub");

        // Round 2: 重新订阅 → 发消息 → 应正常工作
        BlockingQueue<OutboundMessage> subQueue2 = messageBus.subscribeToOutbound();
        assertEquals(1, messageBus.getSubscriberCount());

        Map<String, Object> meta2 = new java.util.HashMap<>();
        meta2.put("requestId", "clean-2");
        meta2.put("streamMode", true);
        messageBus.publishInbound(InboundMessage.builder()
                .sessionId(sessionId).senderId(sessionId).content("r2")
                .channel("api").metadata(meta2).build());

        List<OutboundMessage> events2 = collectStreamEvents(subQueue2, sessionId, "clean-2", 10_000);
        assertFalse(events2.isEmpty(), "Round 2 should receive events after cleanup");
        assertTrue(events2.stream().anyMatch(OutboundMessage::isStreamEnd),
                "Round 2 should complete normally");

        messageBus.unsubscribeFromOutbound(subQueue2);
    }
}
