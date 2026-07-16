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
 * 编辑文件工具
 * ==============
 * 
 * 对文件进行行级编辑（替换指定文本）。
 * 
 * 参数：
 * - path: 文件路径（必填）
 * - oldText: 要替换的文本（必填）
 * - newText: 替换后的文本（必填）
 */
public class EditFileTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();

    public EditFileTool() {
        // Path validation is handled centrally by PathGuard in ToolRegistry.execute()
    }
    
    @Override
    public String getName() {
        return "edit_file";
    }
    
    @Override
    public String getDescription() {
        return "Edit a file by replacing specific text. "
             + "The oldText must appear exactly once in the file — "
             + "if it appears multiple times, the edit will fail. "
             + "Provide enough surrounding context to make oldText unique.";
    }
    
    @Override
    public JsonNode getParameters() {
        ObjectNode props = mapper.createObjectNode();
        props.put("type", "object");
        
        ObjectNode properties = mapper.createObjectNode();
        properties.putObject("path")
            .put("type", "string")
            .put("description", "File path to edit");
        
        properties.putObject("oldText")
            .put("type", "string")
            .put("description", "Text to find and replace (must be unique — provide enough surrounding lines to ensure it appears exactly once)");

        properties.putObject("newText")
            .put("type", "string")
            .put("description", "Replacement text");
        
        props.set("properties", properties);
        props.putArray("required").add("path").add("oldText").add("newText");
        
        return props;
    }
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String pathStr = (String) params.get("path");
            String oldText = (String) params.get("oldText");
            String newText = (String) params.get("newText");
            
            if (pathStr == null || oldText == null || newText == null) {
                return "Error: path, oldText, and newText are all required";
            }
            
            try {
                Path filePath = Paths.get(pathStr);  // Path already validated & resolved by ToolRegistry/PathGuard
                
                if (!Files.exists(filePath)) {
                    return "Error: file not found: " + pathStr;
                }
                
                String content = Files.readString(filePath);
                
                // 唯一性校验：oldText 必须在文件中恰好出现一次，防止误替换
                int count = countOccurrences(content, oldText);
                if (count == 0) {
                    return "Error: oldText not found in file";
                }
                if (count > 1) {
                    return "Error: oldText found " + count + " times in file — "
                         + "must be unique. Provide more surrounding context to make it unique.";
                }

                String newContent = content.replace(oldText, newText);
                
                Files.writeString(filePath, newContent);
                
                return "File edited successfully: " + pathStr;
                
            } catch (IOException e) {
                return "Error editing file: " + e.getMessage();
            }
        });
    }
    
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /** 统计子串在文本中出现的次数 */
    private int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }

    /*
     * Path resolution is handled centrally by PathGuard in ToolRegistry.execute().
     */
}
