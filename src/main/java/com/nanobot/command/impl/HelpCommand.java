package com.nanobot.command.impl;

import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;
import com.nanobot.command.CommandRegistry;
import com.nanobot.v3.tui.TerminalStyle;

import java.util.HashSet;
import java.util.Set;

/** /help — 列出所有命令（含内置命令）。 */
public class HelpCommand implements Command {
    private final CommandRegistry registry;
    public HelpCommand(CommandRegistry registry) { this.registry = registry; }

    @Override public String name() { return "help"; }
    @Override public String description() { return "显示帮助"; }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        System.out.println();
        System.out.println(TerminalStyle.bold("  📋 可用命令"));
        System.out.println(TerminalStyle.dim("  ─────────────────────────────────────────"));

        // 内置命令
        System.out.println("  " + TerminalStyle.bold("/clear")  + TerminalStyle.dim("  — 清除当前会话上下文"));
        System.out.println("  " + TerminalStyle.bold("/exit")   + TerminalStyle.dim("  — 退出（别名 /q, /quit）"));
        System.out.println("  " + TerminalStyle.bold("/history")+ TerminalStyle.dim("  — 查看输入历史"));
        System.out.println("  " + TerminalStyle.bold("!!")      + TerminalStyle.dim("       — 重复上一条命令"));
        System.out.println("  " + TerminalStyle.bold("!N")     + TerminalStyle.dim("       — 重复第 N 条历史命令"));

        // 注册命令
        Set<Command> seen = new HashSet<>();
        for (Command cmd : registry.getCommands()) {
            if (seen.add(cmd)) {
                StringBuilder line = new StringBuilder();
                line.append("  ").append(TerminalStyle.bold("/" + cmd.name()));
                if (!cmd.aliases().isEmpty())
                    line.append(TerminalStyle.dim(" (" + String.join(", ", cmd.aliases()) + ")"));
                line.append(TerminalStyle.dim("  — " + cmd.description()));
                System.out.println(line);
            }
        }

        // 输入语法
        System.out.println(TerminalStyle.dim("  ─────────────────────────────────────────"));
        System.out.println("  " + TerminalStyle.bold("@路径") + TerminalStyle.dim("  — 引用文件内容注入上下文"));
        System.out.println("  " + TerminalStyle.bold("Esc") + TerminalStyle.dim("    — 中断当前 AI 回复"));
        System.out.println();
        return false;
    }
}
