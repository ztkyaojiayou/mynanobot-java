package com.nanobot.command.impl;

import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;
import com.nanobot.security.PermissionMode;

import java.util.List;

/**
 * /mode — 查看或切换权限模式。
 *
 * 支持：
 * - /mode                   查看当前模式
 * - /mode plan              进入规划模式（只读分析 + 出计划）
 * - /mode default           默认模式（读放行，写需确认）
 * - /mode accept_edits      接受编辑模式（读+编辑放行）
 * - /mode bypass            绕过模式（全部放行）
 *
 * Plan Mode 工作流：
 * - /mode plan  或  /plan       → 进入规划模式
 * - /plan approve               → 审批通过，切到执行模式并开始实现
 */
public class ModeCommand implements Command {

    @Override public String name() { return "mode"; }
    @Override public List<String> aliases() { return List.of("plan"); }
    @Override public String description() { return "切换模式 (plan | default | accept_edits | bypass)"; }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        // 提取命令名和参数
        String trimmed = input.startsWith("/") ? input.substring(1).trim() : input.trim();
        int space = trimmed.indexOf(' ');
        String cmdName = space > 0 ? trimmed.substring(0, space) : trimmed;
        String arg = space > 0 ? trimmed.substring(space + 1).trim() : "";

        // ── /plan approve ──
        if ("plan".equalsIgnoreCase(cmdName) && "approve".equalsIgnoreCase(arg)) {
            return approvePlan(ctx);
        }

        // ── /mode ... ──
        return handleMode(ctx, arg);
    }

    /** /mode [mode] — 切换权限模式 */
    private boolean handleMode(CommandContext ctx, String arg) {
        var pm = ctx.permissionManager();
        if (pm == null) {
            System.out.println("权限管理器未就绪");
            return false;
        }

        if (arg.isBlank()) {
            var loop = ctx.agentLoop();
            String planStatus = loop != null && loop.isPlanMode() ? " (规划模式激活)" : "";
            System.out.println("当前权限模式: " + pm.getMode() + planStatus);
            System.out.println("用法: /mode plan | default | accept_edits | bypass");
            System.out.println("Plan 工作流: /plan → 出计划 → /plan approve → 开始实现");
            return false;
        }

        try {
            PermissionMode mode = PermissionMode.fromString(arg);
            pm.setMode(mode);

            // plan 模式：同时激活 AgentLoop 的规划模式
            if (mode == PermissionMode.PLAN) {
                var loop = ctx.agentLoop();
                if (loop != null) {
                    loop.setPlanMode(true);
                    System.out.println("📋 已进入规划模式 — LLM 将只读分析并出计划，不会修改代码");
                    System.out.println("   计划满意后输入 /plan approve 开始实现");
                } else {
                    System.out.println("已切换至: " + pm.getMode());
                }
            } else {
                // 退出 plan 模式时也清除 AgentLoop 状态
                var loop = ctx.agentLoop();
                if (loop != null && loop.isPlanMode()) {
                    loop.setPlanMode(false);
                }
                System.out.println("已切换至: " + pm.getMode());
            }
        } catch (IllegalArgumentException e) {
            System.out.println("无效模式: " + arg
                    + "。可用: plan, default, accept_edits, bypass");
        }
        return false;
    }

    /** /plan approve — 审批通过，开始执行 */
    private boolean approvePlan(CommandContext ctx) {
        var loop = ctx.agentLoop();
        var pm = ctx.permissionManager();

        if (loop == null) {
            System.out.println("AgentLoop 未就绪");
            return false;
        }

        if (!loop.isPlanMode()) {
            System.out.println("当前不在规划模式，无需审批。使用 /mode plan 进入规划模式。");
            return false;
        }

        // 1. 退出规划模式
        loop.setPlanMode(false);

        // 2. 权限切换到 ACCEPT_EDITS（允许编辑）
        if (pm != null) {
            pm.setMode(PermissionMode.ACCEPT_EDITS);
        }

        System.out.println("✅ 计划已审批，进入执行模式...");

        // 3. 发送执行指令到 AgentLoop
        var bus = com.nanobot.NanobotRunner.getMessageBus();
        if (bus != null) {
            try {
                bus.publishInbound(com.nanobot.bus.InboundMessage.builder()
                        .sessionId(ctx.sessionId())
                        .senderId("system")
                        .content("请按照刚才讨论的计划开始实现。直接写代码，不要再次询问或重新规划。")
                        .channel("cli")
                        .metadata(java.util.Map.of("requestId",
                                java.util.UUID.randomUUID().toString(), "streamMode", true))
                        .build());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return false;
    }
}
