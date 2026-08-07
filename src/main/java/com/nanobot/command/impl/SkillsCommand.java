package com.nanobot.command.impl;

import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;
import com.nanobot.skill.SkillManager;

/**
 * /skills — 列出所有已加载技能。
 *
 * 与技能 slash 调用并存：/skills 查看目录，/xxx 直接调用某个技能。
 */
public class SkillsCommand implements Command {

    @Override public String name() { return "skills"; }
    @Override public String description() { return "列出已加载的技能"; }

    @Override
    public String usage() {
        return "  用法: /skills\n  列出已加载技能；/技能名 直接调用某个技能";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        var agentLoop = ctx.agentLoop();
        SkillManager sm = agentLoop != null ? agentLoop.getSkillManager() : null;
        if (sm == null || sm.getRegistry() == null) {
            ctx.out().println("技能系统未启用。");
            return false;
        }
        ctx.out().println(sm.getRegistry().getHelp());
        return false;
    }
}
