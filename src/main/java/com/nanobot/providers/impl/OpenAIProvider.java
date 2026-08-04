package com.nanobot.providers.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI API 提供商实现
 * =========================
 *
 * 本类实现了与 OpenAI API 的交互。
 * 支持 GPT-4、GPT-3.5 等模型，以及函数调用功能。
 *
 * **API 版本**：
 * - v1/chat/completions: 聊天完成 API
 *
 * **支持的模型**：
 * - gpt-4-turbo
 * - gpt-4
 * - gpt-3.5-turbo
 * - gpt-4o
 * - o1-preview
 * - o1-mini
 *
 * **功能支持**：
 * - ✓ 非流式聊天
 * - ✓ 流式聊天
 * - ✓ 函数调用（tools）
 * - ✓ JSON 模式
 * - ✓ 重试机制
 *
 * **使用示例**：
 *
 * ```java
 * // 1. 创建提供商
 * OpenAIProvider provider = new OpenAIProvider("sk-xxx", "gpt-4-turbo");
 *
 * // 2. 发送消息
 * List<Message> messages = List.of(
 *     Message.ofSystem("You are a helpful assistant."),
 *     Message.ofUser("What is the capital of France?")
 * );
 *
 * // 3. 获取响应
 * LLMResponse response = provider.chat(messages, null).join();
 * System.out.println(response.getContent());
 *
 * // 4. 流式响应
 * provider.chatStream(messages, null, delta -> {
 *     System.out.print(delta);
 * }).join();
 * ```
 */
