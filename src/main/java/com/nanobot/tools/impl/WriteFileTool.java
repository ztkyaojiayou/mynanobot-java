package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.tools.Tool;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 写入文件工具
 * =================
 * 
 * 将内容写入指定文件。如果文件存在会覆盖。
 * 
 * 参数：
 * - path: 文件路径（必填）
 * - content: 文件内容（必填）
 * - append: 是否追加（可选）
 */
public class WriteFileTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();

    public WriteFileTool() {
        // Path validation is handled centrally by PathGuard in ToolRegistry.execute()
    }
    
    @Override
    public String getName() {
        return "write_file";
    }
    
    @Override
    public String getDescription() {
        return "Write content to a file. Creates new file or overwrites existing.";
    }
    
    @Override
    public JsonNode getParameters() {
        ObjectNode props = mapper.createObjectNode();
        props.put("type", "object");
        
        ObjectNode properties = mapper.createObjectNode();
        properties.putObject("path")
            .put("type", "string")
            .put("description", "File path to write");
        
        properties.putObject("content")
            .put("type", "string")
            .put("description", "Content to write");
        
        properties.putObject("append")
            .put("type", "boolean")
            .put("description", "Append to file instead of overwriting");
        
        props.set("properties", properties);
        props.putArray("required").add("path").add("content");
        
        return props;
    }
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String pathStr = (String) params.get("path");
            String content = (String) params.get("content");
            Boolean append = (Boolean) params.getOrDefault("append", false);
            
            if (pathStr == null || content == null) {
                return "Error: path and content are required";
            }
            
            try {
                Path filePath = Paths.get(pathStr);  // Path already validated & resolved by ToolRegistry/PathGuard
                
                Path parent = filePath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                
                if (Boolean.TRUE.equals(append)) {
                    Files.writeString(filePath, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } else {
                    Files.writeString(filePath, content);
                }
                
                return "File written successfully: " + pathStr;
                
            } catch (IOException e) {
                return "Error writing file: " + e.getMessage();
            }
        });
    }
    
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /*
     * Path resolution is handled centrally by PathGuard in ToolRegistry.execute().
     * The 'path' parameter is already validated and resolved to an absolute path
     * before this tool's execute() is called.
     */
}
