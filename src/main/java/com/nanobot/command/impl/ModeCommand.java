package com.nanobot.command.impl;

import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;
import com.nanobot.security.PermissionMode;

/** /mode — 查看或切换权限模式。 */
public class ModeCommand implements Command {
    @Override public String name() { return "mode"; }
    @Override public String description() { return "切换权限模式 (plan|default|accept_edits|bypass)"; }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        String arg = input.length() > 5 ? input.substring(6).trim() : "";
        var pm = ctx.permissionManager();
        if (pm == null) {
            System.out.println("权限管理器未就绪");
        } else if (arg.isEmpty()) {
            System.out.println("当前模式: " + pm.getMode());
            System.out.println("用法: /mode plan | default | accept_edits | bypass");
        } else {
            try {
                pm.setMode(PermissionMode.fromString(arg));
                System.out.println("已切换至: " + pm.getMode());
            } catch (IllegalArgumentException e) {
                System.out.println("无效模式: " + arg);
            }
        }
        return false;
    }
}
