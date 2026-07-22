package com.nanobot.v2.controller;

import com.nanobot.NanobotRunner;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.OutboundMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chat REST API Controller — 用户与 Agent 之间的 HTTP 桥梁.
 *
 * <h2>两种通信模式</h2>
 * <b>同步模式 (POST /api/chat)</b>：
 * 发消息到 MessageBus → 阻塞等待 sessionResponses Map → 返回 JSON.
 *
 * <b>流式模式 (POST /api/chat/stream) — SSE</b>：
 * 订阅 MessageBus Outbound Queue → 启动 daemon 消费线程 poll subscriberQueue →
 * 按 sessionId+requestId 过滤 → SSE emitter 推送给前端.
 *
 * <h2>发布-订阅架构</h2>
 * <pre>
 *   RunState → outboundQueue → Dispatcher 扇出 → subscriberQueue (本SSE请求专属)
 *                                                        │
 *                                                        ▼
 *                                              consumer thread poll + filter
 *                                                        │
 *                                                        ▼
 *                                                  emitter.send(token)
 * </pre>
 * 每个 SSE 连接有独立的 subscriberQueue + consumerThread，
 * 连接结束时 unsubscribe + 终止线程.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    private static final long SSE_TIMEOUT = 300_000L; // 5分钟

    /**
     * 同步聊天接口
     * <p>
     * 注意：此接口目前仅用于 WebSocket 流式的预检
     * 实际响应通过 /api/chat/stream 获取
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        logger.info("Received sync chat request: sessionId={}", request.getSessionId());

        try {
            // 使用 sessionId 作为会话标识
            String sessionId = request.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = UUID.randomUUID().toString();
            }

            // 生成 requestId
            String requestId = UUID.randomUUID().toString();

            // 构建元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("requestId", requestId);
            metadata.put("streamMode", request.isStreamMode());
            metadata.put("useSearch", request.isUseSearch());

            // 构建入站消息（senderId 使用 sessionId，因为 API 通道没有独立的发送者 ID）
            //相当于发消息到mq，而不是同步处理！！！这是核心的性能架构设计
            InboundMessage message = InboundMessage.builder()
                    .sessionId(sessionId)
                    .senderId(sessionId)
                    .content(request.getContent())
                    .channel(request.getChannel() != null ? request.getChannel() : "api")
                    .sessionKeyOverride(sessionId) // 用前端传的 sessionId 作为 key，不加 channel 前缀
                    .metadata(metadata)
                    .build();

            // 发送消息到 MessageBus
            MessageBus messageBus = NanobotRunner.getMessageBus();
            messageBus.publishInbound(message);

            // 等待 LLM 响应(这里还是同步阻塞的）
            logger.info("Waiting for response for session: {}, requestId: {}", sessionId, requestId);
            // 去消息出站总线队列中获取（为什么不是直接同步等待而需要消息总线？
            // 这是因为要使用消息总线来适配多种上游交互通道！）
            // 核心就是一个阻塞队列，最多等120s
            OutboundMessage response = messageBus.waitForSessionResponse(sessionId, requestId, 120, java.util.concurrent.TimeUnit.SECONDS);

            Map<String, Object> result = new HashMap<>();
            if (response != null && response.getContent() != null) {
                result.put("status", "success");
                result.put("sessionId", sessionId);
                result.put("requestId", requestId);
                result.put("content", response.getContent());
                logger.info("Response received for session: {}, content length: {}", sessionId, response.getContent().length());
            } else {
                result.put("status", "timeout");
                result.put("sessionId", sessionId);
                result.put("requestId", requestId);
                result.put("content", "请求超时，请稍后重试。");
                logger.warn("Timeout waiting for response for session: {}", sessionId);
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Chat request failed", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            error.put("content", "请求失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 流式聊天接口 (SSE).
     *
     * 三步走：
     * <ol>
     *   <li>订阅 MessageBus 的 outbound 扇出队列 → 启动 daemon 消费者线程 poll 自己的 subscriberQueue</li>
     *   <li>发送 InboundMessage 到 MessageBus（AgentLoop 异步消费）</li>
     *   <li>消费者线程按 sessionId+requestId 过滤，匹配的通过 SseEmitter 推送给前端</li>
     * </ol>
     *
     * 连接结束（超时/完成/报错）时 unsubscribe + 终止消费者线程，防止泄漏.
     */
    @RequestMapping(value = "/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        logger.info("═══════ SSE REQUEST ═══════");
        logger.info("  sessionId     : {}", request.getSessionId());
        logger.info("  content    : '{}'", request.getContent() != null ? request.getContent() : "null");
        logger.info("  channel    : {}", request.getChannel());
        logger.info("  streamMode : {}", request.isStreamMode());
        logger.info("  useSearch  : {}", request.isUseSearch());
        logger.info("══════════════════════════════");

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        String requestId = UUID.randomUUID().toString();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();

        MessageBus messageBus = NanobotRunner.getMessageBus();
        if (messageBus == null) {
            emitter.completeWithError(new IllegalStateException("MessageBus not available"));
            return emitter;
        }

        // ── 订阅 outbound 扇出队列，启动独立消费者线程 ──
        BlockingQueue<OutboundMessage> subscriberQueue = messageBus.subscribeToOutbound();
        AtomicBoolean consumerRunning = new AtomicBoolean(true);
        final int[] dataCount = {0};

        Thread consumerThread = new Thread(() -> {
            try {
                while (consumerRunning.get()) {
                    OutboundMessage msg = subscriberQueue.poll(1, TimeUnit.SECONDS);
                    if (msg == null) continue;
                    // 过滤：只处理匹配 sessionId+requestId 的消息
                    if (!sessionId.equals(msg.getSessionId())) continue;
                    if (!requestId.equals(msg.getRequestId())) continue;

                    try {
                        if (msg.isSessionCleared()) {
                            // 通知前端清空消息列表 + 显示提示
                            emitter.send(SseEmitter.event().name("clear").data("会话已清除。"));
                            if (msg.getContent() != null) {
                                emitter.send(SseEmitter.event().data(msg.getContent()));
                            }
                            emitter.send(SseEmitter.event().data("[DONE]"));
                            emitter.complete();
                            break;
                        } else if (msg.isStreamDelta()) {
                            dataCount[0]++;
                            emitter.send(SseEmitter.event().data(msg.getContent() != null ? msg.getContent() : ""));
                        } else if (msg.isStreamEnd()) {
                            emitter.send(SseEmitter.event().data("[DONE]"));
                            emitter.complete();
                            logger.info("SSE stream completed ({} data events)", dataCount[0]);
                            break;
                        }
                    } catch (java.io.IOException e) {
                        logger.warn("SSE send failed: {}", e.getMessage());
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("SSE consumer error: {}", e.getMessage(), e);
            }
        }, "SSE-consumer-" + requestId);
        consumerThread.setDaemon(true);
        consumerThread.start();

        // ── 清理：连接结束时 unsubscribe + 终止消费者线程 ──
        Runnable cleanup = () -> {
            consumerRunning.set(false);
            consumerThread.interrupt();
            messageBus.unsubscribeFromOutbound(subscriberQueue);
        };
        emitter.onTimeout(cleanup);
        emitter.onCompletion(cleanup);
        emitter.onError(e -> cleanup.run());

        // 构建元数据（/chat/stream 端点始终启用流式模式）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("requestId", requestId);
        metadata.put("streamMode", true);
        metadata.put("useSearch", request.isUseSearch());

        // 构建入站消息（senderId 使用 sessionId，因为 API 通道没有独立的发送者 ID）
        InboundMessage message = InboundMessage.builder()
                .sessionId(sessionId)
                .senderId(sessionId)
                .content(request.getContent())
                .channel(request.getChannel() != null ? request.getChannel() : "api")
                .sessionKeyOverride(sessionId) // 用前端传的 sessionId，不加 channel 前缀
                .metadata(metadata)
                .build();

        // ── 发送消息到 MessageBus ──
        try {
            messageBus.publishInbound(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanup.run();
            emitter.completeWithError(e);
            return emitter;
        }

        return emitter;
    }

    // ==================== 内部类 ====================

    /**
     * 聊天请求 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatRequest {
        private String sessionId;
        private String content;
        private String channel;
        private boolean useSearch;
        private boolean streamMode;
        private String requestId;
    }
}
