package com.nanobot.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * SubagentCommunication - 子 Agent 通信管理器
 * ===========================================
 * 
 * 实现父子 Agent 之间以及子 Agent 之间的通信机制。
 * 
 * **通信模式**：
 * 
 * 1. **父子通信**：父 Agent → 子 Agent（任务分配、配置更新）
 * 2. **子父通信**：子 Agent → 父 Agent（结果汇报、状态通知）
 * 3. **子子通信**：子 Agent → 子 Agent（数据共享、协作）
 * 
 * **核心功能**：
 * - 消息队列（异步通信）
 * - 事件发布/订阅（状态变更通知）
 * - 共享状态存储（跨 Agent 数据共享）
 * - 消息路由（定向消息传递）
 * 
 * **使用示例**：
 * 
 * ```java
 * // 创建通信管理器
 * SubagentCommunication comm = new SubagentCommunication();
 * 
 * // 订阅事件
 * comm.subscribe(SubagentEvent.Type.STATUS_CHANGED, event -> {
 *     System.out.println("Agent " + event.getSourceId() + " changed status to " + event.getPayload());
 * });
 * 
 * // 发送消息
 * comm.send("parent", "child-1", SubagentMessage.of("task", "process this data"));
 * 
 * // 广播消息
 * comm.broadcast("parent", SubagentMessage.of("broadcast", "all agents should know this"));
 * 
 * // 设置共享状态
 * comm.setSharedState("global.config", config);
 * ```
 */
public class SubagentCommunication {
    
    private static final Logger logger = LoggerFactory.getLogger(SubagentCommunication.class);
    
    /** 消息队列（按目标 Agent ID 分组） */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<SubagentMessage>> messageQueues = new ConcurrentHashMap<>();
    
    /** 事件订阅者 */
    private final ConcurrentHashMap<SubagentEvent.Type, List<Consumer<SubagentEvent>>> eventSubscribers = new ConcurrentHashMap<>();
    
    /** 共享状态存储 */
    private final ConcurrentHashMap<String, Object> sharedState = new ConcurrentHashMap<>();
    
    /** 父子关系映射 */
    private final ConcurrentHashMap<String, String> parentChildMap = new ConcurrentHashMap<>();
    
    /** Agent 地址簿 */
    private final ConcurrentHashMap<String, AgentEndpoint> agentEndpoints = new ConcurrentHashMap<>();
    
    // ==================== 消息传递 ====================
    
    /**
     * 发送消息给指定 Agent
     */
    public void send(String fromId, String toId, SubagentMessage message) {
        message.setFromId(fromId);
        message.setToId(toId);
        message.setTimestamp(java.time.Instant.now());
        
        ConcurrentLinkedQueue<SubagentMessage> queue = messageQueues.computeIfAbsent(toId, k -> new ConcurrentLinkedQueue<>());
        queue.offer(message);
        
        logger.debug("Message sent from {} to {}: {}", fromId, toId, message.getType());
        
        // 触发消息接收事件
        publishEvent(SubagentEvent.messageReceived(toId, message));
    }
    
    /**
     * 发送消息（带回调）
     */
    public void send(String fromId, String toId, SubagentMessage message, Consumer<SubagentMessage> replyCallback) {
        message.setReplyCallback(replyCallback);
        send(fromId, toId, message);
    }
    
    /**
     * 广播消息给所有子 Agent
     */
    public void broadcast(String fromId, SubagentMessage message) {
        message.setFromId(fromId);
        message.setBroadcast(true);
        message.setTimestamp(java.time.Instant.now());
        
        // 发送给所有已知的子 Agent
        for (String childId : parentChildMap.keySet()) {
            ConcurrentLinkedQueue<SubagentMessage> queue = messageQueues.computeIfAbsent(childId, k -> new ConcurrentLinkedQueue<>());
            queue.offer(message.copy());
        }
        
        logger.debug("Broadcast message from {} to {} children: {}", 
                   fromId, parentChildMap.size(), message.getType());
        
        // 触发广播事件
        publishEvent(SubagentEvent.broadcastSent(fromId, message));
    }
    
    /**
     * 发送消息给父 Agent
     */
    public void sendToParent(String fromId, SubagentMessage message) {
        String parentId = parentChildMap.get(fromId);
        if (parentId != null) {
            send(fromId, parentId, message);
        } else {
            logger.warn("Cannot send to parent: no parent registered for agent {}", fromId);
        }
    }
    
    /**
     * 接收消息（非阻塞）
     */
    public Optional<SubagentMessage> receive(String agentId) {
        ConcurrentLinkedQueue<SubagentMessage> queue = messageQueues.get(agentId);
        if (queue != null) {
            SubagentMessage message = queue.poll();
            if (message != null) {
                logger.debug("Message received by {}: {}", agentId, message.getType());
                return Optional.of(message);
            }
        }
        return Optional.empty();
    }
    
