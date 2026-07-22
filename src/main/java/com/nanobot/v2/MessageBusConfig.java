package com.nanobot.v2;

import com.nanobot.bus.MessageBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MessageBus Spring 配置 — 整个系统的"神经中枢".
 *
 * <h2>为什么需要 MessageBus？</h2>
 * 用户请求来自多个入口（CLI 终端 / HTTP ChatController / WebSocket），
 * 但 AgentLoop 只有一个实例。如果每个入口都直接调 AgentLoop，
 * 会出现并发竞争、背压失控、请求丢失等问题.
 *
 * MessageBus 解决这个问题的方式是：<b>所有请求异步化</b>.
 *
 * <h2>架构类比</h2>
 * 相当于就是一个内存版的 MQ (Message Queue)——和 Kafka/RabbitMQ 一样
 * 遵循"生产者-消费者"模式，只是消息存储在 JVM 内存的阻塞队列中，
 * 不经过网络、不落盘，极致低延迟.
 *
 * <h2>消息流向</h2>
 * <pre>
 *   Producer (多入口)          Bus               Consumer (单线程)
 *   =================          ===               ==================
 *
 *   CLI Channel  --------+
 *                         |
 *   HTTP Controller ------+----> Inbound Queue  ----> AgentLoop
 *                         |    (BlockingQueue)        (死循环消费)
 *   WebSocket Endpoint ---+
 *
 *
 *   AgentLoop (处理完成) ----> Outbound Queue ----> Response Callback
 *                            (BlockingQueue)        (SSE / WS / HTTP)
 * </pre>
 *
 * <h2>关键设计决策</h2>
 * <ul>
 *   <li><b>异步解耦</b>：用户的所有请求都不会被同步直接处理，而是先发到 Bus 的
 *       Inbound 队列，AgentLoop 再异步消费。用户立即得到"消息已接收"的反馈，
 *       而不是阻塞等待 LLM 生成完整个回复.</li>
 *   <li><b>单线程消费</b>：AgentLoop 内部是单线程死循环消费（{@code while(running) consume}），
 *       保证消息按到达顺序处理，天然线程安全，不需要锁.</li>
 *   <li><b>背压保护</b>：BlockingQueue 有界队列 + offer/put 策略，
 *       流量过载时生产端会被阻塞或拒绝，防止 OOM.</li>
 *   <li><b>多通道合一</b>：CLI / HTTP / WebSocket 三种入口共享同一个 Bus，
 *       所有消息走同一套 AgentLoop 处理，行为一致.</li>
 * </ul>
 *
 * <h2>与其他组件的关系</h2>
 * <ul>
 *   <li>{@link com.nanobot.core.AgentLoop} — 唯一的消费者，从 Inbound Queue 取消息</li>
 *   <li>{@link com.nanobot.v2.controller.ChatController} — HTTP 入口，发布 Inbound 消息</li>
 *   <li>{@link com.nanobot.v2.websocket.NanobotWebSocketEndpoint} — WebSocket 入口</li>
 *   <li>{@link com.nanobot.v3.cli.CliChannel} — CLI 入口</li>
 * </ul>
 */
@Configuration
public class MessageBusConfig {

    /**
     * 全局唯一的 MessageBus 实例.
     *
     * 内部维护两个有界阻塞队列：
     * <ul>
     *   <li><b>Inbound Queue</b>（容量 1000）：所有入口发布用户消息到这里，
     *       AgentLoop 主循环消费</li>
     *   <li><b>Outbound Queue</b>（容量 1000）：AgentLoop 处理完成后发布响应到这里，
     *       各通道的回调（SSE / WS / HTTP）消费并推送给用户</li>
     * </ul>
     *
     * Spring 容器中全局唯一（默认 singleton scope），
     * CLI / HTTP / WebSocket 三种通道共享同一个实例.
     */
    @Bean
    public MessageBus messageBus() {
        return new MessageBus();
    }
}
