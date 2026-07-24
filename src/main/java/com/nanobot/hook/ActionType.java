package com.nanobot.hook;

/**
 * Hook 动作类型 — 定义"做什么".
 *
 * <h2>ECA 模型中的 Action 角色</h2>
 * 当 Hook 的 Event + Condition 都匹配后，按此类型执行对应动作.
 *
 * <h2>三种动作类型</h2>
 * <ul>
 *   <li>{@link #COMMAND} — 执行 shell 命令（bash -c），stdout 作为返回值.
 *       适合：审计脚本、格式化工具、自定义逻辑.</li>
 *   <li>{@link #PROMPT} — 注入提示文本到 LLM 上下文.
 *       适合：TURN_START 时动态注入项目规则、git 状态等上下文.</li>
 *   <li>{@link #SCRIPT} — 执行指定脚本文件路径.
 *       适合：复杂的、需要独立脚本文件的钩子逻辑（如多步骤审核流水线）.</li>
 * </ul>
 */
public enum ActionType {
    COMMAND,
    PROMPT,
    SCRIPT
}
