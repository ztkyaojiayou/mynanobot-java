package com.nanobot.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 消息总线 - 异步消息队列核心
 * ==============================
 * 
 * 消息总线是整个系统架构的核心组件，它实现了**生产者-消费者模式**，
 * 用于解耦消息的发送者和接收者。
 * 
 * **核心职责**：
 * 1. 接收来自各个通道适配器的入站消息（InboundMessage）
 * 2. 将 Agent 的响应消息传递给通道适配器（OutboundMessage）
 * 3. 提供异步处理能力，支持高并发
 * 4. 实现消息的缓冲和限流
 * 
 * **设计思想**：
 * 
 * 1. **双队列设计**：
 *    - inbound: 入站消息队列，存储从用户收到的消息
 *    - outbound: 出站消息队列，存储要发送给用户的响应
 * 
 * 2. **线程安全**：
 *    - 使用 BlockingQueue 实现线程安全的消息存储
 *    - 支持多生产者（多个通道适配器）多消费者（AgentLoop）
 * 
 * 3. **异步处理**：
 *    - 提供异步发布和消费方法
 *    - 使用 CompletableFuture 实现非阻塞操作
 * 
 * 4. **可配置性**：
 *    - 队列容量可配置
 *    - 支持队列满时的阻塞策略
 * 
 * **消息流转图**：
 * 
 * ```
 *  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
 *  │  Telegram   │────▶│             │     │             │
 *  │  Adapter    │     │             │     │             │
 *  └─────────────┘     │   Message   │     │   Agent     │
 *                      │    Bus     │────▶│   Loop      │
 *  ┌─────────────┐     │             │     │             │
 *  │  Discord    │────▶│   inbound   │     │             │
 *  │  Adapter    │     │   queue     │     │             │
 *  └─────────────┘     │             │     └─────────────┘
 *                      │             │     ┌─────────────┐
 *  ┌─────────────┐     │             │     │   Channel   │
 *  │   Feishu    │────▶│             │────▶│   Manager   │
 *  │   Adapter   │     │  outbound   │     │             │
 *  └─────────────┘     │   queue     │     │             │
 *                      │             │     └─────────────┘
 *                      └─────────────┘
 * ```
 * 
 * **使用示例**：
 * 
 * ```java
 * // 1. 创建消息总线（可在配置中设置队列大小）
 * MessageBus bus = new MessageBus();
 * 
 * // 2. 启动消息处理循环（在 AgentLoop 中）
 * CompletableFuture.runAsync(() -> {
 *     while (running) {
 *         try {
 *             InboundMessage msg = bus.consumeInbound()
 *                                      .get(1, TimeUnit.SECONDS);
 *             processMessage(msg);
 *         } catch (TimeoutException e) {
 *             // 继续等待
 *         }
 *     }
 * });
 * 
 * // 3. 通道适配器发布消息
 * InboundMessage userMsg = InboundMessage.builder()
 *     .channel("telegram")
 *     .senderId("123")
 *     .chatId("456")
 *     .content("Hello!")
 *     .build();
 * bus.publishInbound(userMsg);
 * 
 * // 4. Agent 发布响应
 * OutboundMessage response = OutboundMessage.text("telegram", "456", "Hi!");
 * bus.publishOutbound(response);
 * 
 * // 5. 清理资源
 * bus.shutdown();
 * ```
 * 
 * **队列容量说明**：
 * - 入站队列容量：控制同时处理的消息数量
 * - 出站队列容量：控制待发送的响应数量
 * - 队列满时：publish 方法会阻塞
 * - 建议值：取决于并发量和处理速度，通常 100-1000
 */
public class MessageBus {
    
    // ==================== 日志和状态 ====================
    
    /** 日志记录器 */
    private static final Logger logger = LoggerFactory.getLogger(MessageBus.class);
    
    /** 运行状态标志 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // ==================== 消息队列 ====================
    
    /**
     * 入站消息队列
     * 
     * 存储从各个通道适配器收到的用户消息。
     * 使用 ArrayBlockingQueue 实现线程安全的 FIFO 队列。
     * 
     * 特性：
     * - 有界队列，防止内存溢出
     * - 阻塞获取，消息处理可控制节奏
     * - 线程安全，无需额外同步
     */
    private final BlockingQueue<InboundMessage> inboundQueue;
    
