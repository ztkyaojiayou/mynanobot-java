package com.nanobot.v3.cli;

import com.nanobot.NanobotRunner;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.command.CommandContext;
import com.nanobot.command.CommandRegistry;
import com.nanobot.command.impl.HelpCommand;
import com.nanobot.command.impl.InitCommand;
import com.nanobot.command.impl.ModeCommand;
import com.nanobot.command.impl.ResumeCommand;
import com.nanobot.core.AgentLoop;
import com.nanobot.tools.impl.AskUserTool;
import com.nanobot.v3.tui.MarkdownRenderer;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLI 交互通道 — 命令行终端直接对话。
 * <p>
 * 命令: /exit 退出系统, /clear 清空上下文
 */
public class CliChannel {

    private final MessageBus messageBus;
    private final AgentLoop agentLoop;
    private String sessionId;
    private final CommandRegistry commands;
    private final CommandContext cmdCtx;

    private final ConfigurableApplicationContext appContext;

    /**
     * 当前流式输出的 requestId（用于等待完成）
     */
    private volatile String currentRequestId;

    /**
     * 中断标志 — 用户在流式输出期间按 Enter 取消当前回复
     */
    private volatile boolean cancelled;

    /**
     * JLine 终端 — 跨平台原始按键读取（Esc 中断，不干扰 Scanner）
     */
    private final Terminal terminal;

    /**
     * 共享 Scanner — 整个 CLI 共用一个 System.in 读取器，避免多 Scanner 抢输入
     */
    private final Scanner scanner = new Scanner(System.in);

    public CliChannel(ConfigurableApplicationContext appContext) {
        this(appContext, null);
    }

    /**
     * @param initialSessionId 恢复会话时传入，null=新建
     */
    public CliChannel(ConfigurableApplicationContext appContext, String initialSessionId) {
        this.messageBus = NanobotRunner.getMessageBus();
        this.agentLoop = NanobotRunner.getAgentLoop();
        this.sessionId = initialSessionId != null ? initialSessionId : "cli-" + System.currentTimeMillis();
        this.appContext = appContext;

        // 初始化 JLine 终端（跨平台 Esc 检测，使用 /dev/tty 或 CONIN$，不干扰 Scanner）
        Terminal t = null;
        try {
            t = TerminalBuilder.builder().build();
        } catch (IOException e) {
            System.err.println("终端初始化失败，Esc 中断不可用: " + e.getMessage());
        }
        this.terminal = t;

        // 初始化命令注册中心
        var registry = NanobotRunner.getToolRegistry();
        this.cmdCtx = new CommandContext(registry, registry != null ? registry.getPermissionManager() : null, agentLoop, sessionId, appContext::close);
        this.commands = new CommandRegistry();
        this.commands.register(new ModeCommand());
        this.commands.register(new HelpCommand(commands));
        this.commands.register(new InitCommand());
        this.commands.register(new ResumeCommand(sessionKey -> {
            this.sessionId = sessionKey;
            System.out.println("会话已切换至: " + sessionKey + "，历史上下文将在下一条消息中恢复");
        }));
    }

