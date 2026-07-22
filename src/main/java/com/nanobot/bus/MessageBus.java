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
 * <h2>三队列架构</h2>
 * <pre>
 *   Inbound Queue (BlockingQueue, 100)
 *     → 所有入口(CLI/HTTP/WS)发布用户消息
 *     → AgentLoop 单线程消费
 *
 *   Outbound Queue (BlockingQueue, 1000) ← 流式发布-订阅核心
 *     → RunState 发布每个流式 token
 *     → Dispatcher 线程扇出到各 Subscriber Queue
 *     → 各通道独立消费者线程 poll 自己的队列
 *
 *   sessionResponses (ConcurrentHashMap) ← sync /api/chat 专用
 *     → RespondState 发布最终响应
 *     → ChatController.waitForSessionResponse 轮询取
 * </pre>
 *
 * <h2>为什么用 Fan-Out Dispatcher 而不是单队列多消费者？</h2>
 * BlockingQueue.take() 是破坏性消费——消息只能被一个消费者取走。
 * 三个通道 (SSE/CLI/WS) 都需要接收流式数据，必须各自有独立的队列。
 * Dispatcher 线程从 outboundQueue 取一条消息 → 逐个 offer 到各 subscriberQueue。
 */
public class MessageBus {

    private static final Logger logger = LoggerFactory.getLogger(MessageBus.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    // ── Inbound ──
    private final BlockingQueue<InboundMessage> inboundQueue;

    // ── Outbound 扇出 pub-sub ──
    private final BlockingQueue<OutboundMessage> outboundQueue;
    private final java.util.List<BlockingQueue<OutboundMessage>> subscriberQueues =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private Thread dispatcherThread;

    // ── Sync /api/chat 响应匹配 ──
    private final ConcurrentHashMap<String, java.util.Queue<OutboundMessage>> sessionResponses;

    private static final int DEFAULT_QUEUE_CAPACITY = 100;
    private static final int OUTBOUND_QUEUE_CAPACITY = 1000;
    private static final int SUBSCRIBER_QUEUE_CAPACITY = 500;

    public MessageBus() {
        this(DEFAULT_QUEUE_CAPACITY);
    }

    public MessageBus(int queueCapacity) {
        if (queueCapacity <= 0)
            throw new IllegalArgumentException("Queue capacity must be positive");
        this.inboundQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.outboundQueue = new ArrayBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
        this.sessionResponses = new ConcurrentHashMap<>();
        logger.info("MessageBus initialized: inbound={}, outbound={}",
                queueCapacity, OUTBOUND_QUEUE_CAPACITY);
    }

    // ═══════════ 生命周期 ═══════════

    public void start() {
        running.set(true);
        startDispatcher();
        logger.info("MessageBus started (dispatcher + inbound consumer ready)");
    }

    public void stop() {
        running.set(false);
        if (dispatcherThread != null) {
            dispatcherThread.interrupt();
        }
        logger.info("MessageBus stopped");
    }

    public void shutdown(long timeout, TimeUnit unit) {
        stop();
        logger.info("MessageBus shutdown completed");
    }

    public boolean isRunning() { return running.get(); }

    /** 启动扇出 dispatcher — 从 outboundQueue 读，逐个 offer 到各 subscriberQueue */
    private void startDispatcher() {
        dispatcherThread = new Thread(() -> {
            logger.info("Outbound dispatcher started, {} subscribers", subscriberQueues.size());
            while (running.get()) {
                try {
                    OutboundMessage msg = outboundQueue.poll(1, TimeUnit.SECONDS);
                    if (msg == null) continue;
                    for (BlockingQueue<OutboundMessage> sq : subscriberQueues) {
                        try {
                            if (!sq.offer(msg, 100, TimeUnit.MILLISECONDS)) {
                                logger.warn("Subscriber queue full, dropping: sid={}, rid={}",
                                        msg.getSessionId(), msg.getRequestId());
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Dispatcher error: {}", e.getMessage(), e);
                }
            }
            logger.info("Outbound dispatcher stopped");
        }, "MessageBus-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
    }

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

    // ═══════════ 出站：扇出 Pub-Sub（流式分发核心） ═══════════

    /**
     * 发布流式 token/事件到扇出队列（RunState 调用）。
     * 使用 put() 阻塞——队列满时产生背压，自然限速 LLM token 产出。
     */
    public void publishToOutboundQueue(OutboundMessage message) throws InterruptedException {
        if (!running.get()) {
            logger.debug("MessageBus not running, dropping outbound message");
            return;
        }
        outboundQueue.put(message);
    }

    /**
     * 订阅流式消息——返回独立的 subscriberQueue。
     * 消费者线程 poll 自己的队列，不会和其他消费者竞争。
     *
     * @return 订阅者专属的阻塞队列（容量 500）
     */
    public BlockingQueue<OutboundMessage> subscribeToOutbound() {
        BlockingQueue<OutboundMessage> q = new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE_CAPACITY);
        subscriberQueues.add(q);
        logger.info("Outbound subscriber added, total: {}", subscriberQueues.size());
        return q;
    }

    /** 取消订阅——消费者关闭时调用，防止内存泄漏 */
    public void unsubscribeFromOutbound(BlockingQueue<OutboundMessage> q) {
        subscriberQueues.remove(q);
        logger.info("Outbound subscriber removed, remaining: {}", subscriberQueues.size());
    }

    /** 诊断：outboundQueue 当前积压量 */
    public int getOutboundQueueSize() { return outboundQueue.size(); }

    /** 诊断：当前订阅者数量 */
    public int getSubscriberCount() { return subscriberQueues.size(); }

    // ═══════════ 出站消息（会话响应匹配 — sync /api/chat 专用） ═══════════

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