    /**
     * 出站消息队列
     * 
     * 存储要发送给用户的响应消息。
     * 由 ChannelManager 消费，发送到对应通道。
     */
    private final BlockingQueue<OutboundMessage> outboundQueue;
    
    // ==================== 线程池 ====================
    
    /**
     * 异步操作线程池
     * 
     * 用于执行异步发布等操作。
     * 线程池大小适中，避免过多线程切换开销。
     */
    private final ExecutorService executor;
    
    // ==================== 配置常量 ====================
    
    /** 默认队列容量 */
    private static final int DEFAULT_QUEUE_CAPACITY = 100;
    
    // ==================== 构造函数 ====================
    
    /**
     * 使用默认容量创建消息总线
     * 
     * 默认队列容量为 100，适用于大多数场景。
     * 如果消息处理较慢或并发量较高，可以增大容量。
     */
    public MessageBus() {
        this(DEFAULT_QUEUE_CAPACITY);
    }
    
    /**
     * 使用指定容量创建消息总线
     * 
     * @param queueCapacity 入站和出站队列的容量
     * @throws IllegalArgumentException 如果容量 <= 0
     */
    public MessageBus(int queueCapacity) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("Queue capacity must be positive");
        }
        
        // 创建有界阻塞队列
        // ArrayBlockingQueue 特性：
        // - 基于数组实现，内存效率高
        // - FIFO 顺序
        // - 队列满时阻塞生产者
        // - 队列空时阻塞消费者
        this.inboundQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.outboundQueue = new ArrayBlockingQueue<>(queueCapacity);
        
        // 创建单线程池用于异步操作
        // 注意：这里只用少量线程处理异步发布，不是主要的消息处理线程
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "MessageBus-Async");
            t.setDaemon(true);  // 设为守护线程，不阻止 JVM 退出
            return t;
        });
        
        logger.info("MessageBus initialized with queue capacity: {}", queueCapacity);
    }
    
    // ==================== 启动和停止 ====================
    
    /**
     * 启动消息总线
     * 
     * 调用此方法后，消息总线开始接受消息。
     * 通常在 AgentLoop 启动时调用。
     */
    public void start() {
        running.set(true);
        logger.info("MessageBus started");
    }
    
    /**
     * 停止消息总线
     * 
     * 停止后不再接受新消息，但会处理完队列中已有的消息。
     * 通常在系统关闭时调用。
     */
    public void stop() {
        running.set(false);
        logger.info("MessageBus stopped");
    }
    
    /**
     * 安全关闭消息总线
     * 
     * 关闭线程池并清空队列。
     * 调用后 MessageBus 不能继续使用。
     * 
     * @param timeout 等待线程池关闭的最大时间
     */
    public void shutdown(long timeout, TimeUnit unit) {
        stop();
        
        // 清空队列（可选，取决于需求）
        // inboundQueue.clear();
        // outboundQueue.clear();
        
        // 关闭线程池
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout, unit)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(timeout, unit)) {
                    logger.warn("Executor did not terminate");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("MessageBus shutdown completed");
    }
    
    // ==================== 入站消息操作 ====================
    
    /**
     * 发布入站消息（同步）
     * 
     * 将用户消息放入入站队列，等待被 AgentLoop 处理。
     * 
     * 阻塞说明：
     * - 如果队列满，此方法会阻塞直到队列有空间
     * - 阻塞时间由系统负载决定
     * 
     * @param message 要发布的入站消息
     * @throws InterruptedException 如果阻塞时被中断
     */
    public void publishInbound(InboundMessage message) throws InterruptedException {
        if (!running.get()) {
            logger.warn("MessageBus is not running, ignoring message");
            return;
        }
        
        // offer() 可设置超时，put() 一直阻塞直到有空间
        // 这里使用 put() 因为消息发布通常不应该丢失
        inboundQueue.put(message);
        
        logger.debug("Published inbound message: channel={}, chatId={}", 
                    message.getChannel(), message.getChatId());
    }
    
    /**
     * 发布入站消息（异步）
     * 
     * 异步版本，不阻塞调用线程。
     * 
     * @param message 要发布的入站消息
     * @return 表示异步操作结果的 CompletableFuture
     */
    public CompletableFuture<Void> publishInboundAsync(InboundMessage message) {
        return CompletableFuture.runAsync(() -> {
            try {
                publishInbound(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while publishing message", e);
            }
        }, executor);
    }
    
    /**
     * 消费入站消息（阻塞）
     * 
     * 从入站队列获取下一条消息。
     * 如果队列为空，会阻塞等待直到有消息可用。
     * 
     * 典型用法：
     * ```java
     * while (running) {
     *     try {
     *         InboundMessage msg = bus.consumeInbound();
     *         processMessage(msg);
     *     } catch (InterruptedException e) {
     *         break;
     *     }
     * }
     * ```
     * 
     * @return 下一条入站消息
     * @throws InterruptedException 如果阻塞时被中断
     */
    public InboundMessage consumeInbound() throws InterruptedException {
        // take() 会一直阻塞直到队列有元素
        return inboundQueue.take();
    }
    
    /**
     * 尝试获取入站消息（非阻塞）
     * 
     * @return 下一条消息，如果有的话；否则返回 null
     */
    public InboundMessage pollInbound() {
        return inboundQueue.poll();
    }
    
    /**
     * 获取入站消息（带超时）
     * 
     * @param timeout 最大等待时间
     * @param unit 时间单位
     * @return 下一条消息，或 null 如果超时
     * @throws InterruptedException 如果等待时被中断
     */
    public InboundMessage consumeInbound(long timeout, TimeUnit unit) 
            throws InterruptedException {
        return inboundQueue.poll(timeout, unit);
    }
    
    // ==================== 出站消息操作 ====================
    
    /**
     * 发布出站消息（同步）
     * 
     * 将响应消息放入出站队列，等待被 ChannelManager 发送。
     * 
     * @param message 要发布的出站消息
     * @throws InterruptedException 如果阻塞时被中断
     */
    public void publishOutbound(OutboundMessage message) throws InterruptedException {
        if (!running.get()) {
            logger.warn("MessageBus is not running, ignoring outbound message");
            return;
        }
        
        outboundQueue.put(message);
        
        logger.debug("Published outbound message: channel={}, chatId={}", 
                    message.getChannel(), message.getChatId());
    }
    
    /**
     * 发布出站消息（异步）
     * 
     * @param message 要发布的出站消息
     * @return 表示异步操作结果的 CompletableFuture
     */
    public CompletableFuture<Void> publishOutboundAsync(OutboundMessage message) {
        return CompletableFuture.runAsync(() -> {
            try {
                publishOutbound(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while publishing outbound message", e);
            }
        }, executor);
    }
    
    /**
     * 消费出站消息（阻塞）
     * 
     * @return 下一条出站消息
     * @throws InterruptedException 如果阻塞时被中断
     */
    public OutboundMessage consumeOutbound() throws InterruptedException {
        return outboundQueue.take();
    }
    
    /**
     * 尝试获取出站消息（非阻塞）
     * 
     * @return 下一条消息，或 null 如果队列为空
     */
    public OutboundMessage pollOutbound() {
        return outboundQueue.poll();
    }
    
    /**
     * 获取出站消息（带超时）
     * 
     * @param timeout 最大等待时间
     * @param unit 时间单位
     * @return 下一条消息，或 null 如果超时
     * @throws InterruptedException 如果等待时被中断
     */
    public OutboundMessage consumeOutbound(long timeout, TimeUnit unit) 
            throws InterruptedException {
        return outboundQueue.poll(timeout, unit);
    }
    
    // ==================== 队列状态查询 ====================
    
    /**
     * 获取入站队列当前大小
     * 
     * @return 队列中的消息数量
     */
    public int getInboundSize() {
        return inboundQueue.size();
    }
    
    /**
     * 获取出站队列当前大小
     * 
     * @return 队列中的消息数量
     */
    public int getOutboundSize() {
        return outboundQueue.size();
    }
    
    /**
     * 检查入站队列是否为空
     * 
     * @return 如果队列为空返回 true
     */
    public boolean isInboundEmpty() {
        return inboundQueue.isEmpty();
    }
    
    /**
     * 检查出站队列是否为空
     * 
     * @return 如果队列为空返回 true
     */
    public boolean isOutboundEmpty() {
        return outboundQueue.isEmpty();
    }
    
    /**
     * 检查消息总线是否在运行
     * 
     * @return 如果正在运行返回 true
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * 获取队列剩余容量
     * 
     * @return 入站队列剩余容量
     */
    public int getInboundRemainingCapacity() {
        return inboundQueue.remainingCapacity();
    }
}
