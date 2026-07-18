package com.nanobot.core.state;

import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;

/**
 * Agent 状态处理器接口（State 模式）。
 *
 * 每个实现类对应 TurnState 中的一个状态，
 * 负责该状态的全部处理逻辑并返回下一状态。
 *
 * @see TurnState
 */
public interface AgentState {
    /** 执行当前状态逻辑，返回下一状态 */
    TurnState execute(TurnContext ctx);
}
