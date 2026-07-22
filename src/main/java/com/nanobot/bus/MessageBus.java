package com.nanobot.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 消息总线 — 异步消息队列，解耦消息生产者和消费者。
 *
 * 核心设计：
 * - inboundQueue: 入站队列，用户消息 → AgentLoop 消费
 * - sessionResponses: 会话响应映射，AgentLoop 写 → sync /api/chat 按 requestId 匹配读
 *
 * 注：已移除未使用的 outboundQueue（SSE/WS 通过 StreamResponseCallback 直推，不走队列）。
 */
public class MessageBus {

    private static final Logger logger = LoggerFactory.getLogger(MessageBus.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 入站消息队列 — 用户消息在此排队等待 AgentLoop 处理 */
    private final BlockingQueue<InboundMessage> inboundQueue;

    /**
     * 会话响应映射 — sync /api/chat 端点的请求-响应匹配。
     * AgentLoop.doRespond() 发布响应到此 Map，ChatController 按 requestId 取出。
     */
    private final ConcurrentHashMap<String, java.util.Queue<OutboundMessage>> sessionResponses;

    private static final int DEFAULT_QUEUE_CAPACITY = 100;

    public MessageBus() {
        this(DEFAULT_QUEUE_CAPACITY);
    }

    public MessageBus(int queueCapacity) {
        if (queueCapacity <= 0)
            throw new IllegalArgumentException("Queue capacity must be positive");
        this.inboundQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.sessionResponses = new ConcurrentHashMap<>();
        logger.info("MessageBus initialized with queue capacity: {}", queueCapacity);
    }

    // ═══════════ 生命周期 ═══════════

    public void start() {
        running.set(true);
        logger.info("MessageBus started");
    }

    public void stop() {
        running.set(false);
        logger.info("MessageBus stopped");
    }

    public void shutdown(long timeout, TimeUnit unit) {
        stop();
        logger.info("MessageBus shutdown completed");
    }

    public boolean isRunning() { return running.get(); }

    // ═══════════ 入站消息 ═══════════

    /** 发布用户消息到入站队列（阻塞直到队列有空间） */
    public void publishInbound(InboundMessage message) throws InterruptedException {
        if (!running.get()) {
            logger.warn("🛑 MessageBus NOT RUNNING — message dropped! content='{}'",
                    message.getContent() != null ? message.getContent().substring(0, Math.min(60, message.getContent().length())) : "null");
            return;
        }
        inboundQueue.put(message);
        logger.info("📥 [PUB] sessionId={}, content='{}', queueSize={}",
                message.getSessionId(),
                message.getContent() != null ? message.getContent().substring(0, Math.min(60, message.getContent().length())) : "null",
                inboundQueue.size());
    }

    /** 消费入站消息（阻塞直到有消息可用） */
    public InboundMessage consumeInbound() throws InterruptedException {
        return inboundQueue.take();
    }

    /** 消费入站消息（带超时，超时返回 null） */
    public InboundMessage consumeInbound(long timeout, TimeUnit unit) throws InterruptedException {
        return inboundQueue.poll(timeout, unit);
    }

    /** 非阻塞尝试获取入站消息 */
    public InboundMessage pollInbound() {
        return inboundQueue.poll();
    }

    public int getInboundSize() { return inboundQueue.size(); }
    public boolean isInboundEmpty() { return inboundQueue.isEmpty(); }
    public int getInboundRemainingCapacity() { return inboundQueue.remainingCapacity(); }

    // ═══════════ 出站消息（会话响应匹配） ═══════════

    /**
     * 发布响应到会话映射 — AgentLoop.doRespond() 调用。
     * 响应存储在 sessionResponses 中，由 ChatController.waitForSessionResponse() 按 requestId 匹配取出。
     */
    public void publishOutbound(OutboundMessage message) throws InterruptedException {
        if (!running.get()) {
            logger.warn("MessageBus not running, ignoring outbound");
            return;
        }
        if (message.getSessionId() != null) {
            sessionResponses.computeIfAbsent(message.getSessionId(), k -> new java.util.LinkedList<>()).offer(message);
        }
        logger.debug("Published outbound: sessionId={}", message.getSessionId());
    }

    // ═══════════ 响应匹配 ═══════════

    /**
     * 按 requestId 精确匹配响应 — sync /api/chat 端点使用。
     * 轮询 sessionResponses，直到找到匹配 requestId 的响应或超时。
     */
    public OutboundMessage waitForSessionResponse(String sessionId, String requestId, long timeout, TimeUnit unit)
            throws InterruptedException {
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < endTime) {
            java.util.Queue<OutboundMessage> queue = sessionResponses.get(sessionId);
            if (queue != null) {
                for (java.util.Iterator<OutboundMessage> it = queue.iterator(); it.hasNext(); ) {
                    OutboundMessage msg = it.next();
                    if (requestId != null && requestId.equals(msg.getRequestId())) {
                        it.remove(); // 取完即删，防止堆积
                        logger.info("Found response for request: {}", requestId);
                        return msg;
                    }
                }
            }
            Thread.sleep(50);
        }
        logger.warn("Timeout waiting for response: session={}, requestId={}", sessionId, requestId);
        return null;
    }

    /** 兼容旧版调用（无 requestId） */
    public OutboundMessage waitForSessionResponse(String sessionId, long timeout, TimeUnit unit)
            throws InterruptedException {
        return waitForSessionResponse(sessionId, null, timeout, unit);
    }

    /** 清理指定会话的待取响应 */
    public void clearSessionResponse(String sessionId) {
        sessionResponses.remove(sessionId);
    }
}
