package com.nanocode.command.impl;

import com.nanocode.command.Command;
import com.nanocode.command.CommandContext;
import com.nanocode.session.SessionManager;

import java.util.List;
import java.util.Map;

/**
 * /cost — 估算当前会话与全部会话的 token 用量和 API 成本。
 *
 * 简化实现：按消息 content 长度估算 token（中英混合场景约 2 字符/token），
 * 按模型单价表折算人民币。单价为公开参考价，可随模型定价调整。
 * 与 /stats 的 token 估算口径不同（/stats 用 length/4 偏英文）。
 */
public class CostCommand implements Command {

    /** 单价表：每 1M token 人民币 [输入价, 输出价] */
    private static final Map<String, double[]> PRICE_PER_1M = Map.of(
            "deepseek-chat", new double[]{2.0, 8.0},
            "deepseek-reasoner", new double[]{4.0, 16.0}
    );
    private static final double[] DEFAULT_PRICE = {2.0, 8.0};

    @Override public String name() { return "cost"; }
    @Override public String description() { return "估算会话 token 用量与 API 成本"; }

    @Override
    public String usage() {
        return "  用法: /cost\n  估算当前会话与全部会话的 token 用量和 API 成本（按模型单价折算）";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        var agentLoop = ctx.agentLoop();
        if (agentLoop == null) return false;
        SessionManager sm = agentLoop.getSessionManager();
        var out = ctx.out();

        // 模型名（用于单价查找），默认 deepseek-chat
        String model = "deepseek-chat";
        if (agentLoop.getConfig() != null
                && agentLoop.getConfig().getAgents() != null
                && agentLoop.getConfig().getAgents().getDefaults() != null
                && agentLoop.getConfig().getAgents().getDefaults().getModel() != null) {
            model = agentLoop.getConfig().getAgents().getDefaults().getModel();
        }
        double[] price = PRICE_PER_1M.getOrDefault(model, DEFAULT_PRICE);

        // ① 当前会话
        List<Map<String, Object>> current = sm.loadHistory(ctx.sessionKey()).orElse(List.of());
        long curTokens = estimateTokens(current);
        double curCost = estimateCost(current, price);

        // ② 全部会话汇总
        long allTokens = 0;
        double allCost = 0;
        int sessionCount = 0;
        for (String key : sm.listSessions()) {
            List<Map<String, Object>> history = sm.loadHistory(key).orElse(List.of());
            allTokens += estimateTokens(history);
            allCost += estimateCost(history, price);
            sessionCount++;
        }

        out.println("💸 成本估算 (模型 " + model + ")");
        out.println("  ─────────────────────────────────────────");
        out.printf("  当前会话: %d token · ≈ ¥%.4f%n", curTokens, curCost);
        out.printf("  全部会话: %d 个 · %d token · ≈ ¥%.4f%n", sessionCount, allTokens, allCost);
        out.println("  (估算仅供参考，单价按公开价折算)");
        return false;
    }

    /** 估算文本 token 数：中英混合约 2 字符/token */
    public static long estimateTokens(String content) {
        if (content == null || content.isBlank()) return 0;
        return Math.max(1, Math.round(content.length() / 2.0));
    }

    /** 估算一批消息的 token 总数 */
    public static long estimateTokens(List<Map<String, Object>> messages) {
        return messages.stream()
                .mapToLong(m -> estimateTokens(m.getOrDefault("content", "").toString()))
                .sum();
    }

    /** 按 role 分输入/输出估成本：user/system/tool 算输入，assistant 算输出 */
    public static double estimateCost(List<Map<String, Object>> messages, double[] pricePer1M) {
        long inputTokens = 0;
        long outputTokens = 0;
        for (Map<String, Object> m : messages) {
            String role = String.valueOf(m.getOrDefault("role", ""));
            long t = estimateTokens(m.getOrDefault("content", "").toString());
            if ("assistant".equals(role)) outputTokens += t;
            else inputTokens += t;
        }
        return (inputTokens / 1e6 * pricePer1M[0]) + (outputTokens / 1e6 * pricePer1M[1]);
    }
}
