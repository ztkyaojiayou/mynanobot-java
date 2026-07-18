package com.nanobot.core.state;

import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import com.nanobot.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SAVE — 保存助手响应到会话历史并持久化。
 */
public class SaveState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(SaveState.class);
    private final SessionManager sessionManager;

    public SaveState(SessionManager sessionManager) { this.sessionManager = sessionManager; }

    @Override
    public TurnState execute(TurnContext ctx) {
        String content = ctx.getFinalContent();
        if (content != null && !content.isBlank()) {
            ctx.addAssistantMessage(content, null);
            logger.debug("Added assistant response to history: {}", content.length());
        }
        sessionManager.saveHistory(ctx.getSessionKey(), ctx.getMessages());
        return TurnState.RESPOND;
    }
}
