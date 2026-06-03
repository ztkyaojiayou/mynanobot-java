package com.nanobot.core;

/**
 * 状态枚举 - 消息处理状态机
 * ============================
 * 
 * 本枚举定义了 Agent 处理消息时的各种状态。
 * 消息从接收到响应完成，会经历一系列状态转换。
 * 
 * **状态转换流程**：
 * 
 * ```
 * RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE
 *     │          │         │        │       │       │         │
 *     ▼          ▼         ▼        ▼       ▼       ▼         ▼
 *   恢复      压缩历史   命令分发  构建    LLM    保存     发送
 *   会话                  上下文   调用    状态    响应
 * ```
 * 
 * **各状态说明**：
 * 
 * | 状态 | 描述 | 主要操作 |
 * |------|------|---------|
 * | RESTORE | 恢复会话 | 从存储加载会话历史 |
 * | COMPACT | 压缩历史 | 检查是否需要压缩对话历史 |
 * | COMMAND | 命令分发 | 检查并处理命令 |
 * | BUILD | 构建上下文 | 组装系统提示和消息 |
 * | RUN | 运行 | 调用 LLM 并处理工具调用 |
 * | SAVE | 保存状态 | 保存会话到存储 |
 * | RESPOND | 响应 | 发送响应消息 |
 * | DONE | 完成 | 清理资源，准备下一轮 |
 * 
 * **设计思想**：
 * 
 * 1. **清晰的状态划分**：
 *    - 每个状态有明确的职责
 *    - 状态之间低耦合
 * 
 * 2. **灵活的转换**：
 *    - 大部分状态转到 DONE
 *    - 某些状态可以跳转到其他状态（如 COMMAND → BUILD）
 * 
 * 3. **便于扩展**：
 *    - 可以添加新状态
 *    - 可以修改转换逻辑
 * 
 * **使用示例**：
 * 
 * ```java
 * // 在 AgentLoop 中使用
 * TurnState currentState = TurnState.RESTORE;
 * while (currentState != TurnState.DONE) {
 *     switch (currentState) {
 *         case RESTORE:
 *             // 恢复会话
 *             currentState = TurnState.COMPACT;
 *             break;
 *         case COMPACT:
 *             // 压缩历史
 *             currentState = TurnState.COMMAND;
 *             break;
 *         // ...
 *     }
 * }
 * ```
 */
public enum TurnState {
    
    /**
     * 恢复会话
     * 
     * 从存储系统加载会话历史。
     * 包括对话历史、系统提示、用户信息等。
     */
    RESTORE,
    
    /**
     * 压缩历史
     * 
     * 检查对话历史是否过长，
     * 如果超过预算，执行历史压缩（摘要）。
     */
    COMPACT,
    
    /**
     * 命令分发
     * 
     * 检查用户消息是否为特殊命令（如 /stop）。
     * 如果是命令，执行相应的处理逻辑。
     */
    COMMAND,
    
    /**
     * 构建上下文
     * 
     * 组装发送给 LLM 的完整消息列表。
     * 包括系统提示、记忆文件、历史消息等。
     */
    BUILD,
    
    /**
     * 运行（LLM 调用）
     * 
     * 调用 LLM 并处理响应。
     * 可能包含多轮工具调用。
     */
    RUN,
    
    /**
     * 保存状态
     * 
     * 将当前对话历史保存到存储。
     * 确保持久化和一致性。
     */
    SAVE,
    
    /**
     * 响应
     * 
     * 将 LLM 的响应发送给用户。
     * 处理流式输出的最终化。
     */
    RESPOND,
    
    /**
     * 完成
     * 
     * 清理资源，准备下一轮处理。
     * 这是状态机的终态。
     */
    DONE;
    
    /**
     * 获取下一状态
     * 
     * 根据事件获取下一个状态。
     * 这是默认的线性转换逻辑。
     * 
     * @param event 事件名称
     * @return 下一状态
     */
    public TurnState next(String event) {
        // 默认线性转换
        return switch (this) {
            case RESTORE -> COMPACT;
            case COMPACT -> COMMAND;
            case COMMAND -> "shortcut".equals(event) ? DONE : BUILD;
            case BUILD -> RUN;
            case RUN -> SAVE;
            case SAVE -> RESPOND;
            case RESPOND, DONE -> DONE;
        };
    }
    
    /**
     * 获取下一个状态（默认事件）
     */
    public TurnState next() {
        return next("ok");
    }
    
    /**
     * 检查是否为终态
     */
    public boolean isTerminal() {
        return this == DONE;
    }
    
    /**
     * 获取状态描述
     */
    public String getDescription() {
        return switch (this) {
            case RESTORE -> "恢复会话";
            case COMPACT -> "压缩历史";
            case COMMAND -> "命令分发";
            case BUILD -> "构建上下文";
            case RUN -> "运行（LLM 调用）";
            case SAVE -> "保存状态";
            case RESPOND -> "响应";
            case DONE -> "完成";
        };
    }
}
