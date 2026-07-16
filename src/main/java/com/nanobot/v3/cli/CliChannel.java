package com.nanobot.v3.cli;

import com.nanobot.NanobotRunner;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.command.CommandContext;
import com.nanobot.command.CommandRegistry;
import com.nanobot.command.impl.ExitCommand;
import com.nanobot.command.impl.HelpCommand;
import com.nanobot.command.impl.InitCommand;
import com.nanobot.command.impl.ModeCommand;
import com.nanobot.core.AgentLoop;
import com.nanobot.tools.impl.AskUserTool;
import com.nanobot.v3.tui.MarkdownRenderer;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Scanner;

/**
 * CLI 交互通道 — 命令行终端直接对话。
 *
 * 命令: /exit 退出系统, /clear 清空上下文
 */
public class CliChannel {

    private final MessageBus messageBus;
    private final AgentLoop agentLoop;
    private final String sessionId;
    private final CommandRegistry commands;
    private final CommandContext cmdCtx;

    private final ConfigurableApplicationContext appContext;

    /** 当前流式输出的 requestId（用于等待完成） */
    private volatile String currentRequestId;

    /** 共享 Scanner — 整个 CLI 共用一个 System.in 读取器，避免多 Scanner 抢输入 */
    private final Scanner scanner = new Scanner(System.in);

    public CliChannel(ConfigurableApplicationContext appContext) {
        this.messageBus = NanobotRunner.getMessageBus();
        this.agentLoop = NanobotRunner.getAgentLoop();
        this.sessionId = "cli-" + System.currentTimeMillis();
        this.appContext = appContext;

        // 初始化命令注册中心
        var registry = NanobotRunner.getToolRegistry();
        this.cmdCtx = new CommandContext(registry,
                registry != null ? registry.getPermissionManager() : null,
                agentLoop,
                sessionId,
                appContext::close);
        this.commands = new CommandRegistry();
        this.commands.register(new ExitCommand());
        this.commands.register(new ModeCommand());
        this.commands.register(new HelpCommand(commands));
        this.commands.register(new InitCommand());
    }

    public void start() {
        if (messageBus == null || agentLoop == null) {
            System.err.println("CLI 启动失败: MessageBus 或 AgentLoop 未就绪");
            return;
        }

        setupInteractivePermission();
        setupAskUserHandler();
        //监听响应并流式输出到控制台！
        agentLoop.addStreamResponseCallback(new AgentLoop.StreamResponseCallback() {
            @Override
            public void onStreamData(String sid, String reqId, String content) {
                if (sessionId.equals(sid) && currentRequestId != null && currentRequestId.equals(reqId))
                    System.out.print(MarkdownRenderer.renderStreaming(content));
            }

            @Override
            public void onStreamComplete(String sid, String reqId) {
                if (sessionId.equals(sid) && currentRequestId != null && currentRequestId.equals(reqId)) {
                    System.out.println();
                    currentRequestId = null;
                }
            }
        });

        printBanner();
        System.out.println("输入消息开始对话，/exit 退出系统，/clear 清上下文");
        System.out.println();

        //持续监听用户输入，这就是入口！！！
        while (true) {
            System.out.print("> ");
            System.out.flush();
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            //若是命令，直接处理
            if (line.startsWith("/")) {
                if (handleCommand(line)) break;
                continue;
            }
            //若是常规对话，发送到消息总线
            sendMessage(line);
        }
    }

    /** 注册 CLI 交互式权限确认 */
    private void setupInteractivePermission() {
        var registry = NanobotRunner.getToolRegistry();
        if (registry == null || registry.getPermissionManager() == null) return;

        registry.getPermissionManager().setInteractiveHandler((tool, params, reason) -> {
            System.out.println();
            System.out.println("⚠️  工具调用需要确认:");
            System.out.println("  工具: " + tool.getName());
            System.out.println("  参数: " + params);
            System.out.println("  原因: " + reason);
            System.out.print("  允许执行? [y/N] ");
            System.out.flush();
            String input = scanner.nextLine().trim().toLowerCase();
            return "y".equals(input) || "yes".equals(input);
        });
    }

    /** 注入 AskUserTool 的 CLI 交互处理器 */
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

    /** 处理 / 命令，返回 true 表示退出循环 */
    private boolean handleCommand(String cmd) {
        String cmdName = cmd.length() > 1 ? cmd.substring(1).trim().split("\\s+")[0].toLowerCase() : "";

        // /clear 需要访问 CliChannel 的 chatId，不便抽成独立 Command，保留 inline
        if ("clear".equals(cmdName)) {
            System.out.println("上下文已清空");
            return false;
        }

        commands.execute(cmdCtx, cmd);
        return "exit".equals(cmdName) || "q".equals(cmdName) || "quit".equals(cmdName);
    }

    /** 发送用户消息到 MessageBus */
    private void sendMessage(String content) {
        String requestId = java.util.UUID.randomUUID().toString();
        currentRequestId = requestId;

        try {
            messageBus.publishInbound(InboundMessage.builder()
                    .sessionId(sessionId).senderId(sessionId).content(content).channel("cli")
                    .metadata(java.util.Map.of("requestId", requestId, "streamMode", true))
                    .build());

            // 等待流式完成（最多等5分钟）
            Thread.sleep(200);
            int waited = 0;
            while (currentRequestId != null && waited < 300_000) {
                Thread.sleep(100);
                waited += 100;
            }
            if (currentRequestId != null) {
                System.out.println("\n[超时]");
                currentRequestId = null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("发送中断");
        }
    }

    private void printBanner() {
        System.out.println("""
                ╔══════════════════════════════════╗
                ║       my-nanobot CLI 模式       ║
                ║  基于 Java Agent 框架的 AI 助手  ║
                ╚══════════════════════════════════╝""");
    }
}
