package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * 网页搜索工具
 * ===============
 * 
 * 支持多种搜索引擎：
 * 1. 百度搜索 API（Baidu Search）
 * 2. Brave Search API（推荐，免费且不需要信用卡）
 * 3. Bing Search API
 * 
 * **使用示例**：
 * ```java
 * WebSearchTool searchTool = new WebSearchTool();
 * searchTool.execute(Map.of("query", "Java 21 新特性"));
 * ```
 * 
 * **参数**：
 * - query: 搜索查询词（必填）
 * - limit: 返回结果数量（可选，默认 5）
 * 
 * **配置**：
 * 在 config.yaml 中配置搜索 API：
 * ```yaml
 * tools:
 *   web:
 *     provider: "baidu"  # baidu, brave 或 bing
 *     apiKey: "your-api-key"
 * ```
 * 
 * **获取 API Key**：
 * - 百度搜索: https://ai.baidu.com/tech/search
 * - Brave Search: https://brave.com/search/api/
 * - Bing Search: https://azure.microsoft.com/zh-cn/products/cognitive-services/bing-search-api
 */
public class WebSearchTool implements Tool {
    
    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    
    // 百度搜索 API
    private static final String BAIDU_API_URL = "https://qianfan.baidubce.com/v2/ai_search/chat/completions";
    
    // 百度搜索公开接口（国内可访问，无需 API Key）
    private static final String BAIDU_WEB_URL = "https://www.baidu.com/s";
    
    // Brave Search API
    private static final String BRAVE_API_URL = "https://api.search.brave.com/res/v1/web/search";
    
    // Bing Search API
    private static final String BING_API_URL = "https://api.bing.microsoft.com/v7.0/search";
    private static final String DUCKDUCKGO_URL = "https://lite.duckduckgo.com/lite/";
    
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    
    // 搜索引擎配置
    private String provider = "baidu"; // baidu, baidu_web, brave 或 bing
    private String apiKey;
    
    public WebSearchTool() {
        this(null, null);
    }
    
    /**
     * 使用指定的 provider 和 apiKey 创建 WebSearchTool
     */
    public WebSearchTool(String provider, String apiKey) {
        this.objectMapper = new ObjectMapper();
        this.httpClient = createInsecureHttpClient();
        
        // 优先级：构造函数参数 > 环境变量 > 默认值
        if (provider != null && !provider.isBlank()) {
            this.provider = provider;
        } else {
            this.provider = System.getenv("SEARCH_PROVIDER");
            if (this.provider == null || this.provider.isBlank()) {
                this.provider = "baidu";
            }
        }
        
        if (apiKey != null && !apiKey.isBlank()) {
            this.apiKey = apiKey;
        } else {
            this.apiKey = System.getenv("SEARCH_API_KEY");
            if (this.apiKey == null || this.apiKey.isBlank()) {
                if ("baidu".equalsIgnoreCase(this.provider)) {
                    this.apiKey = System.getenv("BAIDU_API_KEY");
                } else if ("brave".equalsIgnoreCase(this.provider)) {
                    this.apiKey = System.getenv("BRAVE_API_KEY");
                } else {
                    this.apiKey = System.getenv("BING_API_KEY");
                }
            }
        }
        
        if (this.apiKey == null || this.apiKey.isBlank()) {
            logger.warn("SEARCH_API_KEY not configured, web search will be disabled");
        }
        logger.info("WebSearchTool initialized with {} provider", this.provider);
    }
    
