package com.nanobot.command.impl;

import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;
import com.nanobot.security.PermissionManager;
import com.nanobot.security.PermissionMode;

import java.io.PrintStream;

/**
 * /permissions — 查看权限系统状态，或切换权限模式。
 *
 * <p>与 /mode 互补：/mode 负责模式切换 + plan 工作流（/plan → /plan approve）；
 * /permissions 提供权限状态总览（当前模式 / 交互确认 / 守卫 / 规则 / 钩子）+ 快速切换。
 *
 * <p>用法：
 * <pre>
 *   /permissions            → 显示权限状态摘要
 *   /permissions plan       → 切换到规划模式
 *   /permissions bypass     → 切换到绕过模式
 * </pre>
 */
public class PermissionsCommand implements Command {

    @Override public String name() { return "permissions"; }
    @Override public String description() { return "查看权限状态或切换模式 (plan/default/accept_edits/bypass)"; }

    @Override
    public String usage() {
        return "  用法: /permissions [模式]\n"
             + "  无参数: 查看权限状态总览\n"
             + "  /permissions plan|default|accept_edits|bypass: 切换权限模式";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        PermissionManager pm = ctx.permissionManager();
        var out = ctx.out();
        if (pm == null) {
            out.println("权限管理器未就绪");
            return false;
        }

        String arg = parseArg(input);
        if (!arg.isBlank()) {
            return switchMode(pm, arg, out);
        }
        return showStatus(pm, ctx, out);
    }

    /** 提取命令参数（去掉 /permissions 前缀） */
    private String parseArg(String input) {
        if (input == null) return "";
        String trimmed = input.startsWith("/") ? input.substring(1).trim() : input.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(space + 1).trim() : "";
    }

    /** /permissions &lt;mode&gt; — 切换权限模式（不碰 plan 工作流，那是 /mode 的职责） */
    private boolean switchMode(PermissionManager pm, String arg, PrintStream out) {
        try {
            PermissionMode mode = PermissionMode.fromString(arg);
            pm.setMode(mode);
            out.println("已切换权限模式: " + pm.getMode());
        } catch (IllegalArgumentException e) {
            out.println("无效模式: " + arg + "。可用: plan, default, accept_edits, bypass");
        }
        return false;
    }

    /** /permissions — 显示权限状态摘要 */
    private boolean showStatus(PermissionManager pm, CommandContext ctx, PrintStream out) {
        out.println("🔐 权限状态");
        out.println("  ─────────────────────────────────────────");
        out.println("  当前模式: " + pm.getMode());

        var loop = ctx.agentLoop();
        if (loop != null && loop.isPlanMode()) {
            out.println("  规划模式: 激活中");
        }

        out.println("  交互确认: " + (pm.getInteractiveHandler() != null ? "开启" : "关闭"));
        out.println("  路径守卫: " + (pm.getPathGuard() != null ? "已启用" : "未启用"));
        out.println("  命令守卫: " + (pm.getCommandGuard() != null ? "已启用" : "未启用"));
        out.println("  网络守卫: " + (pm.getNetworkGuard() != null ? "已启用" : "未启用"));
        out.println("  规则引擎: " + (pm.getRuleEngine() != null ? pm.getRuleEngine().size() + " 条规则" : "未启用"));
        out.println("  Hook 管理: " + (pm.getHookManager() != null ? pm.getHookManager().size() + " 个" : "未启用"));

        out.println();
        out.println("  用法: /permissions plan | default | accept_edits | bypass");
        out.println("  Plan 工作流请用 /mode (/plan)");
        return false;
    }
}
