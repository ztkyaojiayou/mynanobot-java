package com.nanobot.bus;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出站消息 - 发送给聊天通道的消息
 * ====================================
 *
 * 与 InboundMessage 相对，OutboundMessage 用于封装 Agent 生成的响应消息，
 * 这些消息会被发送到对应的聊天通道，最终展示给用户。
 *
 * 设计思想：
 * - 不可变对象模式，确保消息在发送过程中不会被修改
 * - 支持多种消息格式（文本、媒体、按钮）
 * - 丰富的元数据支持，用于控制消息发送行为
 *
 * 消息发送流程：
 * 1. AgentLoop 处理完消息，生成响应
 * 2. 创建 OutboundMessage 封装响应内容
 * 3. 发布到 MessageBus 的出站队列
 * 4. ChannelManager 消费出站队列
 * 5. 通道适配器将消息发送到具体平台
 *
 * 特殊标记说明：
 * - _progress: 进度消息（流式输出时的中间结果）
 * - _stream_delta: 流式消息片段
 * - _stream_end: 流式消息结束标记
 * - _tool_hint: 工具调用提示
 * - _retry_wait: 重试等待消息
 *
 * 示例用法：
 * ```java
 * // 创建普通响应消息
 * OutboundMessage msg = OutboundMessage.builder()
 *     .channel("telegram")
 *     .chatId("123456")
 *     .content("Hello! How can I help you?")
 *     .build();
 *
 * // 创建带按钮的消息
 * OutboundMessage msgWithButtons = OutboundMessage.builder()
 *     .channel("telegram")
 *     .chatId("123456")
 *     .content("Choose an option:")
 *     .buttons(List.of(
 *         List.of("Option A", "action_a"),
 *         List.of("Option B", "action_b")
 *     ))
 *     .build();
 *
 * messageBus.publishOutbound(msg);
 * ```
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@AllArgsConstructor
public class OutboundMessage {

    /**
     * 目标通道
     *
     * 消息要发送到的聊天平台，与 InboundMessage.channel 对应。
     */
    private String channel;

    /**
     * 目标聊天 ID
     *
     * 消息要发送到的具体聊天会话。
     */
    private String chatId;

    /**
     * 消息内容
     *
     * 发送给用户的文本内容。支持 Markdown 格式（取决于通道支持）。
     */
    private String content;

    /**
     * 回复目标消息 ID
     *
     * 如果设置，消息将作为对指定消息的回复发送。
     * 用于在支持的消息平台上创建回复链。
     */
    private String replyTo;

    /**
     * 媒体文件路径列表
     *
     * 随消息一起发送的媒体文件路径。
     * 文件通常是生成的图片或需要上传的本地文件。
     */
    private List<String> media;

    /**
     * 消息元数据
     *
     * 控制消息发送行为的特殊标记和通道特定数据：
     *
     * **系统标记（以 _ 开头）**：
     * - `_progress`: 进度消息标记
     * - `_stream_delta`: 流式输出片段
     * - `_stream_end`: 流式输出结束
     * - `_streamed`: 消息已流式发送完成
     * - `_tool_hint`: 工具调用提示
     * - `_retry_wait`: 重试等待状态
     * - `_wants_stream`: 请求启用流式输出
     *
     * **通道数据**：
     * - `message_id`: 原始消息 ID（用于编辑）
     * - `origin_message_id`: 来源消息 ID
     *
     * **其他**：
     * - 通道特定的格式化选项
     */
    private Map<String, Object> metadata;

    /**
     * 内联按钮
     *
     * 用于在消息下方显示的内联按钮。
     * 结构：List<行>，每行 List<按钮>
     *
     * 按钮格式示例：
     * ```java
     * List.of(
     *     List.of("Yes", "confirm"),      // 第一行按钮
     *     List.of("No", "cancel"),       // 第二行按钮
     *     List.of("Option 1", "opt1", "Option 2", "opt2")  // 一行多个按钮
     * )
     * ```
     *
     * 注意：不是所有通道都支持内联按钮
     */
    private List<List<String>> buttons;

    /**
     * 连接 ID
     *
     * 用于 WebSocket 连接场景，标识消息应该发送到哪个连接。
     */
    private String connectionId;

    /**
     * 会话 ID
     *
     * 关联的会话标识。
     */
    private String sessionId;

    /**
     * 请求 ID
     *
     * 用于精确匹配请求和响应。
     */
    private String requestId;

    // ==================== 构造函数 ====================

    /**
     * 私有构造函数 - 强制使用 Builder 模式
     */
    private OutboundMessage(Builder builder) {
        this.channel = builder.channel;
        this.chatId = builder.chatId;
        this.content = builder.content;
        this.replyTo = builder.replyTo;
        this.media = builder.media != null ? List.copyOf(builder.media) : List.of();
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Map.of();
        this.buttons = builder.buttons != null ? List.copyOf(builder.buttons) : List.of();
        this.connectionId = builder.connectionId;
        this.sessionId = builder.sessionId;
        this.requestId = builder.requestId;
    }

