package com.nanobot.command.impl;

import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;
import com.nanobot.memory.Consolidator;
import com.nanobot.session.SessionManager;

import java.util.List;
import java.util.Map;

/**
 * /compact — 手动压缩对话历史。
 *
 * 从 session 读取历史 → consolidator 压缩 → 覆盖写回。
 * 必须用 SessionManager.replaceHistory（saveHistory 是追加式，压缩后行数变少会静默失效）。
 */
public class CompactCommand implements Command {

    @Override public String name() { return "compact"; }
    @Override public String description() { return "压缩当前对话历史"; }

    @Override
    public String usage() {
        return "  用法: /compact\n  用 LLM 总结压缩当前会话历史，减少后续 token 消耗";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        var agentLoop = ctx.agentLoop();
        if (agentLoop == null) return false;
        Consolidator consolidator = agentLoop.getConsolidator();
        if (consolidator == null) {
            ctx.out().println("压缩器未启用。");
            return false;
        }
        SessionManager sm = agentLoop.getSessionManager();
        List<Map<String, Object>> messages = sm.loadHistory(ctx.sessionKey()).orElse(List.of());
        if (messages.isEmpty()) {
            ctx.out().println("当前会话无历史消息。");
            return false;
        }
        int before = messages.size();
        int tokens = consolidator.getCurrentUsage(messages);
        try {
            List<Map<String, Object>> compacted = consolidator.consolidate(messages).join();
            sm.replaceHistory(ctx.sessionKey(), compacted);
            int saved = before - compacted.size();
            int newTokens = consolidator.getCurrentUsage(compacted);
            ctx.out().printf("✅ 上下文已压缩：%d 条消息 → %d 条（减少 %d 条），token 估算 %d → %d%n",
                    before, compacted.size(), saved, tokens, newTokens);
        } catch (Exception e) {
            ctx.out().println("❌ 压缩失败：" + e.getMessage());
        }
        return false;
    }
}
