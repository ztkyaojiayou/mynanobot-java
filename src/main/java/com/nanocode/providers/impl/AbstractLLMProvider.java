package com.nanocode.providers.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanocode.providers.LLMProvider;
import com.nanocode.providers.LLMResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * LLM Provider 抽象基类 — 提供共享的 HTTP 基础设施.
 *
 * <p>子类只需实现 {@link #buildRequestBody}、{@link #chat}、{@link #chatStream}，
 * 不需要重复构建 HttpClient、设置通用请求头、或格式化 HTTP 错误。
 */
public abstract class AbstractLLMProvider implements LLMProvider {

    protected static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    protected static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);

    protected final String apiKey;
    protected final String apiBase;
    protected final String model;
    protected final HttpClient httpClient;
    protected final ObjectMapper objectMapper;

    protected AbstractLLMProvider(String apiKey, String model, String apiBase) {
        this.apiKey = Objects.requireNonNull(apiKey, "API key cannot be null");
        this.model = Objects.requireNonNull(model, "Model cannot be null");
        this.apiBase = apiBase != null && !apiBase.isBlank() ? apiBase : defaultBaseUrl();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /** 子类覆盖以提供默认 API 地址 */
    protected abstract String defaultBaseUrl();

    @Override
    public String getName() { return model; }

    // ═══════════ 共享 HTTP 工具 ═══════════

    /** 构建带标准头的 HTTP 请求 */
    protected HttpRequest buildPostRequest(String endpoint, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(REQUEST_TIMEOUT)
                .build();
    }

    /** 发送非流式请求并返回响应体字符串 */
    protected HttpResponse<String> sendSync(String endpoint, String requestBody) throws Exception {
        HttpRequest request = buildPostRequest(endpoint, requestBody);
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 发送流式请求并返回 InputStream */
    protected HttpResponse<java.io.InputStream> sendStreaming(String endpoint, String requestBody) throws Exception {
        HttpRequest request = buildPostRequest(endpoint, requestBody);
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    /** 检查非 200 状态码并返回友好的错误响应 */
    protected LLMResponse checkHttpStatus(HttpResponse<?> response) {
        if (response.statusCode() != 200) {
            String body = "";
            try {
                Object respBody = response.body();
                if (respBody instanceof java.io.InputStream is) {
                    body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                } else if (respBody instanceof String s) {
                    body = s;
                }
            } catch (Exception ignored) { /* ignore read failure */ }
            return LLMResponse.httpError(response.statusCode(), body);
        }
        return null; // OK
    }
}
