package com.nanobot.channels;

import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

/**
 * 多通道接入服务器
 * ====================
 * 
 * 本类实现了 HTTP 和 WebSocket 通道的统一管理，
 * 允许客户端通过多种方式与 Agent 进行交互。
 * 
 * **支持的通道类型**：
 * 1. HTTP REST API - 同步请求/响应模式
 * 2. WebSocket - 异步双向通信
 * 
 * **HTTP API 端点**：
 * - POST /api/chat - 发送消息
 * - GET /api/sessions - 获取会话列表
 * - DELETE /api/sessions/{id} - 删除会话
 * 
 * **WebSocket 端点**：
 * - /ws - WebSocket 连接
 */
public class ChannelServer {
    
    private static final Logger logger = LoggerFactory.getLogger(ChannelServer.class);
    
    private final MessageBus messageBus;
    private final ObjectMapper objectMapper;
    private HttpServer httpServer;
    private final int port;
    private final Map<String, WebSocketConnection> wsConnections = new ConcurrentHashMap<>();
    
    public ChannelServer(MessageBus messageBus, int port) {
        this.messageBus = messageBus;
        this.port = port;
        this.objectMapper = new ObjectMapper();
        logger.info("ChannelServer initialized on port {}", port);
    }
    
    /**
     * 启动服务器
     */
    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        
        // 注册 HTTP 处理器
        httpServer.createContext("/", new RootHandler());
        httpServer.createContext("/api/chat", new ChatHandler());
        httpServer.createContext("/api/sessions", new SessionsHandler());
        httpServer.createContext("/ws", new WebSocketHandler());
        httpServer.createContext("/static/", new StaticHandler());
        
