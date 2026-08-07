package com.nanobot.command.impl;

import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;

import java.util.List;

/**
 * /exit (/q /quit) — 退出程序。
 *
 * 返回 true 通知 CLI 主循环退出；Web 通道无实际意义（只打印提示）。
 * 复用原 CliChannel.handleExit 的延迟关闭行为，让提示先输出再关容器。
 */
public class ExitCommand implements Command {

    @Override public String name() { return "exit"; }
    @Override public List<String> aliases() { return List.of("q", "quit"); }
    @Override public String description() { return "退出程序（别名 /q /quit）"; }

    @Override
    public String usage() {
        return "  用法: /exit | /q | /quit\n  退出程序并关闭所有通道";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        ctx.out().println("正在关闭...");
        if (ctx.shutdown() != null) {
            new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                ctx.shutdown().run();
            }).start();
        }
        return true; // 通知 CLI 主循环退出
    }
}
