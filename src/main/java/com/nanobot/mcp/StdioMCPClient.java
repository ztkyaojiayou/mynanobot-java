package com.nanobot.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Stdio MCP 客户端 — 通过子进程 stdin/stdout 与 MCP Server 通信。
 *
 * <h3>协议</h3>
 * 遵循 MCP 规范（JSON-RPC 2.0 over stdio）。
 * 每行一个 JSON 消息，使用标准 {@link JsonRpcMessage.Request} / {@link JsonRpcMessage.Response}。
 *
 * <h3>并发模型</h3>
 * <ul>
 *   <li>{@code pendingRequests} (CHM) — 请求-响应路由表，按消息 id 精确匹配</li>
 *   <li>{@code responseListener} (daemon) — 读 stdout 行，匹配 pending request</li>
 *   <li>{@code executorService} — 超时调度（schedule）+ callTool 异步提交</li>
 * </ul>
 */
public class StdioMCPClient implements MCPClient {

    private static final Logger logger = LoggerFactory.getLogger(StdioMCPClient.class);

    private final String serverName;
    private final Config.MCPServerConfig config;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService executor;

    private Process process;
    private PrintWriter writer;
    private BufferedReader reader;
    private volatile boolean closed;

    /** 请求-响应路由表（CHM + remove() 原子性 → fetch/complete 不重复） */
    private final Map<String, CompletableFuture<JsonRpcMessage.Response>> pendingRequests = new ConcurrentHashMap<>();

    public StdioMCPClient(String serverName, Config.MCPServerConfig config) {
        this.serverName = serverName;
        this.config = config;
        this.mapper = new ObjectMapper();
        this.executor = Executors.newScheduledThreadPool(2, r -> new Thread(r, "MCP-stdio-" + serverName));
    }

    // ═══════════ 连接 + 握手 ═══════════

    /** 启动子进程 + initialize 握手。调用一次即完成完整的 MCP 连接建立。 */
    public void connect() throws IOException {
        if (process != null && process.isAlive()) return;

        // ── 启动子进程 ──
        List<String> cmd = new ArrayList<>();
        cmd.add(config.getCommand());
        if (config.getArgs() != null) cmd.addAll(config.getArgs());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (config.getWorkspace() != null && !config.getWorkspace().isBlank()) {
            pb.directory(new File(config.getWorkspace()));
        }

        logger.info("Starting MCP server {}: {}", serverName, String.join(" ", cmd));
        process = pb.start();
        writer = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // ── 启动响应监听 ──
        startResponseListener();

        try {
            // ── initialize 握手 ──
            JsonRpcMessage.Response initResp = sendAndWait(JsonRpcMessage.Request.initialize(), config.getToolTimeout());
            if (!initResp.isSuccess()) {
                throw new IOException("Initialize failed: " + initResp.error());
            }
            logger.info("MCP server {} initialized, protocol={}", serverName,
                    initResp.result() != null ? initResp.result().get("protocolVersion").asText() : "unknown");

            // ── 发送 initialized 通知 ──
            sendNotification(JsonRpcMessage.Request.notification(JsonRpcMessage.INITIALIZED));
        } catch (IOException e) {
            // 握手失败 → 清理已启动的进程，防止泄漏
            close();
            throw e;
        }
    }

    // ═══════════ 消息收发 ═══════════

    /** 发送通知（无 id，不期待响应） */
    private void sendNotification(JsonRpcMessage.Request notification) {
        try {
            String json = mapper.writeValueAsString(notification);
            writer.println(json);
            writer.flush();
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize notification: {}", e.getMessage());
        }
    }

