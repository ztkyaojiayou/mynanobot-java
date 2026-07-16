package com.nanobot.command;

import java.util.*;

/**
 * 命令注册中心 — 统一管理所有 CLI/WS/HTTP 命令。
 *
 * 使用:
 * <pre>
 *   CommandRegistry registry = new CommandRegistry();
 *   registry.register(new ExitCommand());
 *   registry.register(new ModeCommand());
 *   boolean shouldExit = registry.execute(ctx, "/mode plan");
 * </pre>
 */
public class CommandRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    /** 注册命令 */
    public void register(Command cmd) {
        commands.put(cmd.name().toLowerCase(), cmd);
        for (String alias : cmd.aliases()) {
            commands.put(alias.toLowerCase(), cmd);
        }
    }

    /**
     * 执行命令。
     * @param ctx   命令上下文
     * @param input 用户输入行（以 / 开头）
     * @return true 表示需要终止当前进程
     */
    public Optional<String> execute(CommandContext ctx, String input) {
        if (input == null || !input.startsWith("/")) return Optional.empty();

        String trimmed = input.substring(1).trim();
        if (trimmed.isEmpty()) return Optional.empty();

        // 提取命令名（空格前）
        int space = trimmed.indexOf(' ');
        String cmdName = (space > 0 ? trimmed.substring(0, space) : trimmed).toLowerCase();

        Command cmd = commands.get(cmdName);
        if (cmd == null) {
            System.out.println("未知命令: " + input + " (输入 /help 查看可用命令)");
            return Optional.empty();
        }

        cmd.execute(ctx, input);
        return Optional.empty();
    }

    /** 列出所有命令的帮助信息 */
    public String helpText() {
        var sb = new StringBuilder("可用命令:\n");
        Set<Command> seen = new HashSet<>();
        for (Command cmd : commands.values()) {
            if (seen.add(cmd)) {
                sb.append("  /").append(cmd.name());
                if (!cmd.aliases().isEmpty())
                    sb.append(" (").append(String.join(", ", cmd.aliases())).append(")");
                sb.append("  — ").append(cmd.description()).append("\n");
            }
        }
        return sb.toString();
    }
}
