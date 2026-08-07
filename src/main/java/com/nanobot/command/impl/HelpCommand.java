package com.nanobot.command.impl;

import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;
import com.nanobot.command.CommandRegistry;

import java.io.PrintStream;

/**
 * /help — 列出所有命令（含技能斜杠调用提示），或查看单个命令的用法。
 *
 * <pre>
 *   /help           → 列出全部命令 + 技能
 *   /help mode      → 查看 /mode 的详细用法（支持别名，如 /help plan）
 * </pre>
 *
 * 统一经 ctx.out() 输出、无 ANSI 着色（CLI/Web 共用同一命令类）。
 */
public class HelpCommand implements Command {
    private final CommandRegistry registry;
    public HelpCommand(CommandRegistry registry) { this.registry = registry; }

    @Override public String name() { return "help"; }
    @Override public String description() { return "显示帮助"; }

    @Override
    public String usage() {
        return "  用法: /help [命令名]\n"
             + "  无参数: 列出全部命令\n"
             + "  /help 命令名: 查看单个命令的详细用法（支持别名，如 /help plan）";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        var out = ctx.out();
        String arg = parseArg(input);
        if (!arg.isBlank()) {
            return showCommandHelp(ctx, arg, out);
        }
        return showAllCommands(ctx, out);
    }

    /** 提取参数（去掉 /help 前缀） */
    private String parseArg(String input) {
        if (input == null) return "";
        String trimmed = input.startsWith("/") ? input.substring(1).trim() : input.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(space + 1).trim() : "";
    }

    /** /help &lt;命令&gt; — 查看单个命令用法 */
    private boolean showCommandHelp(CommandContext ctx, String arg, PrintStream out) {
        var found = registry.find(arg);
        if (found.isEmpty()) {
            out.println("未知命令: /" + arg + "（输入 /help 查看全部命令）");
            return false;
        }
        Command cmd = found.get();
        out.println("  /" + cmd.name());
        if (!cmd.aliases().isEmpty()) {
            out.println("  别名: " + String.join(", ", cmd.aliases()));
        }
        out.println("  描述: " + cmd.description());
        String usage = cmd.usage();
        if (usage != null && !usage.isBlank()) {
            out.println(usage);
        }
        return false;
    }

    /** /help — 列出全部命令 + 技能 */
    private boolean showAllCommands(CommandContext ctx, PrintStream out) {
        out.println();
        out.println("  === 可用命令 ===");
        out.println("  ─────────────────────────────────────────");

        for (Command cmd : registry.listUnique()) {
            StringBuilder line = new StringBuilder();
            line.append("  /").append(cmd.name());
            if (!cmd.aliases().isEmpty())
                line.append(" (").append(String.join(", ", cmd.aliases())).append(")");
            line.append("  — ").append(cmd.description());
            out.println(line);
        }

        // 技能斜杠调用
        var agentLoop = ctx.agentLoop();
        if (agentLoop != null && agentLoop.getSkillManager() != null) {
            var skills = agentLoop.getSkillManager().getRegistry().getAllSkills();
            if (!skills.isEmpty()) {
                out.println("  ─────────────────────────────────────────");
                out.println("  === 技能（/技能名 直接调用）===");
                for (var skill : skills) {
                    out.println("  /" + skill.getName() + "  — " + skill.getDescription());
                }
            }
        }

        // 输入语法
        out.println("  ─────────────────────────────────────────");
        out.println("  输入 /help <命令> 查看单个命令用法");
        out.println("  @路径  — 引用文件内容注入上下文");
        out.println("  !命令  — 直接执行 shell 命令（如 !git status）");
        out.println("  Esc    — 中断当前 AI 回复");
        out.println();
        return false;
    }
}
