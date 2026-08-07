package com.nanocode.command;

import com.nanocode.command.impl.ClearCommand;
import com.nanocode.command.impl.CompactCommand;
import com.nanocode.command.impl.CostCommand;
import com.nanocode.command.impl.ExitCommand;
import com.nanocode.command.impl.PermissionsCommand;
import com.nanocode.command.impl.RememberCommand;
import com.nanocode.command.impl.RulesCommand;
import com.nanocode.command.impl.SkillsCommand;
import com.nanocode.command.impl.StatsCommand;
import com.nanocode.command.impl.StopCommand;

import java.util.*;

/**
 * 命令注册中心 — 统一管理所有 CLI/WS/HTTP 命令。
 * <p>
 * 使用:
 * <pre>
 *   CommandRegistry registry = CommandRegistry.buildBase(); // 全部内置命令
 *   registry.register(new HistoryCommand(history));         // 通道专属命令
 *   Optional&lt;Boolean&gt; result = registry.execute(ctx, "/mode plan");
 *   // result.empty()      → 未注册命令，调用方可 fallback 到技能
 *   // result.of(true)     → 命令已执行，且请求退出（/exit）
 *   // result.of(false)    → 命令已执行，正常继续
 * </pre>
 */
public class CommandRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    /**
     * 注册全部内置命令（每通道调用一次，新建独立实例，
     * 避免 CLI 追加 /history 等通道专属命令污染 Web 的 registry）。
     */
    public static CommandRegistry buildBase() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new ExitCommand());
        registry.register(new ClearCommand());
        registry.register(new CompactCommand());
        registry.register(new RememberCommand());
        registry.register(new SkillsCommand());
        registry.register(new RulesCommand());
        registry.register(new StatsCommand());
        registry.register(new StopCommand());
        registry.register(new CostCommand());
        registry.register(new PermissionsCommand());
        return registry;
    }

    /**
     * 注册命令
     */
    public void register(Command cmd) {
        commands.put(cmd.name().toLowerCase(), cmd);
        //别名也按正常的命令名注册进去
        for (String alias : cmd.aliases()) {
            commands.put(alias.toLowerCase(), cmd);
        }
    }

    /** 检查命令是否已注册 */
    public boolean isRegistered(String name) {
        return name != null && commands.containsKey(name.toLowerCase());
    }

    /**
     * 按名称（支持别名、可带 / 前缀）查找命令。供 /help 命令 使用。
     */
    public Optional<Command> find(String name) {
        if (name == null) return Optional.empty();
        String clean = name.startsWith("/") ? name.substring(1) : name;
        Command cmd = commands.get(clean.toLowerCase());
        return cmd != null ? Optional.of(cmd) : Optional.empty();
    }

    /**
     * 去重后的命令列表（name + aliases 指向同一实例，这里只保留一份）。
     * 供 /help 遍历用。
     */
    public List<Command> listUnique() {
        Set<Command> seen = new HashSet<>();
        return commands.values().stream().filter(seen::add).toList();
    }

    /** 获取所有注册的命令（含别名条目，遍历时注意去重） */
    public Collection<Command> getCommands() {
        return commands.values();
    }

    /**
     * 匹配并执行命令。
     *
     * @param ctx   命令上下文
     * @param input 用户输入行（以 / 开头）
     * @return 空 Optional 表示未注册命令（调用方可 fallback 到技能）；
     *         Optional.of(boolean) 表示已执行，boolean=true 请求退出进程（/exit）
     */
    public Optional<Boolean> execute(CommandContext ctx, String input) {
        if (input == null || !input.startsWith("/")) return Optional.empty();

        String trimmed = input.substring(1).trim();
        if (trimmed.isEmpty()) return Optional.empty();

        // 提取命令名（空格前）
        int space = trimmed.indexOf(' ');
        String cmdName = (space > 0 ? trimmed.substring(0, space) : trimmed).toLowerCase();
        Command cmd = commands.get(cmdName);
        if (cmd == null) return Optional.empty(); // 未注册 → 交调用方 fallback

        // 执行命令，透传终止信号（原来这里把返回值丢弃了）
        return Optional.of(cmd.execute(ctx, input));
    }

    /**
     * 列出所有命令的帮助信息
     */
    public String helpText() {
        var sb = new StringBuilder("可用命令:\n");
        for (Command cmd : listUnique()) {
            sb.append("  /").append(cmd.name());
            if (!cmd.aliases().isEmpty())
                sb.append(" (").append(String.join(", ", cmd.aliases())).append(")");
            sb.append("  — ").append(cmd.description()).append("\n");
        }
        return sb.toString();
    }
}
