package com.nanocode.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanocode.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Streamable HTTP MCP 客户端 — 通过单一 HTTP 端点通信。
 *
 * <h3>协议</h3>
 * MCP Streamable HTTP 传输（2025 规范推荐）。
 * <pre>
 *   POST /mcp  {"jsonrpc":"2.0","method":"tools/call",...}
 *     → 200 + Content-Type: application/json        (短响应)
 *     → 200 + Content-Type: text/event-stream       (长/流式响应)
 * </pre>
 *
 * <h3>与旧 HTTP+SSE 的区别</h3>
 * 旧方案：GET /sse（建 SSE 流）+ POST /messages（发请求）— 两连接。
 * 新方案：单一 POST 端点，Server 自行决定返回 JSON 还是 SSE 流。
 */
public class StreamableHttpMCPClient implements MCPClient {

    private static final Logger logger = LoggerFactory.getLogger(StreamableHttpMCPClient.class);

    private final String serverName;
    private final Config.MCPServerConfig config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private volatile boolean initialized;

    public StreamableHttpMCPClient(String serverName, Config.MCPServerConfig config) {
        this.serverName = serverName;
        this.config = config;
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newHttpClient();
    }

    // ═══════════ 握手 ═══════════

    private synchronized void ensureInitialized() throws IOException {
        if (initialized) return;
        JsonRpcMessage.Response resp = sendRpc(JsonRpcMessage.Request.initialize());
        if (!resp.isSuccess()) throw new IOException("Initialize failed: " + resp.error());
        logger.info("MCP server {} initialized via Streamable HTTP", serverName);
        sendRpcNotification(JsonRpcMessage.Request.notification(JsonRpcMessage.INITIALIZED));
        initialized = true;
    }

    // ═══════════ HTTP 通信 ═══════════

    /** 发送 JSON-RPC 请求并等待响应 */
    private JsonRpcMessage.Response sendRpc(JsonRpcMessage.Request request) throws IOException {
        try {
            String body = mapper.writeValueAsString(request);
            HttpRequest httpReq = buildRequest(body);
            HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (httpResp.statusCode() == 202) {
                // 服务器异步处理，响应无 body
                return new JsonRpcMessage.Response("2.0", request.id(), null,
                        JsonRpcMessage.Response.Error.internal("Async not yet supported"));
            }

            if (httpResp.statusCode() != 200) {
                return new JsonRpcMessage.Response("2.0", request.id(), null,
                        JsonRpcMessage.Response.Error.internal("HTTP " + httpResp.statusCode()));
            }

            String contentType = httpResp.headers().firstValue("Content-Type").orElse("");
            String respBody = httpResp.body();

            if (contentType.contains("text/event-stream") || contentType.contains("application/x-ndjson")) {
                // SSE / 流式响应 — 取最后一条作为结果
                respBody = extractLastSseData(respBody);
            }

            return mapper.readValue(respBody, JsonRpcMessage.Response.class);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        } catch (Exception e) {
            throw new IOException("Request failed: " + e.getMessage(), e);
        }
    }

    /** 发送通知（无 id，不等响应） */
    private void sendRpcNotification(JsonRpcMessage.Request notification) {
        try {
            String body = mapper.writeValueAsString(notification);
            httpClient.sendAsync(buildRequest(body), HttpResponse.BodyHandlers.discarding());
        } catch (JsonProcessingException e) {
            logger.warn("Failed to send notification: {}", e.getMessage());
        }
    }

    /** 从 SSE 流中提取最后一条 data: 行 */
    private String extractLastSseData(String sseBody) {
        String lastData = null;
        for (String line : sseBody.split("\n")) {
            if (line.startsWith("data:")) {
                String d = line.substring(5);
                if (d.startsWith(" ")) d = d.substring(1);
                lastData = d;
            }
        }
        return lastData != null ? lastData : sseBody;
    }

    private HttpRequest buildRequest(String body) {
        String ep = config.getEndpoint();
        String endpoint = (ep != null && !ep.isBlank()) ? ep : config.getUrl();
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (config.getHeaders() != null) config.getHeaders().forEach(builder::header);
        return builder.build();
    }

    // ═══════════ MCPClient 接口 ═══════════

    @Override
    public CompletableFuture<MCPResult> callTool(String toolName, Map<String, Object> arguments) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureInitialized();
                JsonRpcMessage.Response resp = sendRpc(JsonRpcMessage.Request.callTool(toolName, arguments));
                return resp.isSuccess() ? MCPResult.fromJsonRpcResponse(resp) : MCPResult.error(resp.error().toString());
            } catch (Exception e) {
                return MCPResult.error(e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<List<MCPToolInfo>> listTools() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureInitialized();
                JsonRpcMessage.Response resp = sendRpc(JsonRpcMessage.Request.listTools());
                if (!resp.isSuccess()) return Collections.emptyList();
                return parseToolList(resp.result());
            } catch (Exception e) {
                logger.error("listTools failed: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    @Override
    public CompletableFuture<MCPResult> readResource(String uri) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureInitialized();
                JsonRpcMessage.Response resp = sendRpc(JsonRpcMessage.Request.readResource(uri));
                return resp.isSuccess() ? MCPResult.fromJsonRpcResponse(resp) : MCPResult.error(resp.error().toString());
            } catch (Exception e) {
                return MCPResult.error(e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<MCPResult> getPrompt(String promptName, Map<String, Object> arguments) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureInitialized();
                JsonRpcMessage.Response resp = sendRpc(JsonRpcMessage.Request.getPrompt(promptName, arguments));
                return resp.isSuccess() ? MCPResult.fromJsonRpcResponse(resp) : MCPResult.error(resp.error().toString());
            } catch (Exception e) {
                return MCPResult.error(e.getMessage());
            }
        });
    }

    @Override public void close() {
        initialized = false;
        // 注：Java 17 HttpClient 无 close()，改用 shared static 实例以减少资源占用
    }
    @Override public boolean isConnected() { return true; }
    @Override public String getServerName() { return serverName; }
}
