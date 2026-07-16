package com.nanobot.command.impl;

import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;

/**
 * /init — 分析当前项目并生成 MEMORY.md。
 *
 * 向 AgentLoop 发送一条特殊的分析指令，LLM 用 Glob/Read/Grep 收集项目元数据，
 * 然后生成 MEMORY.md 写入 .nanobot/workspace/memory/。
 */
public class InitCommand implements Command {

    private final MessageBus messageBus;
    private final String sessionId;

    public InitCommand(MessageBus messageBus, String sessionId) {
        this.messageBus = messageBus;
        this.sessionId = sessionId;
    }

    @Override public String name() { return "init"; }
    @Override public String description() { return "分析当前项目并生成 NANOBOT.md"; }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        String prompt = """
                【项目初始化分析】

                请按以下步骤分析当前项目并生成 NANOBOT.md（放在项目根目录）:

                1. 用 Glob 列出项目根目录结构（只含直接子项）
                2. 用 Read 读取构建文件（pom.xml build.gradle package.json 等）
                3. 用 Grep 搜索关键依赖和框架
                4. 用 Glob 了解包结构
                5. 采样 2-3 个核心源文件的开头

                然后调用 write_file 写入项目根目录下的 NANOBOT.md，
                格式参考 Claude Code 的 CLAUDE.md：项目概述、技术栈、目录结构、常用命令、编码约定。

                直接开始执行。
                """;

        try {
            messageBus.publishInbound(InboundMessage.builder()
                    .sessionId(sessionId).senderId(sessionId)
                    .content(prompt).channel("cli").build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("正在分析项目...");
        return false;
    }
}
