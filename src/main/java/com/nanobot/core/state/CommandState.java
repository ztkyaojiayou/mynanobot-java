package com.nanobot.core.state;

import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import com.nanobot.memory.Consolidator;
import com.nanobot.memory.Dream;
import com.nanobot.rules.RuleManager;
import com.nanobot.session.SessionManager;
import com.nanobot.skill.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * COMMAND — 命令分发。处理 /stop、/clear、/compact、/remember、/skills、/rules 等内置命令，
 * 以及技能斜杠调用。
 */
public class CommandState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(CommandState.class);
    private final SkillManager skillManager;
    private final RuleManager ruleManager;
    private final SessionManager sessionManager;
    private final Consolidator consolidator;
    private final Dream dream;

    public CommandState(SkillManager skillManager, RuleManager ruleManager,
                        SessionManager sessionManager, Consolidator consolidator, Dream dream) {
        this.skillManager = skillManager;
        this.ruleManager = ruleManager;
        this.sessionManager = sessionManager;
        this.consolidator = consolidator;
        this.dream = dream;
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
            case "/compact" -> handleCompact(ctx);
            case "/remember" -> handleRemember(ctx);
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

    /** /compact — 手动触发对话历史压缩 */
    private TurnState handleCompact(TurnContext ctx) {
        if (consolidator == null) {
            ctx.setFinalContent("压缩器未启用。");
            return TurnState.DONE;
        }
        List<Map<String, Object>> messages = ctx.getMessages();
        int before = messages.size();
        int tokens = consolidator.getCurrentUsage(messages);

        try {
            List<Map<String, Object>> compacted = consolidator.consolidate(messages).join();
            ctx.getMessages().clear();
            ctx.getMessages().addAll(compacted);
            int saved = before - compacted.size();
            int newTokens = consolidator.getCurrentUsage(compacted);
            ctx.setFinalContent(String.format(
                    "✅ 上下文已压缩：%d 条消息 → %d 条（减少 %d 条），token 估算 %d → %d",
                    before, compacted.size(), saved, tokens, newTokens));
            logger.info("Manual compaction: {} → {} messages", before, compacted.size());
        } catch (Exception e) {
            logger.error("Manual compaction failed", e);
            ctx.setFinalContent("❌ 压缩失败：" + e.getMessage());
        }
        return TurnState.DONE;
    }

    /** /remember — 手动触发长期记忆提取 */
    private TurnState handleRemember(TurnContext ctx) {
        if (dream == null) {
            ctx.setFinalContent("长期记忆系统未启用。");
            return TurnState.DONE;
        }
        String sessionId = ctx.getSessionKey();
        List<Map<String, Object>> messages = ctx.getMessages();

        try {
            var stored = dream.extractAndStore(sessionId, messages).join();
            if (stored.isEmpty()) {
                ctx.setFinalContent("📝 没有提取到新的长期记忆（可能已存在或增量不足）。");
            } else {
                StringBuilder sb = new StringBuilder("✅ 已提取 " + stored.size() + " 条长期记忆：\n");
                for (var entry : stored) {
                    sb.append("- ").append(entry.getContent()).append("\n");
                }
                ctx.setFinalContent(sb.toString().trim());
                logger.info("Manual memory extraction: {} entries from session {}", stored.size(), sessionId);
            }
        } catch (Exception e) {
            logger.error("Manual memory extraction failed", e);
            ctx.setFinalContent("❌ 记忆提取失败：" + e.getMessage());
        }
        return TurnState.DONE;
    }
}
