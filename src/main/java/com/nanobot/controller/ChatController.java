package com.nanobot.controller;

import com.nanobot.NanobotRunner;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.InboundMessage;
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
        logger.info("Received sync chat request: sessionId={}", request.getSessionId());
        
        try {
            // 生成 sessionId
            String sessionId = request.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = UUID.randomUUID().toString();
            }
            
            // 生成 requestId
            String requestId = UUID.randomUUID().toString();
            
            // 构建入站消息
            InboundMessage message = InboundMessage.builder()
                .chatId(sessionId)
                .content(request.getContent())
                .channel(request.getChannel() != null ? request.getChannel() : "api")
                .build();
            message.getMetadata().put("requestId", requestId);
            message.getMetadata().put("streamMode", false);
            
            // 发送消息到 MessageBus
            MessageBus messageBus = NanobotRunner.getMessageBus();
            messageBus.publishInbound(message);
            
            // 由于没有 waitForResponse 方法，
            // 同步接口直接返回确认，实际响应通过 WebSocket 获取
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "accepted");
            result.put("sessionId", sessionId);
            result.put("requestId", requestId);
            result.put("message", "Message received. Use WebSocket for response.");
            
            return ResponseEntity.accepted().body(result);
            
        } catch (Exception e) {
            logger.error("Chat request failed", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
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
        logger.info("Received stream chat request: sessionId={}", request.getSessionId());
        
        // 创建 SSE emitter
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        
        // 生成 requestId 和 sessionId
        String requestId = UUID.randomUUID().toString();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();
        
        // 设置流式回调
        AgentLoop agentLoop = NanobotRunner.getAgentLoop();
        AgentLoop.StreamResponseCallback callback = new AgentLoop.StreamResponseCallback() {
            @Override
            public void onStreamData(String sId, String reqId, String content) {
                if (sessionId.equals(sId) && requestId.equals(reqId)) {
                    try {
                        emitter.send(content);
                    } catch (IOException e) {
                        logger.warn("Failed to send SSE data", e);
                        emitter.completeWithError(e);
                    }
                }
            }
            
            @Override
            public void onStreamComplete(String sId, String reqId) {
                if (sessionId.equals(sId) && requestId.equals(reqId)) {
                    try {
                        emitter.send(SseEmitter.event()
                            .name("done")
                            .data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }
            }
        };
        
        agentLoop.setStreamResponseCallback(callback);
        
        // 构建入站消息
        InboundMessage message = InboundMessage.builder()
            .chatId(sessionId)
            .content(request.getContent())
            .channel(request.getChannel() != null ? request.getChannel() : "api")
            .build();
        message.getMetadata().put("requestId", requestId);
        message.getMetadata().put("streamMode", true);
        
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
        private String sessionId;
        private String content;
        private String channel;
        private boolean useSearch;
        private boolean streamMode;
        private String requestId;
    }
}
