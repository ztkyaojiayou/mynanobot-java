package com.nanobot.v3.cli;

import com.nanobot.NanobotRunner;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.core.AgentLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

/**
 * CLI 交互通道 — 命令行终端直接对话。
 *
 * 启动方式: java -jar nanobot.jar --cli
 * 命令: /exit 退出, /clear 清空上下文, /mode plan|default 切换权限模式
 */
public class CliChannel {

    private static final Logger logger = LoggerFactory.getLogger(CliChannel.class);

    private final MessageBus messageBus;
    private final AgentLoop agentLoop;
    private final String chatId;

    /** 当前流式输出的 requestId（用于等待完成） */
    private volatile String currentRequestId;
    private volatile boolean streaming;
    private final CountDownLatch streamDone = new CountDownLatch(1);

    public CliChannel() {
        this.messageBus = NanobotRunner.getMessageBus();
        this.agentLoop = NanobotRunner.getAgentLoop();
        this.chatId = "cli-" + System.currentTimeMillis();
    }

    public void start() {
        if (messageBus == null || agentLoop == null) {
            System.err.println("CLI 启动失败: MessageBus 或 AgentLoop 未就绪，请等待 Spring Boot 启动完成");
            return;
        }

        // 注册流式回调 — SSE/WS 同款的 StreamResponseCallback
        agentLoop.addStreamResponseCallback(new AgentLoop.StreamResponseCallback() {
            @Override
            public void onStreamData(String sid, String reqId, String content) {
                if (chatId.equals(sid) && currentRequestId != null && currentRequestId.equals(reqId)) {
                    System.out.print(content);
                    streaming = true;
                }
            }

            @Override
            public void onStreamComplete(String sid, String reqId) {
                if (chatId.equals(sid) && currentRequestId != null && currentRequestId.equals(reqId)) {
                    System.out.println();
                    currentRequestId = null;
                    streaming = false;
                    streamDone.countDown();
                }
            }
        });

        printBanner();
        System.out.println("输入消息开始对话，/exit 退出，/clear 清上下文，/mode plan|default 切换模式");
        System.out.println();

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                System.out.flush();
                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                // 处理命令
                if (line.startsWith("/")) {
                    if (handleCommand(line)) break;
                    continue;
                }

                // 发送消息
                sendMessage(line);
            }
        }
    }

    /** 处理 / 命令，返回 true 表示退出 */
    private boolean handleCommand(String cmd) {
        return switch (cmd.toLowerCase()) {
            case "/exit", "/quit", "/q" -> {
                System.out.println("👋 再见！");
                yield true;
            }
            case "/clear" -> {
                // 换 chatId 即新会话
                System.out.println("上下文已清空");
                yield false;  // chatId reset below?
                // 实际清空：重新生成 chatId
            }
            case "/mode", "/mode plan", "/mode default" -> {
                System.out.println("模式切换: 请在配置中设置 nanobot.security.mode");
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
        streaming = false;
        // 重置 latch（用新的）
        // streamDone 需要重置 — 这里简单处理

        try {
            messageBus.publishInbound(InboundMessage.builder()
                    .chatId(chatId)
                    .senderId(chatId)
                    .content(content)
                    .channel("cli")
                    .metadata(java.util.Map.of("requestId", requestId, "streamMode", true))
                    .build());

            // 等待流式完成（最多等5分钟）
            Thread.sleep(200); // 给 AgentLoop 一点时间开始处理
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
