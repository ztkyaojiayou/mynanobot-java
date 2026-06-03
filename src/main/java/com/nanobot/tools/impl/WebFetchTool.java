package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.tools.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 网页内容抓取工具
 * =================
 * 
 * 获取指定 URL 的网页内容，提取文本信息。
 * 
 * **使用示例**：
 * ```java
 * WebFetchTool fetchTool = new WebFetchTool();
 * fetchTool.execute(Map.of("url", "https://example.com"));
 * ```
 * 
 * **参数**：
 * - url: 网页 URL（必填）
 * - max_length: 最大返回字符数（可选，默认 3000）
 */
public class WebFetchTool implements Tool {
    
    private static final Logger logger = LoggerFactory.getLogger(WebFetchTool.class);
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private static final int DEFAULT_MAX_LENGTH = 3000;
    
    public WebFetchTool() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        logger.info("WebFetchTool initialized");
    }
    
    @Override
    public String getName() {
        return "web_fetch";
    }
    
    @Override
    public String getDescription() {
        return "获取指定 URL 的网页内容，提取纯文本信息";
    }
    
    @Override
    public JsonNode getParameters() {
        com.fasterxml.jackson.databind.node.ObjectNode properties = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode urlNode = objectMapper.createObjectNode();
        urlNode.put("type", "string");
        urlNode.put("description", "网页 URL");
        properties.set("url", urlNode);
        
        com.fasterxml.jackson.databind.node.ObjectNode maxLenNode = objectMapper.createObjectNode();
        maxLenNode.put("type", "integer");
        maxLenNode.put("description", "最大返回字符数，默认 3000");
        properties.set("max_length", maxLenNode);
        
        com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        root.set("properties", properties);
        root.set("required", objectMapper.createArrayNode().add("url"));
        
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
        String url = (String) params.get("url");
        int maxLength = params.containsKey("max_length") ? 
            ((Number) params.get("max_length")).intValue() : DEFAULT_MAX_LENGTH;
        
        if (url == null || url.isBlank()) {
            return CompletableFuture.completedFuture("错误：URL 不能为空");
        }
        
        // 验证 URL 格式
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return CompletableFuture.completedFuture("错误：URL 必须以 http:// 或 https:// 开头");
        }
        
        logger.info("Fetching web page: {}", url);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Nanobot/1.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();
                
                HttpResponse<String> response = httpClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    return "获取网页失败，HTTP 状态码：" + response.statusCode();
                }
                
                return extractContent(response.body(), url, maxLength);
                
            } catch (Exception e) {
                logger.error("Web fetch failed", e);
                return "获取网页失败：" + e.getMessage();
            }
        });
    }
    
    /**
     * 提取网页内容
     */
    private String extractContent(String html, String url, int maxLength) {
        try {
            Document doc = Jsoup.parse(html);
            
            // 获取标题
            String title = doc.title();
            
            // 移除不需要的元素
            doc.select("script, style, iframe, nav, footer, header, aside, form").remove();
            
            // 提取正文
            String bodyText = doc.body().text();
            
            // 如果正文为空，尝试提取 article 或 main 标签
            if (bodyText.isBlank()) {
                Element article = doc.selectFirst("article, main, .article, .content");
                if (article != null) {
                    bodyText = article.text();
                }
            }
            
            // 如果还是为空，使用干净的 HTML 文本
            if (bodyText.isBlank()) {
                bodyText = Jsoup.clean(html, Safelist.none());
            }
            
            // 限制长度
            if (bodyText.length() > maxLength) {
                bodyText = bodyText.substring(0, maxLength) + "...（内容已截断）";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("【网页标题】\n").append(title).append("\n\n");
            result.append("【网页链接】\n").append(url).append("\n\n");
            result.append("【网页内容】\n").append(bodyText);
            
            return result.toString();
            
        } catch (Exception e) {
            logger.error("Failed to extract content", e);
            return "解析网页内容失败：" + e.getMessage();
        }
    }
}
