package com.nanobot.core.state;

import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.config.Config;
import com.nanobot.core.AgentRunner;
import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import com.nanobot.hook.HookManager;
import com.nanobot.hook.HookContext;
import com.nanobot.hook.HookEvent;
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
    private final HookManager hookManager;

    public RunState(AgentRunner runner, Config config, MessageBus messageBus, HookManager hookManager) {
        this.runner = runner;
        this.config = config;
        this.messageBus = messageBus;
        this.hookManager = hookManager;
    }

    @Override
    public TurnState execute(TurnContext ctx) {
        if (ctx.getMessage() == null) return TurnState.RESPOND;
        String connectionId = ctx.getMessage().getConnectionId();
        String requestId = ctx.extractRequestId();
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
            // runner.run() 返回 CompletableFuture<String>，内部 LLM + 工具调用是异步的。
            // .join() 让当前线程（本请求的专属线程）阻塞等待，直到整个 Agent Loop 跑完。
            // 这是有意的同步等待——每个请求都有独立线程，无需在状态机层再引入异步复杂度。
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
     *
     * <h3>理解这个 lambda</h3>
     * 这是一个<b>工厂方法</b>：不直接执行推送，而是返回一个"待调用的回调函数"。
     *
     * <pre>{@code
     * Consumer<String> onDelta = buildOnDelta(...);       // ① 创建回调（此刻什么都不发）
     * runner.run(ctx, messages, onDelta);                 // ② 传入 AgentRunner
     *   └→ provider.chatStream(..., onDelta)              // ③ 传入 LLM Provider
     *        └→ onDelta.accept("北");  // LLM 产出 token  // ④ 回调在这里被触发！
     *        └→ onDelta.accept("京");
     *        └→ onDelta.accept("今天");
     * }</pre>
     *
     * lambda 表达式 {@code delta -> { ... }} 等价于：
     * <pre>{@code
     * new Consumer<String>() {
     *     @Override
     *     public void accept(String delta) {  // delta 由 LLM Provider 传入
     *         // ... 包装为 OutboundMessage → publishToOutboundQueue
     *     }
     * }
     * }</pre>
     *
     * <b>闭包捕获</b>：lambda 内部使用的 channel、sessionId、requestId、connectionId
     * 都是外层方法的局部变量/参数，lambda "记住"了这些值。
     * 所以当 LLM Provider 在另一个线程中调用 {@code accept(delta)} 时，
     * 仍能正确构建包含 sessionId/requestId 的 OutboundMessage。
     *
     * <p>注意：是每输出一个 token 就独立包装为 OutboundMessage！
     */
    private Consumer<String> buildOnDelta(TurnContext ctx, String connectionId,
                                           String requestId, String sessionId) {
        String channel = ctx.getMessage().getChannel();

        return delta -> {
            try {
                // ── Hook: ON_STREAM（每个 token 触发一次）──
                if (hookManager != null) {
                    hookManager.runHooks(HookContext.message(HookEvent.ON_STREAM, sessionId, delta));
                }

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

    /** 发布流结束标记到扇出队列（附带 token 数 + 工具迭代次数） */
    private void sendStreamEnd(TurnContext ctx, String connectionId, String sessionId, String requestId) {
        // ── Hook: STREAM_END ──
        if (hookManager != null) {
            hookManager.runHooks(HookContext.of(HookEvent.STREAM_END, sessionId));
        }

        try {
            Map<String, Object> meta = new java.util.HashMap<>();
            meta.put("_stream_end", true);
            meta.put("_token_count", ctx.getTotalTokens());
            meta.put("_tool_iterations", ctx.getIteration());
            OutboundMessage endMsg = OutboundMessage.builder()
                    .channel(ctx.getMessage().getChannel())
                    .sessionId(sessionId)
                    .requestId(requestId)
                    .connectionId(connectionId)
                    .metadata(meta)
                    .build();
            messageBus.publishToOutboundQueue(endMsg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Failed to publish stream end: {}", e.getMessage());
        }
    }

    private static boolean extractStreamMode(TurnContext ctx) {
        if (ctx.getMessage().getMetadata() == null) return false;
        Object o = ctx.getMessage().getMetadata().get("streamMode");
        return o instanceof Boolean b ? b : o instanceof String s && Boolean.parseBoolean(s);
    }
}
