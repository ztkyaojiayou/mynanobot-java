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
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
    private static final String BAIDU_API_URL = "https://aip.baidubce.com/rpc/2.0/solution/v1/search";
    
    // Brave Search API
    private static final String BRAVE_API_URL = "https://api.search.brave.com/res/v1/web/search";
    
    // Bing Search API
    private static final String BING_API_URL = "https://api.bing.microsoft.com/v7.0/search";
    
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    
    // 搜索引擎配置
    private String provider = "baidu"; // baidu, brave 或 bing
    private String apiKey;
    
    public WebSearchTool() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newHttpClient();
        
        // 尝试从环境变量获取配置
        this.provider = System.getenv("SEARCH_PROVIDER");
        if (this.provider == null || this.provider.isBlank()) {
            this.provider = "baidu";
        }
        
        // 尝试从环境变量获取 API Key
        this.apiKey = System.getenv("SEARCH_API_KEY");
        if (this.apiKey == null || this.apiKey.isBlank()) {
            // 如果没有设置环境变量，尝试获取特定 provider 的 key
            if ("baidu".equalsIgnoreCase(provider)) {
                this.apiKey = System.getenv("BAIDU_API_KEY");
            } else if ("brave".equalsIgnoreCase(provider)) {
                this.apiKey = System.getenv("BRAVE_API_KEY");
            } else {
                this.apiKey = System.getenv("BING_API_KEY");
            }
        }
        
        if (this.apiKey == null || this.apiKey.isBlank()) {
            logger.warn("SEARCH_API_KEY environment variable not set, web search will be disabled");
        }
        logger.info("WebSearchTool initialized with {} provider", provider);
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
        return "使用百度搜索网页内容，获取最新信息和知识";
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
        
        // 如果没有配置 API Key，返回提示信息
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.completedFuture(
                "搜索服务未配置：\n" +
                "1. 设置环境变量 SEARCH_API_KEY=您的API密钥\n" +
                "2. 或设置 SEARCH_PROVIDER 和对应的 API Key\n" +
                "\n获取 API Key：\n" +
                "- 百度搜索: https://ai.baidu.com/tech/search\n" +
                "- Brave Search: https://brave.com/search/api/ （免费）\n" +
                "- Bing Search: https://azure.microsoft.com/zh-cn/products/cognitive-services/bing-search-api"
            );
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
                String url;
                HttpRequest request;
                
                if ("bing".equalsIgnoreCase(provider)) {
                    // 使用 Bing Search API
                    url = BING_API_URL + "?q=" + encodedQuery + "&count=" + limit;
                    request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Ocp-Apim-Subscription-Key", apiKey)
                        .header("Accept", "application/json")
                        .timeout(java.time.Duration.ofSeconds(30))
                        .GET()
                        .build();
                } else if ("baidu".equalsIgnoreCase(provider)) {
                    // 使用百度搜索 API（POST 请求）
                    url = BAIDU_API_URL + "?access_token=" + apiKey;
                    Map<String, Object> body = Map.of(
                        "query", query,
                        "page_size", limit,
                        "page_no", 1
                    );
                    String jsonBody = objectMapper.writeValueAsString(body);
                    request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .timeout(java.time.Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();
                } else {
                    // 使用 Brave Search API（默认）
                    url = BRAVE_API_URL + "?q=" + encodedQuery + "&count=" + limit;
                    request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "application/json")
                        .header("X-Subscription-Token", apiKey)
                        .timeout(java.time.Duration.ofSeconds(30))
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
     * 解析百度搜索 API 搜索结果
     */
    private String parseBaiduResults(String responseBody, int limit, String query) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            
            // 检查是否有错误
            if (root.has("error_code")) {
                String errorMsg = root.has("error_msg") ? root.get("error_msg").asText() : "未知错误";
                logger.error("Baidu API error: {}", errorMsg);
                return "搜索失败：" + errorMsg;
            }
            
            JsonNode result = root.get("result");
            if (result == null || !result.has("items")) {
                return "未找到相关搜索结果";
            }
            
            StringBuilder resultStr = new StringBuilder();
            resultStr.append("搜索结果（").append(query).append("）：\n\n");
            
            JsonNode items = result.get("items");
            int count = 0;
            for (JsonNode item : items) {
                if (count >= limit) break;
                
                String title = item.has("title") ? item.get("title").asText() : "无标题";
                String url = item.has("url") ? item.get("url").asText() : "";
                String description = item.has("description") ? item.get("description").asText() : "";
                
                resultStr.append((count + 1)).append(". ").append(title).append("\n");
                if (!url.isBlank()) {
                    resultStr.append("   链接: ").append(url).append("\n");
                }
                if (!description.isBlank()) {
                    resultStr.append("   摘要: ").append(description).append("\n");
                }
                resultStr.append("\n");
                
                count++;
            }
            
            return resultStr.toString();
            
        } catch (Exception e) {
            logger.error("Failed to parse Baidu results", e);
            return "解析搜索结果失败：" + e.getMessage();
        }
    }
}
