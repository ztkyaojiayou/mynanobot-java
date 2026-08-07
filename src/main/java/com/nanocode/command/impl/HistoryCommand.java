package com.nanocode.command.impl;

import com.nanocode.command.Command;
import com.nanocode.command.CommandContext;

import java.util.List;

/**
 * /history — 查看输入历史（CLI 专用）。
 *
 * 历史列表由 CLI 主循环共享（构造注入），Web 通道不注册。
 */
public class HistoryCommand implements Command {

    private final List<String> history;

    public HistoryCommand(List<String> history) { this.history = history; }

    @Override public String name() { return "history"; }
    @Override public String description() { return "查看输入历史"; }

    @Override
    public String usage() {
        return "  用法: /history\n  查看输入历史；!! 重复上条，!N 重复第 N 条";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        if (history == null || history.isEmpty()) {
            ctx.out().println("暂无历史命令");
            return false;
        }
        ctx.out().println("最近命令（!! 重复上条，!N 指定序号）:");
        int idx = 1;
        for (String h : history) {
            String trimmed = h.length() > 60 ? h.substring(0, 57) + "..." : h;
            ctx.out().printf("  %2d %s%n", idx++, trimmed);
        }
        return false;
    }
}
