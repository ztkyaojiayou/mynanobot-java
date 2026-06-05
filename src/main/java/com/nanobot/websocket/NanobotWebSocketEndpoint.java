package com.nanobot.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.NanobotRunner;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.InboundMessage;
import com.nanobot.controller.ChatController;
import com.nanobot.core.AgentLoop;
import jakarta.annotation.PostConstruct;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 标准 WebSocket 端点
 * 
 * 使用 Jakarta WebSocket API (@ServerEndpoint) 实现
 * 
 * 连接地址: ws://host:port/ws
 * 
 * 消息格式 (JSON):
 * {
 *   "type": "chat",
 *   "sessionId": "xxx",
 *   "content": "用户消息",
 *   "channel": "websocket"
 * }
 */
@ServerEndpoint("/ws")
@Component
public class NanobotWebSocketEndpoint {
    
    private static final Logger logger = LoggerFactory.getLogger(NanobotWebSocketEndpoint.class);
    
    // 存储所有活跃连接
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    
    // 连接计数
    private static final AtomicInteger connectionCount = new AtomicInteger(0);
    
    // Spring Bean将通过setter注入（因为@ServerEndpoint不由Spring管理）
    private static MessageBus messageBus;
    private static AgentLoop agentLoop;
    private static ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    public void setMessageBus(MessageBus messageBus) {
        NanobotWebSocketEndpoint.messageBus = messageBus;
    }
    
    @Autowired
    public void setAgentLoop(AgentLoop agentLoop) {
        NanobotWebSocketEndpoint.agentLoop = agentLoop;
        // 设置流式回调
        initStreamCallback();
    }
    
    @PostConstruct
    public void init() {
        logger.info("NanobotWebSocketEndpoint initialized");
    }
    
