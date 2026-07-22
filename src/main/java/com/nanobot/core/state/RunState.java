package com.nanobot.core.state;

import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.config.Config;
import com.nanobot.core.AgentRunner;
import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * RUN — 调用 LLM 并发布流式输出到 MessageBus Outbound Queue.
 *
 * <h2>发布-订阅解耦</h2>
 * RunState 不持有、不管理任何消费者。LLM 每输出一个 token，
 * 包装为 OutboundMessage 发布到 MessageBus 的扇出队列。
 * Dispatcher 线程自动扇出到各通道的 subscriberQueue。
 * 各通道（SSE/CLI/WS）独立 poll 自己的队列，自行过滤 sessionId+requestId。
 *
 * <h2>和旧回调模式的区别</h2>
 * 旧: RunState 持有 callbacksSupplier → 遍历广播 → 慢回调拖慢整体
 * 新: RunState 只 put 到 Queue → dispatcher 异步扇出 → 消费者独立处理
 */
public class RunState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(RunState.class);
    private final AgentRunner runner;
    private final Config config;
    private final MessageBus messageBus;

    public RunState(AgentRunner runner, Config config, MessageBus messageBus) {
        this.runner = runner;
        this.config = config;
        this.messageBus = messageBus;
    }

    @Override
    public TurnState execute(TurnContext ctx) {
        String connectionId = ctx.getMessage().getConnectionId();
        String requestId = extractRequestId(ctx);
        boolean streamMode = extractStreamMode(ctx);
        String sessionId = ctx.getMessage().getSessionId();

        logger.info("🚀 [DO-RUN] streamMode={}, requestId={}, msgContent='{}'",
                streamMode, requestId,
                ctx.getMessage().getContent() != null
                    ? ctx.getMessage().getContent().substring(0, Math.min(60, ctx.getMessage().getContent().length()))
                    : "null");

        Consumer<String> onDelta = streamMode
                ? buildOnDelta(ctx, connectionId, requestId, sessionId)
                : null;

        try {
            logger.info("🤖 [LLM-CALL] session={}, requestId={}, msgs={}",
                    sessionId, requestId, ctx.getMessages().size());
            long start = System.currentTimeMillis();
            String result = runner.run(ctx, ctx.getMessages(), onDelta).join();
            long duration = System.currentTimeMillis() - start;
            logger.info("✅ [LLM-DONE] session={}, requestId={}, duration={}ms, resultLen={}",
                    sessionId, requestId, duration, result != null ? result.length() : 0);
            ctx.setFinalContent(result);

            if (streamMode) {
                sendStreamEnd(ctx, connectionId, sessionId, requestId);
            }
        } catch (Exception e) {
            logger.error("Runner failed: {}", e.getMessage(), e);
            ctx.setError(e.getMessage());
            ctx.setFinalContent("执行失败：" + e.getMessage());
        }

        return TurnState.SAVE;
    }

    /**
     * 构建流式回调 — LLM 每输出一个 token，包装为 OutboundMessage 发布到扇出队列。
     * 不直接和任何消费者交互。
     */
    private Consumer<String> buildOnDelta(TurnContext ctx, String connectionId,
                                           String requestId, String sessionId) {
        String channel = ctx.getMessage().getChannel();

        return delta -> {
            try {
                OutboundMessage msg = OutboundMessage.builder()
                        .channel(channel)
                        .sessionId(sessionId)
                        .content(delta)
                        .requestId(requestId)
                        .connectionId(connectionId)
                        .metadata(Map.of("_stream_delta", true))
                        .build();
                messageBus.publishToOutboundQueue(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.warn("Failed to publish stream delta: {}", e.getMessage());
            }
        };
    }

    /** 发布流结束标记到扇出队列 */
    private void sendStreamEnd(TurnContext ctx, String connectionId, String sessionId, String requestId) {
        try {
            OutboundMessage endMsg = OutboundMessage.builder()
                    .channel(ctx.getMessage().getChannel())
                    .sessionId(sessionId)
                    .requestId(requestId)
                    .connectionId(connectionId)
                    .metadata(Map.of("_stream_end", true))
                    .build();
            messageBus.publishToOutboundQueue(endMsg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Failed to publish stream end: {}", e.getMessage());
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
