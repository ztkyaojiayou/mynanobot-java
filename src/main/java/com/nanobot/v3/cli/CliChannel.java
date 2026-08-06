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

    /** 命令历史（最近 50 条） */
    private final java.util.LinkedList<String> inputHistory = new java.util.LinkedList<>();
    private static final int MAX_HISTORY = 50;

    /** thinking spinner 运行中 */
    private volatile boolean thinking;

    /** 上一轮 token 用量（用于 prompt 显示） */
    private volatile int lastTokens = -1;
    /** 当前模型名 */
    private String modelName = "?";

    /**
     * 共享 Scanner — 强制 UTF-8 解码，解决 Win CMD GBK 乱码.
     * 所有 scanner 操作必须通过 {@link #readLine()} 同步访问.
     */
    private Scanner scanner = createUtf8Scanner();

    private static Scanner createUtf8Scanner() {
        // Windows: 切控制台代码页到 UTF-8 (65001)，否则中文输入 GBK→UTF-8 乱码
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                new ProcessBuilder("cmd.exe", "/c", "chcp 65001 > nul").start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
        try {
            return new Scanner(System.in, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new Scanner(System.in);
        }
    }

    private String readLine() {
        synchronized (scanner) {
            try {
                return scanner.nextLine();
            } catch (RuntimeException e) {
                logger.warn("Scanner 异常，重建: {}", e.toString());
                try { scanner.close(); } catch (Exception ignored) {}
                scanner = createUtf8Scanner();
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
        try { var c = NanobotRunner.getConfig(); if (c != null) modelName = c.getAgents().getDefaults().getModel(); } catch (Exception ignored) {}
        // 初始化订阅队列（若 MessageBus 未就绪则延迟到 start()）
        if (messageBus != null) {
            this.subscriberQueue = messageBus.subscribeToOutbound(sessionId);
        }
        this.appContext = appContext;

        // ── 终端检测：CMD(纯文本) / WT(彩色无JLine) / Unix(彩色+JLine) ──
        boolean isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
        boolean isWinTerm = isWin && System.getenv("WT_SESSION") != null;

        // JLine：仅 Unix 初始化（Windows 统一跳过，避免 dumb terminal 警告）
        Terminal t = null;
        if (!isWin) {
            try {
                t = TerminalBuilder.builder().build();
            } catch (Exception e) {
                logger.debug("终端初始化失败: {}", e.getMessage());
            }
        }
        this.terminal = t;

        // ANSI：仅 CMD 关闭，WT/Unix 启用
        if (isWin && !isWinTerm) {
            TerminalStyle.disable();
            logger.info("CMD 终端，ANSI 已关闭");
        } else if (isWinTerm) {
            logger.info("Windows Terminal，ANSI 已启用（无 JLine）");
        } else {
            logger.info("Unix 终端 (jline={})，ANSI 已启用",
                    t != null ? t.getType() : "null");
        }

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
            System.out.println("会话已切换至: " + sessionKey + "，历史上下文将在下一条消息中恢复");
        }));
    }

    public void start() {
        if (messageBus == null || agentLoop == null) {
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
            stopThinking();
            int idx = msg.getMetadataInt("_tool_index", -1);
            int total = msg.getMetadataInt("_tool_total", -1);
            String counter = (idx >= 0 && total > 0) ? idx + "/" + total + " " : "";
            // 提取工具名并着色：content 格式 "🔧 toolName"
            String tn = msg.getContent();
            // 去掉 emoji/空格前缀取工具名
            tn = tn.replaceAll("^[^a-zA-Z]+", "");
            System.out.print("\n  " + TerminalStyle.dim(counter)
                    + TerminalStyle.toolColor(tn) + tn + TerminalStyle.R + " ");
        } else if (msg.isSessionCleared()) {
            if (currentRequestId != null && currentRequestId.equals(msg.getRequestId())) {
                System.out.println();
                currentRequestId = null;
            }
        } else if (msg.isStreamDelta()) {
            if (currentRequestId != null && currentRequestId.equals(msg.getRequestId())) {
                stopThinking();
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
                if (tokens > 0) lastTokens = tokens;
                currentRequestId = null;
                return 0;
            }
        }
        return firstDeltaTime;
    }

    /** ② 打印启动横幅 */
    private void printStartupBanner() {
        int termWidth = terminal != null ? terminal.getWidth() : 80;
        if (termWidth < 40) termWidth = 80; // 容错

        if (TerminalStyle.isEnabled()) {
            printRichBanner(termWidth);
        } else {
            printDumbBanner(termWidth);
        }
        System.out.println();
    }

    /** ③ 主输入循环 */
    private void runInputLoop() {
        while (true) {
            System.out.print(buildPrompt());
            System.out.flush();
            synchronized (scanner) { if (!scanner.hasNextLine()) break; }
            String line = readLine().trim();
            if (line.isEmpty()) continue;

            // ── 历史快捷操作 ──
            if ("!!".equals(line)) {
                if (inputHistory.isEmpty()) { System.out.println(TerminalStyle.dim("暂无历史命令")); continue; }
                line = inputHistory.getLast();
                System.out.println(TerminalStyle.dim("  ! " + line));
            } else if (line.matches("!\\d+")) {
                int idx = Integer.parseInt(line.substring(1)) - 1;
                if (idx < 0 || idx >= inputHistory.size()) {
                    System.out.println(TerminalStyle.warn("历史索引无效: " + line + "（共 " + inputHistory.size() + " 条）"));
                    continue;
                }
                line = inputHistory.get(idx);
                System.out.println(TerminalStyle.dim("  ! " + line));
            }

            if (!inputHistory.isEmpty() && inputHistory.getLast().equals(line)) inputHistory.removeLast();
            inputHistory.addLast(line);
            if (inputHistory.size() > MAX_HISTORY) inputHistory.removeFirst();

            if (line.startsWith("/")) {
                String cmdName = extractCmdName(line);
                if ("clear".equals(cmdName)) { handleClear(); continue; }
                if ("exit".equals(cmdName) || "q".equals(cmdName) || "quit".equals(cmdName)) { handleExit(); return; }
                if ("history".equals(cmdName)) { showHistory(); continue; }
                if (commands.isRegistered(cmdName)) { commands.execute(cmdCtx, line); continue; }
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

        var trusted = new java.util.concurrent.atomic.AtomicBoolean(false);
        final Object confirmLock = new Object(); // 防并发工具调用的确认框穿插

        registry.getPermissionManager().setInteractiveHandler((tool, params, reason) -> {
            if (trusted.get()) return true;

            synchronized (confirmLock) {
                // 双重检查：等待锁期间可能已被其他线程信任
                if (trusted.get()) return true;
                dialogActive = true;
            try {
                System.out.println();
                System.out.println(TerminalStyle.ORANGE + TerminalStyle.B + "  [!] 工具调用确认" + TerminalStyle.R);
                System.out.println("  " + TerminalStyle.bold("工具: ") + TerminalStyle.highlight(tool.getName()));
                String ps = params.toString();
                if (ps.length() > 80) ps = ps.substring(0, 77) + "...";
                System.out.println("  " + TerminalStyle.dim("参数: " + ps));
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

    /** 构建带状态指示的输入提示符 */
    private String buildPrompt() {
        StringBuilder sb = new StringBuilder();
        // 模式指示
        try {
            var pm = cmdCtx.permissionManager();
            if (pm != null) {
                var mode = pm.getMode();
                if (mode == com.nanobot.security.PermissionMode.PLAN)
                    sb.append(TerminalStyle.YELLOW).append(TerminalStyle.B).append("[PLAN] ").append(TerminalStyle.R);
                else if (mode == com.nanobot.security.PermissionMode.ACCEPT_EDITS)
                    sb.append(TerminalStyle.GREEN).append("[EDIT] ").append(TerminalStyle.R);
                else if (mode == com.nanobot.security.PermissionMode.BYPASS)
                    sb.append(TerminalStyle.MAGENTA).append(TerminalStyle.B).append("[BYPASS] ").append(TerminalStyle.R);
            }
        } catch (Exception ignored) {}
        // 模型 + token
        sb.append(TerminalStyle.dim(modelName));
        if (lastTokens > 0) {
            String ts = lastTokens >= 1000 ? String.format("%.1fK", lastTokens / 1000.0) : String.valueOf(lastTokens);
            sb.append(TerminalStyle.dim(" " + ts + "t"));
        }
        sb.append(TerminalStyle.dim(" ")).append(TerminalStyle.B).append("> ").append(TerminalStyle.R);
        return sb.toString();
    }

    private void showHistory() {
        if (inputHistory.isEmpty()) { System.out.println(TerminalStyle.dim("暂无历史命令")); return; }
        System.out.println(TerminalStyle.dim("最近命令（!! 重复上条，!N 指定序号）:"));
        int idx = 1;
        for (String h : inputHistory) {
            System.out.printf("  %s%2d%s %s%n",
                    TerminalStyle.GRAY, idx++, TerminalStyle.R,
                    h.length() > 60 ? h.substring(0, 57) + "..." : h);
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
            System.out.println("⚠  危险路径已拒绝: " + cleaned);
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

        System.out.println("⚠  文件未找到: " + rawPath);
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
            System.out.println("📂 已注入目录: " + rawPath + " (" + children.size() + " 项)");
            return sb.toString();
        } catch (IOException e) {
            System.out.println("⚠  无法列出目录: " + rawPath);
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
                        System.out.println("⚠  二进制文件，跳过: " + rawPath);
                        return "@" + rawPath;
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("⚠  无法读取文件: " + rawPath + " (" + e.getMessage() + ")");
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
            System.out.println("⚠  无法读取文件: " + rawPath + " (" + e.getMessage() + ")");
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

            startThinkingSpinner();
            waitForStreamCompletion();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            currentRequestId = null;
        } finally {
            stopThinking();
            currentRequestId = null;
        }
    }

    private void startThinkingSpinner() {
        thinking = true;
        Thread spinner = new Thread(() -> {
            String[] frames = {"|", "/", "-", "\\"};
            int i = 0;
            try {
                while (thinking) {
                    System.out.print("\r  " + TerminalStyle.dim(frames[i] + " Thinking...") + " \r");
                    System.out.flush();
                    i = (i + 1) % frames.length;
                    Thread.sleep(120);
                }
                System.out.print("\r" + " ".repeat(30) + "\r");
            } catch (InterruptedException ignored) {}
        }, "CLI-spinner");
        spinner.setDaemon(true);
        spinner.start();
    }

    private void stopThinking() { if (thinking) thinking = false; }

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

    // ═══════════════════════════════════════════════════════
    //  Banner 渲染（双分支）
    // ═══════════════════════════════════════════════════════

    /** 正常终端：双栏布局 + ANSI 颜色 + Unicode 边框 */
    private void printRichBanner(int w) {
        String R = TerminalStyle.R, B = TerminalStyle.B, D = TerminalStyle.D;
        String CYAN = TerminalStyle.CYAN, GREEN = TerminalStyle.GREEN;
        String BLUE = TerminalStyle.BLUE, GRAY = TerminalStyle.GRAY;
        String MAGENTA = TerminalStyle.MAGENTA;

        boolean doubleCol = w >= 80;
        int leftW = doubleCol ? w - 30 : w - 4; // 右侧面板占 30 列

        // 模型、目录
        String model = "deepseek-chat";
        try { var c = NanobotRunner.getConfig(); if (c != null) model = c.getAgents().getDefaults().getModel(); } catch (Exception ignored) {}
        String ws = System.getProperty("nanobot.workspace", System.getProperty("user.dir", "."));
        if (ws.length() > leftW - 12) {
            int cut = ws.length() - (leftW - 15);
            int sep = Math.max(ws.indexOf('\\', cut), ws.indexOf('/', cut));
            if (sep >= 0 && sep < ws.length() - 1) cut = sep + 1;
            ws = "..." + ws.substring(cut);
        }

        // 上次会话
        String lastSession = null;
        try { var sm = NanobotRunner.getSessionManager(); if (sm != null) { var s = sm.listSessionDetails(); if (!s.isEmpty()) { var l = s.get(0); String t = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(l.lastModified())); lastSession = l.key() + " (" + l.messageCount() + " 条, " + t + ")"; } } } catch (Exception ignored) {}

        // ═══ 顶部装饰线 ═══
        println(GRAY + "  ┏" + "━".repeat(leftW) + (doubleCol ? "┳" + "━".repeat(28) : "") + "┓" + R);

        // ═══ Logo 行 ═══
        println(padR("  ┃  " + B + CYAN + "NANO-BOT" + R + GRAY + "  v2.3.0 — AI Programming Agent" + R, leftW, GRAY, doubleCol, D + "[ 编程搭档 ]" + R));

        // ═══ 分隔 ═══
        println(GRAY + "  ┣" + "━".repeat(leftW) + (doubleCol ? "╋" + "━".repeat(28) : "") + "┫" + R);

        // ═══ 模型 + 目录 ═══
        println(padR("  ┃  " + B + "模型:" + R + " " + BLUE + model + R, leftW, GRAY, doubleCol, B + "/help" + R + GRAY + " 查看命令" + R));

        // ═══ 工作目录 ═══
        println(padR("  ┃  " + GRAY + " ~ " + ws + R, leftW, GRAY, doubleCol, B + "/resume" + R + GRAY + " 恢复会话" + R));

        // ═══ 上次会话 ═══
        if (lastSession != null) {
            println(padR("  ┃  " + GRAY + "上次: " + lastSession + R, leftW, GRAY, doubleCol, GRAY + "!!" + R + GRAY + " 重复上条命令" + R));
        }

        // ═══ 空行 ═══
        println(padR("  ┃  ", leftW, GRAY, doubleCol, B + "@文件" + R + GRAY + " 引用上下文" + R));

        // ═══ 底部 ═══
        println(GRAY + "  ┗" + "━".repeat(leftW) + (doubleCol ? "┻" + "━".repeat(28) : "") + "┛" + R);
    }

    /** dumb 终端：纯 ASCII 单栏，无 ANSI、无 emoji、无 Unicode */
    private void printDumbBanner(int w) {
        int bw = Math.min(w, 80) - 4; // 内容宽
        String line = "+" + "-".repeat(bw) + "+";

        System.out.println(line);
        System.out.println(padAscii("|  *** NANO-BOT v2.3.0 ***", bw));
        System.out.println(padAscii("|  AI Programming Agent", bw));
        System.out.println(padAscii("|", bw));

        String model = "deepseek-chat";
        try { var c = NanobotRunner.getConfig(); if (c != null) model = c.getAgents().getDefaults().getModel(); } catch (Exception ignored) {}
        String ws = System.getProperty("nanobot.workspace", System.getProperty("user.dir", "."));
        if (ws.length() > bw - 12) ws = "..." + ws.substring(ws.length() - (bw - 15));

        System.out.println(padAscii("|  模型: " + model, bw));
        System.out.println(padAscii("|  目录: " + ws, bw));

        String lastSession = null;
        try { var sm = NanobotRunner.getSessionManager(); if (sm != null) { var s = sm.listSessionDetails(); if (!s.isEmpty()) { var l = s.get(0); String t = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(l.lastModified())); lastSession = l.key() + " (" + l.messageCount() + " 条, " + t + ")"; } } } catch (Exception ignored) {}
        if (lastSession != null) {
            System.out.println(padAscii("|  上次: " + lastSession, bw));
            System.out.println(padAscii("|  输入 /resume 恢复，或直接开始对话", bw));
        }

        System.out.println(padAscii("|", bw));
        System.out.println(padAscii("|  /help 命令  |  !! 重复  |  @文件 引用  |  Esc 中断", bw));
        System.out.println(line);
    }

    // ── 渲染辅助 ──

    /** 打印一行（含双栏自动对齐 + ANSI 过滤） */
    private static void println(String s) {
        System.out.println(TerminalStyle.filter(s));
    }

    /** 左栏内容 + 可选右栏，自动补齐到 leftW 宽度 */
    private static String padR(String left, int leftW, String gray, boolean doubleCol, String right) {
        // 计算可见长度（去 ANSI）
        String v = left.replaceAll("\033\\[[0-9;]*m", "");
        int pad = leftW - v.length() + 1; // +1 因为 ┃ 占位
        String s = left + (pad > 0 ? " ".repeat(pad) : " ");
        if (doubleCol) {
            s += gray + "┃  " + right + TerminalStyle.R;
        }
        return s + gray + "┃" + TerminalStyle.R;
    }

    /** ASCII 行：内容左对齐，右侧补齐 | */
    private static String padAscii(String content, int width) {
        // 去 ANSI 计算可见长度
        String v = content.replaceAll("\033\\[[0-9;]*m", "");
        int pad = width - v.length();
        return content + (pad > 0 ? " ".repeat(pad) : "") + "|";
    }
}
