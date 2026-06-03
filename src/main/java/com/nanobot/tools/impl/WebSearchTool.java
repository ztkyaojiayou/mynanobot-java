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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 网页搜索工具
 * ===============
 * 
 * 使用 DuckDuckGo API 进行网页搜索，返回相关搜索结果。
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
 */
public class WebSearchTool implements Tool {
    
    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String API_URL = "https://api.duckduckgo.com/";
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public WebSearchTool() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        logger.info("WebSearchTool initialized");
    }
    
    @Override
    public String getName() {
        return "web_search";
    }
    
    @Override
    public String getDescription() {
        return "使用 DuckDuckGo 搜索网页内容，获取最新信息和知识";
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
        
        logger.info("Performing web search: {}", query);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = API_URL + "?q=" + java.net.URLEncoder.encode(query, "UTF-8") + 
                            "&format=json&no_html=1&no_redirect=1";
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Nanobot/1.0")
                    .GET()
                    .build();
                
                HttpResponse<String> response = httpClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    return "搜索失败，HTTP 状态码：" + response.statusCode();
                }
                
                return parseSearchResults(response.body(), limit, query);
                
            } catch (Exception e) {
                logger.error("Web search failed", e);
                return "搜索失败：" + e.getMessage();
            }
        });
    }
    
    /**
     * 解析搜索结果
     */
    private String parseSearchResults(String jsonResponse, int limit, String query) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            
            StringBuilder result = new StringBuilder();
            result.append("搜索结果（").append(query).append("）：\n\n");
            
            // 处理 Top 结果
            if (root.has("AbstractText") && !root.get("AbstractText").asText().isBlank()) {
                result.append("【摘要】\n");
                result.append(root.get("AbstractText").asText());
                if (root.has("AbstractURL")) {
                    result.append("\n来源：").append(root.get("AbstractURL").asText()).append("\n\n");
                }
            }
            
            // 处理搜索结果列表
            if (root.has("Results") && root.get("Results").isArray()) {
                int count = 0;
                for (JsonNode resultNode : root.get("Results")) {
                    if (count >= limit) break;
                    
                    String title = resultNode.has("Text") ? resultNode.get("Text").asText() : "无标题";
                    String url = resultNode.has("FirstURL") ? resultNode.get("FirstURL").asText() : "";
                    String snippet = resultNode.has("Abstract") ? resultNode.get("Abstract").asText() : "";
                    
                    result.append(String.format("【%d】%s\n", count + 1, title));
                    if (!url.isBlank()) {
                        result.append("链接：").append(url).append("\n");
                    }
                    if (!snippet.isBlank()) {
                        result.append("摘要：").append(snippet).append("\n");
                    }
                    result.append("\n");
                    
                    count++;
                }
            }
            
            return result.toString().trim();
            
        } catch (Exception e) {
            logger.error("Failed to parse search results", e);
            return "解析搜索结果失败：" + e.getMessage();
        }
    }
}
