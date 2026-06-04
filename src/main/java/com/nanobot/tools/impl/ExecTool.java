package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.tools.Tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 执行命令工具
 * ==============
 * 
 * 执行系统命令并返回输出。
 * 
 * 参数：
 * - command: 要执行的命令（必填）
 * - timeout: 超时时间，秒（可选，默认 60）
 */
public class ExecTool implements Tool {
    
    private static final ObjectMapper mapper = new ObjectMapper();
    
    @Override
    public String getName() {
        return "exec";
    }
    
    @Override
    public String getDescription() {
        return "Execute a system command and return its output.";
    }
    
    @Override
    public JsonNode getParameters() {
        ObjectNode props = mapper.createObjectNode();
        props.put("type", "object");
        
        ObjectNode properties = mapper.createObjectNode();
        properties.putObject("command")
            .put("type", "string")
            .put("description", "Command to execute");
        
        properties.putObject("timeout")
            .put("type", "integer")
            .put("description", "Timeout in seconds");
        
        props.set("properties", properties);
        props.putArray("required").add("command");
        
        return props;
    }
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String command = (String) params.get("command");
            Integer timeoutSec = (Integer) params.getOrDefault("timeout", 60);
            
            if (command == null || command.isBlank()) {
                return "Error: command is required";
            }
            
            try {
                ProcessBuilder pb = new ProcessBuilder();
                pb.command("cmd.exe", "/c", command);
                pb.redirectErrorStream(true);
                
                Process process = pb.start();
                
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    long start = System.currentTimeMillis();
                    while (System.currentTimeMillis() - start < timeoutSec * 1000L) {
                        if (reader.ready()) {
                            line = reader.readLine();
                            if (line == null) break;
                            output.append(line).append("\n");
                        } else if (!process.isAlive()) {
                            break;
                        } else {
                            Thread.sleep(50);
                        }
                    }
                }
                
                if (process.isAlive()) {
                    process.destroy();
                    return "Error: command timed out after " + timeoutSec + " seconds\n\nOutput:\n" + output;
                }
                
                int exitCode = process.exitValue();
                String result = output.toString();
                
                if (exitCode != 0) {
                    return "Exit code: " + exitCode + "\n\n" + result;
                }
                
                return result.isEmpty() ? "(no output)" : result;
                
            } catch (IOException e) {
                return "Error executing command: " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Error: command interrupted";
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        });
    }
    
    @Override
    public boolean isReadOnly() {
        return false;
    }
}
