package com.nanobot.core.state;

import com.nanobot.bus.OutboundMessage;
import com.nanobot.config.Config;
import com.nanobot.core.AgentLoop.StreamResponseCallback;
import com.nanobot.core.AgentRunner;
import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * RUN — 调用 LLM 并管理流式输出回调。
 */
public class RunState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(RunState.class);
    private final AgentRunner runner;
    private final Config config;
    private final Supplier<List<StreamResponseCallback>> callbacksSupplier;
    private final Consumer<OutboundMessage> progressPublisher;

    public RunState(AgentRunner runner, Config config,
                    Supplier<List<StreamResponseCallback>> callbacksSupplier,
                    Consumer<OutboundMessage> progressPublisher) {
        this.runner = runner;
        this.config = config;
        this.callbacksSupplier = callbacksSupplier;
        this.progressPublisher = progressPublisher;
    }

    @Override
    public TurnState execute(TurnContext ctx) {
        String connectionId = ctx.getMessage().getConnectionId();
        String requestId = extractRequestId(ctx);
        boolean streamMode = extractStreamMode(ctx);
        String sessionId = ctx.getMessage().getSessionId();

        logger.info("🚀 [DO-RUN] streamMode={}, requestId={}, callbacks={}, msgContent='{}'",
                streamMode, requestId, callbacksSupplier.get().size(),
                ctx.getMessage().getContent() != null
                    ? ctx.getMessage().getContent().substring(0, Math.min(60, ctx.getMessage().getContent().length()))
                    : "null");

        Consumer<String> onDelta = buildOnDelta(ctx, connectionId, requestId, streamMode, sessionId);

        try {
            logger.info("🤖 [LLM-CALL] Starting LLM for session={}, requestId={}, msgs={}",
                    sessionId, requestId, ctx.getMessages().size());
            long start = System.currentTimeMillis();
            String result = runner.run(ctx, ctx.getMessages(), onDelta).join();
            long duration = System.currentTimeMillis() - start;
            logger.info("✅ [LLM-DONE] session={}, requestId={}, duration={}ms, resultLen={}",
                    sessionId, requestId, duration, result != null ? result.length() : 0);
            ctx.setFinalContent(result);

            // 流式结束标记
            if (streamMode && onDelta != null) {
                sendStreamEnd(ctx, connectionId, sessionId, requestId);
            }
        } catch (Exception e) {
            logger.error("Runner failed: {}", e.getMessage(), e);
            ctx.setError(e.getMessage());
            ctx.setFinalContent("执行失败：" + e.getMessage());
        }

        return TurnState.SAVE;
    }

    private Consumer<String> buildOnDelta(TurnContext ctx, String connectionId,
                                           String requestId, boolean streamMode, String sessionId) {
        if (!streamMode || (!config.getChannels().isSendProgress() && requestId == null)) return null;

        List<StreamResponseCallback> activeCallbacks = new ArrayList<>(callbacksSupplier.get());

        return delta -> {
            OutboundMessage msg = OutboundMessage.builder()
                    .channel(ctx.getMessage().getChannel())
                    .sessionId(sessionId)
                    .content(delta)
                    .addMetadata("_stream_delta", true)
                    .addMetadata("_progress", true)
                    .connectionId(connectionId)
                    .build();
            progressPublisher.accept(msg);

            for (StreamResponseCallback cb : activeCallbacks) {
                try { cb.onStreamData(sessionId, requestId, delta); }
                catch (Exception e) { logger.warn("Stream callback failed: {}", e.getMessage()); }
            }
        };
    }

    private void sendStreamEnd(TurnContext ctx, String connectionId, String sessionId, String requestId) {
        if (connectionId != null) {
            progressPublisher.accept(OutboundMessage.builder()
                    .channel(ctx.getMessage().getChannel()).sessionId(sessionId)
                    .content("").connectionId(connectionId)
                    .addMetadata("_stream_end", true).build());
        }
        for (StreamResponseCallback cb : callbacksSupplier.get()) {
            try { cb.onStreamComplete(sessionId, requestId); }
            catch (Exception e) { logger.warn("Stream complete callback failed: {}", e.getMessage()); }
        }
    }

    private static String extractRequestId(TurnContext ctx) {
        if (ctx.getMessage().getMetadata() == null) return null;
        Object o = ctx.getMessage().getMetadata().get("requestId");
        return o instanceof String s ? s : null;
    }

    private static boolean extractStreamMode(TurnContext ctx) {
        if (ctx.getMessage().getMetadata() == null) return false;
        Object o = ctx.getMessage().getMetadata().get("streamMode");
        return o instanceof Boolean b ? b : o instanceof String s && Boolean.parseBoolean(s);
    }
}
