package com.nanocode.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanocode.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * SSE MCP 客户端 — 旧版 MCP 双端点 SSE 传输。
 *
 * <h3>协议</h3>
 * <pre>
 *   GET  /sse        → 建立 SSE 长连接（raw socket 避免 HttpClient 长连接兼容问题）
 *   POST /messages   → 发送 JSON-RPC 2.0 请求（Java HttpClient）
 * </pre>
 */
public class SseMCPClient implements MCPClient {

    private static final Logger logger = LoggerFactory.getLogger(SseMCPClient.class);

    private final String serverName;
    private final Config.MCPServerConfig config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final ScheduledExecutorService executor;
    private volatile boolean closed;
    private volatile boolean initialized;

    private final Map<String, CompletableFuture<JsonRpcMessage.Response>> pendingRequests = new ConcurrentHashMap<>();
    private final CountDownLatch sseReady = new CountDownLatch(1);

    public SseMCPClient(String serverName, Config.MCPServerConfig config) {
        this.serverName = serverName;
        this.config = config;
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newHttpClient();
        this.executor = Executors.newScheduledThreadPool(2, r -> new Thread(r, "MCP-sse-" + serverName));
    }

    // ═══════════ 连接 + 握手 ═══════════

    private synchronized void ensureInitialized() throws IOException {
        if (initialized) return;
        startSseListener(config.getUrl());
        try { sseReady.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("SSE interrupted"); }

        JsonRpcMessage.Response initResp = sendRpc(JsonRpcMessage.Request.initialize());
        if (!initResp.isSuccess()) throw new IOException("Initialize failed: " + initResp.error());
        logger.info("MCP server {} initialized via SSE", serverName);
        sendNotification(JsonRpcMessage.Request.notification(JsonRpcMessage.INITIALIZED));
        initialized = true;
    }

    // ═══════════ SSE 监听（raw Socket） ═══════════

    private void startSseListener(String baseUrl) {
        executor.submit(() -> {
            try {
                URI uri = URI.create(baseUrl + "/sse");
                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 80;
                logger.info("SSE connecting to {}:{}...", host, port);
                Socket socket = new Socket(host, port);
                PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                out.print("GET " + uri.getPath() + " HTTP/1.1\r\nHost: " + host + "\r\nAccept: text/event-stream\r\nConnection: close\r\n\r\n");
                out.flush();

                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                // 跳过 HTTP 响应头
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip headers */ }
                sseReady.countDown();
                logger.info("SSE listener connected to {}", baseUrl);

                // 读 SSE 事件
                String eventData = null; int eventCount = 0;
                while ((line = in.readLine()) != null && !closed) {
                    if (line.startsWith("data:")) {
                        String d = line.substring(5);
                        if (d.startsWith(" ")) d = d.substring(1);
                        eventData = d;
                    } else if (line.isEmpty() && eventData != null) {
                        eventCount++;
                        if (eventCount <= 3) logger.debug("SSE event #{}: {}", eventCount,
                                eventData.length() > 100 ? eventData.substring(0, 100) + "..." : eventData);
                        if (eventData.startsWith("{")) {
                            try {
                                JsonRpcMessage.Response rpcResp = mapper.readValue(eventData, JsonRpcMessage.Response.class);
                                if (rpcResp.id() != null) {
                                    CompletableFuture<JsonRpcMessage.Response> future = pendingRequests.remove(rpcResp.id());
                                    if (future != null) future.complete(rpcResp);
                                }
                            } catch (Exception e) { logger.debug("SSE: skip non-JSON"); }
                        }
                        eventData = null;
                    }
                }
                socket.close();
            } catch (Exception e) {
                if (!closed) logger.error("SSE listener error for {}: {}", serverName, e.getMessage());
                sseReady.countDown(); // 防止 await 永久阻塞
            }
        });
    }

    // ═══════════ 消息收发 ═══════════

    private void sendNotification(JsonRpcMessage.Request notification) {
        try {
            String body = mapper.writeValueAsString(notification);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(config.getUrl() + "/messages"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (JsonProcessingException e) { logger.warn("notif: {}", e.getMessage()); }
    }

    private JsonRpcMessage.Response sendRpc(JsonRpcMessage.Request request) throws IOException {
        String body = mapper.writeValueAsString(request);
        CompletableFuture<JsonRpcMessage.Response> future = new CompletableFuture<>();
        pendingRequests.put(request.id(), future);
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(config.getUrl() + "/messages"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> postResp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            logger.debug("SSE POST {} -> status={}", request.method(), postResp.statusCode());
            return future.get(config.getToolTimeout(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(request.id());
            return new JsonRpcMessage.Response("2.0", request.id(), null, JsonRpcMessage.Response.Error.internal("Request timed out"));
        } catch (Exception e) {
            pendingRequests.remove(request.id());
            throw new IOException("Request failed: " + e.getMessage(), e);
        }
    }

    // ═══════════ MCPClient 接口 ═══════════

    @Override public CompletableFuture<MCPResult> callTool(String toolName, Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            try { ensureInitialized(); JsonRpcMessage.Response r = sendRpc(JsonRpcMessage.Request.callTool(toolName, args)); return r.isSuccess() ? MCPResult.fromJsonRpcResponse(r) : MCPResult.error(r.error().toString()); }
            catch (Exception e) { return MCPResult.error(e.getMessage()); }
        }, executor);
    }

    @Override public CompletableFuture<List<MCPToolInfo>> listTools() {
        return CompletableFuture.supplyAsync(() -> {
            try { ensureInitialized(); JsonRpcMessage.Response r = sendRpc(JsonRpcMessage.Request.listTools()); if (!r.isSuccess()) return Collections.emptyList(); return parseToolList(r.result()); }
            catch (Exception e) { logger.error("listTools: {}", e.getMessage()); return Collections.emptyList(); }
        }, executor);
    }

    @Override public CompletableFuture<MCPResult> readResource(String uri) {
        return callTool("read_resource", Map.of("uri", uri));
    }

    @Override public CompletableFuture<MCPResult> getPrompt(String n, Map<String, Object> a) {
        return CompletableFuture.supplyAsync(() -> {
            try { ensureInitialized(); JsonRpcMessage.Response r = sendRpc(JsonRpcMessage.Request.getPrompt(n, a)); return r.isSuccess() ? MCPResult.fromJsonRpcResponse(r) : MCPResult.error(r.error().toString()); }
            catch (Exception e) { return MCPResult.error(e.getMessage()); }
        }, executor);
    }

    @Override public void close() {
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // 注：Java 17 HttpClient 无 close()，改用 shared static 实例以减少资源占用
    }
    @Override public boolean isConnected() { return initialized && !closed; }
    @Override public String getServerName() { return serverName; }
}
