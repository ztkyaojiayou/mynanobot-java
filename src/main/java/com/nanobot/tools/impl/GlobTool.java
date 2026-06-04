package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.tools.Tool;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Glob 文件匹配工具
 * ==================
 * 
 * 使用通配符匹配文件路径。
 * 
 * 参数：
 * - pattern: 文件模式（必填）
 * - basePath: 搜索基础路径（可选）
 */
public class GlobTool implements Tool {
    
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Path basePath;
    
    public GlobTool(String basePath) {
        this.basePath = Paths.get(basePath);
    }
    
    @Override
    public String getName() {
        return "glob";
    }
    
    @Override
    public String getDescription() {
        return "Find files matching a glob pattern. Use ** for recursive, * for any characters.";
    }
    
    @Override
    public JsonNode getParameters() {
        ObjectNode props = mapper.createObjectNode();
        props.put("type", "object");
        
        ObjectNode properties = mapper.createObjectNode();
        properties.putObject("pattern")
            .put("type", "string")
            .put("description", "Glob pattern (e.g., ** / *.java)");
        
        properties.putObject("basePath")
            .put("type", "string")
            .put("description", "Base path to search from");
        
        props.set("properties", properties);
        props.putArray("required").add("pattern");
        
        return props;
    }
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String pattern = (String) params.get("pattern");
            String basePathStr = (String) params.getOrDefault("basePath", ".");
            
            if (pattern == null || pattern.isBlank()) {
                return "Error: pattern is required";
            }
            
            try {
                Path searchPath = resolvePath(basePathStr);
                
                if (!Files.exists(searchPath)) {
                    return "Error: Path not found: " + basePathStr;
                }
                
                List<Path> matches = new ArrayList<>();
                
                try (var stream = Files.walk(searchPath)) {
                    stream.filter(path -> {
                        PathMatcher matcher = FileSystems.getDefault()
                            .getPathMatcher("glob:" + pattern);
                        try {
                            return matcher.matches(searchPath.relativize(path));
                        } catch (Exception e) {
                            return false;
                        }
                    }).forEach(matches::add);
                }
                
                if (matches.isEmpty()) {
                    return "No files found matching pattern: " + pattern;
                }
                
                matches.sort(Comparator.comparing(Path::toString));
                
                StringBuilder result = new StringBuilder();
                result.append("Found ").append(matches.size()).append(" files:\n");
                
                for (Path match : matches) {
                    result.append("- ").append(searchPath.relativize(match)).append("\n");
                }
                
                return result.toString();
                
            } catch (Exception e) {
                return "Error searching files: " + e.getMessage();
            }
        });
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
