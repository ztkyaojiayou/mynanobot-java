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
    private Consumer<OutboundMessage> progressCallback;

    /**
     * 流式响应回调列表（支持 SSE + WebSocket 同时工作）
     * 使用 CopyOnWriteArrayList 保证线程安全和迭代稳定性
     */
    private final java.util.List<StreamResponseCallback> streamResponseCallbacks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 钩子管理器
     */
    private CompositeHook hooks;

    /**
     * 流式响应回调接口
     */
    @FunctionalInterface
    public interface StreamResponseCallback {
        void onStreamData(String sessionId, String requestId, String content);

        default void onStreamComplete(String sessionId, String requestId) {
        }
    }

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

    /** 消息处理线程池（避免 LLM 调用阻塞主循环） */
    private final java.util.concurrent.ExecutorService messageExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "AgentLoop-worker");
                t.setDaemon(true);
                return t;
            });

    /**
     * 运行主循环
     *
     * 关键设计：processMessage() 提交到线程池异步执行，主循环只负责消费消息。
     * 这样即使某条消息的 LLM 调用卡住，也不会阻塞后续消息的处理。
     */
    private void runLoop() {
        logger.info("AgentLoop main loop started (async mode)");

        long lastHeartbeat = System.currentTimeMillis();
        int processedCount = 0;
        while (running.get()) {
            try {
                // 尝试获取消息（带超时）
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

            TurnContext context = TurnContext.create(message, defaults.getModel(), defaults.getMaxTokens(), defaults.getTemperature(), defaults.getMaxToolIterations(), registry.getDefinitions());

            // 状态机处理
            String result = processStates(context);

            // 调用 finalizeContent 钩子
            if (hooks != null && !hooks.isEmpty()) {
                AgentHookContext hookContext = AgentHookContext.from(context);
                result = hooks.finalizeContent(hookContext, result);
                context.setFinalContent(result);
            }

            // 发送响应
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
     * 处理单个状态
     */
    private TurnState processState(TurnContext context, TurnState state) {
        return switch (state) {
            case RESTORE -> doRestore(context);
            case COMPACT -> doCompact(context);
            case COMMAND -> doCommand(context);
            case BUILD -> doBuild(context);
            //执行大模型查询交互！！！
            case RUN -> doRun(context);
            case SAVE -> doSave(context);
            case RESPOND -> doRespond(context);
            case DONE -> TurnState.DONE;
        };
    }

    // ==================== 各状态实现 ====================

    /**
     * RESTORE: 恢复会话
     */
    private TurnState doRestore(TurnContext context) {
        String sessionKey = context.getSessionKey();

        // 加载会话历史
        Optional<List<Map<String, Object>>> history = sessionManager.loadHistory(sessionKey);

        if (history.isPresent()) {
            for (Map<String, Object> msg : history.get()) {
                context.addMessage(msg);
            }
            logger.info("Restored {} messages for session: {}", history.get().size(), sessionKey);
        } else {
            logger.debug("No history found for session: {}", sessionKey);
        }

        // 添加用户消息
        String content = context.getMessage().getContent();
        if (content != null && !content.isBlank()) {
            context.addUserMessage(content);
            logger.debug("Added user message: {} chars", content.length());
        }

        logger.info("Total messages in context: {}", context.getMessages().size());

        return TurnState.COMPACT;
    }

    /**
     * COMPACT: 压缩历史（简化实现）
     */
    /**
     * 设置记忆压缩器（由 NanobotConfig 注入）。
     */
    public void setConsolidator(com.nanobot.memory.Consolidator c) { this.consolidator = c; }

    /**
     * COMPACT: 对话历史压缩。
     *
     * 当消息的估算 token 数超过 contextWindowTokens 的 90% 时触发，
     * 调用 LLM 将旧的 assistant + tool 消息总结为一条 system 消息，
     * 释放 token 预算，防止上下文窗口溢出。
     *
     * 参考: nanobot (Python) 的 consolidator.py
     */
    private TurnState doCompact(TurnContext context) {
        // 未注入压缩器则跳过（向后兼容，不会报错）
        if (consolidator == null) return TurnState.BUILD;

        List<Map<String, Object>> messages = context.getMessages();
        if (!consolidator.needsConsolidation(messages)) return TurnState.BUILD;

        logger.info("Compacting history: {} messages, ~{} tokens",
                messages.size(), consolidator.getCurrentUsage(messages));
        try {
            // consolidate() 内部调用 LLM 生成总结，是异步操作，这里阻塞等待
            List<Map<String, Object>> compacted = consolidator.consolidate(messages).join();
            context.getMessages().clear();
            context.getMessages().addAll(compacted);
            logger.info("Compacted to {} messages", compacted.size());
        } catch (Exception e) {
            // 压缩失败不影响对话，继续使用原始消息
            logger.warn("Compaction failed, continuing with original: {}", e.getMessage());
        }
        return TurnState.BUILD;
    }

    /**
        // 1. 计算当前 token 数
        // 2. 超过预算时，压缩旧消息
        // 3. 调用 LLM 生成摘要
        return TurnState.COMMAND;
    }

    /**
     * COMMAND: 命令分发
     */
    private TurnState doCommand(TurnContext context) {
        String content = context.getMessage().getContent();

        if (content == null || !content.startsWith("/")) {
            return TurnState.BUILD;
        }

        // 尝试解析技能调用（Skills）
        if (skillManager != null) {
            SkillManager.SkillCall skillCall = skillManager.parseSlashCommand(content);
            if (skillCall != null) {
                // 执行技能
                String result = skillManager.executeSkill(skillCall.skillName(), java.util.Map.of(), skillCall.args());
                context.setFinalContent(result);
                return TurnState.DONE;
            }
        }

        // 简单命令处理
        String command = content.split("\\s")[0].toLowerCase();

        return switch (command) {
            case "/stop" -> {
                context.cancel();
                context.setFinalContent("已停止处理。");
                yield TurnState.DONE;
            }
            case "/clear" -> {
                // 清除会话历史
                sessionManager.clearSession(context.getSessionKey());
                context.setFinalContent("会话已清除。");
                yield TurnState.DONE;
            }
            case "/skills" -> {
                // 显示可用技能列表
                String skillsHelp = skillManager != null ? skillManager.getRegistry().getHelp() : "技能系统未启用。";
                context.setFinalContent(skillsHelp);
                yield TurnState.DONE;
            }
            case "/rules" -> {
                // 显示规则摘要
                String rulesSummary = ruleManager != null ? ruleManager.getRulesSummary() : "规则系统未启用。";
                context.setFinalContent(rulesSummary);
                yield TurnState.DONE;
            }
            default -> TurnState.BUILD;
        };
    }

    /**
     * BUILD: 构建上下文
     */
    private TurnState doBuild(TurnContext context) {
        // 获取当前日期（实时）
        java.time.LocalDate today = java.time.LocalDate.now();
        String currentDate = today.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日"));

        // 检查是否启用联网搜索
        boolean useSearch = false;
        if (context.getMessage() != null && context.getMessage().getMetadata() != null) {
            Object useSearchObj = context.getMessage().getMetadata().get("useSearch");
            if (useSearchObj instanceof Boolean) {
                useSearch = (Boolean) useSearchObj;
            } else if (useSearchObj instanceof String) {
                useSearch = Boolean.parseBoolean((String) useSearchObj);
            }
        }

        logger.info("Building context: useSearch={}", useSearch);

        StringBuilder systemPrompt = new StringBuilder();

        // ============ 身份信息注入（SOUL, IDENTITY, USER） ============
        if (identityManager != null) {
            systemPrompt.append(identityManager.getSystemPrompt(currentDate));
        } else {
            // 默认提示词
            systemPrompt.append("""
                    你是 my-nanobot，一个基于 Java 实现的轻量级 AI Agent 框架驱动的智能助手。

                    ⚠️ 重要：你不是 Claude、DeepSeek 或任何其他 AI 产品。你的名字是 my-nanobot。
                    当用户问"你是谁"时，必须回答你是 my-nanobot，不要自称 Claude 或任何其他名字。

                    你的任务是帮助用户解决问题，回答问题，执行任务。

                    【当前真实日期 — 覆盖你的训练数据】
                    今天是""" + currentDate + "，这是真实日期。训练数据中日期已过时。\n"
                    + "涉及日期/星期/时间的回答必须以这个日期为准。\n\n");
        }

        // 根据 useSearch 参数决定是否启用联网搜索工具
        if (!useSearch) {
            systemPrompt.append("""
                    当前未启用联网搜索，不要使用 web_search 和 web_fetch 工具。
                    如果用户的问题需要最新信息，告知用户可以使用"联网查"功能。

                    """);
        }

        // ============ NANOBOT.md 项目记忆加载 ============
        // 如果项目根目录存在 NANOBOT.md（通过 /init 生成），注入系统提示词
        try {
            java.nio.file.Path nanobotMd = java.nio.file.Paths.get("NANOBOT.md");
            if (java.nio.file.Files.exists(nanobotMd)) {
                String content = java.nio.file.Files.readString(nanobotMd);
                systemPrompt.append("\n\n【项目上下文 — 来自 NANOBOT.md】\n")
                        .append(content).append("\n");
                logger.debug("Loaded NANOBOT.md ({} chars)", content.length());
            }
        } catch (Exception e) {
            logger.debug("NANOBOT.md not found or unreadable: {}", e.getMessage());
        }

        // ============ Rules 自动注入 ============
        // 如果有规则管理器，将规则合并到系统提示词中
        if (ruleManager != null) {
            String rulesPrompt = ruleManager.getRulesPrompt();
            if (rulesPrompt != null && !rulesPrompt.isBlank()) {
                systemPrompt.append("\n\n").append(rulesPrompt);
            }
        }

        // 在消息列表开头添加系统提示
        List<Map<String, Object>> messages = context.getMessages();
        if (!messages.isEmpty()) {
            Map<String, Object> firstMsg = messages.get(0);
            if (!"system".equals(firstMsg.get("role"))) {
                messages.add(0, Map.of("role", "system", "content", systemPrompt.toString()));
            }
        } else {
            messages.add(Map.of("role", "system", "content", systemPrompt.toString()));
        }

        return TurnState.RUN;
    }

    /**
     * RUN: 运行 LLM
     */
    private TurnState doRun(TurnContext context) {
        // 获取连接 ID（用于 WebSocket 流式传输）
        final String connectionId = context.getMessage().getConnectionId();

        // 获取 requestId（用于 HTTP SSE 流式传输）
        final String requestId;
        if (context.getMessage().getMetadata() != null) {
            Object requestIdObj = context.getMessage().getMetadata().get("requestId");
            if (requestIdObj instanceof String) {
                requestId = (String) requestIdObj;
            } else {
                requestId = null;
            }
        } else {
            requestId = null;
        }

        // 检查是否启用流式模式
        final boolean streamMode;
        if (context.getMessage().getMetadata() != null) {
            Object streamModeObj = context.getMessage().getMetadata().get("streamMode");
            logger.debug("Stream mode object from metadata: type={}, value={}", streamModeObj != null ? streamModeObj.getClass().getName() : "null", streamModeObj);
            if (streamModeObj instanceof Boolean) {
                streamMode = (Boolean) streamModeObj;
            } else if (streamModeObj instanceof String) {
                streamMode = Boolean.parseBoolean((String) streamModeObj);
            } else {
                streamMode = false;
            }
        } else {
            streamMode = false;
        }
        logger.info("🚀 [DO-RUN] streamMode={}, requestId={}, callbacks={}, msgContent='{}'",
                streamMode, requestId, streamResponseCallbacks.size(),
                context.getMessage().getContent() != null
                    ? context.getMessage().getContent().substring(0, Math.min(60, context.getMessage().getContent().length()))
                    : "null");

        // 创建流式回调（仅在流式模式启用时）
        Consumer<String> onDelta = null;
        final String sessionId = context.getMessage().getSessionId();

        // 流式条件：streamMode=true 且（进度启用 或 已设置回调）
        // 捕获当前回调列表的快照，防止迭代期间列表变化
        final java.util.List<StreamResponseCallback> activeCallbacks =
                streamMode ? new java.util.ArrayList<>(streamResponseCallbacks) : java.util.List.of();
        boolean hasStreamCallback = !activeCallbacks.isEmpty() && requestId != null;

        logger.info("doRun stream setup: mode={}, sendProgress={}, callbacks={}, requestId={}",
                streamMode, config.getChannels().isSendProgress(), activeCallbacks.size(), requestId);

        if (streamMode && (config.getChannels().isSendProgress() || hasStreamCallback)) {
            onDelta = delta -> {
                OutboundMessage.Builder builder = OutboundMessage.builder()
                        .channel(context.getMessage().getChannel())
                        .sessionId(sessionId)
                        .content(delta)
                        .addMetadata("_stream_delta", true)
                        .addMetadata("_progress", true);

                if (connectionId != null) {
                    builder.connectionId(connectionId);
                }

                publishProgress(builder.build());

                // 使用捕获的快照迭代，防止 ConcurrentModificationException
                for (StreamResponseCallback cb : activeCallbacks) {
                    try {
                        cb.onStreamData(sessionId, requestId, delta);
                    } catch (Exception e) {
                        logger.warn("Stream callback onStreamData failed: {}", e.getMessage());
                    }
                }
            };
            logger.info("onDelta created: callbacks={}", activeCallbacks.size());
        } else {
            logger.info("Skipping onDelta: mode={}, progress={}, hasCallback={}",
                    streamMode, config.getChannels().isSendProgress(), hasStreamCallback);
        }

        // 执行 Runner
        try {
            logger.info("🤖 [LLM-CALL] Starting LLM for session={}, requestId={}, msgs={}",
                    sessionId, requestId, context.getMessages().size());
            long llmStart = System.currentTimeMillis();
            String result = runner.run(context, context.getMessages(), onDelta).join();
            long llmDuration = System.currentTimeMillis() - llmStart;
            logger.info("✅ [LLM-DONE] session={}, requestId={}, duration={}ms, resultLen={}",
                    sessionId, requestId, llmDuration, result != null ? result.length() : 0);
            context.setFinalContent(result);

            // 发送流式结束标记（仅在流式模式启用时）
            if (streamMode && onDelta != null) {
                // WebSocket 结束标记
                if (connectionId != null) {
                    OutboundMessage streamEnd = OutboundMessage.builder().channel(context.getMessage().getChannel()).sessionId(sessionId).content("").connectionId(connectionId).addMetadata("_stream_end", true).build();
                    publishProgress(streamEnd);
                }

                // 通知所有回调流式结束（使用快照防止迭代时被修改）
                for (StreamResponseCallback cb : activeCallbacks) {
                    try {
                        cb.onStreamComplete(sessionId, requestId);
                    } catch (Exception e) {
                        logger.warn("Stream callback onStreamComplete failed: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Runner failed: {}", e.getMessage(), e);
            context.setError(e.getMessage());
            context.setFinalContent("执行失败：" + e.getMessage());
        }

        return TurnState.SAVE;
    }

    /**
     * SAVE: 保存状态
     */
    private TurnState doSave(TurnContext context) {
        // 添加助手响应到消息历史
        String responseContent = context.getFinalContent();
        if (responseContent != null && !responseContent.isBlank()) {
            context.addAssistantMessage(responseContent, null);
            logger.debug("Added assistant response to history: {}", responseContent.length());
        }

        // 保存会话历史
        sessionManager.saveHistory(context.getSessionKey(), context.getMessages());
        return TurnState.RESPOND;
    }

    /**
     * RESPOND: 发送响应
     */
    private TurnState doRespond(TurnContext context) {
        String content = context.getFinalContent();
        if (content == null) {
            content = "(无响应)";
        }

        // 获取 requestId 用于精确匹配
        String requestId = null;
        if (context.getMessage().getMetadata() != null) {
            Object requestIdObj = context.getMessage().getMetadata().get("requestId");
            if (requestIdObj != null) {
                requestId = requestIdObj.toString();
            }
        }

        // 发送最终响应到消息总线
        OutboundMessage response = OutboundMessage.builder().channel(context.getMessage().getChannel()).sessionId(context.getMessage().getSessionId()).content(content).requestId(requestId).build();

        try {
            messageBus.publishOutbound(response);
            logger.info("Response sent for sessionId: {}, requestId: {}, content length: {}", context.getMessage().getSessionId(), requestId, content.length());
        } catch (Exception e) {
            logger.error("Failed to send response: {}", e.getMessage(), e);
        }

        return TurnState.DONE;
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

    /**
     * 发布进度
     */
    private void publishProgress(OutboundMessage progress) {
        messageBus.offerOutbound(progress);
    }

    // ==================== 状态查询 ====================

    public boolean isRunning() {
        return running.get();
    }

    public void setProgressCallback(Consumer<OutboundMessage> callback) {
        this.progressCallback = callback;
    }

    /**
     * 设置流式响应回调
     */
    /**
     * 添加流式响应回调（支持多个订阅者：SSE + WebSocket）
     */
    public void addStreamResponseCallback(StreamResponseCallback callback) {
        if (callback != null && !streamResponseCallbacks.contains(callback)) {
            streamResponseCallbacks.add(callback);
            logger.debug("Added stream callback, total: {}", streamResponseCallbacks.size());
        }
    }

    /**
     * 移除流式响应回调
     */
    public void removeStreamResponseCallback(StreamResponseCallback callback) {
        streamResponseCallbacks.remove(callback);
        logger.debug("Removed stream callback, remaining: {}", streamResponseCallbacks.size());
    }

    /**
     * 获取当前回调数量（诊断用）
     */
    public int getStreamCallbackCount() {
        return streamResponseCallbacks.size();
    }

    /**
     * @deprecated 使用 addStreamResponseCallback 替代
     */
    @Deprecated
    public void setStreamResponseCallback(StreamResponseCallback callback) {
        streamResponseCallbacks.clear();
        if (callback != null) {
            streamResponseCallbacks.add(callback);
        }
    }
}
