package com.nanobot.core;

import com.nanobot.bus.*;
import com.nanobot.config.Config;
import com.nanobot.core.hook.AgentHookContext;
import com.nanobot.core.hook.CompositeHook;
import com.nanobot.core.hook.HookLoader;
import com.nanobot.identity.IdentityManager;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import com.nanobot.rules.RuleManager;
import com.nanobot.session.SessionManager;
import com.nanobot.skill.SkillManager;
import com.nanobot.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
     * 钩子管理器
     */
    private CompositeHook hooks;

    // ==================== 构造函数 ====================

    public AgentLoop(MessageBus messageBus, LLMProvider provider, ToolRegistry registry, SessionManager sessionManager, Config config) {

        this(messageBus, provider, registry, sessionManager, config, null, null);
    }

    public AgentLoop(MessageBus messageBus, LLMProvider provider, ToolRegistry registry, SessionManager sessionManager, Config config, RuleManager ruleManager, SkillManager skillManager) {

        this(messageBus, provider, registry, sessionManager, config, ruleManager, skillManager, null);
    }

    public AgentLoop(MessageBus messageBus, LLMProvider provider, ToolRegistry registry, SessionManager sessionManager, Config config, RuleManager ruleManager, SkillManager skillManager, IdentityManager identityManager) {

        this.messageBus = Objects.requireNonNull(messageBus, "messageBus cannot be null");
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.ruleManager = ruleManager;
        this.skillManager = skillManager;
        this.identityManager = identityManager;

        this.runner = new AgentRunner(provider, registry);
        this.consolidator = null;  // 通过 setConsolidator() 注入

        // 初始化 State 处理器 (State 模式)
        initStateHandlers();
        // 加载钩子配置
        loadHooks();
    }

    // ==================== 钩子加载 ====================

    /**
     * 加载钩子配置
     */
    private void loadHooks() {
        try {
            HookLoader loader = new HookLoader(config.getHooks());
            this.hooks = loader.loadHooks();
            logger.info("Loaded {} hooks", hooks.size());
        } catch (Exception e) {
            logger.error("Failed to load hooks: {}", e.getMessage());
            this.hooks = new CompositeHook();
        }
    }

    /**
     * 获取钩子管理器
     */
    public CompositeHook getHooks() {
        return hooks;
    }

    /**
     * 设置钩子管理器
     */
    public void setHooks(CompositeHook hooks) {
        this.hooks = hooks;
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
     * 运行主循环，项目启动时就拉起了！
     * 整个服务就一个循环，不同会话都是这一个循环处理，相当于是一个用户请求的消费者！
     * 它主要用于源源不断得从mq中拉取请求（我们的请求都是发到mq），和大模型交互，返回结果到mq
     * <p>
     * 关键设计：processMessage() 提交到线程池异步执行，主循环只负责消费消息。
     * 这样即使某条消息的 LLM 调用卡住，也不会阻塞后续消息的处理。
     */
    private void runLoop() {
        logger.info("AgentLoop main loop started (async mode)");

        long lastHeartbeat = System.currentTimeMillis();
        int processedCount = 0;
        while (running.get()) {
            try {
                // 尝试获取消息（带超时）--即消费用户发来的请求/消息
                InboundMessage message = messageBus.consumeInbound(1, TimeUnit.SECONDS);

                if (message != null) {
                    processedCount++;
                    final int msgNum = processedCount;
                    logger.info("📨 [MSG-IN] #{}: channel={}, sessionId={}, content='{}' (len={})",
                            msgNum,
                            message.getChannel(),
                            message.getSessionId(),
                            message.getContent() != null ? message.getContent().substring(0, Math.min(80, message.getContent().length())) : "null",
                            message.getContent() != null ? message.getContent().length() : 0);

                    // 异步处理，不阻塞主循环
                    final InboundMessage msg = message;
                    messageExecutor.submit(() -> {
                        try {
                            processMessage(msg);
                        } catch (Exception e) {
                            logger.error("Async message processing failed: {}", e.getMessage(), e);
                        }
                    });
                }

                // 每30秒心跳，证明 loop 还活着
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat > 30_000) {
                    logger.info("💓 AgentLoop heartbeat: running={}, processed={}", running.get(), processedCount);
                    lastHeartbeat = now;
                }

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

    /**
     * 处理单条消息
     */
    private void processMessage(InboundMessage message) {
        String sessionKey = message.getSessionKey();
        logger.info("Processing message for session: {}", sessionKey);

        long startTime = System.currentTimeMillis();

        try {
            // 创建上下文
            Config.AgentDefaults defaults = config.getAgents().getDefaults();

            TurnContext context = TurnContext.create(message, defaults.getModel(), defaults.getMaxTokens(), defaults.getTemperature(), defaults.getMaxToolIterations(),
                    planMode ? registry.getDefinitions(true) : registry.getDefinitions(),
                    defaults.getMaxTurns(), defaults.getMaxCost());

            // 状态机处理--其实就是和大模型交互的一套标准流程，
            // 相当于是一套模板代码，一种状态就是一个步骤。
            String result = processStates(context);

            // 调用 finalizeContent 钩子
            if (hooks != null && !hooks.isEmpty()) {
                AgentHookContext hookContext = AgentHookContext.from(context);
                result = hooks.finalizeContent(hookContext, result);
                context.setFinalContent(result);
            }

            // 发送响应到出站队列，同步聊天接口在轮询拉取该消息
            sendResponse(message, result, context);

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Message processed in {}ms, tokens: {}", duration, context.getTotalTokens());

        } catch (Exception e) {
            logger.error("Failed to process message: {}", e.getMessage(), e);
            sendResponse(message, "发生错误：" + e.getMessage(), null);
        }
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
        stateHandlers.put(TurnState.COMMAND, new com.nanobot.core.state.CommandState(skillManager, ruleManager, sessionManager, consolidator, dream));
        stateHandlers.put(TurnState.BUILD, new com.nanobot.core.state.BuildState(identityManager, ruleManager, () -> planMode, dream, skillManager != null ? skillManager.getRegistry() : null));
        stateHandlers.put(TurnState.RUN, new com.nanobot.core.state.RunState(runner, config, messageBus));
        stateHandlers.put(TurnState.SAVE, new com.nanobot.core.state.SaveState(sessionManager));
        stateHandlers.put(TurnState.RESPOND, new com.nanobot.core.state.RespondState(messageBus));
    }

    /**
     * 设置记忆压缩器，同时更新 CompactState 和 CommandState 处理器
     */
    public void setConsolidator(com.nanobot.memory.Consolidator c) {
        this.consolidator = c;
        stateHandlers.put(TurnState.COMPACT, new com.nanobot.core.state.CompactState(c));
        stateHandlers.put(TurnState.COMMAND, new com.nanobot.core.state.CommandState(skillManager, ruleManager, sessionManager, c, dream));
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
        stateHandlers.put(TurnState.BUILD, new com.nanobot.core.state.BuildState(identityManager, ruleManager, () -> planMode, d, skillManager != null ? skillManager.getRegistry() : null));
        stateHandlers.put(TurnState.COMMAND, new com.nanobot.core.state.CommandState(skillManager, ruleManager, sessionManager, consolidator, d));
    }

    // ==================== 响应发送 ====================

    /**
     * 发送响应
     */
    private void sendResponse(InboundMessage message, String content, TurnContext context) {
        if (content == null) {
            content = "(无响应)";
        }

        OutboundMessage response = OutboundMessage.builder().channel(message.getChannel()).sessionId(message.getSessionId()).content(content).build();

        try {
            messageBus.publishOutbound(response);
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
