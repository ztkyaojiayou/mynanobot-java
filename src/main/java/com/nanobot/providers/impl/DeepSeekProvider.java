package com.nanobot.providers.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * DeepSeek 提供商实现
 * ====================
 *
 * 支持 DeepSeek 系列模型：
 * - deepseek-chat
 * - deepseek-coder
 * - deepseek-r1-chat
 *
 * API 文档：https://platform.deepseek.com/api-docs
 */
public class DeepSeekProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(DeepSeekProvider.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final String apiBase;
    private final HttpClient httpClient;

    public DeepSeekProvider(String apiKey, String model) {
        this(apiKey, model, "https://api.deepseek.com");
    }

    public DeepSeekProvider(String apiKey, String model, String apiBase) {
        this.apiKey = apiKey;
        this.model = model;
        this.apiBase = apiBase;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        logger.info("DeepSeekProvider initialized for model: {}", model);
    }

    @Override
    public String getName() {
        return "deepseek";
    }

    @Override
    public String getDefaultModel() {
        return model;
    }

    @Override
    public int getMaxTokens() {
        return 8192;
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public CompletableFuture<LLMResponse> chat(List<Message> messages, List<JsonNode> tools) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String requestBody = buildRequestBody(messages, tools);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(300))
                    .build();

                HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return parseResponse(response.body());
                } else {
                    logger.error("DeepSeek API error: {} - {}", response.statusCode(), response.body());
                    return LLMResponse.error("API request failed: " + response.statusCode(), "api_error");
                }

            } catch (Exception e) {
                logger.error("DeepSeek API request failed", e);
                return LLMResponse.error("Failed to call DeepSeek API: " + e.getMessage(), "network_error");
            }
        });
    }

    /**
     * 发送聊天请求（流式）.
     *
     * <h3>执行流程（3 步）</h3>
     * <ol>
     *   <li>构建流式请求并发送</li>
     *   <li>{@link #parseSseStream} — 解析 SSE 流，累积 content + tool_call 碎片</li>
     *   <li>从累加器构建最终 LLMResponse（tool_calls 或 text）</li>
     * </ol>
     */
    @Override
    public CompletableFuture<LLMResponse> chatStream(List<Message> messages, List<JsonNode> tools,
                                                     Consumer<String> onDelta) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // ① 构建流式请求并发送
                String requestBody = buildRequestBody(messages, tools, true);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(300))
                    .build();

                HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    return LLMResponse.error("HTTP error: " + response.statusCode() + " - " + body, "http_error");
                }

                // ② 解析 SSE 流。
                // DeepSeek 对 tool_calls.arguments 是逐字符流式返回的（不是一次给完整 JSON），
                // 例如 get_weather 的参数 {"city":"北京"} 可能分 3 个 chunk 到达：
                //   chunk1: "{\"ci"   chunk2: "ty\":\"北"   chunk3: "京\"}"
                // 所以 parseSseStream 内部需按 tool_call index 跨 delta 拼接，
                // 等流结束后再整体 parse 成 JSON。如果边收边 parse 会失败（不完整的 JSON）。
                StreamAccumulator acc = parseSseStream(response.body(), onDelta);

                // ③ 构建最终响应
                if (acc.toolCallMode) {
                    List<LLMResponse.ToolCallRequest> toolCalls = buildToolCalls(acc.accumulators);
                    if (!toolCalls.isEmpty()) {
                        return LLMResponse.toolCalls(toolCalls, model);
                    }
                }

                return LLMResponse.success(acc.content.toString(), "stop", null);

            } catch (Exception e) {
                logger.error("DeepSeek streaming request failed", e);
                return LLMResponse.error("Failed to call DeepSeek API: " + e.getMessage(), "network_error");
            }
        });
    }

    // ── chatStream 子步骤 ──

    /** ② 解析 SSE 流，返回累积的 content + tool_call 碎片 */
    private StreamAccumulator parseSseStream(InputStream body, Consumer<String> onDelta) throws Exception {
        StreamAccumulator acc = new StreamAccumulator();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || !line.startsWith("data: ")) continue;

                String json = line.substring(6);
                if ("[DONE]".equals(json)) continue;

                JsonNode chunk = objectMapper.readTree(json);
                JsonNode choices = chunk.get("choices");
                if (choices != null && choices.isArray() && choices.size() > 0) {
                    JsonNode delta = choices.get(0).get("delta");
                    if (delta != null) {
                        if (delta.has("tool_calls")) {
                            acc.toolCallMode = true;
                            parseToolCallsFromDelta(delta.get("tool_calls"), acc.accumulators);
                        }
                        if (delta.has("content")) {
                            String content = delta.get("content").asText();
                            // 两件事同时做：
                            //   acc.content.append(content)  — 累积完整响应，流结束后返回给 AgentRunner
                            //   onDelta.accept(content)       — ★实时推送核心★ 每个 token 立即发给 RunState
                            //     → buildOnDelta 包装为 OutboundMessage → publishToOutboundQueue
                            //     → Dispatcher 扇出 → SSE/CLI/WS subscriberQueues → 用户看到逐字输出
                            acc.content.append(content);
                            if (onDelta != null) onDelta.accept(content);
                        }
                    }
                }
            }
        }

        return acc;
    }

    /** SSE 流解析的中间累积状态 */
    private static class StreamAccumulator {
        final StringBuilder content = new StringBuilder();
        final Map<Integer, ToolCallAccumulator> accumulators = new HashMap<>();
        boolean toolCallMode = false;
    }

    private String buildRequestBody(List<Message> messages, List<JsonNode> tools) {
        return buildRequestBody(messages, tools, false);
    }

    private String buildRequestBody(List<Message> messages, List<JsonNode> tools, boolean stream) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("max_tokens", 8192);
            body.put("temperature", 0.7);
            body.put("stream", stream);

            List<Map<String, Object>> messagesList = new ArrayList<>();
            for (Message msg : messages) {
                Map<String, Object> msgMap = new HashMap<>();
                msgMap.put("role", msg.getRole());

                if (msg.getContent() != null) {
                    msgMap.put("content", msg.getContent());
                }

                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    List<Map<String, Object>> toolCalls = new ArrayList<>();
                    for (Message.ToolCallInfo tc : msg.getToolCalls()) {
                        Map<String, Object> toolCall = new HashMap<>();
                        toolCall.put("id", tc.getId());
                        toolCall.put("type", "function");

                        Map<String, Object> function = new HashMap<>();
                        function.put("name", tc.getName());
                        // DeepSeek API 要求 arguments 必须是字符串格式（JSON 字符串）
                        Object args = tc.getArguments();
                        if (args instanceof String) {
                            function.put("arguments", args);
                        } else {
                            try {
                                String argsJson = objectMapper.writeValueAsString(args);
                                function.put("arguments", argsJson);
                            } catch (Exception e) {
                                function.put("arguments", "{}");
                            }
                        }
                        toolCall.put("function", function);

                        toolCalls.add(toolCall);
                    }
                    msgMap.put("tool_calls", toolCalls);
                }

                if (msg.getToolCallId() != null) {
                    msgMap.put("tool_call_id", msg.getToolCallId());
                }

                messagesList.add(msgMap);
            }
            body.put("messages", messagesList);

            if (tools != null && !tools.isEmpty()) {
                body.put("tools", tools);
            }

            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request body", e);
        }
    }

    /** DeepSeek 流式工具调用参数累加器（按 index 拼接碎片） */
    private static class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder argsBuilder = new StringBuilder();

        Map<String, Object> buildArgs(ObjectMapper mapper) {
            Map<String, Object> args = new HashMap<>();
            String argsStr = argsBuilder.toString();
            if (!argsStr.isEmpty()) {
                try {
                    args = mapper.readValue(argsStr, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    // JSON 不完整，等流式结束再统一解析
                }
            }
            return args;
        }
    }

    private ToolCallAccumulator getOrCreateAcc(Map<Integer, ToolCallAccumulator> accs, int index) {
        return accs.computeIfAbsent(index, k -> new ToolCallAccumulator());
    }

    private void parseToolCallsFromDelta(JsonNode toolCallsNode,
                                          Map<Integer, ToolCallAccumulator> accumulators) {
        for (JsonNode tc : toolCallsNode) {
            int index = tc.has("index") ? tc.get("index").asInt() : 0;
            JsonNode function = tc.get("function");
            if (function == null) continue;

            ToolCallAccumulator acc = getOrCreateAcc(accumulators, index);

            if (tc.has("id") && !tc.get("id").asText().isEmpty())
                acc.id = tc.get("id").asText();
            if (function.has("name") && !function.get("name").asText().isEmpty())
                acc.name = function.get("name").asText();
            if (function.has("arguments")) {
                String fragment = function.get("arguments").asText();
                if (!fragment.isEmpty()) acc.argsBuilder.append(fragment);
            }
        }
    }

    /** 在所有 delta 处理完后，将累加器中的内容转为最终的工具调用列表 */
    private List<LLMResponse.ToolCallRequest> buildToolCalls(Map<Integer, ToolCallAccumulator> accumulators) {
        List<LLMResponse.ToolCallRequest> result = new ArrayList<>();
        accumulators.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    ToolCallAccumulator acc = e.getValue();
                    result.add(new LLMResponse.ToolCallRequest(
                            acc.id, acc.name, acc.buildArgs(objectMapper)));
                });
        return result;
    }

    private LLMResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");

            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode choice = choices.get(0);
                JsonNode message = choice.get("message");
                String finishReason = choice.has("finish_reason") ? choice.get("finish_reason").asText() : null;

                if (message != null) {
                    String content = message.has("content") ? message.get("content").asText() : null;
                    JsonNode toolCalls = message.get("tool_calls");

                    List<LLMResponse.ToolCallRequest> toolCallRequests = new ArrayList<>();
                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            String id = tc.has("id") ? tc.get("id").asText() : null;
                            JsonNode function = tc.get("function");

                            if (function != null) {
                                String name = function.has("name") ? function.get("name").asText() : null;
                                JsonNode args = function.get("arguments");

                                if (name != null) {
                                    Map<String, Object> arguments = new HashMap<>();
                                    if (args != null) {
                                        if (args.isTextual()) {
                                            try {
                                                String argsStr = args.asText();
                                                arguments = objectMapper.readValue(argsStr, new TypeReference<Map<String, Object>>() {});
                                            } catch (Exception e) {
                                                logger.warn("Failed to parse arguments string: {}", args.asText());
                                            }
                                        } else {
                                            arguments = objectMapper.convertValue(args, new TypeReference<Map<String, Object>>() {});
                                        }
                                    }

                                    toolCallRequests.add(new LLMResponse.ToolCallRequest(id, name, arguments));
                                }
                            }
                        }
                    }

                    if (!toolCallRequests.isEmpty()) {
                        return LLMResponse.toolCalls(toolCallRequests, model);
                    }

                    return LLMResponse.success(content, finishReason, null);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to parse response", e);
        }

        return LLMResponse.error("Failed to parse response", "api_error");
    }
}
