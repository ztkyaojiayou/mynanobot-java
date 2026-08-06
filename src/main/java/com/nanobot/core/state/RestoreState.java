package com.nanobot.core.state;

import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import com.nanobot.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RESTORE — 从 SessionManager 恢复会话历史，附加用户消息。
 */
public class RestoreState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(RestoreState.class);
    private final SessionManager sessionManager;

    public RestoreState(SessionManager sessionManager) { this.sessionManager = sessionManager; }

    @Override
    public TurnState execute(TurnContext ctx) {
        String sessionKey = ctx.getSessionKey();

        Optional<List<Map<String, Object>>> history = sessionManager.loadHistory(sessionKey);
        if (history.isPresent()) {
            for (Map<String, Object> msg : history.get()) ctx.addMessage(msg);
            logger.info("Restored {} messages for session: {}", history.get().size(), sessionKey);
        } else {
            logger.debug("No history found for session: {}", sessionKey);
        }

        // ── 重新生成：删掉指定位置起的所有后续消息 → LLM 基于原问题重新回答 ──
        Object regenVal = ctx.getMessage().getMetadata() != null
                ? ctx.getMessage().getMetadata().get("_regenerate") : null;
        if (regenVal != null) {
            List<Map<String, Object>> msgs = ctx.getMessages();
            if (regenVal instanceof Number) {
                int idx = ((Number) regenVal).intValue();
                System.err.println("[REGEN] idx=" + idx + " before=" + msgs.size());
                while (msgs.size() > idx) {
                    System.err.println("[REGEN]   del[" + (msgs.size()-1) + "]=" + msgs.get(msgs.size()-1).get("role"));
                    msgs.remove(msgs.size() - 1);
                }
                System.err.println("[REGEN] after=" + msgs.size());
            } else {
                if (!msgs.isEmpty() && "assistant".equals(msgs.get(msgs.size() - 1).get("role"))) {
                    msgs.remove(msgs.size() - 1);
                    logger.info("Regenerate: removed last assistant (total={})", msgs.size());
                }
            }
            // 不追加新用户消息——原问题已在历史中
        } else {
            String content = ctx.getMessage().getContent();
            if (content != null && !content.isBlank()) {
                ctx.addUserMessage(content);
                logger.debug("Added user message: {} chars", content.length());
            }
        }

        logger.info("Total messages in context: {}", ctx.getMessages().size());
        return TurnState.COMPACT;
    }
}
