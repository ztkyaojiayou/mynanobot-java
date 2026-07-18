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

        String content = ctx.getMessage().getContent();
        if (content != null && !content.isBlank()) {
            ctx.addUserMessage(content);
            logger.debug("Added user message: {} chars", content.length());
        }

        logger.info("Total messages in context: {}", ctx.getMessages().size());
        return TurnState.COMPACT;
    }
}
