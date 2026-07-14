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
 * 列出目录工具
 * ===============
 * 
 * 列出指定目录的内容。
 * 
 * 参数：
 * - path: 目录路径（必填）
 * - recursive: 是否递归（可选）
 */
public class ListDirTool implements Tool {
    
    private static final ObjectMapper mapper = new ObjectMapper();

    public ListDirTool() {
        // Path validation is handled centrally by PathGuard in ToolRegistry.execute()
    }
    
    @Override
    public String getName() {
        return "list_dir";
    }
    
    @Override
    public String getDescription() {
        return "List contents of a directory. Shows files and subdirectories.";
    }
    
    @Override
    public JsonNode getParameters() {
        ObjectNode props = mapper.createObjectNode();
        props.put("type", "object");
        
        ObjectNode properties = mapper.createObjectNode();
        properties.putObject("path")
            .put("type", "string")
            .put("description", "Directory path to list");
        
        properties.putObject("recursive")
            .put("type", "boolean")
            .put("description", "List recursively");
        
        props.set("properties", properties);
        props.putArray("required").add("path");
        
        return props;
    }
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String pathStr = (String) params.getOrDefault("path", ".");
            Boolean recursive = (Boolean) params.getOrDefault("recursive", false);
            
            try {
                Path dirPath = Paths.get(pathStr);  // Path already validated & resolved by ToolRegistry/PathGuard
                
                if (!Files.exists(dirPath)) {
                    return "Error: directory not found: " + pathStr;
                }
                
                if (!Files.isDirectory(dirPath)) {
                    return "Error: path is not a directory: " + pathStr;
                }
                
                StringBuilder result = new StringBuilder();
                result.append("Directory: ").append(dirPath).append("\n\n");
                
                if (Boolean.TRUE.equals(recursive)) {
                    listRecursive(dirPath, result, 0);
                } else {
                    listDirectory(dirPath, result);
                }
                
                return result.toString();
                
            } catch (IOException e) {
                return "Error listing directory: " + e.getMessage();
            }
        });
    }
    
    private void listDirectory(Path dir, StringBuilder result) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(entries::add);
        }
        
        entries.sort((a, b) -> {
            boolean aIsDir = Files.isDirectory(a);
            boolean bIsDir = Files.isDirectory(b);
            if (aIsDir != bIsDir) {
                return aIsDir ? -1 : 1;
            }
            return a.getFileName().toString().compareTo(b.getFileName().toString());
        });
        
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                result.append("[DIR]  ").append(name).append("/\n");
            } else {
                result.append("[FILE] ").append(name);
                result.append(" (").append(formatSize(Files.size(entry))).append(")\n");
            }
        }
    }
    
    private void listRecursive(Path dir, StringBuilder result, int indent) throws IOException {
        String prefix = "  ".repeat(indent);
        String name = dir.getFileName().toString();
        
        if (indent > 0) {
            result.append(prefix).append("[DIR]  ").append(name).append("/\n");
        }
        
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(entries::add);
        }
        
        entries.sort((a, b) -> {
            boolean aIsDir = Files.isDirectory(a);
            boolean bIsDir = Files.isDirectory(b);
            if (aIsDir != bIsDir) {
                return aIsDir ? -1 : 1;
            }
            return a.getFileName().toString().compareTo(b.getFileName().toString());
        });
        
        for (Path entry : entries) {
            name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                result.append(prefix).append("  [DIR]  ").append(name).append("/\n");
                listRecursive(entry, result, indent + 1);
            } else {
                result.append(prefix).append("  [FILE] ").append(name);
                result.append(" (").append(formatSize(Files.size(entry))).append(")\n");
            }
        }
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /*
     * Path resolution is handled centrally by PathGuard in ToolRegistry.execute().
     */
}
