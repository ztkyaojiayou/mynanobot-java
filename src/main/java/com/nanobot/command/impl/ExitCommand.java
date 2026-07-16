package com.nanobot.command.impl;

import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;

import java.util.List;

/** /exit — 退出 CLI 进程。 */
public class ExitCommand implements Command {
    @Override public String name() { return "exit"; }
    @Override public List<String> aliases() { return List.of("q", "quit"); }
    @Override public String description() { return "退出系统"; }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        System.out.println("正在关闭...");
        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            ctx.shutdown().run();
        }).start();
        return true; // 终止 CLI 循环
    }
}