    // ==================== 业务方法 ====================

    /**
     * 获取指定元数据值
     */
    public Object getMetadataValue(String key) {
        return metadata != null ? metadata.get(key) : null;
    }

    /**
     * 获取布尔类型元数据值
     */
    public boolean getMetadataBoolean(String key, boolean defaultValue) {
        if (metadata == null) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    /**
     * 检查是否为进度消息
     *
     * 进度消息是流式输出时的中间结果，用于实时显示生成内容。
     */
    public boolean isProgress() {
        return getMetadataBoolean("_progress", false);
    }

    /**
     * 检查是否为流式消息片段
     */
    public boolean isStreamDelta() {
        return getMetadataBoolean("_stream_delta", false);
    }

    /**
     * 检查是否为流式消息结束标记
     */
    public boolean isStreamEnd() {
        return getMetadataBoolean("_stream_end", false);
    }

    /**
     * 检查是否已流式发送完成
     */
    public boolean isStreamed() {
        return getMetadataBoolean("_streamed", false);
    }

    /**
     * 检查是否为工具调用提示
     */
    public boolean isToolHint() {
        return getMetadataBoolean("_tool_hint", false);
    }

    /**
     * 检查消息是否有媒体附件
     */
    public boolean hasMedia() {
        return media != null && !media.isEmpty();
    }

    /**
     * 检查消息是否有按钮
     */
    public boolean hasButtons() {
        return buttons != null && !buttons.isEmpty();
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建简单的文本消息
     */
    public static OutboundMessage text(String channel, String chatId, String content) {
        return builder()
            .channel(channel)
            .chatId(chatId)
            .content(content)
            .build();
    }

    /**
     * 创建进度消息
     *
     * 进度消息用于流式输出时实时显示内容。
     *
     * @param channel 目标通道
     * @param chatId 目标聊天
     * @param delta 新增的内容片段
     * @return 进度消息
     */
    public static OutboundMessage progress(String channel, String chatId, String delta) {
        return builder()
            .channel(channel)
            .chatId(chatId)
            .content(delta)
            .metadata(Map.of("_progress", true))
            .build();
    }

    /**
     * 创建流式消息片段
     *
     * @param channel 目标通道
     * @param chatId 目标聊天
     * @param delta 内容片段
     * @param isEnd 是否为最后一个片段
     * @return 流式消息
     */
    public static OutboundMessage streamDelta(String channel, String chatId, String delta, boolean isEnd) {
        var metadata = new HashMap<String, Object>();
        metadata.put("_stream_delta", true);
        if (isEnd) {
            metadata.put("_stream_end", true);
        }

        return builder()
            .channel(channel)
            .chatId(chatId)
            .content(delta)
            .metadata(metadata)
            .build();
    }

    // ==================== Builder 模式 ====================

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
            .channel(this.channel)
            .chatId(this.chatId)
            .content(this.content)
            .replyTo(this.replyTo)
            .media(this.media)
            .metadata(this.metadata)
            .buttons(this.buttons)
            .connectionId(this.connectionId)
            .sessionId(this.sessionId)
            .requestId(this.requestId);
    }

    public static class Builder {
        private String channel;
        private String chatId;
        private String content;
        private String replyTo;
        private List<String> media;
        private Map<String, Object> metadata;
        private List<List<String>> buttons;
        private String connectionId;
        private String sessionId;
        private String requestId;

        public Builder channel(String channel) {
            this.channel = channel;
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

        public Builder replyTo(String replyTo) {
            this.replyTo = replyTo;
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

        public Builder addMetadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        public Builder buttons(List<List<String>> buttons) {
            this.buttons = buttons;
            return this;
        }

        public Builder connectionId(String connectionId) {
            this.connectionId = connectionId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public OutboundMessage build() {
            validate();
            return new OutboundMessage(this);
        }

        private void validate() {
            if (channel == null || channel.isBlank()) {
                throw new IllegalStateException("channel is required");
            }
            // chatId 不再强制要求，支持系统消息等场景
        }
    }

    // ==================== Object 方法 ====================

    @Override
    public String toString() {
        return "OutboundMessage{" +
            "channel='" + channel + '\'' +
            ", chatId='" + chatId + '\'' +
            ", content='" + (content != null ? content.substring(0, Math.min(50, content.length())) + "..." : "null") + '\'' +
            ", isProgress=" + isProgress() +
            ", isStreamDelta=" + isStreamDelta() +
            ", hasMedia=" + hasMedia() +
            ", hasButtons=" + hasButtons() +
            '}';
    }
}
