package com.nanobot.controller;

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
 * 
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
     * 
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
            
            // 等待 LLM 响应
            logger.info("Waiting for response for session: {}, requestId: {}", sessionId, requestId);
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
     * 
     * 通过 Server-Sent Events 实时推送 LLM 响应
     */
    @RequestMapping(value = "/chat/stream", 
                   produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        logger.info("Received stream chat request: chatId={}, streamMode={}, useSearch={}", 
                   request.getChatId(), request.isStreamMode(), request.isUseSearch());
        
        // 创建 SSE emitter
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        
        // 生成 requestId 和 sessionId（使用 chatId）
        String requestId = UUID.randomUUID().toString();
        String sessionId = request.getChatId() != null ? request.getChatId() : UUID.randomUUID().toString();
        
        logger.info("Stream chat requestId={}, sessionId={}", requestId, sessionId);
        
        // 设置流式回调
        AgentLoop agentLoop = NanobotRunner.getAgentLoop();
        AgentLoop.StreamResponseCallback callback = new AgentLoop.StreamResponseCallback() {
            @Override
            public void onStreamData(String sId, String reqId, String content) {
                logger.debug("Stream callback onStreamData: sId={}, reqId={}, content_length={}", 
                           sId, reqId, content != null ? content.length() : 0);
                if (sessionId.equals(sId) && requestId.equals(reqId)) {
                    try {
                        emitter.send("data: " + content + "\n\n");
                        logger.debug("SSE data sent successfully: \"{}\"", content);
                    } catch (IOException e) {
                        logger.warn("Failed to send SSE data", e);
                        emitter.completeWithError(e);
                    }
                } else {
                    logger.debug("Session mismatch: expected sessionId={}, got {}; expected requestId={}, got {}", 
                               sessionId, sId, requestId, reqId);
                }
            }
            
            @Override
            public void onStreamComplete(String sId, String reqId) {
                logger.debug("Stream callback onStreamComplete: sId={}, reqId={}", sId, reqId);
                if (sessionId.equals(sId) && requestId.equals(reqId)) {
                    try {
                        emitter.send("data: [DONE]\n\n");
                        emitter.complete();
                        logger.info("SSE stream completed");
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }
            }
        };
        
        agentLoop.setStreamResponseCallback(callback);
        logger.info("Stream callback set for sessionId={}, requestId={}", sessionId, requestId);
        
        // 构建元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("requestId", requestId);
        metadata.put("streamMode", request.isStreamMode());
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
        
        // 超时处理
        emitter.onTimeout(() -> {
            logger.warn("SSE timeout for request: {}", requestId);
        });
        
        emitter.onCompletion(() -> {
            logger.debug("SSE completed for request: {}", requestId);
        });
        
        emitter.onError(e -> {
            logger.warn("SSE error for request: {}", requestId, e);
        });
        
        return emitter;
    }
    
    /**
     * 健康检查接口
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("service", "nanobot-java");
        result.put("timestamp", System.currentTimeMillis());
        
        // 检查核心组件状态
        boolean componentsReady = 
            NanobotRunner.getMessageBus() != null &&
            NanobotRunner.getAgentLoop() != null;
        
        result.put("componentsReady", componentsReady);
        
        return ResponseEntity.ok(result);
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
