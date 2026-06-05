package com.nanobot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.NanobotRunner;
import com.nanobot.bus.MessageBus;
import com.nanobot.core.AgentLoop;
import com.nanobot.core.TurnContext;
import com.nanobot.session.SessionManager;
import com.nanobot.core.OutboundMessage;
import com.nanobot.core.InboundMessage;
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
import java.util.concurrent.*;

/**
 * Chat REST API Controller
 * 
 * 提供 REST API 接口：
 * - POST /api/chat - 同步聊天
 * - POST /api/chat/stream - 流式聊天（SSE）
 * - GET /api/health - 健康检查
 */
@RestController
@RequestMapping("/api")
public class ChatController {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    private static final long SSE_TIMEOUT = 300_000L; // 5分钟
    private static final long RESPONSE_TIMEOUT = 300_000L; // 5分钟
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 同步聊天接口
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        logger.info("Received chat request: sessionId={}, streamMode={}", 
                   request.getSessionId(), request.isStreamMode());
        
        try {
            // 生成 requestId
            String requestId = request.getRequestId();
            if (requestId == null || requestId.isBlank()) {
                requestId = UUID.randomUUID().toString();
            }
            
            // 生成 sessionId
            String sessionId = request.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = UUID.randomUUID().toString();
            }
            
            // 构建入站消息
            InboundMessage message = InboundMessage.of(
                sessionId,
                request.getContent(),
                request.getChannel() != null ? request.getChannel() : "api"
            );
            message.getMetadata().put("requestId", requestId);
            message.getMetadata().put("streamMode", false); // 同步模式
            
            // 发送消息到 MessageBus
            MessageBus messageBus = NanobotRunner.getMessageBus();
            messageBus.publishInbound(message);
            
            // 等待响应
            String response = waitForResponse(sessionId, requestId, RESPONSE_TIMEOUT);
            
            // 构建响应
            Map<String, Object> result = new HashMap<>();
            result.put("status", "ok");
            result.put("sessionId", sessionId);
            result.put("requestId", requestId);
            result.put("content", response);
            
            return ResponseEntity.ok(result);
            
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
     */
    @RequestMapping(value = "/chat/stream", 
                   produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        logger.info("Received stream chat request: sessionId={}", request.getSessionId());
        
        // 创建 SSE emitter
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        
        // 生成 requestId 和 sessionId
        String requestId = request.getRequestId() != null ? request.getRequestId() : UUID.randomUUID().toString();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();
        
        // 设置流式回调
        AgentLoop agentLoop = NanobotRunner.getAgentLoop();
        StreamResponseCallback callback = new StreamResponseCallback() {
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
            
            @Override
            public void onStreamError(String sId, String reqId, String error) {
                if (sessionId.equals(sId) && requestId.equals(reqId)) {
                    try {
                        emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"error\":\"" + error + "\"}"));
                        emitter.completeWithError(new RuntimeException(error));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }
            }
        };
        
        agentLoop.setStreamResponseCallback(callback);
        
        // 构建入站消息
        InboundMessage message = InboundMessage.of(
            sessionId,
            request.getContent(),
            request.getChannel() != null ? request.getChannel() : "api"
        );
        message.getMetadata().put("requestId", requestId);
        message.getMetadata().put("streamMode", true);
        
        // 发送消息到 MessageBus
        MessageBus messageBus = NanobotRunner.getMessageBus();
        messageBus.publishInbound(message);
        
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
    
    /**
     * 等待响应
     */
    private String waitForResponse(String sessionId, String requestId, long timeoutMs) {
        MessageBus messageBus = NanobotRunner.getMessageBus();
        SessionManager sessionManager = NanobotRunner.getSessionManager();
        
        try {
            // 使用 CompletableFuture 等待响应
            CompletableFuture<String> future = new CompletableFuture<>();
            
            // 设置超时
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            scheduler.schedule(() -> {
                future.completeExceptionally(new TimeoutException("Response timeout"));
            }, timeoutMs, TimeUnit.MILLISECONDS);
            
            // 监听消息总线
            MessageBus.Subscriber subscriber = messageBus.subscribeInbound(message -> {
                // 这个订阅实际上不会被触发，因为我们订阅的是入站消息
            });
            
            // 从会话管理器获取响应
            // 这里简化处理，实际应该等待 OutboundMessage
            String response = sessionManager.waitForResponse(sessionId, requestId, timeoutMs);
            
            scheduler.shutdown();
            messageBus.unsubscribe(subscriber);
            
            return response;
            
        } catch (Exception e) {
            logger.error("Failed to wait for response", e);
            throw new RuntimeException("Failed to get response: " + e.getMessage());
        }
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 聊天请求 DTO
     */
    public static class ChatRequest {
        private String sessionId;
        private String content;
        private String channel;
        private boolean useSearch;
        private boolean streamMode;
        private String requestId;
        
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        
        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }
        
        public boolean isUseSearch() { return useSearch; }
        public void setUseSearch(boolean useSearch) { this.useSearch = useSearch; }
        
        public boolean isStreamMode() { return streamMode; }
        public void setStreamMode(boolean streamMode) { this.streamMode = streamMode; }
        
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
    }
    
    /**
     * 流式响应回调接口
     */
    public interface StreamResponseCallback {
        void onStreamData(String sessionId, String requestId, String content);
        void onStreamComplete(String sessionId, String requestId);
        default void onStreamError(String sessionId, String requestId, String error) {}
    }
}