    /**
     * 初始化流式响应回调
     */
    private static void initStreamCallback() {
        if (agentLoop != null) {
            agentLoop.setStreamResponseCallback(new AgentLoop.StreamResponseCallback() {
                @Override
                public void onStreamData(String sessionId, String requestId, String content) {
                    Session session = SESSIONS.get(sessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            // 发送 JSON 格式的消息
                            Map<String, Object> msg = new ConcurrentHashMap<>();
                            msg.put("type", "stream");
                            msg.put("requestId", requestId);
                            msg.put("content", content);
                            session.getBasicRemote().sendText(objectMapper.writeValueAsString(msg));
                        } catch (IOException e) {
                            logger.warn("Failed to send WebSocket message to session: {}", sessionId);
                        }
                    }
                }
                
                @Override
                public void onStreamComplete(String sessionId, String requestId) {
                    Session session = SESSIONS.get(sessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            Map<String, Object> msg = new ConcurrentHashMap<>();
                            msg.put("type", "done");
                            msg.put("requestId", requestId);
                            session.getBasicRemote().sendText(objectMapper.writeValueAsString(msg));
                        } catch (IOException e) {
                            logger.warn("Failed to send WebSocket completion to session: {}", sessionId);
                        }
                    }
                }
            });
        }
    }
    
    // ==================== WebSocket 生命周期方法 ====================
    
    @OnOpen
    public void onOpen(Session session) {
        String sessionId = session.getId();
        SESSIONS.put(sessionId, session);
        
        // 配置会话
        session.setMaxTextMessageBufferSize(1024 * 1024); // 1MB
        session.setMaxIdleTimeout(300_000); // 5分钟超时
        
        int count = connectionCount.incrementAndGet();
        logger.info("WebSocket connected: sessionId={}, total={}", sessionId, count);
        
        // 发送连接成功消息
        try {
            Map<String, Object> msg = Map.of(
                "type", "connected",
                "sessionId", sessionId,
                "message", "WebSocket connection established"
            );
            session.getBasicRemote().sendText(objectMapper.writeValueAsString(msg));
        } catch (IOException e) {
            logger.warn("Failed to send connected message", e);
        }
    }
    
    @OnMessage
    public void onMessage(String message, Session session) {
        logger.debug("Received WebSocket message: sessionId={}, length={}", 
                     session.getId(), message.length());
        
        try {
            JsonNode json = objectMapper.readTree(message);
            String type = json.has("type") ? json.get("type").asText() : "unknown";
            
            switch (type) {
                case "chat" -> handleChatMessage(json, session);
                case "ping" -> handlePing(session);
                default -> sendError(session, "Unknown message type: " + type);
            }
            
        } catch (Exception e) {
            logger.error("Failed to process WebSocket message", e);
            sendError(session, "Failed to process message: " + e.getMessage());
        }
    }
    
    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        String sessionId = session.getId();
        SESSIONS.remove(sessionId);
        
        int count = connectionCount.decrementAndGet();
        logger.info("WebSocket closed: sessionId={}, reason={}, remaining={}", 
                   sessionId, closeReason.getReasonPhrase(), count);
    }
    
    @OnError
    public void onError(Session session, Throwable throwable) {
        String sessionId = session.getId();
        SESSIONS.remove(sessionId);
        
        logger.error("WebSocket error: sessionId={}", sessionId, throwable);
    }
    
    // ==================== 消息处理 ====================
    
    /**
     * 处理聊天消息
     */
    private void handleChatMessage(JsonNode json, Session session) {
        String sessionId = session.getId();
        String requestId = json.has("requestId") ? json.get("requestId").asText() : java.util.UUID.randomUUID().toString();
        String content = json.has("content") ? json.get("content").asText() : "";
        String channel = json.has("channel") ? json.get("channel").asText() : "websocket";
        
        if (content.isBlank()) {
            sendError(session, "Content cannot be empty");
            return;
        }
        
        logger.info("Processing chat message: sessionId={}, requestId={}, contentLength={}", 
                   sessionId, requestId, content.length());
        
        try {
            // 构建入站消息
            InboundMessage message = InboundMessage.builder()
                .chatId(sessionId)
                .content(content)
                .channel(channel)
                .build();
            message.getMetadata().put("requestId", requestId);
            message.getMetadata().put("streamMode", true); // WebSocket 默认流式
            message.getMetadata().put("connectionId", sessionId);
            
            // 发送到 MessageBus
            try {
                messageBus.publishInbound(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendError(session, "Failed to publish message: " + e.getMessage());
                return;
            }
            
            // 发送确认消息
            Map<String, Object> ack = Map.of(
                "type", "ack",
                "requestId", requestId,
                "message", "Message received, processing..."
            );
            session.getBasicRemote().sendText(objectMapper.writeValueAsString(ack));
            
        } catch (Exception e) {
            logger.error("Failed to process chat message", e);
            sendError(session, "Failed to process message: " + e.getMessage());
        }
    }
    
    /**
     * 处理 Ping 消息
     */
    private void handlePing(Session session) {
        try {
            Map<String, Object> pong = Map.of(
                "type", "pong",
                "timestamp", System.currentTimeMillis()
            );
            session.getBasicRemote().sendText(objectMapper.writeValueAsString(pong));
        } catch (IOException e) {
            logger.warn("Failed to send pong", e);
        }
    }
    
    /**
     * 发送错误消息
     */
    private void sendError(Session session, String error) {
        try {
            Map<String, Object> msg = Map.of(
                "type", "error",
                "error", error
            );
            session.getBasicRemote().sendText(objectMapper.writeValueAsString(msg));
        } catch (IOException e) {
            logger.warn("Failed to send error message", e);
        }
    }
    
    // ==================== 静态工具方法 ====================
    
    /**
     * 向指定会话发送消息
     */
    public static void send(String sessionId, String message) {
        Session session = SESSIONS.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
            } catch (IOException e) {
                logger.warn("Failed to send message to session: {}", sessionId, e);
            }
        }
    }
    
    /**
     * 向指定会话发送 JSON 消息
     */
    public static void sendJson(String sessionId, Map<String, Object> data) {
        try {
            send(sessionId, objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            logger.warn("Failed to send JSON to session: {}", sessionId, e);
        }
    }
    
    /**
     * 广播消息给所有连接
     */
    public static void broadcast(String message) {
        SESSIONS.values().forEach(session -> {
            if (session.isOpen()) {
                try {
                    session.getBasicRemote().sendText(message);
                } catch (IOException e) {
                    logger.warn("Failed to broadcast to session: {}", session.getId());
                }
            }
        });
    }
    
    /**
     * 获取当前连接数
     */
    public static int getConnectionCount() {
        return connectionCount.get();
    }
    
    /**
     * 获取活跃会话 ID 列表
     */
    public static java.util.Set<String> getActiveSessionIds() {
        return new java.util.HashSet<>(SESSIONS.keySet());
    }
}