        // 设置执行器
        httpServer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        
        httpServer.start();
        logger.info("ChannelServer started on http://0.0.0.0:{}", port);
    }
    
    /**
     * 停止服务器
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        wsConnections.values().forEach(WebSocketConnection::close);
        wsConnections.clear();
        logger.info("ChannelServer stopped");
    }
    
    /**
     * 处理出站消息
     */
    private void handleOutboundMessage(OutboundMessage message) {
        String channel = message.getChannel();
        
        // 如果指定了连接 ID，只发送给特定连接
        if (message.getConnectionId() != null) {
            WebSocketConnection conn = wsConnections.get(message.getConnectionId());
            if (conn != null) {
                conn.send(message);
            }
            return;
        }
        
        // 广播到所有 WebSocket 连接
        if ("broadcast".equals(channel) || channel == null) {
            wsConnections.values().forEach(conn -> conn.send(message));
        }
    }
    
    /**
     * HTTP 聊天处理器
     */
    private class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            
            try {
                // 解析请求体
                byte[] body = exchange.getRequestBody().readAllBytes();
                JsonNode json = objectMapper.readTree(body);
                
                String sessionId = json.has("sessionId") ? json.get("sessionId").asText() : UUID.randomUUID().toString();
                String content = json.has("content") ? json.get("content").asText() : "";
                String channel = json.has("channel") ? json.get("channel").asText() : "http";
                boolean useSearch = json.has("useSearch") && json.get("useSearch").asBoolean();
                
                // 创建入站消息（包含 useSearch 元数据）
                InboundMessage message = InboundMessage.builder()
                    .chatId(sessionId)
                    .senderId(sessionId)
                    .content(content)
                    .channel(channel)
                    .metadata(Map.of("useSearch", useSearch))
                    .build();
                
                logger.info("Received chat request: sessionId={}, contentLength={}, useSearch={}", 
                           sessionId, content.length(), useSearch);
                
                // 发布到消息总线
                messageBus.publishInbound(message);
                
                // 等待并获取响应（最多60秒）
                String responseContent = waitForResponse(sessionId, 60);
                
                if (responseContent != null) {
                    // 返回实际响应内容
                    String response = objectMapper.writeValueAsString(Map.of(
                        "status", "ok",
                        "sessionId", sessionId,
                        "content", responseContent
                    ));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.getBytes().length);
                    exchange.getResponseBody().write(response.getBytes());
                } else {
                    String error = objectMapper.writeValueAsString(Map.of(
                        "status", "error",
                        "message", "Request timed out"
                    ));
                    exchange.sendResponseHeaders(504, error.getBytes().length);
                    exchange.getResponseBody().write(error.getBytes());
                }
                
            } catch (Exception e) {
                logger.error("Error handling chat request", e);
                String error = objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", e.getMessage()
                ));
                exchange.sendResponseHeaders(500, error.getBytes().length);
                exchange.getResponseBody().write(error.getBytes());
            } finally {
                exchange.close();
            }
        }
        
        /**
         * 等待指定会话的响应
         */
        private String waitForResponse(String sessionId, long timeoutSeconds) {
            logger.info("Waiting for response for session: {}, timeout: {}s", sessionId, timeoutSeconds);
            long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000);
            
            while (System.currentTimeMillis() < endTime) {
                try {
                    // 尝试获取出站消息（超时1秒）
                    OutboundMessage msg = messageBus.consumeOutbound(1, TimeUnit.SECONDS);
                    
                    if (msg != null) {
                        logger.debug("Received outbound message for chatId: {}, content length: {}", 
                                   msg.getChatId(), 
                                   msg.getContent() != null ? msg.getContent().length() : 0);
                        // 检查是否是当前会话的响应
                        if (sessionId.equals(msg.getChatId())) {
                            logger.info("Found response for session: {}, content length: {}", 
                                       sessionId, 
                                       msg.getContent() != null ? msg.getContent().length() : 0);
                            return msg.getContent();
                        }
                        // 如果不是当前会话的消息，需要处理或重新放入队列
                        logger.warn("Received message for different session: expected={}, got={}", 
                                   sessionId, msg.getChatId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Wait interrupted for session: {}", sessionId);
                    return null;
                }
            }
            
            logger.warn("Timeout waiting for response for session: {}", sessionId);
            return null;
        }
    }
    
    /**
     * 会话管理处理器
     */
    private class SessionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            
            switch (method.toUpperCase()) {
                case "GET":
                    handleGetSessions(exchange);
                    break;
                case "DELETE":
                    handleDeleteSession(exchange);
                    break;
                default:
                    exchange.sendResponseHeaders(405, -1);
            }
        }
        
        private void handleGetSessions(HttpExchange exchange) throws IOException {
            // 简化实现：返回空列表
            String response = objectMapper.writeValueAsString(Map.of(
                "sessions", new Object[0],
                "count", 0
            ));
            
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();
        }
        
        private void handleDeleteSession(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String sessionId = path.substring("/api/sessions/".length());
            
            // 简化实现：返回成功
            String response = objectMapper.writeValueAsString(Map.of(
                "status", "ok",
                "sessionId", sessionId
            ));
            
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();
        }
    }
    
    /**
     * WebSocket 处理器
     */
    private class WebSocketHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 检查是否是 WebSocket 升级请求
            String upgrade = exchange.getRequestHeaders().getFirst("Upgrade");
            if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            
            // 简化实现：使用简单的 WebSocket 协议处理
            String connectionId = UUID.randomUUID().toString();
            WebSocketConnection conn = new WebSocketConnection(exchange, connectionId);
            wsConnections.put(connectionId, conn);
            
            logger.info("WebSocket connection opened: {}", connectionId);
            
            // 异步处理连接
            new Thread(() -> {
                try {
                    conn.handle();
                } finally {
                    wsConnections.remove(connectionId);
                    logger.info("WebSocket connection closed: {}", connectionId);
                }
            }).start();
        }
    }
    
    /**
     * WebSocket 连接封装
     */
    private class WebSocketConnection {
        private final HttpExchange exchange;
        private final String connectionId;
        
        WebSocketConnection(HttpExchange exchange, String connectionId) {
            this.exchange = exchange;
            this.connectionId = connectionId;
        }
        
        void handle() {
            // 简化实现：实际应用中应实现完整的 WebSocket 协议
            // 包括握手、帧解析、心跳等
        }
        
        void send(OutboundMessage message) {
            try {
                String json = objectMapper.writeValueAsString(Map.of(
                    "type", "message",
                    "content", message.getContent(),
                    "channel", message.getChannel(),
                    "sessionId", message.getSessionId()
                ));
                
                // 简化实现：实际应用中应发送 WebSocket 帧
                exchange.getResponseBody().write(json.getBytes());
                exchange.getResponseBody().flush();
                
            } catch (IOException e) {
                logger.error("Failed to send message to WebSocket", e);
            }
        }
        
        void close() {
            try {
                exchange.close();
            } catch (Exception e) {
                logger.warn("Error closing WebSocket connection", e);
            }
        }
        
        String getConnectionId() {
            return connectionId;
        }
    }
    
    /**
     * 根路径处理器 - 返回首页
     */
    private class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            
            // 读取首页 HTML
            try (InputStream is = getClass().getResourceAsStream("/static/index.html")) {
                if (is != null) {
                    byte[] content = is.readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, content.length);
                    exchange.getResponseBody().write(content);
                } else {
                    String error = "<h1>首页未找到</h1>";
                    exchange.sendResponseHeaders(404, error.getBytes().length);
                    exchange.getResponseBody().write(error.getBytes());
                }
            } finally {
                exchange.close();
            }
        }
    }
    
    /**
     * 静态资源处理器
     */
    private class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            
            String path = exchange.getRequestURI().getPath();
            String resourcePath = path; // 路径已经包含 /static/
            
            try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                if (is != null) {
                    byte[] content = is.readAllBytes();
                    
                    // 设置正确的 Content-Type
                    String contentType = getContentType(path);
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    exchange.sendResponseHeaders(200, content.length);
                    exchange.getResponseBody().write(content);
                } else {
                    String error = "Resource not found: " + path;
                    exchange.sendResponseHeaders(404, error.getBytes().length);
                    exchange.getResponseBody().write(error.getBytes());
                }
            } finally {
                exchange.close();
            }
        }
        
        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=UTF-8";
            if (path.endsWith(".css")) return "text/css; charset=UTF-8";
            if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (path.endsWith(".json")) return "application/json; charset=UTF-8";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
            if (path.endsWith(".gif")) return "image/gif";
            if (path.endsWith(".svg")) return "image/svg+xml";
            return "application/octet-stream";
        }
    }
}
