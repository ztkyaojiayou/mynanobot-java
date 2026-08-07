package com.nanobot.command.impl;

import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;
import com.nanobot.memory.Dream;
import com.nanobot.session.SessionManager;

import java.util.List;
import java.util.Map;

/**
 * /remember — 手动触发长期记忆提取（Dream）。
 *
 * 从 session 读取历史 → dream 增量提取并入库。dream 未启用时给提示。
 */
public class RememberCommand implements Command {

    @Override public String name() { return "remember"; }
    @Override public String description() { return "手动触发长期记忆提取"; }

    @Override
    public String usage() {
        return "  用法: /remember\n  手动触发长期记忆提取，将对话要点写入长期记忆库";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        var agentLoop = ctx.agentLoop();
        if (agentLoop == null) return false;
        Dream dream = agentLoop.getDream();
        if (dream == null) {
            ctx.out().println("长期记忆系统未启用。");
            return false;
        }
        SessionManager sm = agentLoop.getSessionManager();
        List<Map<String, Object>> messages = sm.loadHistory(ctx.sessionKey()).orElse(List.of());
        try {
            var stored = dream.extractAndStore(ctx.sessionKey(), messages).join();
            if (stored.isEmpty()) {
                ctx.out().println("📝 没有提取到新的长期记忆（可能已存在或增量不足）。");
            } else {
                ctx.out().println("✅ 已提取 " + stored.size() + " 条长期记忆：");
                for (var entry : stored) {
                    ctx.out().println("- " + entry.getContent());
                }
            }
        } catch (Exception e) {
            ctx.out().println("❌ 记忆提取失败：" + e.getMessage());
        }
        return false;
    }
}
