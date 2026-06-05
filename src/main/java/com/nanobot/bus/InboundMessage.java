package com.nanobot.bus;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 入站消息 - 从聊天通道接收到的消息
 * ====================================
 * 
 * 这是整个系统的核心数据模型之一。当用户通过任意聊天通道（如 Telegram、Discord、飞书等）
 * 发送消息时，消息会被封装成 InboundMessage 对象，然后发送到消息队列中等待处理。
 * 
 * 设计思想：
 * - 采用不可变对象模式（Immutable Pattern），确保消息在流转过程中不会被意外修改
 * - 使用 Optional 处理可选字段，避免 null 检查
 * - 保留丰富的元数据，支持通道扩展
 * 
 * 消息流转流程：
 * 1. 用户在聊天应用中发送消息
 * 2. 通道适配器（Channel Adapter）接收消息
 * 3. 适配器创建 InboundMessage 并发布到 MessageBus
 * 4. AgentLoop 从 MessageBus 消费消息进行处理
 * 5. 处理结果通过 OutboundMessage 返回给用户
 * 
 * 示例用法：
 * ```java
 * // 创建入站消息
 * InboundMessage msg = InboundMessage.builder()
 *     .channel("telegram")
 *     .senderId("user123")
 *     .chatId("chat456")
 *     .content("Hello, bot!")
 *     .build();
 * 
 * // 发布到消息总线
 * messageBus.publishInbound(msg);
 * ```
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InboundMessage {
    
    /**
     * 消息来源通道
     * 
     * 标识消息来自哪个聊天平台，例如：
     * - "telegram" - Telegram
     * - "discord" - Discord
     * - "feishu" - 飞书
     * - "wechat" - 微信
     * - "cli" - 命令行界面
     * - "websocket" - WebSocket 连接
     */
    private final String channel;
    
    /**
     * 发送者 ID
     * 
     * 发送消息的用户的唯一标识符。
     * 格式因通道而异，例如：
     * - Telegram: 数字形式的用户 ID
     * - Discord: 用户 ID (字符串形式)
     * - 飞书: 用户的 open_id
     */
    private final String senderId;
    
    /**
     * 聊天会话 ID
     * 
     * 标识消息所属的聊天会话（群组或私聊）。
     * 用于消息路由和会话管理。
     */
    private final String chatId;
    
    /**
     * 消息内容
     * 
     * 用户的文本消息内容。
     * 注意：附件和媒体文件路径在 media 字段中。
     */
    private final String content;
    
    /**
     * 消息时间戳
     * 
     * 消息创建的时间，使用 ISO-8601 格式。
     * 如果未指定，默认为当前时间。
     */
    private final Instant timestamp;
    
    /**
     * 媒体文件路径列表
     * 
     * 消息附带的媒体文件路径，可以包括：
     * - 图片文件
     * - 音频文件
     * - 视频文件
     * - 文档文件
     * 
     * 文件通常是已下载到本地的临时文件。
     */
    private final List<String> media;
    
    /**
     * 消息元数据
     * 
     * 通道特定的额外信息，例如：
     * - 原始消息 ID（用于回复）
     * - 消息编辑状态
     * - 回复引用信息
     * - 用户头像 URL
     * - 消息来源信息
     * 
     * 这是一个灵活的 Map，允许不同通道添加自己的元数据。
     */
    private final Map<String, Object> metadata;
    
    /**
     * 会话密钥覆盖
     * 
     * 用于支持特殊的会话路由场景。
     * 例如：
     * - 线程内回复（thread-scoped sessions）
     * - 多通道统一会话
     * 
     * 如果为 null，则使用默认的会话密钥格式："{channel}:{chatId}"
     */
    private final String sessionKeyOverride;
    
    /**
     * 连接 ID
     * 
     * 用于 WebSocket 连接场景，标识消息来自哪个连接。
     * 用于流式消息的路由。
     */
    private final String connectionId;
    
    // ==================== 构造函数 ====================
    
    /**
     * 私有构造函数 - 强制使用 Builder 模式创建对象
     * 
     * 采用 Builder 模式的好处：
     * 1. 代码更清晰，避免构造函数参数过多
     * 2. 支持可选参数，无需多个构造函数重载
     * 3. 便于后续添加新字段而不破坏现有代码
     */
    private InboundMessage(Builder builder) {
        this.channel = builder.channel;
        this.senderId = builder.senderId;
        this.chatId = builder.chatId;
        this.content = builder.content;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.media = builder.media != null ? List.copyOf(builder.media) : List.of();
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Map.of();
        this.sessionKeyOverride = builder.sessionKeyOverride;
        this.connectionId = builder.connectionId;
    }
    
    // ==================== 获取方法 ====================
    
    /**
     * 获取消息来源通道
     */
    public String getChannel() {
        return channel;
    }
    
    /**
     * 获取发送者 ID
     */
    public String getSenderId() {
        return senderId;
    }
    
    /**
     * 获取聊天会话 ID
     */
    public String getChatId() {
        return chatId;
    }
    
    /**
     * 获取消息内容
     */
    public String getContent() {
        return content;
    }
    
    /**
     * 获取消息时间戳
     */
    public Instant getTimestamp() {
        return timestamp;
    }
    
    /**
     * 获取媒体文件路径列表（不可变视图）
     */
    public List<String> getMedia() {
        return media;
    }
    
    /**
     * 获取消息元数据（不可变视图）
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    /**
     * 获取会话密钥覆盖值
     */
    public Optional<String> getSessionKeyOverride() {
        return Optional.ofNullable(sessionKeyOverride);
    }
    
    /**
     * 获取连接 ID
     */
    public String getConnectionId() {
        return connectionId;
    }
    
    /**
     * 计算会话密钥
     * 
     * 会话密钥用于：
     * 1. 会话隔离 - 不同会话有独立的对话历史
     * 2. 消息路由 - 将消息路由到正确的会话
     * 3. 并发控制 - 防止同一会话的并发处理
     * 
     * 格式说明：
     * - 如果有 sessionKeyOverride：使用覆盖值
     * - 否则：使用 "{channel}:{chatId}" 格式
     * 
     * 示例：
     * - "telegram:123456789"
     * - "discord:987654321"
     * - "feishu:ou_xxxxx"
     */
    public String getSessionKey() {
        return sessionKeyOverride != null 
            ? sessionKeyOverride 
            : channel + ":" + chatId;
    }
    
    /**
     * 获取指定元数据值
     * 
     * @param key 元数据键名
     * @return 元数据值，如果不存在返回 Optional.empty()
     */
    public Optional<Object> getMetadataValue(String key) {
        return Optional.ofNullable(metadata.get(key));
    }
    
    /**
     * 检查消息是否有媒体附件
     */
    public boolean hasMedia() {
        return !media.isEmpty();
    }
    
    /**
     * 检查消息内容是否为空
     */
    public boolean isContentEmpty() {
        return content == null || content.isBlank();
    }
    
    // ==================== Builder 模式 ====================
    
    /**
     * 创建新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * 从现有消息创建 Builder（用于复制和修改）
     */
    public Builder toBuilder() {
        return new Builder()
            .channel(this.channel)
            .senderId(this.senderId)
            .chatId(this.chatId)
            .content(this.content)
            .timestamp(this.timestamp)
            .media(this.media)
            .metadata(this.metadata)
            .sessionKeyOverride(this.sessionKeyOverride)
            .connectionId(this.connectionId);
    }
    
    /**
     * Builder 类 - 用于构建 InboundMessage 对象
     * 
     * 使用示例：
     * ```java
     * InboundMessage msg = InboundMessage.builder()
     *     .channel("telegram")
     *     .senderId("123456")
     *     .chatId("789012")
     *     .content("Hello!")
     *     .media(List.of("/path/to/image.jpg"))
     *     .metadata(Map.of("messageId", "msg123"))
     *     .build();
     * ```
     */
    public static class Builder {
        private String channel;
        private String senderId;
        private String chatId;
        private String content;
        private Instant timestamp;
        private List<String> media;
        private Map<String, Object> metadata;
        private String sessionKeyOverride;
        private String connectionId;
        
        public Builder channel(String channel) {
            this.channel = channel;
            return this;
        }
        
        public Builder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }
        
        public Builder chatId(String chatId) {
            this.chatId = chatId;
            return this;
        }
        
        public Builder content(String content) {
            this.content = content;
            return this;
        }
        
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder media(List<String> media) {
            this.media = media;
            return this;
        }
        
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }
        
        public Builder sessionKeyOverride(String sessionKeyOverride) {
            this.sessionKeyOverride = sessionKeyOverride;
            return this;
        }
        
        public Builder connectionId(String connectionId) {
            this.connectionId = connectionId;
            return this;
        }
        
        /**
         * 构建 InboundMessage 对象
         * 
         * @return 新的 InboundMessage 实例
         * @throws IllegalStateException 如果必需字段未设置
         */
        public InboundMessage build() {
            validate();
            return new InboundMessage(this);
        }
        
        /**
         * 验证必需字段
         */
        private void validate() {
            if (channel == null || channel.isBlank()) {
                throw new IllegalStateException("channel is required");
            }
            if (senderId == null || senderId.isBlank()) {
                throw new IllegalStateException("senderId is required");
            }
            if (chatId == null || chatId.isBlank()) {
                throw new IllegalStateException("chatId is required");
            }
        }
    }
    
    // ==================== Object 方法 ====================
    
    @Override
    public String toString() {
        return "InboundMessage{" +
            "channel='" + channel + '\'' +
            ", senderId='" + senderId + '\'' +
            ", chatId='" + chatId + '\'' +
            ", content='" + (content != null ? content.substring(0, Math.min(50, content.length())) + "..." : "null") + '\'' +
            ", media=" + media.size() + " files" +
            ", timestamp=" + timestamp +
            '}';
    }
}
