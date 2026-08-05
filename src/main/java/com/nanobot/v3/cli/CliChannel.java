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
import com.nanobot.v3.tui.TerminalStyle;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(CliChannel.class);

    private final MessageBus messageBus;
    private final AgentLoop agentLoop;
    private String sessionId;
    /** 当前订阅的出站队列（volatile：session 切换时原子替换，消费者线程每次 snapshot 读取） */
    private volatile BlockingQueue<OutboundMessage> subscriberQueue;
    private AtomicBoolean consumerRunning;
    private Thread consumerThread;
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

    /** 交互对话框活跃中（权限确认/ask_user 等），CancelMonitor 暂停读终端 */
    private volatile boolean dialogActive;

    /** 终端输入锁——CancelMonitor 和交互对话框共用，防抢 System.in */
    private final Object terminalLock = new Object();

    /**
     * JLine 终端 — 跨平台原始按键读取（Esc 中断，不干扰 Scanner）
     */
    private final Terminal terminal;

    /**
     * 共享 Scanner — 整个 CLI 共用一个 System.in 读取器，避免多 Scanner 抢输入.
     * 所有 scanner 操作必须通过 {@link #readLine()} 同步访问，防止多线程并发读导致
     * IndexOutOfBoundsException（Scanner 非线程安全）.
     */
    private Scanner scanner = new Scanner(System.in);  // 非 final：readLine() 异常时重建

    /** 同步读一行（Scanner 非线程安全，多线程共享时必须加锁） */
    private String readLine() {
        synchronized (scanner) {
            try {
                return scanner.nextLine();
            } catch (RuntimeException e) {
                logger.warn("Scanner 异常，重建: {}", e.toString());
                try { scanner.close(); } catch (Exception ignored) {}
                scanner = new Scanner(System.in);
                return "";
            }
        }
    }

    /**
     * 交互对话框专用：从 JLine terminal.reader() 逐字符读 + 回显。
     * 与 CancelMonitor 共用 terminalLock，确保同一时刻只有一个读终端。
     */
    private String readInteractiveLine() {
        synchronized (terminalLock) {
            if (terminal == null) return readLine();
            NonBlockingReader reader = terminal.reader();
            StringBuilder sb = new StringBuilder();
            try {
                while (true) {
                    int ch = reader.read(200);
                    if (ch < 0) continue;
                    if (ch == '\r' || ch == '\n') break;
                    sb.append((char) ch);
                    System.out.print((char) ch);
                    System.out.flush();
                }
            } catch (java.io.IOException e) {
                logger.warn("终端读取失败: {}", e.getMessage());
            }
            System.out.println();
            return sb.toString();
        }
    }

    public CliChannel(ConfigurableApplicationContext appContext) {
        this(appContext, null);
    }

    /**
     * @param initialSessionId 恢复会话时传入，null=新建
     */
    public CliChannel(ConfigurableApplicationContext appContext, String initialSessionId) {
        this.messageBus = NanobotRunner.getMessageBus();
        this.agentLoop = NanobotRunner.getAgentLoop();
        this.sessionId = initialSessionId != null ? initialSessionId : String.valueOf(System.currentTimeMillis());
        // 初始化订阅队列（若 MessageBus 未就绪则延迟到 start()）
        if (messageBus != null) {
            this.subscriberQueue = messageBus.subscribeToOutbound(sessionId);
        }
        this.appContext = appContext;

        // 初始化 JLine 终端（跨平台 Esc 检测，使用 /dev/tty 或 CONIN$，不干扰 Scanner）
        Terminal t = null;
        try {
            t = TerminalBuilder.builder().build();
        } catch (IOException e) {
            logger.error("终端初始化失败，Esc 中断不可用: {}", e.getMessage());
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
            // 取消旧 session 的精准路由订阅，注册新 session 的
            BlockingQueue<OutboundMessage> oldQueue = this.subscriberQueue;
            this.subscriberQueue = messageBus.subscribeToOutbound(sessionKey);
            if (oldQueue != null) messageBus.unsubscribeFromOutbound(this.sessionId, oldQueue);
            // safe key (cli_xxx) → 去掉 channel 前缀，还原原始 sessionId
            // 否则 getSessionKey() 会再次加 channel: → 生成新目录
            String channelPrefix = "cli_";
            this.sessionId = sessionKey.startsWith(channelPrefix)
                    ? sessionKey.substring(channelPrefix.length())
                    : sessionKey;
            System.out.println(TerminalStyle.success("会话已切换至: " + sessionKey + "，历史上下文将在下一条消息中恢复"));
        }));
    }

    public void start() {
        if (messageBus == null || agentLoop == null) {
            System.out.println(TerminalStyle.error("CLI 启动失败: MessageBus 或 AgentLoop 未就绪"));
            System.out.println(TerminalStyle.dim("  请检查 Spring 容器日志，确认所有 Bean 已正确初始化。"));
            logger.error("CLI 启动失败: MessageBus 或 AgentLoop 未就绪");
            return;
        }

        setupInteractivePermission();
        setupAskUserHandler();
        startConsumerThread();
        printStartupBanner();
        runInputLoop();
    }

    // ── start 子步骤 ──

    /** ① 启动流式消费线程 — 监听 outbound 扇出队列，渲染到控制台 */
    private void startConsumerThread() {
        if (this.subscriberQueue == null) {
            this.subscriberQueue = messageBus.subscribeToOutbound(sessionId);
        }
        consumerRunning = new AtomicBoolean(true);
        consumerThread = new Thread(() -> {
            long firstDeltaTime = 0;
            while (consumerRunning.get()) {
                try {
                    BlockingQueue<OutboundMessage> q = this.subscriberQueue;
                    OutboundMessage msg = q.poll(500, TimeUnit.MILLISECONDS);
                    if (msg == null) continue;
                    firstDeltaTime = renderStreamMessage(msg, firstDeltaTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.debug("CLI consumer error", e);
                }
            }
        }, "CLI-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    /** 渲染单条流式消息到控制台，返回更新后的 firstDeltaTime */
    private long renderStreamMessage(OutboundMessage msg, long firstDeltaTime) {
        if (msg.isToolCall()) {
            System.out.print("\n  " + TerminalStyle.dim("⚙ " + msg.getContent()) + " ");
        } else if (msg.isSessionCleared()) {
            if (currentRequestId != null && currentRequestId.equals(msg.getRequestId())) {
                System.out.println();
                currentRequestId = null;
            }
        } else if (msg.isStreamDelta()) {
            if (currentRequestId != null && currentRequestId.equals(msg.getRequestId())) {
                if (firstDeltaTime == 0) firstDeltaTime = System.currentTimeMillis();
                System.out.print(MarkdownRenderer.renderStreaming(msg.getContent()));
            }
        }
        if (msg.isStreamEnd()) {
            if (currentRequestId != null && currentRequestId.equals(msg.getRequestId())) {
                System.out.println();
                int tokens = msg.getMetadataInt("_token_count", -1);
                int iterations = msg.getMetadataInt("_tool_iterations", 0);
                long duration = firstDeltaTime > 0 ? System.currentTimeMillis() - firstDeltaTime : 0;
                StringBuilder stats = new StringBuilder();
                if (duration > 0) stats.append(String.format("  %.1fs", duration / 1000.0));
                if (tokens > 0) stats.append(" · ").append(tokens).append(" tokens");
                if (iterations > 0) stats.append(" · ").append(iterations).append(" tool calls");
                if (stats.length() > 0) System.out.println(TerminalStyle.dim(stats.toString().trim()));
                currentRequestId = null;
                return 0;
            }
        }
        return firstDeltaTime;
    }

    /** ② 打印启动横幅 */
    private void printStartupBanner() {
        printBanner();
    }

    /** ③ 主输入循环 */
    private void runInputLoop() {
        while (true) {
            System.out.print("> ");
            System.out.flush();
            synchronized (scanner) { if (!scanner.hasNextLine()) break; }
            String line = readLine().trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("/")) {
                String cmdName = extractCmdName(line);
                // 内置命令（不依赖 CommandRegistry）
                if ("clear".equals(cmdName)) { handleClear(); continue; }
                if ("exit".equals(cmdName) || "q".equals(cmdName) || "quit".equals(cmdName)) { handleExit(); return; }
                // 注册的命令（/help, /mode, /init, /resume 等）
                if (commands.isRegistered(cmdName)) {
                    commands.execute(cmdCtx, line);
                    continue;
                }
                // 未知命令
                System.out.println(TerminalStyle.warn("未知命令: " + line + "（输入 /help 查看可用命令）"));
                continue;
            }
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
            if (trusted.get()) return true;

            dialogActive = true;
            try {
                System.out.println();
                System.out.println(TerminalStyle.ORANGE + TerminalStyle.B + "  ⚡ 工具调用确认" + TerminalStyle.R);
                System.out.println("  " + TerminalStyle.bold("工具: ") + TerminalStyle.highlight(tool.getName()));
                // 参数仅显示摘要（完整 JSON 太冗长）
                String paramStr = params.toString();
                if (paramStr.length() > 80) paramStr = paramStr.substring(0, 77) + "...";
                System.out.println("  " + TerminalStyle.dim("参数: " + paramStr));
                System.out.println("  " + TerminalStyle.dim("原因: " + reason));
                System.out.print("  " + TerminalStyle.GREEN + "[1] 允许 " + TerminalStyle.R
                        + TerminalStyle.CYAN + "[2] 之后都放行 " + TerminalStyle.R
                        + TerminalStyle.RED + "[3] 拒绝 " + TerminalStyle.R);
                System.out.flush();
                String input = readInteractiveLine().trim();
                if ("2".equals(input)) {
                    trusted.set(true);
                    System.out.println("  " + TerminalStyle.success("已信任当前会话，后续不再询问。"));
                    return true;
                }
                return "1".equals(input);
            } finally {
                dialogActive = false;
            }
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
                dialogActive = true;
                try {
                    System.out.println();
                    System.out.println("❓ " + question);
                    System.out.print("> ");
                    System.out.flush();
                    return readInteractiveLine().trim();
                } finally {
                    dialogActive = false;
                }
            });
        }
    }

    /** 从输入行提取命令名（去掉 / 前缀，取第一个空格前 token 小写） */
    private static String extractCmdName(String line) {
        if (line == null || line.length() <= 1) return "";
        return line.substring(1).trim().split("\\s+")[0].toLowerCase();
    }

    /** /clear — 直接调 SessionManager，不经过 MessageBus（避免等待永不来的 _stream_end） */
    private boolean handleClear() {
        var sm = NanobotRunner.getSessionManager();
        if (sm != null) {
            sm.clearSession(sessionId);
            System.out.println(TerminalStyle.info("会话已清除。"));
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

    /** @ 文件引用匹配：@ 前必须是空白或行首，避免 git@github.com 等被误判 */
    private static final Pattern FILE_REF_PATTERN =
            Pattern.compile("(?<!\\S)@(\\S+)");

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

    /**
     * 解析单个 @文件引用：安全检查 + 读取 + 格式化为 markdown 代码块.
     *
     * <h3>处理流程（6 步）</h3>
     * <ol>
     *   <li>清理尾部标点</li>
     *   <li>展开 ~ 并规范化为绝对路径</li>
     *   <li>安全检查（危险路径拒绝）</li>
     *   <li>文件存在性检查（含智能回退 + 目录列表）</li>
     *   <li>文件校验（大小 + 二进制检测）</li>
     *   <li>读取文件内容并格式化为 markdown 代码块</li>
     * </ol>
     */
    private String resolveFilePath(String rawPath) {
        // ① 清理尾部标点（如 @foo.java, → foo.java）
        String cleaned = cleanTrailingPunctuation(rawPath);

        // ② 展开 ~ 并规范化为绝对路径
        Path path = expandAndResolvePath(cleaned);

        // ③ 安全检查
        if (isDangerousPath(path)) {
            System.out.println(TerminalStyle.error("危险路径已拒绝: " + cleaned));
            return "@" + cleaned;
        }

        // ④ 文件存在性检查（含智能回退）+ 目录列表
        path = resolveExistingPath(path, cleaned);
        if (path == null) return "@" + cleaned;
        if (Files.isDirectory(path)) return listDirectory(path, cleaned);

        // ⑤ 文件校验（大小 + 二进制检测）
        String error = validateFile(path, cleaned);
        if (error != null) return error;

        // ⑥ 读取文件内容
        return readFileAsCodeBlock(path, cleaned);
    }

    // ── resolveFilePath 子步骤 ──

    /** ① 清理尾部标点（逗号、分号、引号、括号等） */
    private static String cleanTrailingPunctuation(String rawPath) {
        return rawPath.replaceAll("[,;:'\"!?)\\]}]+$", "");
    }

    /** ② 展开 ~ 为用户主目录，解析相对路径为 workspace 绝对路径并规范化 */
    private static Path expandAndResolvePath(String rawPath) {
        String expanded = rawPath.startsWith("~")
                ? rawPath.replaceFirst("^~", System.getProperty("user.home", "~"))
                : rawPath;
        Path path = Paths.get(expanded);
        if (!path.isAbsolute()) {
            // 使用 workspace 作为基准（而非 user.dir — pushd 会污染）
            String ws = System.getProperty("nanobot.workspace");
            Path base = (ws != null && !ws.isBlank())
                    ? Paths.get(ws)
                    : Paths.get(System.getProperty("user.dir", "."));
            path = base.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    /** ③ 检查路径是否匹配危险模式 */
    private boolean isDangerousPath(Path path) {
        return DANGEROUS_PATH.matcher(path.toString()).find();
    }

    /**
     * ④-a 智能回退路径：路径不存在时逐级向上查找存在的父路径.
     *
     * @return 找到的路径（可能是目录），或 null 表示完全不存在
     */
    private Path resolveExistingPath(Path path, String rawPath) {
        if (Files.exists(path)) return path;

        Path probe = path;
        while (probe != null && !Files.exists(probe)) {
            Path parent = probe.getParent();
            if (parent == null) break;
            probe = parent;
        }
        if (probe != null && Files.exists(probe)) return probe;

        System.out.println(TerminalStyle.warn("文件未找到: " + rawPath));
        return null;
    }

    /** ④-b 列出目录内容（markdown 格式），最多 50 项 */
    private String listDirectory(Path dir, String rawPath) {
        try {
            var children = Files.list(dir).limit(50).toList();
            StringBuilder sb = new StringBuilder("```\n");
            sb.append(dir).append("/\n");
            for (var child : children) {
                String name = child.getFileName().toString();
                if (Files.isDirectory(child)) name += "/";
                sb.append("  ").append(name).append("\n");
            }
            if (children.size() == 50) sb.append("  ... (截断)\n");
            sb.append("```");
            System.out.println(TerminalStyle.success("已注入目录: " + rawPath + " (" + children.size() + " 项)"));
            return sb.toString();
        } catch (IOException e) {
            System.out.println(TerminalStyle.warn("无法列出目录: " + rawPath));
            return "@" + rawPath;
        }
    }

    /**
     * ⑤ 文件校验：大小检查 + 二进制检测.
     *
     * @return 错误消息（以 @ 开头），或 null 表示校验通过
     */
    private String validateFile(Path path, String rawPath) {
        // 大小检查
        try {
            long size = Files.size(path);
            if (size > MAX_FILE_BYTES) {
                System.out.println(TerminalStyle.warn("文件过大 (>" + (MAX_FILE_BYTES / 1024 / 1024) + "MB): " + rawPath));
                return "@" + rawPath;
            }
            if (size == 0) {
                System.out.println(TerminalStyle.warn("空文件: " + rawPath));
                return "@" + rawPath;
            }
        } catch (IOException e) {
            System.out.println(TerminalStyle.warn("无法读取文件大小: " + rawPath));
            return "@" + rawPath;
        }

        // 二进制检测
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
                        System.out.println(TerminalStyle.warn("二进制文件，跳过: " + rawPath));
                        return "@" + rawPath;
                    }
                }
            }
        } catch (IOException e) {
            System.out.println(TerminalStyle.warn("无法读取文件: " + rawPath + " (" + e.getMessage() + ")"));
            return "@" + rawPath;
        }

        return null; // 校验通过
    }

    /** ⑥ 读取文件内容并格式化为 markdown 代码块，超过 MAX_FILE_LINES 行则截断 */
    private String readFileAsCodeBlock(Path path, String rawPath) {
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
                sb.append("... (截断，共 ").append(lines.size())
                        .append(" 行，仅显示前 ").append(MAX_FILE_LINES).append(" 行)\n");
            }
            sb.append("```");

            System.out.println("📄 已注入: " + rawPath
                    + (truncated ? " (截断至 " + MAX_FILE_LINES + " 行)" : ""));
            return sb.toString();
        } catch (IOException e) {
            System.out.println(TerminalStyle.warn("无法读取文件: " + rawPath + " (" + e.getMessage() + ")"));
            return "@" + rawPath;
        }
    }

    private static final java.util.Map<String, String> EXT_TO_LANG = java.util.Map.ofEntries(
            java.util.Map.entry(".java", "java"), java.util.Map.entry(".kt", "kotlin"),
            java.util.Map.entry(".py", "python"), java.util.Map.entry(".js", "javascript"),
            java.util.Map.entry(".ts", "typescript"), java.util.Map.entry(".tsx", "typescript"),
            java.util.Map.entry(".jsx", "jsx"), java.util.Map.entry(".go", "go"),
            java.util.Map.entry(".rs", "rust"), java.util.Map.entry(".c", "c"),
            java.util.Map.entry(".h", "c"), java.util.Map.entry(".cpp", "cpp"),
            java.util.Map.entry(".cc", "cpp"), java.util.Map.entry(".cxx", "cpp"),
            java.util.Map.entry(".hpp", "cpp"), java.util.Map.entry(".cs", "csharp"),
            java.util.Map.entry(".rb", "ruby"), java.util.Map.entry(".sh", "bash"),
            java.util.Map.entry(".bash", "bash"), java.util.Map.entry(".sql", "sql"),
            java.util.Map.entry(".xml", "xml"), java.util.Map.entry(".json", "json"),
            java.util.Map.entry(".yaml", "yaml"), java.util.Map.entry(".yml", "yaml"),
            java.util.Map.entry(".toml", "toml"), java.util.Map.entry(".md", "markdown"),
            java.util.Map.entry(".markdown", "markdown"), java.util.Map.entry(".html", "html"),
            java.util.Map.entry(".css", "css"), java.util.Map.entry(".properties", "properties"),
            java.util.Map.entry(".gradle", "groovy"), java.util.Map.entry(".pom", "xml")
    );

    /** 根据文件扩展名推断 markdown 代码块语言标记 */
    private static String inferLanguage(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "";
        return EXT_TO_LANG.getOrDefault(fileName.substring(dot).toLowerCase(), "");
    }

    /**
     * 发送用户消息到 MessageBus — @引用解析 → 发送 → 等待流式完成.
     */
    private void sendMessage(String content) {
        String requestId = java.util.UUID.randomUUID().toString();
        currentRequestId = requestId;
        cancelled = false;

        // ── @ 文件引用解析 ──
        content = resolveFileRefs(content);

        // ① 启动后台 Esc/Enter 中断监听线程
        startCancelMonitor();

        try {
            messageBus.publishInbound(InboundMessage.builder().sessionId(sessionId).senderId(sessionId).content(content).channel("cli").metadata(java.util.Map.of("requestId", requestId, "streamMode", true)).build());

            // ② 等待流式完成（最多等5分钟，或按 Esc/Enter 取消）
            waitForStreamCompletion();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            currentRequestId = null;
        } finally {
            currentRequestId = null;
        }
    }

    /** ① 启动后台监听线程：流式输出期间按 Esc 中断当前回复 */
    private void startCancelMonitor() {
        Thread cancelMonitor = new Thread(() -> {
            try {
                if (terminal != null) {
                    NonBlockingReader reader = terminal.reader();
                    while (currentRequestId != null && !cancelled) {
                        if (dialogActive) { Thread.sleep(100); continue; }
                        int ch;
                        synchronized (terminalLock) {
                            ch = reader.read(50);
                        }
                        if (ch < 0) continue;
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
                            readLine();
                            cancelled = true;
                            currentRequestId = null;
                            break;
                        }
                        Thread.sleep(200);
                    }
                }
            } catch (Exception e) {
                logger.debug("CancelMonitor error", e);
            }
        }, "CancelMonitor");
        cancelMonitor.setDaemon(true);
        cancelMonitor.start();
    }

    /** ② 等待流式完成（最多等5分钟），超时或被 Esc 中断则输出提示 */
    private void waitForStreamCompletion() throws InterruptedException {
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
    }

    private void printBanner() {
        final String R = "\033[0m";
        final String B = "\033[1m";
        final String D = "\033[2m";
        final String MAGENTA = "\033[38;5;201m";
        final String CYAN = "\033[38;5;51m";
        final String GREEN = "\033[38;5;82m";
        final String BLUE = "\033[38;5;75m";
        final String GRAY = "\033[38;5;242m";

        final int W = 54;

        // 辅助：打印框内一行（自动对齐右侧边框）
        Runnable sep = () -> System.out.println(GRAY + "  │" + " ".repeat(W) + "│" + R);

        System.out.println(GRAY + "  ╭" + "─".repeat(W) + "╮" + R);

        // ── ASCII Art Logo ──
        boxLine(W, "  " + B + MAGENTA + "███╗   ██╗ █████╗ ███╗   ██╗" + R,GRAY);
        boxLine(W, "  " + B + MAGENTA + "████╗  ██║██╔══██╗████╗  ██║" + R,GRAY);
        boxLine(W, "  " + B + CYAN   + "██╔██╗ ██║███████║██╔██╗ ██║" + R,GRAY);
        boxLine(W, "  " + B + CYAN   + "██║╚██╗██║██╔══██║██║╚██╗██║" + R,GRAY);
        boxLine(W, "  " + B + GREEN  + "██║ ╚████║██║  ██║██║ ╚████║" + R,GRAY);
        boxLine(W, "  " + B + GREEN  + "╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝  ╚═══╝" + R,GRAY);
        boxLine(W, "      " + D + "— AI Programming Agent —" + R,GRAY);
        sep.run();

        boxLine(W, "  " + B + "my-nanobot" + R + GRAY + "  v2.3.0  基于 Java 的 AI Agent 编程助手" + R,GRAY);
        sep.run();

        // ── 模型 + 目录 ──
        String model = "deepseek-chat";
        try { var cfg = NanobotRunner.getConfig(); if (cfg != null) model = cfg.getAgents().getDefaults().getModel(); } catch (Exception ignored) {}
        String ws = System.getProperty("nanobot.workspace", System.getProperty("user.dir", "."));
        if (ws.length() > 38) ws = "..." + ws.substring(ws.length() - 35);
        boxLine(W, "  " + B + "模型:" + R + " " + BLUE + model + R + GRAY + "  │  📁 " + ws + R,GRAY);
        sep.run();

        // ── 上次会话 ──
        try {
            var sm = NanobotRunner.getSessionManager();
            if (sm != null) {
                var sessions = sm.listSessionDetails();
                if (!sessions.isEmpty()) {
                    var last = sessions.get(0);
                    String time = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(java.time.Instant.ofEpochMilli(last.lastModified()));
                    boxLine(W, "  " + GRAY + "上次: " + last.key() + " (" + last.messageCount() + " 条, " + time + ")" + R,GRAY);
                    boxLine(W, "  " + GRAY + "输入 " + R + B + "/resume" + R + GRAY + " 恢复，或直接开始对话" + R,GRAY);
                    sep.run();
                }
            }
        } catch (Exception ignored) {}

        // ── 命令提示 ──
        boxLine(W, "  " + B + "/help" + R + GRAY + " 命令  ·  " + R + B + "@文件" + R + GRAY + " 引用  ·  " + R + B + "Esc" + R + GRAY + " 中断回复" + R,GRAY);

        System.out.println(GRAY + "  ╰" + "─".repeat(W) + "╯" + R);
        System.out.println();
    }

    /** 打印框内一行：内容靠左，右侧自动补齐灰色边框 */
    private static void boxLine(int boxWidth, String content, String grayColor) {
        // 去掉 ANSI 转义序列计算可见长度
        String visible = content.replaceAll("\033\\[[0-9;]*m", "");
        int pad = boxWidth - visible.length();
        System.out.println(grayColor + "  │" + content + (pad > 0 ? " ".repeat(pad) : "") + "│" + "\033[0m");
    }
}
