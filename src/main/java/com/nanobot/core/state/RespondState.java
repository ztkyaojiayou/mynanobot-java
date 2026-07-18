package com.nanobot.core.state;

import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RESPOND — 发送最终响应到消息总线。
 */
public class RespondState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(RespondState.class);
    private final MessageBus messageBus;

    public RespondState(MessageBus messageBus) { this.messageBus = messageBus; }

    @Override
    public TurnState execute(TurnContext ctx) {
        String content = ctx.getFinalContent();
        if (content == null) content = "(无响应)";

        String requestId = null;
        if (ctx.getMessage().getMetadata() != null) {
            Object o = ctx.getMessage().getMetadata().get("requestId");
            if (o != null) requestId = o.toString();
        }

        OutboundMessage response = OutboundMessage.builder()
                .channel(ctx.getMessage().getChannel())
                .sessionId(ctx.getMessage().getSessionId())
                .content(content)
                .requestId(requestId)
                .build();

        try {
            messageBus.publishOutbound(response);
            logger.info("Response sent for sessionId: {}, requestId: {}, content length: {}",
                    ctx.getMessage().getSessionId(), requestId, content.length());
        } catch (Exception e) {
            logger.error("Failed to send response: {}", e.getMessage(), e);
        }

        return TurnState.DONE;
    }
}
