package com.nanocode.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanocode.tools.Tool;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行命令工具
 * ==============
 *
 * 执行系统命令并返回输出。支持前台（等待完成）和后台（不等待）两种模式。
 *
 * 参数：
 * - command: 要执行的命令（必填）
 * - timeout: 超时时间，秒（可选，默认 120，最大 600，仅前台模式）
 * - background: 后台模式（可选，默认 false）。适用于 spring-boot:run、java -jar 等长期服务
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
        this.workspace = null;
    }

    public ExecTool(java.io.File workspace) {
        this.workspace = workspace;
    }

    /** 将 Unix 命令转换为 Windows 等价命令（仅在 Windows 平台生效） */
    static String translateCommand(String command) {
        if (!IS_WINDOWS || command == null || command.isBlank()) return command;
        String trimmed = command.trim();
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
        return "Execute a system command and return its output.\n"
             + "On Windows, common Unix commands are auto-translated "
             + "(pwd→cd, ls→dir, cat→type, clear→cls, rm→del, cp→copy, mv→move, touch→type nul >).\n"
             + "Default timeout 120s, max 600s.\n"
             + "Use background=true for long-running servers (mvn spring-boot:run, java -jar, npm start, etc.) "
             + "— starts process, captures startup output for 3s, then returns immediately without waiting.";
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
            .put("description", "Timeout in seconds (foreground mode only)");

        properties.putObject("background")
            .put("type", "boolean")
            .put("description", "Run in background — return immediately after startup. "
                    + "Set true for servers, daemons, and long-running processes.");

        props.set("properties", properties);
        props.putArray("required").add("command");

        return props;
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String command = (String) params.get("command");
            boolean background = Boolean.TRUE.equals(params.get("background"));
            int timeoutSec = Math.min(
                    (Integer) params.getOrDefault("timeout", 120), 600);

            if (command == null || command.isBlank()) {
                return "Error: command is required";
            }

            try {
                command = translateCommand(command);

                ProcessBuilder pb = new ProcessBuilder();
                if (workspace != null) {
                    pb.directory(workspace);
                }
                String cmd = command.toLowerCase().trim();
                if (cmd.startsWith("powershell ") || cmd.startsWith("powershell\t")) {
                    pb.command("powershell.exe", "-NoProfile", "-Command", command.substring(11));
                } else if (cmd.startsWith("pwsh ") || cmd.startsWith("pwsh\t")) {
                    pb.command("pwsh.exe", "-NoProfile", "-Command", command.substring(5));
                } else {
                    pb.command("cmd.exe", "/c", command);
                }
                pb.redirectErrorStream(false);

                // ═══════════════════ 后台模式 ═══════════════════
                if (background) {
                    return executeBackground(pb, command);
                }

                // ═══════════════════ 前台模式（原有逻辑）═══════════════════
                return executeForeground(pb, timeoutSec);

            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return "Error: " + e.getMessage();
            }
        });
    }

    // ── 前台执行 ──

    private String executeForeground(ProcessBuilder pb, int timeoutSec) throws Exception {
        Process process = pb.start();
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread stdoutThread = new Thread(() -> readStream(process.getInputStream(), stdout, deadline));
        Thread stderrThread = new Thread(() -> readStream(process.getErrorStream(), stderr, deadline));
        stdoutThread.start(); stderrThread.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
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
    }

    // ── 后台执行 ──

    /**
     * 后台启动进程：采集启动阶段输出（3s），然后返回"已启动"确认。
     * daemon 线程持续排空 stdout/stderr，避免输出缓冲区满导致进程阻塞。
     */
    private String executeBackground(ProcessBuilder pb, String command) throws Exception {
        Process process = pb.start();
        long pid = process.pid();
        long startTime = System.currentTimeMillis();

        StringBuilder startupOutput = new StringBuilder();
        AtomicBoolean draining = new AtomicBoolean(true);

        // 启动阶段：采集前 3 秒输出
        Thread outReader = new Thread(() -> {
            try (var r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                long deadline = System.currentTimeMillis() + 3000;
                String line;
                while (System.currentTimeMillis() < deadline) {
                    if (r.ready()) {
                        line = r.readLine();
                        if (line == null) break;
                        startupOutput.append(line).append("\n");
                    } else {
                        Thread.sleep(50);
                    }
                }
                // 启动阶段结束 → 继续静默排水，防止缓冲区满
                while (draining.get()) {
                    line = r.readLine();
                    if (line == null) break;
                }
            } catch (Exception ignored) {}
        }, "exec-bg-out");

        Thread errReader = new Thread(() -> {
            try (var r = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                long deadline = System.currentTimeMillis() + 3000;
                String line;
                while (System.currentTimeMillis() < deadline) {
                    if (r.ready()) {
                        line = r.readLine();
                        if (line == null) break;
                        startupOutput.append("[stderr] ").append(line).append("\n");
                    } else {
                        Thread.sleep(50);
                    }
                }
                while (draining.get()) {
                    line = r.readLine();
                    if (line == null) break;
                }
            } catch (Exception ignored) {}
        }, "exec-bg-err");

        outReader.setDaemon(true);
        errReader.setDaemon(true);
        outReader.start();
        errReader.start();

        // 等 3 秒收集启动日志
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // 检查进程是否已死
        boolean alive = process.isAlive();
        if (!alive) {
            draining.set(false);
            outReader.join(1000);
            errReader.join(1000);
            int exitCode = process.exitValue();
            return "后台进程启动后立即退出 (exit code: " + exitCode + ")\n\n启动日志:\n"
                    + (startupOutput.isEmpty() ? "(无输出)" : startupOutput.toString().trim());
        }

        // 活着 → 关闭排水标记，让 daemon 线程继续排水
        draining.set(false);

        return "后台进程已启动 (PID: " + pid + ")\n\n启动日志 (" + (System.currentTimeMillis() - startTime) / 1000 + "s):\n"
                + (startupOutput.isEmpty() ? "(无输出)" : startupOutput.toString().trim())
                + "\n\n💡 服务正在后台运行。用 exec 发送 curl 请求测试接口。";
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
