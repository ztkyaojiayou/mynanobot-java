package com.nanobot.command.impl;

import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;

/**
 * /stop — 请求取消当前会话正在处理的轮次。
 *
 * 链路：cancelCurrentTurn → TurnContext.cancel()（volatile 标志）→ AgentRunner
 * 在下一 LLM 迭代边界返回"处理已取消"。单次流式调用不会中途打断（与 Esc 中断是两套机制）。
 */
public class StopCommand implements Command {

    @Override public String name() { return "stop"; }
    @Override public String description() { return "取消当前正在处理的回复"; }

    @Override
    public String usage() {
        return "  用法: /stop\n  取消当前会话正在处理的轮次（Web 场景常用）";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        var agentLoop = ctx.agentLoop();
        if (agentLoop == null || !agentLoop.cancelCurrentTurn(ctx.sessionKey())) {
            ctx.out().println("当前没有正在处理的任务。");
            return false;
        }
        ctx.out().println("已请求停止当前处理...");
        return false;
    }
}
