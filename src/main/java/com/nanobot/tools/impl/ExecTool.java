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
        return "Execute a system command and return its output. "
             + "Default timeout 120s, max 600s. "
             + "For long-running commands, increase the timeout parameter.";
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
            int timeoutSec = Math.min(
                    (Integer) params.getOrDefault("timeout", 120), 600); // 默认120s, 最大600s
            
            if (command == null || command.isBlank()) {
                return "Error: command is required";
            }
            
            try {
                ProcessBuilder pb = new ProcessBuilder();
                // 根据命令类型选择 Shell：PowerShell 用 -Command，其余用 cmd /c
                String cmd = command.toLowerCase().trim();
                if (cmd.startsWith("powershell ") || cmd.startsWith("powershell\t")) {
                    pb.command("powershell.exe", "-NoProfile", "-Command", command.substring(11));
                } else if (cmd.startsWith("pwsh ") || cmd.startsWith("pwsh\t")) {
                    pb.command("pwsh.exe", "-NoProfile", "-Command", command.substring(5));
                } else {
                    pb.command("cmd.exe", "/c", command);
                }
                pb.redirectErrorStream(false); // stderr 单独捕获

                Process process = pb.start();
                long deadline = System.currentTimeMillis() + timeoutSec * 1000L;

                // 并行读取 stdout 和 stderr
                StringBuilder stdout = new StringBuilder();
                StringBuilder stderr = new StringBuilder();
                Thread stdoutThread = new Thread(() -> readStream(process.getInputStream(), stdout, deadline));
                Thread stderrThread = new Thread(() -> readStream(process.getErrorStream(), stderr, deadline));
                stdoutThread.start(); stderrThread.start();

                boolean finished;
                try {
                    finished = process.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
                    stdoutThread.join(1000);
                    stderrThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroy();
                    return "Error: command interrupted";
                }

                if (!finished) {
                    process.destroy();
                    return "Error: command timed out after " + timeoutSec + "s\n\nStdout:\n" + stdout;
                }

                int exitCode = process.exitValue();
                StringBuilder result = new StringBuilder();
                String out = stdout.toString().trim();
                String err = stderr.toString().trim();
                if (!out.isEmpty()) result.append(out);
                if (!err.isEmpty()) result.append(result.isEmpty() ? "" : "\n").append("[stderr]\n").append(err);
                if (result.isEmpty()) result.append("(no output)");
                if (exitCode != 0) result.insert(0, "Exit code: " + exitCode + "\n\n");

                return result.toString();
                
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return "Error: " + e.getMessage();
            }
        });
    }
    
    @Override public boolean isReadOnly() { return false; }

    private void readStream(java.io.InputStream in, StringBuilder sb, long deadline) {
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
            String line;
            while (System.currentTimeMillis() < deadline) {
                if (r.ready()) { line = r.readLine(); if (line == null) break; sb.append(line).append("\n"); }
                else if (!Thread.currentThread().isInterrupted()) Thread.sleep(50);
                else break;
            }
        } catch (Exception ignored) {}
    }
}