    /**
     * 接收消息（阻塞等待）
     */
    public SubagentMessage receiveBlocking(String agentId, long timeoutMs) throws InterruptedException {
        ConcurrentLinkedQueue<SubagentMessage> queue = messageQueues.computeIfAbsent(agentId, k -> new ConcurrentLinkedQueue<>());
        
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            SubagentMessage message = queue.poll();
            if (message != null) {
                logger.debug("Message received by {}: {}", agentId, message.getType());
                return message;
            }
            Thread.sleep(10);
        }
        
        throw new InterruptedException("Timeout waiting for message");
    }
    
    /**
     * 检查是否有消息
     */
    public boolean hasMessages(String agentId) {
        ConcurrentLinkedQueue<SubagentMessage> queue = messageQueues.get(agentId);
        return queue != null && !queue.isEmpty();
    }
    
    /**
     * 获取消息数量
     */
    public int getMessageCount(String agentId) {
        ConcurrentLinkedQueue<SubagentMessage> queue = messageQueues.get(agentId);
        return queue != null ? queue.size() : 0;
    }
    
    // ==================== 事件系统 ====================
    
    /**
     * 订阅事件
     */
    public void subscribe(SubagentEvent.Type type, Consumer<SubagentEvent> subscriber) {
        eventSubscribers.computeIfAbsent(type, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(subscriber);
    }
    
    /**
     * 取消订阅
     */
    public void unsubscribe(SubagentEvent.Type type, Consumer<SubagentEvent> subscriber) {
        List<Consumer<SubagentEvent>> subscribers = eventSubscribers.get(type);
        if (subscribers != null) {
            subscribers.remove(subscriber);
        }
    }
    
    /**
     * 发布事件
     */
    private void publishEvent(SubagentEvent event) {
        List<Consumer<SubagentEvent>> subscribers = eventSubscribers.get(event.getType());
        if (subscribers != null) {
            for (Consumer<SubagentEvent> subscriber : subscribers) {
                try {
                    subscriber.accept(event);
                } catch (Exception e) {
                    logger.error("Error processing event subscriber: {}", e.getMessage());
                }
            }
        }
    }
    
    /**
     * 发布自定义事件
     */
    public void publishEvent(String sourceId, SubagentEvent.Type type, Object payload) {
        publishEvent(new SubagentEvent(sourceId, type, payload));
    }
    
    // ==================== 共享状态 ====================
    
    /**
     * 设置共享状态
     */
    public void setSharedState(String key, Object value) {
        sharedState.put(key, value);
        logger.debug("Shared state set: {} = {}", key, value != null ? value.getClass().getSimpleName() : "null");
        
        // 触发状态变更事件
        publishEvent(SubagentEvent.stateChanged("system", key, value));
    }
    
    /**
     * 获取共享状态
     */
    @SuppressWarnings("unchecked")
    public <T> T getSharedState(String key) {
        return (T) sharedState.get(key);
    }
    
    /**
     * 获取共享状态（带默认值）
     */
    @SuppressWarnings("unchecked")
    public <T> T getSharedState(String key, T defaultValue) {
        return (T) sharedState.getOrDefault(key, defaultValue);
    }
    
    /**
     * 删除共享状态
     */
    public void removeSharedState(String key) {
        Object removed = sharedState.remove(key);
        if (removed != null) {
            publishEvent(SubagentEvent.stateChanged("system", key, null));
        }
    }
    
    /**
     * 获取所有共享状态键
     */
    public Set<String> getSharedStateKeys() {
        return sharedState.keySet();
    }
    
    /**
     * 检查共享状态是否存在
     */
    public boolean hasSharedState(String key) {
        return sharedState.containsKey(key);
    }
    
    // ==================== 父子关系管理 ====================
    
    /**
     * 注册父子关系
     */
    public void registerParentChild(String parentId, String childId) {
        parentChildMap.put(childId, parentId);
        logger.debug("Registered parent-child relationship: {} -> {}", parentId, childId);
        
        // 触发注册事件
        publishEvent(SubagentEvent.agentRegistered(childId, parentId));
    }
    
    /**
     * 解除父子关系
     */
    public void unregisterChild(String childId) {
        String parentId = parentChildMap.remove(childId);
        if (parentId != null) {
            logger.debug("Unregistered parent-child relationship: {} -> {}", parentId, childId);
            publishEvent(SubagentEvent.agentUnregistered(childId));
        }
    }
    
    /**
     * 获取父 Agent ID
     */
    public Optional<String> getParentId(String childId) {
        return Optional.ofNullable(parentChildMap.get(childId));
    }
    
    /**
     * 获取所有子 Agent ID
     */
    public List<String> getChildIds(String parentId) {
        return parentChildMap.entrySet().stream()
            .filter(entry -> parentId.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .toList();
    }
    
    /**
     * 检查是否有子 Agent
     */
    public boolean hasChildren(String parentId) {
        return parentChildMap.values().stream().anyMatch(parentId::equals);
    }
    
    // ==================== Agent 端点管理 ====================
    
    /**
     * 注册 Agent 端点
     */
    public void registerEndpoint(String agentId, AgentEndpoint endpoint) {
        agentEndpoints.put(agentId, endpoint);
    }
    
    /**
     * 获取 Agent 端点
     */
    public Optional<AgentEndpoint> getEndpoint(String agentId) {
        return Optional.ofNullable(agentEndpoints.get(agentId));
    }
    
    /**
     * 移除 Agent 端点
     */
    public void removeEndpoint(String agentId) {
        agentEndpoints.remove(agentId);
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 清理 Agent 相关资源
     */
    public void cleanupAgent(String agentId) {
        // 移除消息队列
        messageQueues.remove(agentId);
        
        // 解除父子关系
        unregisterChild(agentId);
        
        // 移除端点
        removeEndpoint(agentId);
        
        logger.debug("Cleaned up resources for agent {}", agentId);
    }
    
    /**
     * 获取通信统计
     */
    public CommunicationStats getStats() {
        int totalMessages = messageQueues.values().stream().mapToInt(Queue::size).sum();
        int totalSubscribers = eventSubscribers.values().stream().mapToInt(List::size).sum();
        
        return new CommunicationStats(
            messageQueues.size(),
            totalMessages,
            eventSubscribers.size(),
            totalSubscribers,
            sharedState.size(),
            parentChildMap.size()
        );
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 子 Agent 消息
     */
    public static class SubagentMessage {
        private String id;
        private String type;
        private Object payload;
        private String fromId;
        private String toId;
        private boolean broadcast;
        private java.time.Instant timestamp;
        private Consumer<SubagentMessage> replyCallback;
        
        private SubagentMessage(String type, Object payload) {
            this.id = generateId();
            this.type = type;
            this.payload = payload;
        }
        
        /**
         * 创建消息
         */
        public static SubagentMessage of(String type, Object payload) {
            return new SubagentMessage(type, payload);
        }
        
        /**
         * 创建任务消息
         */
        public static SubagentMessage task(String task, Map<String, Object> params) {
            return new SubagentMessage("task", Map.of("task", task, "params", params));
        }
        
        /**
         * 创建状态更新消息
         */
        public static SubagentMessage status(String status) {
            return new SubagentMessage("status", status);
        }
        
        /**
         * 创建结果消息
         */
        public static SubagentMessage result(String content) {
            return new SubagentMessage("result", content);
        }
        
        /**
         * 创建错误消息
         */
        public static SubagentMessage error(String error) {
            return new SubagentMessage("error", error);
        }
        
        /**
         * 创建回复消息
         */
        public SubagentMessage createReply(Object payload) {
            SubagentMessage reply = new SubagentMessage("reply", payload);
            reply.setToId(this.fromId);
            reply.setFromId(this.toId);
            return reply;
        }
        
        /**
         * 发送回复
         */
        public void reply(SubagentCommunication comm, Object payload) {
            if (fromId != null) {
                SubagentMessage reply = createReply(payload);
                comm.send(toId, fromId, reply);
            }
        }
        
        /**
         * 复制消息
         */
        public SubagentMessage copy() {
            SubagentMessage copy = new SubagentMessage(type, payload);
            copy.fromId = fromId;
            copy.toId = toId;
            copy.broadcast = broadcast;
            copy.timestamp = timestamp;
            return copy;
        }
        
        private static String generateId() {
            return "msg-" + System.currentTimeMillis() + "-" + 
                   java.util.UUID.randomUUID().toString().substring(0, 8);
        }
        
        // Getters and Setters
        public String getId() { return id; }
        public String getType() { return type; }
        public Object getPayload() { return payload; }
        public String getFromId() { return fromId; }
        public String getToId() { return toId; }
        public boolean isBroadcast() { return broadcast; }
        public java.time.Instant getTimestamp() { return timestamp; }
        public Consumer<SubagentMessage> getReplyCallback() { return replyCallback; }
        
        public void setFromId(String fromId) { this.fromId = fromId; }
        public void setToId(String toId) { this.toId = toId; }
        public void setBroadcast(boolean broadcast) { this.broadcast = broadcast; }
        public void setTimestamp(java.time.Instant timestamp) { this.timestamp = timestamp; }
        public void setReplyCallback(Consumer<SubagentMessage> replyCallback) { this.replyCallback = replyCallback; }
        
        @Override
        public String toString() {
            return "SubagentMessage{" +
                "id='" + id + '\'' +
                ", type='" + type + '\'' +
                ", from='" + fromId + '\'' +
                ", to='" + toId + '\'' +
                ", broadcast=" + broadcast +
                '}';
        }
    }
    
    /**
     * 子 Agent 事件
     */
    public static class SubagentEvent {
        private final String sourceId;
        private final Type type;
        private final Object payload;
        private final java.time.Instant timestamp;
        
        public SubagentEvent(String sourceId, Type type, Object payload) {
            this.sourceId = sourceId;
            this.type = type;
            this.payload = payload;
            this.timestamp = java.time.Instant.now();
        }
        
        // 便捷工厂方法
        public static SubagentEvent messageReceived(String agentId, SubagentMessage message) {
            return new SubagentEvent(agentId, Type.MESSAGE_RECEIVED, message);
        }
        
        public static SubagentEvent broadcastSent(String agentId, SubagentMessage message) {
            return new SubagentEvent(agentId, Type.BROADCAST_SENT, message);
        }
        
        public static SubagentEvent agentRegistered(String agentId, String parentId) {
            return new SubagentEvent(agentId, Type.AGENT_REGISTERED, parentId);
        }
        
        public static SubagentEvent agentUnregistered(String agentId) {
            return new SubagentEvent(agentId, Type.AGENT_UNREGISTERED, null);
        }
        
        public static SubagentEvent stateChanged(String sourceId, String key, Object value) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("key", key);
            payload.put("value", value);  // HashMap 允许 null 值，Map.of 不允许
            return new SubagentEvent(sourceId, Type.STATE_CHANGED, payload);
        }
        
        public static SubagentEvent taskCompleted(String agentId, String taskId) {
            return new SubagentEvent(agentId, Type.TASK_COMPLETED, taskId);
        }
        
        public static SubagentEvent errorOccurred(String agentId, String error) {
            return new SubagentEvent(agentId, Type.ERROR_OCCURRED, error);
        }
        
        // Getters
        public String getSourceId() { return sourceId; }
        public Type getType() { return type; }
        public Object getPayload() { return payload; }
        public java.time.Instant getTimestamp() { return timestamp; }
        
        /**
         * 事件类型
         */
        public enum Type {
            MESSAGE_RECEIVED,    // 收到消息
            BROADCAST_SENT,      // 发送广播
            AGENT_REGISTERED,    // Agent 注册
            AGENT_UNREGISTERED,  // Agent 注销
            STATE_CHANGED,       // 状态变更
            TASK_COMPLETED,      // 任务完成
            ERROR_OCCURRED       // 发生错误
        }
        
        @Override
        public String toString() {
            return "SubagentEvent{" +
                "sourceId='" + sourceId + '\'' +
                ", type=" + type +
                ", timestamp=" + timestamp +
                '}';
        }
    }
    
    /**
     * Agent 端点信息
     */
    public static class AgentEndpoint {
        private final String agentId;
        private final String host;
        private final int port;
        private final String protocol;
        
        public AgentEndpoint(String agentId, String host, int port, String protocol) {
            this.agentId = agentId;
            this.host = host;
            this.port = port;
            this.protocol = protocol;
        }
        
        public String getAgentId() { return agentId; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getProtocol() { return protocol; }
        
        @Override
        public String toString() {
            return protocol + "://" + host + ":" + port + "/" + agentId;
        }
    }
    
    /**
     * 通信统计
     */
    public static class CommunicationStats {
        private final int queueCount;
        private final int messageCount;
        private final int eventTypeCount;
        private final int subscriberCount;
        private final int sharedStateCount;
        private final int parentChildCount;
        
        public CommunicationStats(int queueCount, int messageCount, int eventTypeCount,
                                 int subscriberCount, int sharedStateCount, int parentChildCount) {
            this.queueCount = queueCount;
            this.messageCount = messageCount;
            this.eventTypeCount = eventTypeCount;
            this.subscriberCount = subscriberCount;
            this.sharedStateCount = sharedStateCount;
            this.parentChildCount = parentChildCount;
        }
        
        public int getQueueCount() { return queueCount; }
        public int getMessageCount() { return messageCount; }
        public int getEventTypeCount() { return eventTypeCount; }
        public int getSubscriberCount() { return subscriberCount; }
        public int getSharedStateCount() { return sharedStateCount; }
        public int getParentChildCount() { return parentChildCount; }
        
        @Override
        public String toString() {
            return String.format(
                "CommunicationStats{queues=%d, messages=%d, events=%d, subscribers=%d, state=%d, parentChild=%d}",
                queueCount, messageCount, eventTypeCount, subscriberCount, sharedStateCount, parentChildCount);
        }
    }
}