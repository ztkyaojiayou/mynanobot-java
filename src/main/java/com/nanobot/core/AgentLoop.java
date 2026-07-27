package com.nanobot.core;

import com.nanobot.bus.*;
import com.nanobot.config.Config;
import com.nanobot.hook.HookManager;
import com.nanobot.hook.HookContext;
import com.nanobot.hook.HookEvent;
import com.nanobot.identity.IdentityManager;
import com.nanobot.providers.LLMProvider;
import com.nanobot.rules.RuleManager;
import com.nanobot.session.SessionManager;
import com.nanobot.skill.SkillManager;
import com.nanobot.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent Loop - 消息处理状态机引擎
 * =================================
 * <p>
 * 本类是整个 Agent 系统的核心，负责：
 * 1. 从消息队列消费消息
 * 2. 管理状态机转换
 * 3. 协调各个组件完成消息处理
 * <p>
 * **状态机流程**：
 * <p>
 * ```
 * ┌────────────┐
 * │   START    │
 * └─────┬──────┘
 * ▼
 * ┌────────────┐     ┌────────────┐
 * │  RESTORE   │────▶│  COMPACT   │
 * └────────────┘     └─────┬──────┘
 * ▼
 * ┌────────────┐
 * │  COMMAND   │◀───┐
 * └─────┬──────┘    │
 * ▼          │
 * ┌────────────┐    │
 * │   BUILD    │────┘
 * └─────┬──────┘
 * ▼
 * ┌────────────┐
 * │    RUN     │
 * └─────┬──────┘
 * ▼
 * ┌────────────┐
 * │   SAVE    │
 * └─────┬──────┘
 * ▼
 * ┌────────────┐
 * │  RESPOND   │
 * └─────┬──────┘
 * ▼
 * ┌────────────┐
 * │    DONE    │
 * └────────────┘
 * ```
 * <p>
 * **设计思想**：
 * <p>
 * 1. **状态驱动**：
 * - 每个状态对应一个处理阶段
 * - 状态之间通过事件转换
 * - 便于理解和扩展
 * <p>
 * 2. **组件协作**：
 * - SessionManager: 会话管理
 * - AgentRunner: LLM 执行
 * - ToolRegistry: 工具调用
 * - MessageBus: 消息传递
 * <p>
 * 3. **异步处理**：
 * - 消息异步消费
 * - LLM 调用异步执行
 * - 响应异步发送
 * <p>
 * **使用示例**：
 * <p>
 * ```java
 * // 1. 创建组件
 * Config config = ConfigLoader.load();
 * ToolRegistry registry = new ToolRegistry();
 * LLMProvider provider = new OpenAIProvider(apiKey, model);
 * SessionManager sessionManager = new SessionManager(config);
 * MessageBus bus = new MessageBus();
 * <p>
 * // 2. 创建 AgentLoop
 * AgentLoop loop = new AgentLoop(bus, provider, registry, sessionManager, config);
 * <p>
 * // 3. 启动
 * loop.start();
 * <p>
 * // 4. 发布消息（由通道适配器执行）
 * bus.publishInbound(message);
 * ```
 */
public class AgentLoop {

    // ==================== 日志 ====================

    private static final Logger logger = LoggerFactory.getLogger(AgentLoop.class);

    /**
     * State 模式处理器注册表
     */
    private final java.util.Map<TurnState, com.nanobot.core.state.AgentState> stateHandlers = new java.util.EnumMap<>(TurnState.class);

    // ==================== 组件 ====================

    /**
     * 消息总线
     */
    private final MessageBus messageBus;

    /**
     * LLM 提供商
     */
    private final LLMProvider provider;

    /**
     * 工具注册中心
     */
    private final ToolRegistry registry;

    /**
     * 会话管理器
     */
    private final SessionManager sessionManager;

    /**
     * 配置
     */
    private final Config config;

    /**
     * 规则管理器
     */
    private RuleManager ruleManager;

    /**
     * 技能管理器
     */
    private SkillManager skillManager;

    /**
     * 身份管理器（SOUL, IDENTITY, USER）
     */
    private IdentityManager identityManager;

    // ==================== 运行时 ====================

    /**
     * Agent Runner
     */
    private final AgentRunner runner;

    /**
     * 记忆压缩器 — 当对话历史超过 token 预算时，自动调用 LLM 总结旧消息。
     * 可选注入，null 时跳过压缩。通过 {@link #setConsolidator} 设置。
     */
    private com.nanobot.memory.Consolidator consolidator;

    /**
     * 运行状态
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 执行线程
     */
    private ExecutorService executor;

    /**
     * 进度回调
     */
    /**
     * 规划模式 — 只读分析 + 出计划 + 等审批。
     * true 时 LLM 只能读文件/搜索，不能修改或执行命令。
     */
    private volatile boolean planMode = false;

