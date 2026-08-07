package com.nanocode.command.impl;

import com.nanocode.command.Command;
import com.nanocode.command.CommandContext;
import com.nanocode.rules.RuleManager;

/**
 * /rules — 查看当前生效的行为规则。
 */
public class RulesCommand implements Command {

    @Override public String name() { return "rules"; }
    @Override public String description() { return "查看当前生效的规则"; }

    @Override
    public String usage() {
        return "  用法: /rules\n  查看当前生效的行为规则";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        var agentLoop = ctx.agentLoop();
        RuleManager rm = agentLoop != null ? agentLoop.getRuleManager() : null;
        if (rm == null) {
            ctx.out().println("规则系统未启用。");
            return false;
        }
        ctx.out().println(rm.getRulesSummary());
        return false;
    }
}
