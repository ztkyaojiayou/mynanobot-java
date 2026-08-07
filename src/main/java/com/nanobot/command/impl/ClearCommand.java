package com.nanobot.command.impl;

import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;
import com.nanobot.session.SessionManager;

import java.util.Map;
import java.util.UUID;

/**
 * /clear — 清除当前会话上下文，并发布 _session_cleared 事件通知各通道清空展示。
 *
 * 注意：必须用 ctx.sessionKey()（已算好的完整 key），不能用裸 sessionId——
 * CLI 下裸 sessionId 会清错 key（原 CliChannel.handleClear 的 bug）。
 */
public class ClearCommand implements Command {

    @Override public String name() { return "clear"; }
    @Override public String description() { return "清除当前会话上下文"; }

    @Override
    public String usage() {
        return "  用法: /clear\n  清空当前会话上下文（含历史文件与前端展示）";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        var agentLoop = ctx.agentLoop();
        SessionManager sm = agentLoop != null ? agentLoop.getSessionManager() : null;
        if (sm == null) {
            ctx.out().println("会话管理器未就绪。");
            return false;
        }
        sm.clearSession(ctx.sessionKey());
        ctx.out().println("会话已清除。");
        publishSessionCleared(ctx);
        return false;
    }

    /** 发布 _session_cleared 事件到 outboundQueue，通知各通道清空展示 */
    private void publishSessionCleared(CommandContext ctx) {
        var agentLoop = ctx.agentLoop();
        if (agentLoop == null) return;
        MessageBus bus = agentLoop.getMessageBus();
        if (bus == null) return;
        try {
            OutboundMessage msg = OutboundMessage.builder()
                    .sessionId(ctx.sessionId())
                    .requestId(UUID.randomUUID().toString())
                    .channel(ctx.channel())
                    .metadata(Map.of("_session_cleared", true))
                    .build();
            bus.publishToOutboundQueue(msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
