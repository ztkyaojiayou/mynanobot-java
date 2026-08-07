package com.nanocode.command.impl;

import com.nanocode.bus.MessageBus;
import com.nanocode.command.Command;
import com.nanocode.command.CommandContext;
import com.nanocode.hook.HookManager;
import com.nanocode.memory.Dream;
import com.nanocode.session.SessionManager;

import java.util.List;
import java.util.Map;

/**
 * /stats — 显示当前会话和全局统计。
 *
 * 从 CommandState.handleStats 迁出；会话消息改从 session 持久化历史读取
 * （命令类没有 TurnContext，不能访问轮次内的实时消息）。
 */
public class StatsCommand implements Command {

    @Override public String name() { return "stats"; }
    @Override public String description() { return "显示会话和全局统计"; }

    @Override
    public String usage() {
        return "  用法: /stats\n  显示当前会话消息数/token、全局队列、Hook 事件与工具耗时";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        var agentLoop = ctx.agentLoop();
        if (agentLoop == null) return false;

        StringBuilder sb = new StringBuilder("📊 会话统计\n\n");

        // ① 当前会话（从持久化历史统计）
        SessionManager sm = agentLoop.getSessionManager();
        List<Map<String, Object>> msgs = sm.loadHistory(ctx.sessionKey()).orElse(List.of());
        int msgCount = (int) msgs.stream().filter(m -> !"system".equals(m.get("role"))).count();
        int tokens = (int) (msgs.stream()
                .mapToInt(m -> m.getOrDefault("content", "").toString().length()).sum() / 4.0);
        sb.append("消息数: ").append(msgCount).append(" 条 · Token 估算: ").append(tokens).append("\n\n");

        // ② 全局统计
        MessageBus bus = agentLoop.getMessageBus();
        sb.append("📊 全局\n\n");
        sb.append("会话总数: ").append(sm.getSessionCount()).append(" 个\n");
        if (bus != null) {
            sb.append("入站队列: ").append(bus.getInboundSize()).append("/100\n");
            sb.append("出站队列: ").append(bus.getOutboundQueueSize()).append("/1000\n");
            sb.append("订阅者数: ").append(bus.getSubscriberCount()).append("\n");
        }

        // ③ 长期记忆
        Dream dream = agentLoop.getDream();
        if (dream != null) {
            sb.append("长期记忆: ").append(dream.getMemoryCount()).append(" 条\n");
        }

        // ④ Hook 系统统计
        HookManager hm = agentLoop.getHookManager();
        if (hm != null) {
            var eventCounts = hm.getRunCounts();
            if (!eventCounts.isEmpty()) {
                sb.append("\n📊 Hook 事件触发\n\n");
                eventCounts.forEach((event, count) ->
                        sb.append("  ").append(event.name()).append(": ").append(count).append(" 次\n"));
            }
            sb.append("Hook 拦截: ").append(hm.getRejectCount()).append(" 次\n");
            sb.append("已注册: ").append(hm.getHookCount()).append(" 个 Hook\n");

            var timings = hm.getToolTimings();
            if (!timings.isEmpty()) {
                sb.append("\n📊 工具耗时\n\n");
                timings.values().stream()
                        .sorted((a, b) -> Long.compare(b.totalMs(), a.totalMs()))
                        .limit(10)
                        .forEach(t -> sb.append("  ").append(t).append("\n"));
            }
        }

        ctx.out().print(sb);
        return false;
    }
}
