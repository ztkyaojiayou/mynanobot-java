package com.nanocode.hook;

/**
 * Hook 事件 — 定义"什么时候"触发钩子.
 *
 * <h2>ECA 模型中的 Event 角色</h2>
 * 事件是 Hook 规则匹配的第一层：HookManager 遍历所有 Hook，
 * 只有 {@code hook.event() == ctx.event()} 的 Hook 才会进入条件匹配。
 *
 * <h2>事件分类</h2>
 *
 * <b>会话级（一次应用生命周期触发一次）</b>
 * <ul>
 *   <li>{@link #SESSION_START} — 应用启动就绪，所有组件初始化完成后</li>
 *   <li>{@link #SESSION_END} — JVM 关闭前，在 MessageBus/AgentLoop 关闭之前</li>
 * </ul>
 *
 * <b>轮次级（每条用户消息触发一次）</b>
 * <ul>
 *   <li>{@link #TURN_START} — 用户消息进入 AgentLoop 开始处理时</li>
 *   <li>{@link #TURN_END} — 状态机处理完成，响应已发布后</li>
 * </ul>
 *
 * <b>工具级（每次 LLM 调用工具触发）</b>
 * <ul>
 *   <li>{@link #PRE_TOOL_USE} — 工具执行前，可拦截</li>
 *   <li>{@link #POST_TOOL_USE} — 工具执行后</li>
 * </ul>
 *
 * <b>流式输出级</b>
 * <ul>
 *   <li>{@link #ON_STREAM} — LLM 每输出一个 token</li>
 *   <li>{@link #STREAM_END} — 流式输出全部完成</li>
 * </ul>
 *
 * <b>异常级</b>
 * <ul>
 *   <li>{@link #ON_ERROR} — 任何异常发生时</li>
 * </ul>
 *
 * <h2>拦截能力</h2>
 * 仅 {@link #TURN_START} 和 {@link #PRE_TOOL_USE} 支持 reject（拦截）.
 * 当 Hook 设置了 {@code reject = true} 且条件匹配时：
 * <ul>
 *   <li>TURN_START 被拦截 → 直接返回拒绝信息，不走状态机，不调 LLM</li>
 *   <li>PRE_TOOL_USE 被拦截 → 跳过该工具调用，返回 "[HOOK BLOCKED: ...]" 作为工具结果</li>
 * </ul>
 *
 * <h2>从配置反序列化</h2>
 * YAML 中的事件名不区分大小写：{@code event: "pre_tool_use"} 和
 * {@code event: PRE_TOOL_USE} 等价. 使用 {@link #of(String)} 解析.
 */
public enum HookEvent {

    // ── 会话级 ──
    SESSION_START,
    SESSION_END,

    // ── 轮次级 ──
    TURN_START,
    TURN_END,

    // ── 工具级 ──
    PRE_TOOL_USE,
    POST_TOOL_USE,

    // ── 流式输出级 ──
    ON_STREAM,
    STREAM_END,

    // ── 异常级 ──
    ON_ERROR;

    /**
     * 字符串 → HookEvent，不区分大小写.
     * 用于 YAML/JSON 反序列化和 CLI 命令场景.
     *
     * @param s 事件名（如 "pre_tool_use" 或 "PRE_TOOL_USE"）
     * @return 匹配的 HookEvent
     * @throws IllegalArgumentException s 为空或无法匹配时
     */
    public static HookEvent of(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("HookEvent name is required");
        }
        for (HookEvent e : values()) {
            if (e.name().equalsIgnoreCase(s.trim())) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown HookEvent: " + s);
    }
}