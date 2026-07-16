package com.nanobot.v2.controller;

import com.nanobot.NanobotRunner;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.core.AgentLoop;
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

/**
 * Chat REST API Controller
 * <p>
 * 提供 REST API 接口：
 * - POST /api/chat - 同步聊天（简化为仅返回确认）
 * - POST /api/chat/stream - 流式聊天（SSE）
 * - GET /api/health - 健康检查
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
        logger.info("Received sync chat request: chatId={}", request.getChatId());

        try {
            // 使用 chatId 作为会话标识
            String sessionId = request.getChatId();
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
                    .chatId(sessionId)
                    .senderId(sessionId) // API 通道使用 sessionId 作为 senderId
                    .content(request.getContent())
                    .channel(request.getChannel() != null ? request.getChannel() : "api")
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
     * 流式聊天接口（SSE）
     * <p>
     * 通过 Server-Sent Events 实时推送 LLM 响应
     */
    @RequestMapping(value = "/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        // ═══════ 诊断日志：打印每次请求的消息内容（不含历史）═══════
        logger.info("═══════ SSE REQUEST ═══════");
        logger.info("  chatId     : {}", request.getChatId());
        logger.info("  content    : '{}'", request.getContent() != null ? request.getContent() : "null");
        logger.info("  channel    : {}", request.getChannel());
        logger.info("  streamMode : {}", request.isStreamMode());
        logger.info("  useSearch  : {}", request.isUseSearch());
        logger.info("══════════════════════════════");

        // 创建 SSE emitter
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 生成 requestId 和 sessionId（使用 chatId）
        String requestId = UUID.randomUUID().toString();
        String sessionId = request.getChatId() != null ? request.getChatId() : UUID.randomUUID().toString();

        logger.info("Stream chat requestId={}, sessionId={}", requestId, sessionId);

        // 设置流式回调
        AgentLoop agentLoop = NanobotRunner.getAgentLoop();
        if (agentLoop == null) {
            logger.error("AgentLoop is NULL - cannot stream!");
            emitter.completeWithError(new IllegalStateException("AgentLoop not available"));
            return emitter;
        }
        final String cbSessionId = sessionId;
        final String cbRequestId = requestId;
        logger.info("Creating stream callback: sessionId={}, requestId={}", cbSessionId, cbRequestId);

        AgentLoop.StreamResponseCallback callback = new AgentLoop.StreamResponseCallback() {
            private volatile boolean completed = false;
            private int dataCount = 0;

            @Override
            public void onStreamData(String sId, String reqId, String content) {
                dataCount++;
                boolean match = cbSessionId.equals(sId) && cbRequestId.equals(reqId);
                logger.info("SSE onStreamData[#{}]: cbSid={}, cbRid={}, gotSid={}, gotRid={}, match={}, len={}",
                        dataCount, cbSessionId, cbRequestId, sId, reqId, match,
                        content != null ? content.length() : 0);
                if (match && !completed) {
                    try {
                        emitter.send(SseEmitter.event().data(content != null ? content : ""));
                    } catch (Exception e) {
                        // IOException + IllegalStateException(emitter已关闭/超时)
                        logger.warn("Failed to send SSE data: {} ({})", e.getMessage(), e.getClass().getSimpleName());
                        completed = true;
                        try {
                            emitter.completeWithError(e);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            @Override
            public void onStreamComplete(String sId, String reqId) {
                boolean match = cbSessionId.equals(sId) && cbRequestId.equals(reqId);
                logger.info("SSE onStreamComplete: cbSid={}, cbRid={}, gotSid={}, gotRid={}, match={}, dataCount={}",
                        cbSessionId, cbRequestId, sId, reqId, match, dataCount);
                if (match && !completed) {
                    completed = true;
                    try {
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                        logger.info("SSE stream completed ({} data events)", dataCount);
                    } catch (Exception e) {
                        logger.warn("SSE complete failed: {}", e.getMessage());
                        try {
                            emitter.completeWithError(e);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        };

        agentLoop.addStreamResponseCallback(callback);
        logger.info("Stream callback added for sessionId={}, requestId={}, totalCallbacks={}",
                cbSessionId, cbRequestId, agentLoop.getStreamCallbackCount());

        // 构建元数据（/chat/stream 端点始终启用流式模式）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("requestId", requestId);
        metadata.put("streamMode", true);
        metadata.put("useSearch", request.isUseSearch());

        // 构建入站消息（senderId 使用 sessionId，因为 API 通道没有独立的发送者 ID）
        InboundMessage message = InboundMessage.builder()
                .chatId(sessionId)
                .senderId(sessionId) // API 通道使用 sessionId 作为 senderId
                .content(request.getContent())
                .channel(request.getChannel() != null ? request.getChannel() : "api")
                .metadata(metadata)
                .build();

        // 发送消息到 MessageBus
        MessageBus messageBus = NanobotRunner.getMessageBus();
        try {
            messageBus.publishInbound(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(e);
            return emitter;
        }

        // 超时/完成/错误时清理回调（防止僵尸callback堆积）
        emitter.onTimeout(() -> {
            logger.warn("SSE timeout for request: {} (dataCount={})", requestId, 0);
            agentLoop.removeStreamResponseCallback(callback);
        });

        emitter.onCompletion(() -> {
            logger.info("SSE completed for request: {}", requestId);
            agentLoop.removeStreamResponseCallback(callback);
        });

        emitter.onError(e -> {
            logger.warn("SSE error for request: {} - {}", requestId, e.getMessage());
            agentLoop.removeStreamResponseCallback(callback);
        });

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
        private String chatId;
        private String content;
        private String channel;
        private boolean useSearch;
        private boolean streamMode;
        private String requestId;
    }
}