public class OpenAIProvider extends AbstractLLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(OpenAIProvider.class);

    // ==================== 常量 ====================

    /** API 基础 URL */
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    // ==================== 配置 ====================

    private final int maxTokens;
    private final double temperature;
    private final Map<String, String> extraHeaders;
    private final Map<String, Object> extraBody;

    // ==================== 构造函数 ====================

    public OpenAIProvider(String apiKey) {
        this(apiKey, "gpt-4-turbo-preview");
    }

    public OpenAIProvider(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL);
    }

    public OpenAIProvider(String apiKey, String model, String apiBase) {
        super(apiKey, model, apiBase != null ? apiBase : DEFAULT_BASE_URL);
        this.maxTokens = 4096;
        this.temperature = 0.7;
        this.extraHeaders = Map.of();
        this.extraBody = Map.of();

        logger.info("OpenAIProvider initialized: model={}, baseUrl={}", this.model, this.apiBase);
    }

    @Override protected String defaultBaseUrl() { return DEFAULT_BASE_URL; }

    /**
     * 创建 OpenAI 提供商（使用配置对象）
     */
    public OpenAIProvider(ProviderConfig config) {
        this(
            config.getApiKey(),
            config.getModel() != null ? config.getModel() : "gpt-4-turbo-preview",
            config.getApiBase()
        );
        // 注意：这里可以进一步使用 config 中的其他配置
    }

    // ==================== 接口实现 ====================

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public String getDefaultModel() {
        return model;
    }

    @Override
    public int getMaxTokens() {
        return maxTokens;
    }

    @Override
    public boolean supportsTools() {
        // o1 模型不支持 tools
        return !model.startsWith("o1");
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String getToolCallFormat() {
        return "openai";
    }

    // ==================== 聊天方法 ====================

    /**
     * 发送聊天请求（非流式）
     */
    @Override
    public CompletableFuture<LLMResponse> chat(List<Message> messages, List<JsonNode> tools) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ObjectNode requestBody = buildRequestBody(messages, tools, false);
                String endpoint = apiBase + "/chat/completions";
                HttpRequest request = buildRequest(endpoint, requestBody);
                HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
                return parseResponse(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return LLMResponse.error("Request interrupted", "interrupted");
            } catch (Exception e) {
                return handleException(e);
            }
        });
    }

    /**
     * 发送聊天请求（流式）.
     *
     * <h3>执行流程（3 步）</h3>
     * <ol>
     *   <li>构建流式请求并发送</li>
     *   <li>{@link #parseSseStream} — 解析 SSE 流，累积 content/tool_calls/usage</li>
     *   <li>根据累积结果构建 LLMResponse（tool_calls 或 text）</li>
     * </ol>
     */
    @Override
    public CompletableFuture<LLMResponse> chatStream(
            List<Message> messages,
            List<JsonNode> tools,
            Consumer<String> onDelta) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // ① 构建流式请求并发送
                ObjectNode requestBody = buildRequestBody(messages, tools, true);
                String endpoint = apiBase + "/chat/completions";
                HttpRequest request = buildRequest(endpoint, requestBody);

                HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    logger.warn("OpenAI API returned {}: {}",
                            response.statusCode(), body.length() > 200 ? body.substring(0, 200) : body);
                    return LLMResponse.httpError(response.statusCode(), body);
                }

                // ② 解析 SSE 流
                StreamAccumulator acc = parseSseStream(response.body(), onDelta);

                // ③ 构建最终响应
                if (acc.toolCallMode && !acc.toolCalls.isEmpty()) {
                    return LLMResponse.toolCalls(acc.toolCalls, model);
                } else {
                    return LLMResponse.success(
                        acc.content.toString(),
                        acc.finishReason != null ? acc.finishReason : "stop",
                        acc.usage
                    );
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return LLMResponse.error("Streaming interrupted", "interrupted");
            } catch (Exception e) {
                logger.error("OpenAI streaming request failed: {}", e.getMessage());
                return handleException(e);
            }
        });
    }

    // ── chatStream 子步骤 ──

    /**
     * ② 解析 SSE（Server-Sent Events）流，累积所有 delta 到 StreamAccumulator.
     *
     * <p>处理 OpenAI 的流式响应格式：
     * <pre>
     *   data: {"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}
     *   data: {"choices":[{"delta":{"tool_calls":[...]}}]}
     *   data: {"usage":{"prompt_tokens":10,"completion_tokens":5}}
     *   data: [DONE]
     * </pre>
     */
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
                processStreamChunk(chunk, acc, onDelta);
            }
        }

        return acc;
    }

    /** 处理单个 SSE chunk，提取 delta/content/tool_calls/usage 到累加器 */
    private void processStreamChunk(JsonNode chunk, StreamAccumulator acc, Consumer<String> onDelta) {
        JsonNode choices = chunk.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode choice = choices.get(0);

            // delta：文本内容 + 工具调用
            JsonNode delta = choice.get("delta");
            if (delta != null) {
                if (delta.has("tool_calls")) {
                    acc.toolCallMode = true;
                    parseToolCalls(delta.get("tool_calls"), acc.toolCalls);
                }
                if (delta.has("content")) {
                    String content = delta.get("content").asText();
                    // 两件事同时做：
                    //   acc.content.append(content)  — 累积完整响应，流结束后作为最终结果返回给 AgentRunner
                    //   onDelta.accept(content)       — 实时推送给订阅者（SSE/CLI/WS），不等流结束
                    // 两个操作操作的是同一个 content 字符串引用，不是两份数据。
                    acc.content.append(content);
                    if (onDelta != null) onDelta.accept(content);
                }
            }

            // finish_reason
            if (choice.has("finish_reason")) {
                acc.finishReason = choice.get("finish_reason").asText();
            }
        }

        // usage（通常在最后一个 chunk 中）
        if (chunk.has("usage")) {
            parseUsage(chunk.get("usage"), acc.usage);
        }
    }

    /** SSE 流解析的中间累积状态 */
    private static class StreamAccumulator {
        final StringBuilder content = new StringBuilder();
        final List<LLMResponse.ToolCallRequest> toolCalls = new ArrayList<>();
        final Map<String, Integer> usage = new HashMap<>();
        String finishReason = null;
        boolean toolCallMode = false;
    }

    // ==================== 请求构建 ====================

    /**
     * 构建请求体
     */
    private ObjectNode buildRequestBody(List<Message> messages, List<JsonNode> tools, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();

        // 模型
        body.put("model", model);

        // 消息
        ArrayNode messagesArray = body.putArray("messages");
        for (Message msg : messages) {
            messagesArray.add(objectMapper.valueToTree(msg.toMap()));
        }

        // 工具
        if (tools != null && !tools.isEmpty() && supportsTools()) {
            ObjectNode toolsObj = body.putObject("tools");
            ArrayNode toolArray = toolsObj.putArray("tools");
            for (JsonNode tool : tools) {
                // 提取 function 对象
                if (tool.has("function")) {
                    toolArray.add(tool.get("function"));
                } else {
                    toolArray.add(tool);
                }
            }
        }

        // 流式
        body.put("stream", stream);

        // 参数
        if (maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }

        // o1 模型不支持 temperature
        if (!model.startsWith("o1")) {
            body.put("temperature", (double) temperature);
        }

        // 额外参数
        for (Map.Entry<String, Object> entry : extraBody.entrySet()) {
            body.putPOJO(entry.getKey(), entry.getValue());
        }

        return body;
    }

    /**
     * 构建 HTTP 请求
     */
    private HttpRequest buildRequest(String endpoint, ObjectNode body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()));

        // 添加额外头
        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        return builder.build();
    }

    // ==================== 响应解析 ====================

    /**
     * 解析响应 — 提取 choice 数据 → 构建 LLMResponse.
     */
    private LLMResponse parseResponse(HttpResponse<String> response) {
        int statusCode = response.statusCode();
        String body = response.body();
        if (statusCode != 200) return parseError(body, statusCode);

        try {
            JsonNode json = objectMapper.readTree(body);

            // ① 提取 choices[0] 中的 content + tool_calls + finish_reason
            ParsedChoice choice = extractChoiceData(json);

            // ② 提取 usage
            Map<String, Integer> usage = new HashMap<>();
            if (json.has("usage")) parseUsage(json.get("usage"), usage);

            // ③ 构建响应
            if (!choice.toolCalls().isEmpty()) {
                return LLMResponse.toolCalls(choice.toolCalls(), model);
            }
            return LLMResponse.success(choice.content(), choice.finishReason(), usage);

        } catch (Exception e) {
            return LLMResponse.error("Failed to parse response: " + e.getMessage(), "parse_error");
        }
    }

    /** ① 从 JSON 根节点提取 choices[0].message 中的 content/tool_calls/finish_reason */
    private ParsedChoice extractChoiceData(JsonNode json) {
        String content = null;
        List<LLMResponse.ToolCallRequest> toolCalls = new ArrayList<>();
        String finishReason = null;

        JsonNode choices = json.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode choice = choices.get(0);

            JsonNode message = choice.get("message");
            if (message != null) {
                if (message.has("content")) content = message.get("content").asText();
                if (message.has("tool_calls")) parseToolCalls(message.get("tool_calls"), toolCalls);
            }
            if (choice.has("finish_reason")) finishReason = choice.get("finish_reason").asText();
        }

        return new ParsedChoice(content, toolCalls, finishReason);
    }

    /** 解析后的 choice 数据容器 */
    private record ParsedChoice(String content, List<LLMResponse.ToolCallRequest> toolCalls, String finishReason) {}

    /**
     * 解析工具调用
     */
    private void parseToolCalls(JsonNode toolCallsNode, List<LLMResponse.ToolCallRequest> toolCalls) {
        for (JsonNode tc : toolCallsNode) {
            String id = tc.has("id") ? tc.get("id").asText() : null;
            String name = null;
            Map<String, Object> arguments = new HashMap<>();

            if (tc.has("function")) {
                name = tc.get("function").get("name").asText();
                String argsStr = tc.get("function").get("arguments").asText();

                // 解析 JSON 参数
                try {
                    JsonNode argsNode = objectMapper.readTree(argsStr);
                    argsNode.fields().forEachRemaining(entry ->
                        arguments.put(entry.getKey(), parseJsonValue(entry.getValue())));
                } catch (Exception e) {
                    // 解析失败，尝试作为字符串处理
                    arguments.put("__raw", argsStr);
                }
            }

            if (name != null) {
                toolCalls.add(new LLMResponse.ToolCallRequest(id, name, arguments));
            }
        }
    }

    /**
     * 解析 JSON 值
     */
    private Object parseJsonValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        } else if (node.isNumber()) {
            return node.numberValue();
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(n -> list.add(parseJsonValue(n)));
            return list;
        } else if (node.isObject()) {
            Map<String, Object> map = new HashMap<>();
            node.fields().forEachRemaining(entry ->
                map.put(entry.getKey(), parseJsonValue(entry.getValue())));
            return map;
        }
        return node.toString();
    }

    /**
     * 解析 usage
     */
    private void parseUsage(JsonNode usageNode, Map<String, Integer> usage) {
        if (usageNode.has("prompt_tokens")) {
            usage.put("promptTokens", usageNode.get("prompt_tokens").asInt());
        }
        if (usageNode.has("completion_tokens")) {
            usage.put("completionTokens", usageNode.get("completion_tokens").asInt());
        }
        if (usageNode.has("total_tokens")) {
            usage.put("totalTokens", usageNode.get("total_tokens").asInt());
        }
    }

    /**
     * 解析错误响应
     */
    private LLMResponse parseError(String body, int statusCode) {
        try {
            JsonNode error = objectMapper.readTree(body).get("error");
            String message = error != null && error.has("message")
                ? error.get("message").asText()
                : "Unknown error";

            String kind;
            if (statusCode == 401) {
                kind = "authentication_error";
            } else if (statusCode == 429) {
                kind = "rate_limit";
            } else if (statusCode >= 500) {
                kind = "server_error";
            } else {
                kind = "invalid_request";
            }

            return LLMResponse.error(message, kind);

        } catch (Exception e) {
            return LLMResponse.error("HTTP " + statusCode + ": " + body, "http_error");
        }
    }

    /**
     * 处理异常
     */
    private LLMResponse handleException(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return LLMResponse.error("Request interrupted", "interrupted");
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return LLMResponse.timeout(120.0);
        } else if (e instanceof java.net.ConnectException) {
            return LLMResponse.error("Connection failed: " + e.getMessage(), "network_error");
        }
        return LLMResponse.error(e.getMessage(), "unknown_error");
    }

    @Override
    public boolean isRetryable(Throwable error) {
        if (error instanceof java.net.http.HttpTimeoutException) {
            return true;
        }
        String message = error.getMessage();
        if (message != null) {
            // 速率限制或服务器错误可重试
            if (message.contains("429") || message.contains("500") || message.contains("502")) {
                return true;
            }
        }
        return false;
    }
}