    /**
     * Hook 管理器 — ECA 声明式钩子系统.
     * 在 9 个生命周期节点触发，支持条件 DSL 匹配 + 三种动作（COMMAND/PROMPT/SCRIPT）.
     * 通过构造函数注入.
     */
    private HookManager hookManager;

    // ==================== 构造函数 ====================

    public AgentLoop(MessageBus messageBus, LLMProvider provider, ToolRegistry registry, SessionManager sessionManager, Config config) {
        this(messageBus, provider, registry, sessionManager, config, null, null, null, null);
    }

    public AgentLoop(MessageBus messageBus, LLMProvider provider, ToolRegistry registry, SessionManager sessionManager, Config config, RuleManager ruleManager, SkillManager skillManager) {
        this(messageBus, provider, registry, sessionManager, config, ruleManager, skillManager, null, null);
    }

    public AgentLoop(MessageBus messageBus, LLMProvider provider, ToolRegistry registry, SessionManager sessionManager, Config config, RuleManager ruleManager, SkillManager skillManager, IdentityManager identityManager, HookManager hookManager) {

        this.messageBus = Objects.requireNonNull(messageBus, "messageBus cannot be null");
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.ruleManager = ruleManager;
        this.skillManager = skillManager;
        this.identityManager = identityManager;
        this.hookManager = hookManager;

        this.runner = new AgentRunner(provider, registry, messageBus);
        if (hookManager != null) {
            this.runner.setHookManager(hookManager);
        }
        this.consolidator = null;  // 通过 setConsolidator() 注入

        // 初始化 State 处理器 (State 模式)
        initStateHandlers();
    }

    // ==================== Hook 管理器 ====================

    /**
     * 获取 Hook 管理器.
     */
    public HookManager getHookManager() {
        return hookManager;
    }

    /**
     * 设置 Hook 管理器（用于测试或运行时替换）.
     */
    public void setHookManager(HookManager hookManager) {
        this.hookManager = hookManager;
    }

    // ==================== 生命周期 ====================

