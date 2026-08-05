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

    private final java.io.File workspace;

    /** 当前是否运行在 Windows */
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /**
     * Unix → Windows 命令转换表。
     * 仅映射语义等价、参数兼容的纯查看类命令（非破坏性）。
     * AI 模型常输出 Unix 风格命令（pwd/ls/cat），在 Windows cmd 中直接执行会失败。
     */
    private static final Map<String, String> UNIX_TO_WIN = Map.ofEntries(
            Map.entry("pwd", "cd"),
            Map.entry("ls", "dir"),
            Map.entry("cat", "type"),
            Map.entry("clear", "cls"),
            Map.entry("which", "where"),
            Map.entry("cp", "copy"),
            Map.entry("mv", "move"),
            Map.entry("rm", "del"),
            Map.entry("touch", "type nul >"),
            Map.entry("wc", "find /c")
    );

    public ExecTool() {
        this.workspace = null; // 继承 JVM 工作目录
    }

    public ExecTool(java.io.File workspace) {
        this.workspace = workspace;
    }

    /** 将 Unix 命令转换为 Windows 等价命令（仅在 Windows 平台生效） */
    static String translateCommand(String command) {
        if (!IS_WINDOWS || command == null || command.isBlank()) return command;
        String trimmed = command.trim();
        // 获取第一个空白前的 token 作为命令名
        String baseCmd;
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace > 0) {
            baseCmd = trimmed.substring(0, firstSpace);
        } else {
            baseCmd = trimmed;
        }
        String winCmd = UNIX_TO_WIN.get(baseCmd);
        if (winCmd != null) {
            String args = firstSpace > 0 ? trimmed.substring(firstSpace) : "";
            return winCmd + args;
        }
        return command;
    }
    
    @Override
    public String getName() {
        return "exec";
    }
    
    @Override
    public String getDescription() {
        return "Execute a system command and return its output. "
             + "On Windows, common Unix commands are auto-translated "
             + "(pwd→cd, ls→dir, cat→type, clear→cls, rm→del, cp→copy, mv→move, touch→type nul >). "
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
                // Windows 上自动转换 Unix 命令（pwd→cd, ls→dir, ...）
                command = translateCommand(command);

                ProcessBuilder pb = new ProcessBuilder();
                if (workspace != null) {
                    pb.directory(workspace);
                }
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
