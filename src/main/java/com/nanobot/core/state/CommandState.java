package com.nanobot.core.state;

import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import com.nanobot.rules.RuleManager;
import com.nanobot.session.SessionManager;
import com.nanobot.skill.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * COMMAND — 命令分发。处理 /stop、/clear、/skills、/rules 等内置命令，
 * 以及技能斜杠调用。
 */
public class CommandState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(CommandState.class);
    private final SkillManager skillManager;
    private final RuleManager ruleManager;
    private final SessionManager sessionManager;

    public CommandState(SkillManager skillManager, RuleManager ruleManager, SessionManager sessionManager) {
        this.skillManager = skillManager;
        this.ruleManager = ruleManager;
        this.sessionManager = sessionManager;
    }

    @Override
    public TurnState execute(TurnContext ctx) {
        String content = ctx.getMessage().getContent();
        if (content == null || !content.startsWith("/")) return TurnState.BUILD;

        // 技能调用
        if (skillManager != null) {
            SkillManager.SkillCall skillCall = skillManager.parseSlashCommand(content);
            if (skillCall != null) {
                String result = skillManager.executeSkill(skillCall.skillName(), java.util.Map.of(), skillCall.args());
                ctx.setFinalContent(result);
                return TurnState.DONE;
            }
        }

        String command = content.split("\\s")[0].toLowerCase();
        return switch (command) {
            case "/stop" -> {
                ctx.cancel();
                ctx.setFinalContent("已停止处理。");
                yield TurnState.DONE;
            }
            case "/clear" -> {
                sessionManager.clearSession(ctx.getSessionKey());
                ctx.setFinalContent("会话已清除。");
                yield TurnState.DONE;
            }
            case "/skills" -> {
                String help = skillManager != null ? skillManager.getRegistry().getHelp() : "技能系统未启用。";
                ctx.setFinalContent(help);
                yield TurnState.DONE;
            }
            case "/rules" -> {
                String summary = ruleManager != null ? ruleManager.getRulesSummary() : "规则系统未启用。";
                ctx.setFinalContent(summary);
                yield TurnState.DONE;
            }
            default -> TurnState.BUILD;
        };
    }
}
