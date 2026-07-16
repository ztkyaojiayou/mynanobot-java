package com.nanobot.core;

import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.config.Config;
import com.nanobot.config.ConfigLoader;
import com.nanobot.core.AgentLoop.StreamResponseCallback;
import com.nanobot.providers.LLMProvider;
import com.nanobot.session.SessionManager;
import com.nanobot.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 StreamResponseCallback 在多轮流式对话中的生命周期
 *
 * 复现场景：模拟 SSE 流式对话连续3轮，验证每轮都能正常收到数据
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

        // 使用一个简单的 mock LLM provider
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

    @Test
    @DisplayName("连续3轮SSE流式对话 — 每轮都应收到数据和完成信号")
    void testThreeConsecutiveStreamingRounds() throws Exception {
        String sessionId = "test-chat-" + System.currentTimeMillis();

        // 模拟 WebSocket 回调（始终存在）
        List<String> wsEvents = new ArrayList<>();
        StreamResponseCallback wsCallback = new StreamResponseCallback() {
            @Override
            public void onStreamData(String sid, String rid, String content) {
                wsEvents.add("ws:data:" + rid + ":" + (content != null ? content.length() : 0));
            }
            @Override
            public void onStreamComplete(String sid, String rid) {
                wsEvents.add("ws:complete:" + rid);
            }
        };
        agentLoop.addStreamResponseCallback(wsCallback);

        // 执行3轮
        for (int round = 1; round <= 3; round++) {
            String requestId = "req-" + round;
            System.out.println("\n=== Round " + round + " (requestId=" + requestId + ") ===");

            // 本轮事件收集
            List<String> sseDataEvents = new ArrayList<>();
            CountDownLatch completeLatch = new CountDownLatch(1);

            StreamResponseCallback sseCallback = new StreamResponseCallback() {
                @Override
                public void onStreamData(String sid, String rid, String content) {
                    sseDataEvents.add("data:" + content);
                    System.out.println("  SSE data: sid=" + sid + " rid=" + rid + " content=" + content);
                }
                @Override
                public void onStreamComplete(String sid, String rid) {
                    sseDataEvents.add("complete");
                    System.out.println("  SSE complete: sid=" + sid + " rid=" + rid);
                    completeLatch.countDown();
                }
            };

            agentLoop.addStreamResponseCallback(sseCallback);
            assertEquals(2, agentLoop.getStreamCallbackCount(), // wsCallback + sseCallback
                    "Round " + round + ": should have 2 callbacks");

            // 模拟 ChatController 发布消息
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

            // 等待本轮完成
            boolean completed = completeLatch.await(10, TimeUnit.SECONDS);
            assertTrue(completed, "Round " + round + ": should complete within timeout");

            // 验证收到了数据
            assertFalse(sseDataEvents.isEmpty(),
                    "Round " + round + ": should have SSE data events");
            assertTrue(sseDataEvents.contains("complete"),
                    "Round " + round + ": should have complete event, got: " + sseDataEvents);

            // 清理本轮回调
            agentLoop.removeStreamResponseCallback(sseCallback);
            assertEquals(1, agentLoop.getStreamCallbackCount(), // only wsCallback left
                    "Round " + round + ": should have 1 callback after cleanup");
        }

        // 验证 WebSocket 回调每轮都被调用
        System.out.println("\n=== WebSocket callback events: " + wsEvents.size() + " ===");
        wsEvents.forEach(e -> System.out.println("  " + e));
        assertTrue(wsEvents.size() >= 6,
                "WebSocket callback should receive at least 6 events (3 data + 3 complete for 3 rounds), got: " + wsEvents.size());
    }

    @Test
    @DisplayName("callback sessionId/requestId 匹配验证")
    void testCallbackIdMatching() throws Exception {
        String sessionId = "match-test";

        for (int round = 1; round <= 3; round++) {
            String requestId = "match-req-" + round;
            System.out.println("\n=== Match test Round " + round + " ===");

            CountDownLatch latch = new CountDownLatch(1);
            boolean[] matched = {false};

            StreamResponseCallback cb = new StreamResponseCallback() {
                @Override
                public void onStreamData(String sid, String rid, String content) {
                    if (sessionId.equals(sid) && requestId.equals(rid)) {
                        matched[0] = true;
                    } else {
                        System.out.println("  MISMATCH: expected(" + sessionId + "," + requestId
                                + ") got(" + sid + "," + rid + ")");
                    }
                }
                @Override
                public void onStreamComplete(String sid, String rid) {
                    System.out.println("  Complete: expected(" + sessionId + "," + requestId
                            + ") got(" + sid + "," + rid + ") match="
                            + (sessionId.equals(sid) && requestId.equals(rid)));
                    latch.countDown();
                }
            };

            agentLoop.addStreamResponseCallback(cb);

            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("requestId", requestId);
            metadata.put("streamMode", true);

            messageBus.publishInbound(InboundMessage.builder()
                    .sessionId(sessionId).senderId(sessionId).content("test")
                    .channel("api").metadata(metadata).build());

            latch.await(10, TimeUnit.SECONDS);
            assertTrue(matched[0], "Round " + round + ": requestId should match");
            agentLoop.removeStreamResponseCallback(cb);
        }
    }

    @Test
    @DisplayName("emitter.complete 后异步清理 callback — 不应影响下一轮")
    void testAsyncCallbackCleanup() throws Exception {
        String sessionId = "cleanup-test";

        // Round 1: 正常流程
        CountDownLatch round1Latch = new CountDownLatch(1);
        StreamResponseCallback cb1 = new StreamResponseCallback() {
            @Override public void onStreamData(String s, String r, String c) {}
            @Override
            public void onStreamComplete(String s, String r) {
                // 模拟 emitter.complete() 异步触发 cleanup
                new Thread(() -> {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    agentLoop.removeStreamResponseCallback(this);
                }).start();
                round1Latch.countDown();
            }
        };

        agentLoop.addStreamResponseCallback(cb1);
        assertEquals(1, agentLoop.getStreamCallbackCount()); // cb1 only, no pre-registered callback

        Map<String, Object> meta1 = new java.util.HashMap<>();
        meta1.put("requestId", "clean-1");
        meta1.put("streamMode", true);
        messageBus.publishInbound(InboundMessage.builder()
                .sessionId(sessionId).senderId(sessionId).content("r1")
                .channel("api").metadata(meta1).build());

        round1Latch.await(10, TimeUnit.SECONDS);
        Thread.sleep(100); // 等待异步清理完成
        assertEquals(0, agentLoop.getStreamCallbackCount(), "cb1 should be cleaned up");

        // Round 2: 验证不受影响
        CountDownLatch round2Latch = new CountDownLatch(1);
        List<String> r2Events = new ArrayList<>();
        StreamResponseCallback cb2 = new StreamResponseCallback() {
            @Override public void onStreamData(String s, String r, String c) { r2Events.add(c); }
            @Override public void onStreamComplete(String s, String r) {
                r2Events.add("complete");
                round2Latch.countDown();
            }
        };

        agentLoop.addStreamResponseCallback(cb2);
        assertEquals(1, agentLoop.getStreamCallbackCount());

        Map<String, Object> meta2 = new java.util.HashMap<>();
        meta2.put("requestId", "clean-2");
        meta2.put("streamMode", true);
        messageBus.publishInbound(InboundMessage.builder()
                .sessionId(sessionId).senderId(sessionId).content("r2")
                .channel("api").metadata(meta2).build());

        round2Latch.await(10, TimeUnit.SECONDS);
        assertFalse(r2Events.isEmpty(), "Round 2 should receive data after async cleanup");
        assertTrue(r2Events.contains("complete"), "Round 2 should complete");

        agentLoop.removeStreamResponseCallback(cb2);
    }
}
