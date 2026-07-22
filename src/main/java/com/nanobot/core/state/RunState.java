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
    /**
     * 这是注册的所有回调接口，等大模型返回流式消息时agentloop就会逐一回调这些接口，将消息广播给各个回调接口
     * 其实就是观察者/订阅监听/生产者消费者模式，每一份消息都会广播给每个回调接口，各个接口按需消费，
     * 如只过滤出当前请求的消息发送到前端！！！
     * 每个流式请求都会注册一个回调接口到这里
     * 而这恰恰就是流式返回接口的核心原理！！！
     */
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

    /**
     * 构建流式回调 — LLM 每输出一个 token 触发一次.
     *
     * <h2>双路径分发</h2>
     * 每个 token 同时走两条路径：
     * <ol>
     *   <li><b>Outbound Queue（路径A）</b>：包装为 OutboundMessage → publish → CLI/WebSocket 消费.
     *       走 MessageBus 异步队列，有背压保护.</li>
     *   <li><b>直接回调（路径B）</b>：遍历所有注册的 StreamResponseCallback → 直接调 onStreamData().
     *       不走 Queue，纯内存调用，零延迟. 每个回调自行过滤 sessionId+requestId.</li>
     * </ol>
     *
     * <h2>"广播电台"模式</h2>
     * AgentLoop 不对回调做任何过滤——所有回调都会收到每个 token.
     * 过滤逻辑在回调自身（如 ChatController 中比对 sessionId + requestId）.
     * 代价 O(n) 遍历，但实际活跃 SSE 连接极少，完全够用.
     */
    private Consumer<String> buildOnDelta(TurnContext ctx, String connectionId,
                                           String requestId, boolean streamMode, String sessionId) {
        if (!streamMode || (!config.getChannels().isSendProgress() && requestId == null)) return null;

        // 快照当前回调列表，避免迭代过程中 ConcurrentModification
        List<StreamResponseCallback> activeCallbacks = new ArrayList<>(callbacksSupplier.get());

        return delta -> {
            // ── 路径A：Outbound Queue → CLI / WebSocket ──
            OutboundMessage msg = OutboundMessage.builder()
                    .channel(ctx.getMessage().getChannel())
                    .sessionId(sessionId)
                    .content(delta)
                    .addMetadata("_stream_delta", true)
                    .addMetadata("_progress", true)
                    .connectionId(connectionId)
                    .build();
            progressPublisher.accept(msg);

            // ── 路径B：直接回调 → SSE emitter ──
            for (StreamResponseCallback cb : activeCallbacks) {
                try { cb.onStreamData(sessionId, requestId, delta); }
                catch (Exception e) { logger.warn("Stream callback failed: {}", e.getMessage()); }
            }
        };
    }

    /** 流结束通知 — 告知所有回调流已完成，回调内部自行过滤 sessionId+requestId */
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
