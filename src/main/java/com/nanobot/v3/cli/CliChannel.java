package com.nanobot.v3.cli;

import com.nanobot.NanobotRunner;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.command.CommandContext;
import com.nanobot.command.CommandRegistry;
import com.nanobot.command.impl.ExitCommand;
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
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        this.commands.register(new ExitCommand());
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
                    if (msg.isStreamDelta()) {
                        if (currentRequestId != null && currentRequestId.equals(msg.getRequestId())) {
                            System.out.print(MarkdownRenderer.renderStreaming(msg.getContent()));
                        }
                    } else if (msg.isStreamEnd()) {
                        // 流式输出结束，重置 requestId
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
        System.out.println("输入消息开始对话，/exit 退出系统，/clear 清上下文，Esc 中断当前回复");
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

        // /clear：直接调 SessionManager，不经过 MessageBus。
        // 因为 sendMessage 会等待 _stream_end（流式完成信号），
        // 但 /clear 是 AgentLoop 短路命令不产生流式输出，会等到超时。
        if ("clear".equals(cmdName)) {
            var sm = NanobotRunner.getSessionManager();
            if (sm != null) {
                sm.clearSession(sessionId);
                System.out.println("会话已清除。");
            } else {
                System.out.println("会话管理器未就绪。");
            }
            return false;
        }
        //其他命令
        commands.execute(cmdCtx, cmd);
        return "exit".equals(cmdName) || "q".equals(cmdName) || "quit".equals(cmdName);
    }

    /**
     * 发送用户消息到 MessageBus
     */
    private void sendMessage(String content) {
        String requestId = java.util.UUID.randomUUID().toString();
        currentRequestId = requestId;
        cancelled = false;

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