    public void start() {
        if (messageBus == null || agentLoop == null) {
            System.err.println("CLI 启动失败: MessageBus 或 AgentLoop 未就绪");
            return;
        }

        setupInteractivePermission();
        setupAskUserHandler();

        // ── 订阅 outbound 扇出队列，流式输出到控制台 ──
        BlockingQueue<OutboundMessage> subscriberQueue = messageBus.subscribeToOutbound();
        AtomicBoolean consumerRunning = new AtomicBoolean(true);
        // 流式输出线程：持续监听 outbound 扇出队列，渲染到控制台
        Thread consumerThread = new Thread(() -> {
            while (consumerRunning.get()) {
                try {
                    //取走即移除引用（但对应的消息还在堆中，等待JVM GC回收），避免阻塞其他线程
                    OutboundMessage msg = subscriberQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (msg == null) continue;
                    if (!sessionId.equals(msg.getSessionId())) continue;

                    //流式数据处理：根据 requestId 匹配当前流式输出，渲染到控制台
                    if (msg.isSessionCleared()) {
                        if (currentRequestId != null && currentRequestId.equals(msg.getRequestId())) {
                            System.out.println();
                            currentRequestId = null;
                        }
                    } else if (msg.isStreamDelta()) {
                        if (currentRequestId != null && currentRequestId.equals(msg.getRequestId())) {
                            System.out.print(MarkdownRenderer.renderStreaming(msg.getContent()));
                        }
                    } else if (msg.isStreamEnd()) {
                        if (currentRequestId != null && currentRequestId.equals(msg.getRequestId())) {
                            System.out.println();
                            currentRequestId = null;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                }
            }
        }, "CLI-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();

        printBanner();
        System.out.println("输入消息开始对话，/exit 退出，/clear 清上下文，Esc 中断回复");
        System.out.println("💡 @文件路径 可引用文件内容（如 @src/main/Foo.java）");
        System.out.println();

        //持续监听用户输入，这就是入口！！！
        while (true) {
            System.out.print("> ");
            System.out.flush();
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            //优先处理命令
            if (line.startsWith("/")) {
                if (handleCommand(line)) break;
                continue;
            }
            //非命令则正常发消息到 MessageBus
            sendMessage(line);
        }

        // ── 退出时清理 ──
        consumerRunning.set(false);
        consumerThread.interrupt();
        //取消订阅 outbound 扇出队列防止内存泄漏
        messageBus.unsubscribeFromOutbound(subscriberQueue);
    }

    /**
     * 注册 CLI 交互式权限确认
     */
    private void setupInteractivePermission() {
        var registry = NanobotRunner.getToolRegistry();
        if (registry == null || registry.getPermissionManager() == null) return;

        // 当前 CLI 会话级别的"信任"标记（仅对本次进程有效）
        var trusted = new java.util.concurrent.atomic.AtomicBoolean(false);

        registry.getPermissionManager().setInteractiveHandler((tool, params, reason) -> {
            // 已信任则直接放行，不再询问
            if (trusted.get()) return true;

            System.out.println();
            System.out.println("[!] 工具调用需要确认:");
            System.out.println("  工具: " + tool.getName());
            System.out.println("  参数: " + params);
            System.out.println("  原因: " + reason);
            System.out.print("  1=允许  2=之后都放行  3=拒绝  [1/2/3] ");
            System.out.flush();
            String input = scanner.nextLine().trim();
            if ("2".equals(input)) {
                trusted.set(true);
                System.out.println("  已信任当前会话，后续不再询问。");
                return true;
            }
            return "1".equals(input);
        });
    }

    /**
     * 注入 AskUserTool 的 CLI 交互处理器
     */
    private void setupAskUserHandler() {
        var registry = NanobotRunner.getToolRegistry();
        if (registry == null) return;
        var tool = registry.get("ask_user");
        if (tool instanceof AskUserTool askTool) {
            askTool.setInteractiveHandler(question -> {
                System.out.println();
                System.out.println("❓ " + question);
                System.out.print("> ");
                System.out.flush();
                return scanner.nextLine().trim();
            });
        }
    }

    /**
     * 处理 / 命令，返回 true 表示退出循环
     */
    private boolean handleCommand(String cmd) {
        String cmdName = cmd.length() > 1 ? cmd.substring(1).trim().split("\\s+")[0].toLowerCase() : "";
        return switch (cmdName) {
            case "clear"    -> handleClear();
            case "exit", "q", "quit" -> handleExit();
            default         -> { commands.execute(cmdCtx, cmd); yield false; }
        };
    }

    /** /clear — 直接调 SessionManager，不经过 MessageBus（避免等待永不来的 _stream_end） */
    private boolean handleClear() {
        var sm = NanobotRunner.getSessionManager();
        if (sm != null) {
            sm.clearSession(sessionId);
            System.out.println("会话已清除。");
        } else {
            System.out.println("会话管理器未就绪。");
        }
        return false;
    }

    /** /exit — 延迟关闭 Spring 容器，AgentLoop 线程随之终止 */
    private boolean handleExit() {
        System.out.println("正在关闭...");
        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            appContext.close();
        }).start();
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // @ 文件引用
    // ═══════════════════════════════════════════════════════════════

    /** @ 文件引用匹配：@后跟非空白字符，捕获后再清理尾部标点 */
    private static final Pattern FILE_REF_PATTERN =
            Pattern.compile("@(\\S+)");

    /** 单次注入最大行数（超过截断） */
    private static final int MAX_FILE_LINES = 500;

    /** 最大文件字节数（超过拒绝读取，防止大文件 OOM） */
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024; // 2MB

    /** 危险路径模式（禁止访问） */
    private static final Pattern DANGEROUS_PATH = Pattern.compile(
            "(\\.\\./|\\.\\.\\\\|^/etc/|^/proc/|^/sys/|^/dev/|~/.ssh|~/.gnupg|" +
            "\\.pem$|\\.key$|\\.crt$|\\.pfx$|\\.p12$|\\.keystore$|\\.jks$)");

    /** 二进制文件检测：前 512 字节中 null 字节比例超过阈值视为二进制 */
    private static final double BINARY_NULL_RATIO_THRESHOLD = 0.05;

    /**
     * 解析用户输入中的 @文件引用，读取文件内容并替换为 markdown 代码块。
     *
     * @return 替换后的内容；文件读取失败时 @引用保留原文并输出警告
     */
    private String resolveFileRefs(String content) {
        Matcher matcher = FILE_REF_PATTERN.matcher(content);
        if (!matcher.find()) return content; // 没有 @引用，直接返回

        System.out.println();  // 视觉分隔

        // 用列表收集替换（不能在循环中修改原字符串，会打乱索引）
        record Replacement(int start, int end, String text) {}
        List<Replacement> replacements = new ArrayList<>();

        matcher.reset(); // 重置匹配器
        while (matcher.find()) {
            String rawPath = matcher.group(1);
            String resolved = resolveFilePath(rawPath);
            replacements.add(new Replacement(matcher.start(), matcher.end(), resolved));
        }

        System.out.println();  // 视觉分隔

        // 从后往前替换（避免索引偏移）
        StringBuilder result = new StringBuilder(content);
        for (int i = replacements.size() - 1; i >= 0; i--) {
            Replacement r = replacements.get(i);
            result.replace(r.start, r.end, r.text);
        }
        return result.toString();
    }

    /** 解析单个 @文件引用：安全检查 + 读取 + 格式化为 markdown 代码块 */
    private String resolveFilePath(String rawPath) {
        // ── 0. 清理尾部标点（如 @foo.java, → foo.java）──
        rawPath = rawPath.replaceAll("[,;:'\"!?)\\]}]+$", "");

        // ── 1. 展开 ~ → 用户主目录 ──
        String expanded = rawPath.startsWith("~")
                ? rawPath.replaceFirst("^~", System.getProperty("user.home", "~"))
                : rawPath;

        // ── 2. 解析为绝对路径并规范化 ──
        Path path = Paths.get(expanded);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir", ".")).resolve(path);
        }
        path = path.toAbsolutePath().normalize();

        // ── 3. 安全检查：危险路径拒绝 ──
        if (DANGEROUS_PATH.matcher(path.toString()).find()) {
            System.out.println("⚠  危险路径已拒绝: " + rawPath);
            return "@" + rawPath; // 保留原文
        }

        // ── 4. 文件存在性 + 类型检查 ──
        if (!Files.exists(path)) {
            System.out.println("⚠  文件未找到: " + rawPath);
            return "@" + rawPath;
        }
        if (Files.isDirectory(path)) {
            System.out.println("⚠  是目录，请指定具体文件: " + rawPath);
            return "@" + rawPath;
        }

        // ── 5. 大小检查 ──
        try {
            long size = Files.size(path);
            if (size > MAX_FILE_BYTES) {
                System.out.println("⚠  文件过大 (>" + (MAX_FILE_BYTES / 1024 / 1024) + "MB): " + rawPath);
                return "@" + rawPath;
            }
            if (size == 0) {
                System.out.println("⚠  空文件: " + rawPath);
                return "@" + rawPath;
            }
        } catch (IOException e) {
            System.out.println("⚠  无法读取文件大小: " + rawPath);
            return "@" + rawPath;
        }

        // ── 6. 二进制检测 ──
        try {
            byte[] head = new byte[512];
            try (var in = Files.newInputStream(path)) {
                int read = in.read(head);
                if (read > 0) {
                    int nullCount = 0;
                    for (int i = 0; i < read; i++) {
                        if (head[i] == 0) nullCount++;
                    }
                    if ((double) nullCount / read > BINARY_NULL_RATIO_THRESHOLD) {
                        System.out.println("⚠  二进制文件，跳过: " + rawPath);
                        return "@" + rawPath;
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("⚠  无法读取文件: " + rawPath + " (" + e.getMessage() + ")");
            return "@" + rawPath;
        }

        // ── 7. 读取文件内容 ──
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            String lang = inferLanguage(path.getFileName().toString());
            boolean truncated = lines.size() > MAX_FILE_LINES;

            StringBuilder sb = new StringBuilder();
            sb.append("```").append(lang).append("\n");
            int limit = Math.min(lines.size(), MAX_FILE_LINES);
            for (int i = 0; i < limit; i++) {
                sb.append(lines.get(i)).append("\n");
            }
            if (truncated) {
                sb.append("... (截断，共 ").append(lines.size()).append(" 行，仅显示前 ").append(MAX_FILE_LINES).append(" 行)\n");
            }
            sb.append("```");

            System.out.println("📄 已注入: " + rawPath + (truncated ? " (截断至 " + MAX_FILE_LINES + " 行)" : ""));
            return sb.toString();
        } catch (IOException e) {
            System.out.println("⚠  无法读取文件: " + rawPath + " (" + e.getMessage() + ")");
            return "@" + rawPath;
        }
    }

    /** 根据文件扩展名推断 markdown 代码块语言标记 */
    private static String inferLanguage(String fileName) {
        String name = fileName.toLowerCase();
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".kt")) return "kotlin";
        if (name.endsWith(".py")) return "python";
        if (name.endsWith(".js")) return "javascript";
        if (name.endsWith(".ts") || name.endsWith(".tsx")) return "typescript";
        if (name.endsWith(".jsx")) return "jsx";
        if (name.endsWith(".go")) return "go";
        if (name.endsWith(".rs")) return "rust";
        if (name.endsWith(".c") || name.endsWith(".h")) return "c";
        if (name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".cxx") || name.endsWith(".hpp")) return "cpp";
        if (name.endsWith(".cs")) return "csharp";
        if (name.endsWith(".rb")) return "ruby";
        if (name.endsWith(".sh") || name.endsWith(".bash")) return "bash";
        if (name.endsWith(".sql")) return "sql";
        if (name.endsWith(".xml")) return "xml";
        if (name.endsWith(".json")) return "json";
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return "yaml";
        if (name.endsWith(".toml")) return "toml";
        if (name.endsWith(".md") || name.endsWith(".markdown")) return "markdown";
        if (name.endsWith(".html")) return "html";
        if (name.endsWith(".css")) return "css";
        if (name.endsWith(".properties")) return "properties";
        if (name.endsWith(".gradle")) return "groovy";
        if (name.endsWith(".xml") || name.endsWith(".pom")) return "xml";
        return "";
    }

