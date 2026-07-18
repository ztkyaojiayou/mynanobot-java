package com.nanobot.core.state;

import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import com.nanobot.memory.Consolidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * COMPACT — 对话历史压缩。token 超过预算时调用 LLM 生成摘要。
 */
public class CompactState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(CompactState.class);
    private final Consolidator consolidator;

    public CompactState(Consolidator consolidator) { this.consolidator = consolidator; }

    @Override
    public TurnState execute(TurnContext ctx) {
        if (consolidator == null) return TurnState.BUILD;

        List<Map<String, Object>> messages = ctx.getMessages();
        if (!consolidator.needsConsolidation(messages)) return TurnState.BUILD;

        logger.info("Compacting history: {} messages, ~{} tokens",
                messages.size(), consolidator.getCurrentUsage(messages));
        try {
            List<Map<String, Object>> compacted = consolidator.consolidate(messages).join();
            ctx.getMessages().clear();
            ctx.getMessages().addAll(compacted);
            logger.info("Compacted to {} messages", compacted.size());
        } catch (Exception e) {
            logger.warn("Compaction failed, continuing with original: {}", e.getMessage());
        }
        return TurnState.BUILD;
    }
}