    /**
     * 创建一个不验证 SSL 证书的 HttpClient（仅用于开发/测试环境）
     */
    private HttpClient createInsecureHttpClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509ExtendedTrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) {}
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) {}
                    @Override
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };
            
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            
            return HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            logger.warn("Failed to create insecure HTTP client, falling back to default: {}", e.getMessage());
            return HttpClient.newHttpClient();
        }
    }
    
    /**
     * 设置搜索引擎提供商
     */
    public void setProvider(String provider) {
        this.provider = provider;
    }
    
    /**
     * 设置 API Key
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
    
    @Override
    public String getName() {
        return "web_search";
    }
    
    @Override
    public String getDescription() {
        return "Search the web via Baidu. Returns titles, URLs, and snippets. "
             + "Use for recent events, documentation, or current information. "
             + "Combine with web_fetch to read full pages from the results.";
    }
    
    @Override
    public JsonNode getParameters() {
        com.fasterxml.jackson.databind.node.ObjectNode properties = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode queryNode = objectMapper.createObjectNode();
        queryNode.put("type", "string");
        queryNode.put("description", "搜索查询词");
        properties.set("query", queryNode);
        
        com.fasterxml.jackson.databind.node.ObjectNode limitNode = objectMapper.createObjectNode();
        limitNode.put("type", "integer");
        limitNode.put("description", "返回结果数量，默认 5");
        properties.set("limit", limitNode);
        
        com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        root.set("properties", properties);
        root.set("required", objectMapper.createArrayNode().add("query"));
        
        return root;
    }
    
    @Override
    public boolean isReadOnly() {
        return true;
    }
    
    @Override
    public boolean isExclusive() {
        return false;
    }
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        String query = (String) params.get("query");
        int limit = params.containsKey("limit") ? ((Number) params.get("limit")).intValue() : 5;
        
        if (query == null || query.isBlank()) {
            return CompletableFuture.completedFuture("错误：搜索查询词不能为空");
        }
        
        logger.info("Performing web search with {}: {}", provider, query);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
                String url;
                HttpRequest request;
                
                if ("bing".equalsIgnoreCase(provider)) {
                    // 使用 Bing Search API
                    if (apiKey == null || apiKey.isBlank()) {
                        return "Bing 搜索需要配置 API Key\n获取方式: https://azure.microsoft.com/zh-cn/products/cognitive-services/bing-search-api";
                    }
                    url = BING_API_URL + "?q=" + encodedQuery + "&count=" + limit;
                    request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Ocp-Apim-Subscription-Key", apiKey)
                        .header("Accept", "application/json")
                        .timeout(java.time.Duration.ofSeconds(60))
                        .GET()
                        .build();
                } else if ("baidu".equalsIgnoreCase(provider)) {
                    // 百度千帆 AI 搜索（OpenAI 兼容协议，Bearer 鉴权）
                    if (apiKey == null || apiKey.isBlank()) {
                        return "百度 AI 搜索需要配置 API Key\n获取方式: https://console.bce.baidu.com/qianfan";
                    }
                    url = BAIDU_API_URL;
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("messages", java.util.List.of(Map.of("role", "user", "content", query)));
                    body.put("model", "ernie-4.5-turbo-32k");
                    body.put("stream", false);
                    body.put("enable_deep_search", true);
                    String jsonBody = objectMapper.writeValueAsString(body);
                    request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(java.time.Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();
                } else if ("duckduckgo".equalsIgnoreCase(provider)) {
                    // DuckDuckGo 免费公开接口（全球可用）
                    url = DUCKDUCKGO_URL + "?q=" + encodedQuery;
                    request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .timeout(java.time.Duration.ofSeconds(60))
                        .GET()
                        .build();
                } else if ("baidu_web".equalsIgnoreCase(provider)) {
                    // 使用百度搜索公开接口（国内可访问，无需 API Key）
                    url = BAIDU_WEB_URL + "?wd=" + encodedQuery + "&pn=0";
                    request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .header("Accept-Encoding", "gzip, deflate")
                        .timeout(java.time.Duration.ofSeconds(60))
                        .GET()
                        .build();
                } else {
                    // 使用 Brave Search API（默认）
                    if (apiKey == null || apiKey.isBlank()) {
                        return "Brave 搜索需要配置 API Key\n获取方式: https://brave.com/search/api/ （免费）\n或者使用 baidu_web 模式（无需 API Key）";
                    }
                    url = BRAVE_API_URL + "?q=" + encodedQuery + "&count=" + limit;
                    request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "application/json")
                        .header("X-Subscription-Token", apiKey)
                        .timeout(java.time.Duration.ofSeconds(60))
                        .GET()
                        .build();
                }
                
                logger.debug("Fetching URL: {}", url);
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                logger.debug("Response status: {}", response.statusCode());
                
                if (response.statusCode() == 200) {
                    if ("bing".equalsIgnoreCase(provider)) {
                        return parseBingResults(response.body(), limit, query);
                    } else if ("baidu".equalsIgnoreCase(provider)) {
                        return parseBaiduResults(response.body(), limit, query);
                    } else if ("baidu_web".equalsIgnoreCase(provider)) {
                        return parseBaiduWebResults(response.body(), limit, query);
                    } else if ("duckduckgo".equalsIgnoreCase(provider)) {
                        return parseDuckDuckGoResults(response.body(), limit, query);
                    } else {
                        return parseBraveResults(response.body(), limit, query);
                    }
                } else {
                    logger.error("{} API error: {} - {}", provider.toUpperCase(), response.statusCode(), response.body());
                    return "搜索失败：API 返回错误 - " + response.statusCode();
                }
                
            } catch (java.net.ConnectException e) {
                logger.error("Web search failed - connection refused: {}", e.getMessage());
                return "网络连接失败：无法连接到搜索服务，请检查网络设置";
            } catch (java.net.SocketTimeoutException e) {
                logger.error("Web search failed - timeout");
                return "搜索超时：请求搜索服务超时，请稍后重试";
            } catch (java.io.IOException e) {
                logger.error("Web search failed - IO error: {}", e.getMessage());
                return "搜索失败：网络IO错误 - " + e.getMessage();
            } catch (Exception e) {
                logger.error("Web search failed", e);
                return "搜索失败：" + e.getMessage();
            }
        });
    }
    
    /**
     * 解析 Bing API 搜索结果
     */
    private String parseBingResults(String responseBody, int limit, String query) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode webPages = root.get("webPages");
            
            if (webPages == null || !webPages.has("value")) {
                return "未找到相关搜索结果";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("搜索结果（").append(query).append("）：\n\n");
            
            JsonNode results = webPages.get("value");
            int count = 0;
            for (JsonNode item : results) {
                if (count >= limit) break;
                
                String title = item.has("name") ? item.get("name").asText() : "无标题";
                String url = item.has("url") ? item.get("url").asText() : "";
                String snippet = item.has("snippet") ? item.get("snippet").asText() : "";
                
                result.append((count + 1)).append(". ").append(title).append("\n");
                if (!url.isBlank()) {
                    result.append("   链接: ").append(url).append("\n");
                }
                if (!snippet.isBlank()) {
                    result.append("   摘要: ").append(snippet).append("\n");
                }
                result.append("\n");
                
                count++;
            }
            
            return result.toString();
            
        } catch (Exception e) {
            logger.error("Failed to parse Bing results", e);
            return "解析搜索结果失败：" + e.getMessage();
        }
    }
    
    /**
     * 解析 Brave Search API 搜索结果
     */
    private String parseBraveResults(String responseBody, int limit, String query) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode web = root.get("web");
            
            if (web == null || !web.has("results")) {
                return "未找到相关搜索结果";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("搜索结果（").append(query).append("）：\n\n");
            
            JsonNode results = web.get("results");
            int count = 0;
            for (JsonNode item : results) {
                if (count >= limit) break;
                
                String title = item.has("title") ? item.get("title").asText() : "无标题";
                String url = item.has("url") ? item.get("url").asText() : "";
                String description = item.has("description") ? item.get("description").asText() : "";
                
                result.append((count + 1)).append(". ").append(title).append("\n");
                if (!url.isBlank()) {
                    result.append("   链接: ").append(url).append("\n");
                }
                if (!description.isBlank()) {
                    result.append("   摘要: ").append(description).append("\n");
                }
                result.append("\n");
                
                count++;
            }
            
            return result.toString();
            
        } catch (Exception e) {
            logger.error("Failed to parse Brave results", e);
            return "解析搜索结果失败：" + e.getMessage();
        }
    }
    
    /**
     * 解析百度搜索网页结果（HTML格式） — 使用 Jsoup 通用解析，不依赖特定 class 名。
     */
    private String parseBaiduWebResults(String responseBody, int limit, String query) {
        try {
            StringBuilder result = new StringBuilder();
            result.append("搜索结果（").append(query).append("）：\n\n");

            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(responseBody);
            // 百度搜索结果通常在 h3 > a 中，提取所有带链接的 h3
            var headings = doc.select("h3 a[href]");
            int count = 0;
            for (var a : headings) {
                if (count >= limit) break;
                String title = a.text().trim();
                String url = a.attr("href");
                if (title.isEmpty() || title.length() < 2) continue;

                // 提取摘要：h3 的父容器中找 text
                String snippet = "";
                var parent = a.parent(); // h3
                if (parent != null) {
                    var container = parent.parent();
                    if (container != null) {
                        snippet = container.text().replace(title, "").trim();
                        // 截短
                        if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
                    }
                }

                result.append(++count).append(". ").append(title).append("\n");
                if (!url.isEmpty()) result.append("   链接: ").append(url).append("\n");
                if (!snippet.isEmpty()) result.append("   摘要: ").append(snippet).append("\n");
                result.append("\n");
            }

            return count > 0 ? result.toString() : "未找到相关搜索结果（百度可能已改版，可尝试切到 duckduckgo）";
        } catch (Exception e) {
            logger.error("Failed to parse Baidu web results", e);
            return "解析搜索结果失败：" + e.getMessage();
        }
    }

    /**
     * 解析 DuckDuckGo HTML 搜索结果（免费，无需 API Key）
     */
    private String parseDuckDuckGoResults(String responseBody, int limit, String query) {
        try {
            StringBuilder result = new StringBuilder();
            result.append("搜索结果（").append(query).append("）：\n\n");
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(responseBody);

            int count = 0;
            // lite.duckduckgo.com 格式：每个结果是 <a> 链接 + 后续 <td> 摘要
            var links = doc.select("a.result-link, a[rel=nofollow]");
            if (links.isEmpty()) links = doc.select("table tr td a[href^=http]");

            for (var a : links) {
                if (count >= limit) break;
                String title = a.text().trim();
                String url = a.attr("href");
                if (title.isEmpty() || title.length() < 2) continue;

                // 提取摘要：找最近的包含 text 的兄弟元素
                String snippet = "";
                var row = a.parent();
                if (row != null) {
                    var nextRow = row.parent() != null ? row.parent().nextElementSibling() : null;
                    if (nextRow != null) {
                        snippet = nextRow.text().trim();
                        if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
                    }
                }

                result.append(++count).append(". ").append(title).append("\n");
                if (!url.isEmpty()) result.append("   链接: ").append(url).append("\n");
                if (!snippet.isEmpty() && !snippet.equals(title))
                    result.append("   摘要: ").append(snippet).append("\n");
                result.append("\n");
            }
            return count > 0 ? result.toString() : "未找到相关搜索结果";
        } catch (Exception e) {
            return "DuckDuckGo 搜索失败: " + e.getMessage();
        }
    }

    /**
     * 清理 HTML 标签
     */
    private String cleanHtml(String html) {
        if (html == null) return "";
        // 移除 HTML 标签
        String text = html.replaceAll("<[^>]*>", "");
        // 移除 HTML 实体
        text = text.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&apos;", "'")
                   .trim();
        return text;
    }
    
    /**
     * 解析百度搜索 API 搜索结果
     */
    private String parseBaiduResults(String responseBody, int limit, String query) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 错误检查（OpenAI 兼容格式）
            if (root.has("error")) {
                String err = root.get("error").has("message") ? root.get("error").get("message").asText() : root.get("error").asText();
                logger.error("Baidu API error: {}", err);
                throw new Exception("API错误: " + err);
            }

            // 提取 references（结构化搜索结果）
            JsonNode refs = root.get("references");
            if (refs == null || !refs.isArray() || refs.size() == 0) {
                // 无 references 时尝试返回 AI 回答
                JsonNode choices = root.get("choices");
                if (choices != null && choices.size() > 0) {
                    JsonNode msg = choices.get(0).get("message");
                    if (msg != null && msg.has("content")) {
                        return "搜索结果（" + query + "）：\n\n" + msg.get("content").asText();
                    }
                }
                return "未找到相关搜索结果";
            }

            StringBuilder result = new StringBuilder();
            result.append("搜索结果（").append(query).append("）：\n\n");
            int count = 0;
            for (JsonNode ref : refs) {
                if (count >= limit) break;
                String title = ref.has("title") ? ref.get("title").asText().trim() : "";
                String url = ref.has("url") ? ref.get("url").asText().trim() : "";
                String snippet = ref.has("snippet") ? ref.get("snippet").asText().trim() : "";
                if (title.isEmpty()) continue;

                result.append(++count).append(". ").append(title).append("\n");
                if (!url.isEmpty()) result.append("   链接: ").append(url).append("\n");
                if (!snippet.isEmpty()) result.append("   摘要: ").append(snippet).append("\n");
                result.append("\n");
            }

            // 追加 AI 总结
            JsonNode choices = root.get("choices");
            if (choices != null && choices.size() > 0) {
                JsonNode msg = choices.get(0).get("message");
                if (msg != null && msg.has("content")) {
                    result.append("AI 总结: ").append(msg.get("content").asText()).append("\n");
                }
            }

            return count > 0 ? result.toString() : "未找到相关搜索结果";
        } catch (Exception e) {
            logger.error("Failed to parse Baidu results", e);
            throw e;
        }
    }
}
