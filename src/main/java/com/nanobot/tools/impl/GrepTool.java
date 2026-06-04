package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.tools.Tool;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Grep 内容搜索工具
 * ==================
 * 
 * 在文件中搜索匹配的行。
 * 
 * 参数：
 * - pattern: 搜索模式（必填）
 * - path: 文件路径或目录（必填）
 * - recursive: 是否递归（可选）
 * - caseSensitive: 是否大小写敏感（可选）
 */
public class GrepTool implements Tool {
    
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Path basePath;
    
    public GrepTool(String basePath) {
        this.basePath = Paths.get(basePath);
    }
    
    @Override
    public String getName() {
        return "grep";
    }
    
    @Override
    public String getDescription() {
        return "Search for text pattern in files. Returns matching lines with line numbers.";
    }
    
    @Override
    public JsonNode getParameters() {
        ObjectNode props = mapper.createObjectNode();
        props.put("type", "object");
        
        ObjectNode properties = mapper.createObjectNode();
        properties.putObject("pattern")
            .put("type", "string")
            .put("description", "Search pattern (regex supported)");
        
        properties.putObject("path")
            .put("type", "string")
            .put("description", "File or directory to search");
        
        properties.putObject("recursive")
            .put("type", "boolean")
            .put("description", "Search recursively in directories");
        
        properties.putObject("caseSensitive")
            .put("type", "boolean")
            .put("description", "Case sensitive search");
        
        props.set("properties", properties);
        props.putArray("required").add("pattern").add("path");
        
        return props;
    }
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String pattern = (String) params.get("pattern");
            String pathStr = (String) params.getOrDefault("path", ".");
            Boolean recursive = (Boolean) params.getOrDefault("recursive", false);
            Boolean caseSensitive = (Boolean) params.getOrDefault("caseSensitive", true);
            
            if (pattern == null) {
                return "Error: pattern is required";
            }
            
            try {
                Path searchPath = resolvePath(pathStr);
                
                if (!Files.exists(searchPath)) {
                    return "Error: path not found: " + pathStr;
                }
                
                List<String> results = new ArrayList<>();
                
                if (Files.isRegularFile(searchPath)) {
                    searchFile(searchPath, pattern, caseSensitive, results);
                } else if (Files.isDirectory(searchPath)) {
                    if (Boolean.TRUE.equals(recursive)) {
                        try (var stream = Files.walk(searchPath)) {
                            stream.filter(Files::isRegularFile)
                                .forEach(file -> searchFile(file, pattern, caseSensitive, results));
                        }
                    } else {
                        try (var stream = Files.newDirectoryStream(searchPath)) {
                            for (Path file : stream) {
                                if (Files.isRegularFile(file)) {
                                    searchFile(file, pattern, caseSensitive, results);
                                }
                            }
                        }
                    }
                }
                
                if (results.isEmpty()) {
                    return "No matches found for: " + pattern;
                }
                
                StringBuilder output = new StringBuilder();
                output.append("Found ").append(results.size()).append(" matches:\n\n");
                
                int displayLimit = 100;
                for (int i = 0; i < Math.min(results.size(), displayLimit); i++) {
                    output.append(results.get(i)).append("\n");
                }
                
                if (results.size() > displayLimit) {
                    output.append("\n... and ").append(results.size() - displayLimit).append(" more matches");
                }
                
                return output.toString();
                
            } catch (IOException e) {
                return "Error searching files: " + e.getMessage();
            }
        });
    }
    
    private void searchFile(Path file, String pattern, Boolean caseSensitive, List<String> results) {
        try {
            List<String> lines = Files.readAllLines(file);
            Pattern regex = Pattern.compile(pattern, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
            
            for (int i = 0; i < lines.size(); i++) {
                if (regex.matcher(lines.get(i)).find()) {
                    results.add(file + ":" + (i + 1) + ": " + lines.get(i));
                }
            }
        } catch (IOException e) {
            // 跳过无法读取的文件
        }
    }
    
    @Override
    public boolean isReadOnly() {
        return true;
    }
    
    private Path resolvePath(String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        return basePath.resolve(p).normalize();
    }
}