    /** 发送请求并等待响应 */
    private JsonRpcMessage.Response sendAndWait(JsonRpcMessage.Request request, int timeoutSecs) throws IOException {
        CompletableFuture<JsonRpcMessage.Response> future = new CompletableFuture<>();
        pendingRequests.put(request.id(), future);
        try {
            String json = mapper.writeValueAsString(request);
            writer.println(json);
            writer.flush();
            return future.get(timeoutSecs, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(request.id());
            return new JsonRpcMessage.Response("2.0", request.id(), null,
                    JsonRpcMessage.Response.Error.internal("Request timed out"));
        } catch (Exception e) {
            pendingRequests.remove(request.id());
            throw new IOException("Request failed: " + e.getMessage(), e);
        }
    }

    /** 读 stdout 行 → 反序列化为 Response → 匹配 pendingRequest */
    private void startResponseListener() {
        executor.submit(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null && !closed) {
                    if (line.isBlank()) continue;
                    // 跳过非 JSON 行（MCP Server 启动时的日志输出等）
                    if (!line.startsWith("{")) continue;
                    try {
                        JsonRpcMessage.Response resp = mapper.readValue(line, JsonRpcMessage.Response.class);
                        // 无 id = 服务端推送的通知（当前忽略）
                        if (resp.id() == null) continue;
                        // 使用 remove() 而非 get()+remove()，原子性防止竞态
                        CompletableFuture<JsonRpcMessage.Response> future = pendingRequests.remove(resp.id());
                        if (future != null) future.complete(resp);
                    } catch (Exception e) {
                        logger.debug("Skipping non-JSON MCP output: {}", line.substring(0, Math.min(80, line.length())));
                    }
                }
            } catch (IOException e) {
                if (!closed) logger.error("MCP reader error for {}: {}", serverName, e.getMessage());
            }
        });
    }

    // ═══════════ MCPClient 接口 ═══════════

    @Override
    public CompletableFuture<MCPResult> callTool(String toolName, Map<String, Object> arguments) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isConnected()) connect();
                JsonRpcMessage.Request req = JsonRpcMessage.Request.callTool(toolName, arguments);
                JsonRpcMessage.Response resp = sendAndWait(req, config.getToolTimeout());
                return resp.isSuccess()
                        ? MCPResult.fromJsonRpcResponse(resp)
                        : MCPResult.error(resp.error().toString());
            } catch (Exception e) {
                return MCPResult.error(e.getMessage());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<MCPToolInfo>> listTools() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isConnected()) connect();
                JsonRpcMessage.Response resp = sendAndWait(JsonRpcMessage.Request.listTools(), config.getToolTimeout());
                if (!resp.isSuccess()) {
                    logger.warn("listTools failed: {}", resp.error());
                    return Collections.emptyList();
                }
                return parseToolList(resp.result());
            } catch (Exception e) {
                logger.error("listTools failed: {}", e.getMessage());
                return Collections.emptyList();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<MCPResult> readResource(String uri) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isConnected()) connect();
                JsonRpcMessage.Response resp = sendAndWait(JsonRpcMessage.Request.readResource(uri), config.getToolTimeout());
                return resp.isSuccess() ? MCPResult.fromJsonRpcResponse(resp) : MCPResult.error(resp.error().toString());
            } catch (Exception e) {
                return MCPResult.error(e.getMessage());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<MCPResult> getPrompt(String promptName, Map<String, Object> arguments) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isConnected()) connect();
                JsonRpcMessage.Response resp = sendAndWait(JsonRpcMessage.Request.getPrompt(promptName, arguments), config.getToolTimeout());
                return resp.isSuccess() ? MCPResult.fromJsonRpcResponse(resp) : MCPResult.error(resp.error().toString());
            } catch (Exception e) {
                return MCPResult.error(e.getMessage());
            }
        }, executor);
    }

    // ═══════════ 生命周期 ═══════════

    @Override
    public void close() {
        closed = true;
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        if (process != null) {
            process.destroy();
            try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (process.isAlive()) process.destroyForcibly();
        }
        executor.shutdown();
    }

    @Override
    public boolean isConnected() {
        return process != null && process.isAlive() && !closed;
    }

    @Override
    public String getServerName() {
        return serverName;
    }
}