    /**
     * 发送用户消息到 MessageBus
     */
    private void sendMessage(String content) {
        String requestId = java.util.UUID.randomUUID().toString();
        currentRequestId = requestId;
        cancelled = false;

        // ── @ 文件引用解析 ──
        content = resolveFileRefs(content);

        // 后台监听线程：流式输出期间按 Esc 中断当前回复
        Thread cancelMonitor = new Thread(() -> {
            try {
                if (terminal != null) {
                    NonBlockingReader reader = terminal.reader();
                    while (currentRequestId != null && !cancelled) {
                        int ch = reader.read(50); // 50ms 超时轮询
                        if (ch == 27) { // Esc key
                            cancelled = true;
                            currentRequestId = null;
                            break;
                        }
                    }
                } else {
                    // 回退：无终端时用 Enter 中断
                    while (currentRequestId != null && !cancelled) {
                        if (System.in.available() > 0) {
                            scanner.nextLine();
                            cancelled = true;
                            currentRequestId = null;
                            break;
                        }
                        Thread.sleep(200);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "CancelMonitor");
        cancelMonitor.setDaemon(true);
        cancelMonitor.start();

        try {
            messageBus.publishInbound(InboundMessage.builder().sessionId(sessionId).senderId(sessionId).content(content).channel("cli").metadata(java.util.Map.of("requestId", requestId, "streamMode", true)).build());

            // 等待流式完成（最多等5分钟，或按 Esc 取消）
            Thread.sleep(200);
            int waited = 0;
            while (currentRequestId != null && waited < 300_000) {
                Thread.sleep(100);
                waited += 100;
            }
            if (cancelled) {
                System.out.println("\n[已中断]");
            } else if (currentRequestId != null) {
                System.out.println("\n[超时]");
                currentRequestId = null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            currentRequestId = null;
        } finally {
            currentRequestId = null;
        }
    }

    private void printBanner() {
        System.out.println("""
                ╔══════════════════════════════════╗
                ║       my-nanobot CLI 模式       ║
                ║  基于 Java 的 AI Agent助手  ║
                ╚══════════════════════════════════╝""");
    }
}
