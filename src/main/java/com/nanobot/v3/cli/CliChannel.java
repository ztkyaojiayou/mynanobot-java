package com.nanobot.v3.cli;

import com.nanobot.NanobotRunner;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.core.AgentLoop;
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
    private final String chatId;
    private final ConfigurableApplicationContext appContext;

    /** 当前流式输出的 requestId（用于等待完成） */
    private volatile String currentRequestId;

    public CliChannel(ConfigurableApplicationContext appContext) {
        this.messageBus = NanobotRunner.getMessageBus();
        this.agentLoop = NanobotRunner.getAgentLoop();
        this.chatId = "cli-" + System.currentTimeMillis();
        this.appContext = appContext;
    }

    public void start() {
        if (messageBus == null || agentLoop == null) {
            System.err.println("CLI 启动失败: MessageBus 或 AgentLoop 未就绪");
            return;
        }

        setupInteractivePermission();

        agentLoop.addStreamResponseCallback(new AgentLoop.StreamResponseCallback() {
            @Override
            public void onStreamData(String sid, String reqId, String content) {
                if (chatId.equals(sid) && currentRequestId != null && currentRequestId.equals(reqId))
                    System.out.print(content);
            }

            @Override
            public void onStreamComplete(String sid, String reqId) {
                if (chatId.equals(sid) && currentRequestId != null && currentRequestId.equals(reqId)) {
                    System.out.println();
                    currentRequestId = null;
                }
            }
        });

        printBanner();
        System.out.println("输入消息开始对话，/exit 退出系统，/clear 清上下文");
        System.out.println();

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                System.out.flush();
                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("/")) {
                    if (handleCommand(line)) break;
                    continue;
                }
                sendMessage(line);
            }
        }
    }

    /** 注册 CLI 交互式权限确认 */
    private void setupInteractivePermission() {
        var registry = NanobotRunner.getToolRegistry();
        if (registry == null || registry.getPermissionManager() == null) return;

        Scanner confirmScanner = new Scanner(System.in);
        registry.getPermissionManager().setInteractiveHandler((tool, params, reason) -> {
            System.out.println();
            System.out.println("⚠️  工具调用需要确认:");
            System.out.println("  工具: " + tool.getName());
            System.out.println("  参数: " + params);
            System.out.println("  原因: " + reason);
            System.out.print("  允许执行? [y/N] ");
            System.out.flush();
            String input = confirmScanner.nextLine().trim().toLowerCase();
            return "y".equals(input) || "yes".equals(input);
        });
    }

    /** 处理 / 命令，返回 true 表示退出 */
    private boolean handleCommand(String cmd) {
        return switch (cmd.toLowerCase()) {
            case "/exit", "/quit", "/q" -> {
                System.out.println("正在关闭系统...");
                new Thread(() -> {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    appContext.close();  // 优雅关闭 Spring Boot
                }).start();
                yield true;
            }
            case "/clear" -> {
                System.out.println("上下文已清空（重启 CLI 或发送新消息即新会话）");
                yield false;
            }
            default -> {
                System.out.println("未知命令: " + cmd + " (可用: /exit, /clear)");
                yield false;
            }
        };
    }

    /** 发送用户消息到 MessageBus */
    private void sendMessage(String content) {
        String requestId = java.util.UUID.randomUUID().toString();
        currentRequestId = requestId;

        try {
            messageBus.publishInbound(InboundMessage.builder()
                    .chatId(chatId).senderId(chatId).content(content).channel("cli")
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
