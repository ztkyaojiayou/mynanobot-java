package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.tools.Tool;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 读取文件工具
 * ================
 * 
 * 读取指定路径的文件内容。
 * 
 * 参数：
 * - path: 文件路径（必填）
 * - maxLines: 最大行数（可选，默认全部）
 */
public class ReadFileTool implements Tool {
    
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Path basePath;
    
    public ReadFileTool(String basePath) {
        this.basePath = Paths.get(basePath);
    }
    
    @Override
    public String getName() {
        return "read_file";
    }
    
    @Override
    public String getDescription() {
        return "Read the content of a file. Use this to view file contents.";
    }
    
    @Override
    public JsonNode getParameters() {
        ObjectNode props = mapper.createObjectNode()
            .put("type", "object")
            .putObject("properties");
        
        props.putObject("path")
            .put("type", "string")
            .put("description", "File path to read");
        
        props.putObject("maxLines")
            .put("type", "integer")
            .put("description", "Maximum number of lines to read");
        
        return props.putArray("required").add("path");
    }
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String pathStr = (String) params.get("path");
            Integer maxLines = (Integer) params.get("maxLines");
            
            if (pathStr == null) {
                return "Error: path is required";
            }
            
            try {
                Path filePath = resolvePath(pathStr);
                
                if (!Files.exists(filePath)) {
                    return "Error: file not found: " + pathStr;
                }
                
                if (Files.isDirectory(filePath)) {
                    return "Error: path is a directory, not a file: " + pathStr;
                }
                
                List<String> lines = Files.readAllLines(filePath);
                
                if (maxLines != null && maxLines > 0 && maxLines < lines.size()) {
                    lines = lines.subList(0, maxLines);
                }
                
                StringBuilder content = new StringBuilder();
                content.append("File: ").append(filePath).append("\n");
                content.append("Lines: ").append(lines.size()).append("\n\n");
                
                for (int i = 0; i < lines.size(); i++) {
                    content.append(String.format("%6d  %s\n", i + 1, lines.get(i)));
                }
                
                return content.toString();
                
            } catch (IOException e) {
                return "Error reading file: " + e.getMessage();
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