    /**
     * 启动 Agent Loop
     */
    public void start() {
        if (running.get()) {
            logger.warn("AgentLoop is already running");
            return;
        }

        running.set(true);
        messageBus.start();

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AgentLoop");
            t.setDaemon(true);
            return t;
        });

        executor.submit(this::runLoop);

        logger.info("AgentLoop started");
    }

    /**
     * 停止 Agent Loop
     */
    public void stop() {
        if (!running.get()) {
            return;
        }

        running.set(false);

        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        messageExecutor.shutdown();
        messageBus.stop();
        runner.shutdown();

        logger.info("AgentLoop stopped");
    }

    // ==================== 主循环 ====================

    /**
     * 消息处理线程池（避免 LLM 调用阻塞主循环）
     */
    private final java.util.concurrent.ExecutorService messageExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "AgentLoop-worker");
                t.setDaemon(true);
                return t;
            });

    /**
     * 运行主循环 — 消费入站队列，异步分发到工作线程.
     *
     * <h3>循环体（3 步）</h3>
     * <ol>
     *   <li>{@link #dispatchMessage} — 消费入站消息 + 提交到工作线程池</li>
     *   <li>{@link #emitHeartbeat} — 定时输出心跳日志（含队列堆积告警）</li>
     *   <li>异常处理 — InterruptedException 退出 / 其他异常记录日志</li>
     * </ol>
     */
    private void runLoop() {
        logger.info("AgentLoop main loop started (async mode)");

        long lastHeartbeat = System.currentTimeMillis();
        int processedCount = 0;
        while (running.get()) {
            try {
                // ① 消费消息 + 异步分发
                InboundMessage message = messageBus.consumeInbound(1, TimeUnit.SECONDS);
                if (message != null) {
                    processedCount = dispatchMessage(message, processedCount);
                }

                // ② 定时心跳
                lastHeartbeat = emitHeartbeat(lastHeartbeat, processedCount);

            } catch (InterruptedException e) {
                logger.info("AgentLoop interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error in AgentLoop: {}", e.getMessage(), e);
            }
        }

        logger.info("AgentLoop main loop ended");
    }

    // ── runLoop 子步骤 ──

    /** ① 记录入站消息并提交到工作线程池异步处理，返回新的 processedCount */
    private int dispatchMessage(InboundMessage message, int processedCount) {
        processedCount++;
        final int msgNum = processedCount;
        logger.info("📨 [MSG-IN] #{}: channel={}, sessionId={}, content='{}' (len={})",
                msgNum,
                message.getChannel(),
                message.getSessionId(),
                message.getContent() != null ? message.getContent().substring(0, Math.min(80, message.getContent().length())) : "null",
                message.getContent() != null ? message.getContent().length() : 0);

        final InboundMessage msg = message;
        messageExecutor.submit(() -> {
            try {
                processMessage(msg);
            } catch (Exception e) {
                logger.error("Async message processing failed: {}", e.getMessage(), e);
            }
        });
        return processedCount;
    }

    /** ② 每30秒输出心跳日志，含队列堆积告警；返回新的 lastHeartbeat 时间戳 */
    private long emitHeartbeat(long lastHeartbeat, int processedCount) {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat <= 30_000) return lastHeartbeat;

        int inboundSize = messageBus.getInboundSize();
        int outboundSize = messageBus.getOutboundQueueSize();
        logger.info("💓 AgentLoop heartbeat: processed={}, inbound={}/100, outbound={}/1000, subscribers={}",
                processedCount, inboundSize, outboundSize, messageBus.getSubscriberCount());
        if (inboundSize > 50) logger.warn("⚠️ 入站队列堆积: {}/100", inboundSize);
        if (outboundSize > 500) logger.warn("⚠️ 出站队列堆积: {}/1000", outboundSize);
        return now;
    }

    /**
     * 处理单条消息 — Hook 门控 → 状态机 → 响应 → Hook 收尾.
     *
     * <h3>处理流程（4 步）</h3>
     * <ol>
     *   <li>{@link #runPreProcessingHooks} — TURN_START 拦截检查</li>
     *   <li>{@link #createTurnContext} — 构建 TurnContext（含 plan mode 工具定义）</li>
     *   <li>{@link #processStates} — 状态机执行</li>
     *   <li>{@link #runPostProcessingHooks} — TURN_END / ON_ERROR</li>
     * </ol>
     */
    private void processMessage(InboundMessage message) {
        String sessionId = message.getSessionId();
        logger.info("Processing message for session: {}", sessionId);

        // ① Hook 门控：TURN_START 拦截 + prompt 收集
        if (runPreProcessingHooks(message)) return;

        long startTime = System.currentTimeMillis();

        try {
            // ② 构建 TurnContext
            TurnContext context = createTurnContext(message);

            // ③ 状态机处理
            String result = processStates(context);

            // ④ 发送响应 + TURN_END Hook
            sendResponse(message, result, context);

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Message processed in {}ms, tokens: {}", duration, context.getTotalTokens());
            runPostProcessingHooks(sessionId);

        } catch (Exception e) {
            handleProcessingError(message, sessionId, e);
        }
    }

    // ── processMessage 子步骤 ──

    /**
     * ① 执行 TURN_START Hook（拦截检查 + prompt 收集）.
     *
     * @return true 表示消息被拦截，调用方应直接返回
     */
    private boolean runPreProcessingHooks(InboundMessage message) {
        if (hookManager == null) return false;

        String sessionId = message.getSessionId();
        HookContext startCtx = HookContext.message(HookEvent.TURN_START,
                sessionId, message.getContent());

        // 拦截检查
        if (hookManager.runTurnStartHooks(startCtx)) {
            logger.info("Turn blocked by hook for session: {}", sessionId);
            sendResponse(message, "[HOOK BLOCKED] 消息被拦截，未进入处理", null);
            hookManager.runHooks(HookContext.of(HookEvent.TURN_END, sessionId));
            return true;
        }

        // 收集 PROMPT 钩子文本（由 BuildState 注入 System Prompt）
        hookManager.collectPrompts(startCtx);
        return false;
    }

    /** ② 从消息 + 全局配置 + planMode 构建 TurnContext */
    private TurnContext createTurnContext(InboundMessage message) {
        Config.AgentDefaults defaults = config.getAgents().getDefaults();
        return TurnContext.create(message,
                defaults.getModel(),
                defaults.getMaxTokens(),
                defaults.getTemperature(),
                defaults.getMaxToolIterations(),
                planMode ? registry.getDefinitions(true) : registry.getDefinitions(),
                defaults.getMaxTurns(),
                defaults.getMaxCost());
    }

    /** ③ 触发 TURN_END Hook（fire-and-forget） */
    private void runPostProcessingHooks(String sessionId) {
        if (hookManager != null) {
            hookManager.runHooks(HookContext.of(HookEvent.TURN_END, sessionId));
        }
    }

    /** ④ 处理消息异常：ON_ERROR Hook + 错误响应 */
    private void handleProcessingError(InboundMessage message, String sessionId, Exception e) {
        logger.error("Failed to process message: {}", e.getMessage(), e);
        if (hookManager != null) {
            hookManager.runHooks(HookContext.error(sessionId, e.getMessage()));
        }
        sendResponse(message, "发生错误：" + e.getMessage(), null);
    }

    // ==================== 状态处理 ====================

    /**
     * 处理所有状态
     * 返回最终的结果
     */
    private String processStates(TurnContext context) {
        //初始状态
        TurnState state = TurnState.RESTORE;

        //状态流转
        while (!state.isTerminal()) {
            logger.debug("Processing state: {} for session: {}", state, context.getSessionKey());
            try {
                //处理一个返回一个，不断流转，直到结束--state.isTerminal()！
                state = processState(context, state);
            } catch (Exception e) {
                logger.error("Error in state {}: {}", state, e.getMessage(), e);
                context.setError(e.getMessage());
                return "处理失败：" + e.getMessage();
            }
        }

        return context.getFinalContent();
    }

    /**
     * 处理单个状态 — 委托给 State 处理器（State 模式）
     */
    private TurnState processState(TurnContext context, TurnState state) {
        if (state.isTerminal()) return state;
        com.nanobot.core.state.AgentState handler = stateHandlers.get(state);
        if (handler == null) {
            logger.warn("No handler registered for state: {}, skipping", state);
            return TurnState.DONE;
        }
        return handler.execute(context);
    }

    // ==================== State 处理器初始化 ====================

    private void initStateHandlers() {
        stateHandlers.put(TurnState.RESTORE, new com.nanobot.core.state.RestoreState(sessionManager));
        stateHandlers.put(TurnState.COMPACT, new com.nanobot.core.state.CompactState(consolidator));
        stateHandlers.put(TurnState.COMMAND, new com.nanobot.core.state.CommandState(skillManager, ruleManager, sessionManager, consolidator, dream, messageBus, hookManager));
        stateHandlers.put(TurnState.BUILD, new com.nanobot.core.state.BuildState(identityManager, ruleManager, () -> planMode, dream, skillManager != null ? skillManager.getRegistry() : null, config.getWorkspacePath(), hookManager));
        stateHandlers.put(TurnState.RUN, new com.nanobot.core.state.RunState(runner, config, messageBus, hookManager));
        stateHandlers.put(TurnState.SAVE, new com.nanobot.core.state.SaveState(sessionManager));
        stateHandlers.put(TurnState.RESPOND, new com.nanobot.core.state.RespondState(messageBus));
    }

    /**
     * 设置记忆压缩器，同时更新 CompactState 和 CommandState 处理器
     */
    public void setConsolidator(com.nanobot.memory.Consolidator c) {
        this.consolidator = c;
        stateHandlers.put(TurnState.COMPACT, new com.nanobot.core.state.CompactState(c));
        stateHandlers.put(TurnState.COMMAND, new com.nanobot.core.state.CommandState(skillManager, ruleManager, sessionManager, c, dream, messageBus, hookManager));
    }

    /**
     * 长期记忆引擎
     */
    private com.nanobot.memory.Dream dream;

    /**
     * 设置长期记忆引擎，同时更新 SaveState、BuildState、CommandState 处理器
     */
    public void setDream(com.nanobot.memory.Dream d) {
        this.dream = d;
        stateHandlers.put(TurnState.SAVE, new com.nanobot.core.state.SaveState(sessionManager, d));
        stateHandlers.put(TurnState.BUILD, new com.nanobot.core.state.BuildState(identityManager, ruleManager, () -> planMode, d, skillManager != null ? skillManager.getRegistry() : null, config.getWorkspacePath(), hookManager));
        stateHandlers.put(TurnState.COMMAND, new com.nanobot.core.state.CommandState(skillManager, ruleManager, sessionManager, consolidator, d, messageBus, hookManager));
    }

    // ==================== 响应发送 ====================

    /**
     * 发送响应
     */
    private void sendResponse(InboundMessage message, String content, TurnContext context) {
        if (content == null) {
            content = "(无响应)";
        }

        String requestId = null;
        if (context != null && context.getMessage().getMetadata() != null) {
            var o = context.getMessage().getMetadata().get("requestId");
            if (o != null) requestId = o.toString();
        }

        OutboundMessage response = OutboundMessage.builder()
                .channel(message.getChannel())
                .sessionId(message.getSessionId())
                .content(content)
                .requestId(requestId)
                .metadata(new java.util.HashMap<>(java.util.Map.of("_stream_delta", true, "_stream_end", true)))
                .build();

        try {
            // sync /api/chat 轮询匹配
            messageBus.publishOutbound(response);
            // 流式通道（SSE/CLI/WS）也推送一份
            messageBus.publishToOutboundQueue(response);
        } catch (Exception e) {
            logger.error("Failed to send response: {}", e.getMessage(), e);
        }
    }

    // ==================== 状态查询 ====================

    public boolean isRunning() {
        return running.get();
    }

    // ════════════════════════════════════════════════════════
    // Plan Mode — 规划模式
    // ════════════════════════════════════════════════════════

    /**
     * 进入/退出规划模式
     */
    public void setPlanMode(boolean planMode) {
        this.planMode = planMode;
        logger.info("Plan mode: {}", planMode ? "ON (只读分析)" : "OFF (正常模式)");
    }

    public boolean isPlanMode() {
        return planMode;
    }
}
