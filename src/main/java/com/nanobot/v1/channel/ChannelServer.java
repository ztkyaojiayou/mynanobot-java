package com.nanobot.v1.channel;

import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private final Map<String, ChatHandler.StreamResponseHandler> streamResponseHandlers = new ConcurrentHashMap<>();
    
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
        httpServer.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
        
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
     * 添加 CORS 响应头
     */
    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Upgrade, Connection, Sec-WebSocket-Key, Sec-WebSocket-Version, Sec-WebSocket-Protocol, Sec-WebSocket-Extensions");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
    }
    
    /**
     * HTTP 聊天处理器
     */
    private class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 添加 CORS 头
            addCorsHeaders(exchange);
            
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            
            try {
                // 解析请求体
                byte[] body = exchange.getRequestBody().readAllBytes();
                JsonNode json = objectMapper.readTree(body);
                
                // 优先使用 chatId，兼容 sessionId
                String sessionId = json.has("chatId") ? json.get("chatId").asText() : 
                                  (json.has("sessionId") ? json.get("sessionId").asText() : UUID.randomUUID().toString());
                String content = json.has("content") ? json.get("content").asText() : "";
                String channel = json.has("channel") ? json.get("channel").asText() : "http";
                boolean useSearch = json.has("useSearch") && json.get("useSearch").asBoolean();
                boolean streamMode = json.has("streamMode") && json.get("streamMode").asBoolean();
                
                // 获取请求ID（用于追踪）
                String requestId = json.has("requestId") ? json.get("requestId").asText() : UUID.randomUUID().toString();
                
                // 创建入站消息（包含 useSearch 和 streamMode 元数据）
                Map<String, Object> metadata = new java.util.HashMap<>();
                metadata.put("useSearch", useSearch);
                metadata.put("streamMode", streamMode);
                metadata.put("requestId", requestId);
                
                InboundMessage message = InboundMessage.builder()
                    .chatId(sessionId)
                    .senderId(sessionId)
                    .content(content)
                    .channel(channel)
                    .metadata(metadata)
                    .build();
                
                logger.info("Received chat request: requestId={}, sessionId={}, contentLength={}, useSearch={}, streamMode={}", 
                           requestId, sessionId, content.length(), useSearch, streamMode);
                
                // 发布到消息总线
                messageBus.publishInbound(message);
                
                // 如果是流式模式，使用 SSE 流式响应
                if (streamMode) {
                    handleStreamingResponse(exchange, sessionId, requestId);
                } else {
                    // 非流式模式，等待完整响应
                    String responseContent = waitForResponse(sessionId, requestId, 300);
                    
                    if (responseContent != null) {
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
         * 处理流式响应（SSE）
         */
        private void handleStreamingResponse(HttpExchange exchange, String sessionId, String requestId) {
            try {
                // 设置 SSE 响应头
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.getResponseHeaders().set("Connection", "keep-alive");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, 0); // 0 表示不预先设置内容长度
                
                OutputStream outputStream = exchange.getResponseBody();
                
                // 创建流式响应处理器
                StreamResponseHandler handler = new StreamResponseHandler(outputStream);
                
                // 注册流式响应回调
                synchronized (streamResponseHandlers) {
                    String key = sessionId + ":" + requestId;
                    streamResponseHandlers.put(key, handler);
                }
                
                // 等待响应完成或超时
                try {
                    synchronized (handler) {
                        handler.wait(300000); // 5分钟超时
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    // 清理
                    synchronized (streamResponseHandlers) {
                        String key = sessionId + ":" + requestId;
                        streamResponseHandlers.remove(key);
                    }
                    
                    // 发送结束标记
                    try {
                        outputStream.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    } catch (IOException ignored) {}
                }
                
            } catch (IOException e) {
                logger.error("Error handling streaming response", e);
            }
        }
        
        /**
         * 流式响应处理器
         */
        private class StreamResponseHandler {
            private final OutputStream outputStream;
            private volatile boolean completed = false;
            
            public StreamResponseHandler(OutputStream outputStream) {
                this.outputStream = outputStream;
            }
            
            public void sendChunk(String content) {
                try {
                    String json = objectMapper.writeValueAsString(Map.of(
                        "type", "chunk",
                        "content", content
                    ));
                    String sseMessage = "data: " + json + "\n\n";
                    outputStream.write(sseMessage.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (IOException e) {
                    logger.warn("Error sending stream chunk", e);
                }
            }
            
            public void complete() {
                completed = true;
                synchronized (this) {
                    this.notifyAll();
                }
            }
            
            public boolean isCompleted() {
                return completed;
            }
        }
        
    /**
     * 推送流式响应数据
     */
    public void pushStreamResponse(String sessionId, String requestId, String content) {
        synchronized (streamResponseHandlers) {
            String key = sessionId + ":" + requestId;
            ChatHandler.StreamResponseHandler handler = streamResponseHandlers.get(key);
            if (handler != null) {
                handler.sendChunk(content);
            }
        }
    }
    
    /**
     * 完成流式响应
     */
    public void completeStreamResponse(String sessionId, String requestId) {
        synchronized (streamResponseHandlers) {
            String key = sessionId + ":" + requestId;
            ChatHandler.StreamResponseHandler handler = streamResponseHandlers.get(key);
            if (handler != null) {
                handler.complete();
            }
        }
    }
        
        /**
     * 等待指定会话的响应（使用 requestId 精确匹配）
     */
    private String waitForResponse(String sessionId, String requestId, long timeoutSeconds) {
        logger.info("Waiting for response for session: {}, requestId: {}, timeout: {}s", 
                   sessionId, requestId, timeoutSeconds);
        
        try {
            // 使用 requestId 精确匹配响应
            OutboundMessage msg = messageBus.waitForSessionResponse(sessionId, requestId, timeoutSeconds, TimeUnit.SECONDS);
            
            if (msg != null) {
                logger.info("Found response for session: {}, requestId: {}, content length: {}", 
                           sessionId, requestId,
                           msg.getContent() != null ? msg.getContent().length() : 0);
                logger.info("Response content: {}", 
                           msg.getContent() != null && msg.getContent().length() > 100 ? 
                                       msg.getContent().substring(0, 100) + "..." : msg.getContent());
                return msg.getContent();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Wait interrupted for session: {}, requestId: {}", sessionId, requestId);
        }
        
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
            // 添加 CORS 头
            addCorsHeaders(exchange);
            
            // 检查是否是 WebSocket 升级请求
            String upgrade = exchange.getRequestHeaders().getFirst("Upgrade");
            if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            
            // WebSocket 握手
            String key = exchange.getRequestHeaders().getFirst("Sec-WebSocket-Key");
            if (key == null) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            
            // 计算 Sec-WebSocket-Accept
            String acceptKey = computeWebSocketAccept(key);
            
            // 发送握手响应
            exchange.getResponseHeaders().set("Upgrade", "websocket");
            exchange.getResponseHeaders().set("Connection", "Upgrade");
            exchange.getResponseHeaders().set("Sec-WebSocket-Accept", acceptKey);
            
            // 发送 101 响应
            exchange.sendResponseHeaders(101, 0);  // 101 Switching Protocols
            
            // 创建连接
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
        
        /**
         * 计算 WebSocket Accept Key
         */
        private String computeWebSocketAccept(String key) {
            try {
                String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
                String combined = key + magic;
                java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
                byte[] hash = sha1.digest(combined.getBytes(StandardCharsets.UTF_8));
                return java.util.Base64.getEncoder().encodeToString(hash);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-1 algorithm not available", e);
            }
        }
    }
    
    /**
     * WebSocket 连接封装
     */
    private class WebSocketConnection {
        private final HttpExchange exchange;
        private final String connectionId;
        private volatile boolean closed = false;
        private java.io.InputStream inputStream;
        private java.io.OutputStream outputStream;
        
        WebSocketConnection(HttpExchange exchange, String connectionId) {
            this.exchange = exchange;
            this.connectionId = connectionId;
        }
        
        void handle() {
            try {
                inputStream = exchange.getRequestBody();
                outputStream = exchange.getResponseBody();
                
                logger.info("WebSocket connection {} ready for messages", connectionId);
                
                // 使用 PushbackInputStream 支持回退已读取的字节
                java.io.PushbackInputStream pushbackStream = new java.io.PushbackInputStream(inputStream, 1);
                
                while (!closed) {
                    logger.debug("WebSocket connection {} waiting for message", connectionId);
                    
                    // 读取第一个字节（阻塞等待）
                    int firstByte = pushbackStream.read();
                    
                    if (firstByte == -1) {
                        logger.debug("WebSocket input stream closed for connection {}", connectionId);
                        break;
                    }
                    
                    logger.debug("WebSocket connection {} received first byte: 0x{}", connectionId, Integer.toHexString(firstByte));
                    
                    // 将字节放回流中，让 parse() 方法重新读取
                    pushbackStream.unread(firstByte);
                    
                    WebSocketFrame frame = WebSocketFrame.parse(pushbackStream);
                    
                    if (frame.isClose()) {
                        logger.debug("Received close frame for connection {}", connectionId);
                        try {
                            WebSocketFrame.close().send(outputStream);
                        } catch (IOException e) {
                            logger.debug("Failed to send close frame");
                        }
                        close();
                        break;
                    }
                    
                    if (frame.isPing()) {
                        logger.debug("Received ping frame for connection {}", connectionId);
                        WebSocketFrame.pong().send(outputStream);
                        continue;
                    }
                    
                    if (frame.isText()) {
                        String text = frame.getText();
                        logger.debug("Received text message for connection {}: {}", connectionId, text.length() > 50 ? text.substring(0, 50) + "..." : text);
                        handleIncomingMessage(text);
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                logger.debug("WebSocket read timeout for connection {}", connectionId);
            } catch (IOException e) {
                if (!closed) {
                    logger.debug("WebSocket connection error for {}: {}", connectionId, e.getMessage());
                    close();
                }
            }
        }
        
        /**
         * 处理入站消息
         */
        private void handleIncomingMessage(String text) {
            try {
                JsonNode node = objectMapper.readTree(text);
                String type = node.has("type") ? node.get("type").asText() : "message";
                
                switch (type) {
                    case "message" -> {
                        // 创建入站消息
                        String chatId = node.has("chatId") ? node.get("chatId").asText() : 
                                       (node.has("chat_id") ? node.get("chat_id").asText() : connectionId);
                        String content = node.has("content") ? node.get("content").asText() : "";
                        
                        // 解析 useSearch 和 streamMode 参数
                        boolean useSearch = node.has("useSearch") && node.get("useSearch").asBoolean();
                        boolean streamMode = node.has("streamMode") && node.get("streamMode").asBoolean();
                        
                        // 构建元数据
                        Map<String, Object> metadata = new java.util.HashMap<>();
                        metadata.put("_wants_stream", streamMode);
                        metadata.put("useSearch", useSearch);
                        metadata.put("streamMode", streamMode);
                        
                        InboundMessage inbound = InboundMessage.builder()
                            .channel("websocket")
                            .chatId(chatId)
                            .content(content)
                            .connectionId(connectionId)
                            .metadata(metadata)
                            .build();
                        
                        logger.info("WebSocket message received: chatId={}, useSearch={}, streamMode={}", chatId, useSearch, streamMode);
                        messageBus.publishInbound(inbound);
                    }
                    
                    case "cancel" -> {
                        // 取消当前处理
                        logger.info("Received cancel request from {}", connectionId);
                        // TODO: 实现取消逻辑
                    }
                    
                    default -> logger.warn("Unknown message type: {}", type);
                }
            } catch (Exception e) {
                logger.error("Failed to parse WebSocket message: {}", e.getMessage(), e);
            }
        }
        
        /**
         * 发送消息
         */
        void send(OutboundMessage message) {
            if (closed) {
                return;
            }
            
            try {
                Map<String, Object> jsonMap = new java.util.HashMap<>();
                
                if (message.isStreamDelta()) {
                    jsonMap.put("type", "stream_delta");
                    jsonMap.put("delta", message.getContent());
                } else if (message.isStreamEnd()) {
                    jsonMap.put("type", "stream_end");
                } else {
                    jsonMap.put("type", "message");
                    jsonMap.put("content", message.getContent());
                    jsonMap.put("channel", message.getChannel());
                    jsonMap.put("chatId", message.getChatId());
                    jsonMap.put("sessionId", message.getSessionId());
                    jsonMap.put("isProgress", message.isProgress());
                    jsonMap.put("metadata", message.getMetadata());
                }
                
                String json = objectMapper.writeValueAsString(jsonMap);
                logger.debug("Sending WebSocket message: type={}, content={}", 
                           jsonMap.get("type"), 
                           message.getContent() != null ? 
                           (message.getContent().length() > 50 ? 
                            message.getContent().substring(0, 50) + "..." : 
                            message.getContent()) : "null");
                
                WebSocketFrame.text(json).send(outputStream);
                
            } catch (IOException e) {
                logger.error("Failed to send message to WebSocket", e);
                close();
            }
        }
        
        /**
         * 发送流式消息片段
         */
        void sendStreamDelta(String delta, boolean isEnd) {
            if (closed) {
                return;
            }
            
            try {
                String json = objectMapper.writeValueAsString(Map.of(
                    "type", isEnd ? "stream_end" : "stream_delta",
                    "delta", delta,
                    "isEnd", isEnd
                ));
                
                WebSocketFrame.textFragment(json, isEnd).send(outputStream);
                
            } catch (IOException e) {
                logger.error("Failed to send stream delta to WebSocket", e);
                close();
            }
        }
        
        /**
         * 关闭连接
         */
        void close() {
            if (closed) {
                return;
            }
            
            closed = true;
            try {
                // 只在输出流可用时尝试发送关闭帧
                if (outputStream != null) {
                    try {
                        WebSocketFrame.close().send(outputStream);
                    } catch (IOException e) {
                        // 流可能已经关闭，忽略这个错误
                        logger.debug("Failed to send close frame, stream may already be closed");
                    }
                }
                exchange.close();
            } catch (Exception e) {
                logger.debug("Error closing WebSocket connection: {}", e.getMessage());
            }
        }
        
        String getConnectionId() {
            return connectionId;
        }
        
        boolean isClosed() {
            return closed;
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
