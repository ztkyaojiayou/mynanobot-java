package com.nanobot.core.state;

import com.nanobot.command.CommandContext;
import com.nanobot.command.CommandRegistry;
import com.nanobot.core.AgentLoop;
import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import com.nanobot.skill.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * COMMAND — 命令分发。
 *
 * <h2>匹配优先级（与 CLI 一致）</h2>
 * <ol>
 *   <li>统一注册中心 CommandRegistry 内置命令（/stop /clear /compact /remember /skills /rules /stats）
 *       与 CLI 共用一套命令类，依赖统一经 ctx.agentLoop() getter 获取</li>
 *   <li>技能斜杠调用：/xxx → SkillManager 查找，返回 SKILL.md 全文</li>
 *   <li>都不匹配 → BUILD（当作普通消息进入 LLM 处理）</li>
 * </ol>
 *
 * <p>命令类统一通过 {@code ctx.out()} 输出：CLI 传 System.out，本状态传收集 buffer，
 * 执行完把内容作为最终响应返回给 Web 前端（原内置命令 setFinalContent 的行为保留）。
 */
public class CommandState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(CommandState.class);
    private final AgentLoop agentLoop;
    private final SkillManager skillManager;
    private final CommandRegistry registry;

    public CommandState(AgentLoop agentLoop, SkillManager skillManager) {
        this.agentLoop = agentLoop;
        this.skillManager = skillManager;
        this.registry = CommandRegistry.buildBase();
    }

    @Override
    public TurnState execute(TurnContext ctx) {
        if (ctx.getMessage() == null) return TurnState.BUILD;
        String content = ctx.getMessage().getContent();
        if (content == null || !content.startsWith("/")) return TurnState.BUILD;

        String command = content.split("\\s")[0].toLowerCase();
        logger.info("CommandState: content='{}', command='{}'", content, command);

        // ── ① 内置命令优先（系统级操作不能被用户技能覆盖）──
        // 内置命令类只用 agentLoop/sessionKey/sessionId/channel/out；toolRegistry、
        // permissionManager、shutdown 传 null/忽略（Web 不退出进程）。
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CommandContext cmdCtx = new CommandContext(
                null,
                null,
                agentLoop,
                ctx.getMessage().getSessionId(),
                ctx.getSessionKey(),
                ctx.getMessage().getChannel(),
                new PrintStream(buffer, true, StandardCharsets.UTF_8),
                null);

        var result = registry.execute(cmdCtx, content);
        if (result.isPresent()) {
            // 命令已执行（boolean 为退出信号，Web 下忽略）；把输出作为最终响应
            String output = buffer.toString(StandardCharsets.UTF_8).trim();
            ctx.setFinalContent(output.isEmpty() ? "命令已执行。" : output);
            return TurnState.DONE;
        }

        // ── ② 不是内置命令 → 尝试技能匹配 ──
        if (skillManager != null) {
            SkillManager.SkillCall skillCall = skillManager.parseSlashCommand(content);
            if (skillCall != null) {
                String skillOutput = skillManager.executeSkill(
                        skillCall.skillName(), java.util.Map.of(), skillCall.args());
                ctx.setFinalContent(skillOutput);
                return TurnState.DONE;
            }
        }

        // ── ③ 都不匹配 → 当作普通 / 开头消息，进入 LLM ──
        return TurnState.BUILD;
    }
}
