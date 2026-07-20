package com.nanobot.core.state;

import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import com.nanobot.memory.Dream;
import com.nanobot.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SAVE — 保存助手响应到会话历史、持久化，并异步触发长期记忆提取。
 */
public class SaveState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(SaveState.class);
    private final SessionManager sessionManager;
    private final Dream dream;  // 可为 null

    public SaveState(SessionManager sessionManager) {
        this(sessionManager, null);
    }

    public SaveState(SessionManager sessionManager, Dream dream) {
        this.sessionManager = sessionManager;
        this.dream = dream;
    }

    @Override
    public TurnState execute(TurnContext ctx) {
        String content = ctx.getFinalContent();
        if (content != null && !content.isBlank()) {
            ctx.addAssistantMessage(content, null);
            logger.debug("Added assistant response to history: {}", content.length());
        }
        sessionManager.saveHistory(ctx.getSessionKey(), ctx.getMessages());

        // 异步触发长期记忆提取（不阻塞响应）
        triggerMemoryExtraction(ctx);

        return TurnState.RESPOND;
    }

    private void triggerMemoryExtraction(TurnContext ctx) {
        if (dream == null) return;
        try {
            dream.extractAndStore(ctx.getSessionKey(), ctx.getMessages())
                .thenAccept(stored -> {
                    if (!stored.isEmpty()) {
                        logger.info("🧠 Dream extracted {} memories from session {}",
                                stored.size(), ctx.getSessionKey());
                    }
                })
                .exceptionally(e -> {
                    logger.warn("Dream extraction failed: {}", e.getMessage());
                    return null;
                });
        } catch (Exception e) {
            logger.warn("Failed to trigger dream extraction: {}", e.getMessage());
        }
    }
}
