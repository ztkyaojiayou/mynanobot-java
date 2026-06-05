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
 * 
 * 本类是整个 Agent 系统的核心，负责：
 * 1. 从消息队列消费消息
 * 2. 管理状态机转换
 * 3. 协调各个组件完成消息处理
 * 
 * **状态机流程**：
 * 
 * ```
 * ┌────────────┐
 * │   START    │
 * └─────┬──────┘
 *       ▼
 * ┌────────────┐     ┌────────────┐
 * │  RESTORE   │────▶│  COMPACT   │
 * └────────────┘     └─────┬──────┘
 *                          ▼
 *                    ┌────────────┐
 *                    │  COMMAND   │◀───┐
 *                    └─────┬──────┘    │
 *                          ▼          │
 *                    ┌────────────┐    │
 *                    │   BUILD    │────┘
 *                    └─────┬──────┘
 *                          ▼
 *                    ┌────────────┐
 *                    │    RUN     │
 *                    └─────┬──────┘
 *                          ▼
 *                    ┌────────────┐
 *                    │   SAVE    │
 *                    └─────┬──────┘
 *                          ▼
 *                    ┌────────────┐
 *                    │  RESPOND   │
 *                    └─────┬──────┘
 *                          ▼
 *                    ┌────────────┐
 *                    │    DONE    │
 *                    └────────────┘
 * ```
 * 
 * **设计思想**：
 * 
 * 1. **状态驱动**：
 *    - 每个状态对应一个处理阶段
 *    - 状态之间通过事件转换
 *    - 便于理解和扩展
 * 
 * 2. **组件协作**：
 *    - SessionManager: 会话管理
 *    - AgentRunner: LLM 执行
 *    - ToolRegistry: 工具调用
 *    - MessageBus: 消息传递
 * 
 * 3. **异步处理**：
 *    - 消息异步消费
 *    - LLM 调用异步执行
 *    - 响应异步发送
 * 
 * **使用示例**：
 * 
 * ```java
 * // 1. 创建组件
 * Config config = ConfigLoader.load();
 * ToolRegistry registry = new ToolRegistry();
 * LLMProvider provider = new OpenAIProvider(apiKey, model);
 * SessionManager sessionManager = new SessionManager(config);
 * MessageBus bus = new MessageBus();
 * 
 * // 2. 创建 AgentLoop
 * AgentLoop loop = new AgentLoop(bus, provider, registry, sessionManager, config);
 * 
 * // 3. 启动
 * loop.start();
 * 
 * // 4. 发布消息（由通道适配器执行）
 * bus.publishInbound(message);
 * ```
 */
public class AgentLoop {
    
    // ==================== 日志 ====================
    
    private static final Logger logger = LoggerFactory.getLogger(AgentLoop.class);
    
    // ==================== 组件 ====================
    
    /** 消息总线 */
    private final MessageBus messageBus;
    
    /** LLM 提供商 */
    private final LLMProvider provider;
    
    /** 工具注册中心 */
    private final ToolRegistry registry;
    
    /** 会话管理器 */
    private final SessionManager sessionManager;
    
    /** 配置 */
    private final Config config;
    
    /** 规则管理器 */
    private RuleManager ruleManager;
    
    /** 技能管理器 */
    private SkillManager skillManager;
    
    /** 身份管理器（SOUL, IDENTITY, USER） */
    private IdentityManager identityManager;
    
    // ==================== 运行时 ====================
    
    /** Agent Runner */
    private final AgentRunner runner;
    
    /** 运行状态 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    /** 执行线程 */
    private ExecutorService executor;
    
    /** 进度回调 */
    private Consumer<OutboundMessage> progressCallback;
    
    /** 流式响应回调 */
    private StreamResponseCallback streamResponseCallback;
    
    /** 钩子管理器 */
    private CompositeHook hooks;
    
    /**
     * 流式响应回调接口
     */
    @FunctionalInterface
    public interface StreamResponseCallback {
        void onStreamData(String sessionId, String requestId, String content);
        default void onStreamComplete(String sessionId, String requestId) {}
    }
    
    // ==================== 构造函数 ====================
    
    public AgentLoop(
            MessageBus messageBus,
            LLMProvider provider,
            ToolRegistry registry,
            SessionManager sessionManager,
            Config config) {
        
        this(messageBus, provider, registry, sessionManager, config, null, null);
    }
    
    public AgentLoop(
            MessageBus messageBus,
            LLMProvider provider,
            ToolRegistry registry,
            SessionManager sessionManager,
            Config config,
            RuleManager ruleManager,
            SkillManager skillManager) {
        
        this(messageBus, provider, registry, sessionManager, config, ruleManager, skillManager, null);
    }
    
    public AgentLoop(
            MessageBus messageBus,
            LLMProvider provider,
            ToolRegistry registry,
            SessionManager sessionManager,
            Config config,
            RuleManager ruleManager,
            SkillManager skillManager,
            IdentityManager identityManager) {
        
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus cannot be null");
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.ruleManager = ruleManager;
        this.skillManager = skillManager;
        this.identityManager = identityManager;
        
        this.runner = new AgentRunner(provider, registry);
        
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
        
        messageBus.stop();
        runner.shutdown();
        
        logger.info("AgentLoop stopped");
    }
    
    // ==================== 主循环 ====================
    
    /**
     * 运行主循环
     */
    private void runLoop() {
        logger.info("AgentLoop main loop started");
        
        while (running.get()) {
            try {
                // 尝试获取消息（带超时）
                InboundMessage message = messageBus.consumeInbound(1, TimeUnit.SECONDS);
                
                if (message != null) {
                    processMessage(message);
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
            
            TurnContext context = TurnContext.create(
                message,
                defaults.getModel(),
                defaults.getMaxTokens(),
                defaults.getTemperature(),
                defaults.getMaxToolIterations(),
                registry.getDefinitions()
            );
            
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
            logger.info("Message processed in {}ms, tokens: {}", 
                       duration, context.getTotalTokens());
            
        } catch (Exception e) {
            logger.error("Failed to process message: {}", e.getMessage(), e);
            sendResponse(message, "发生错误：" + e.getMessage(), null);
        }
    }
    
    // ==================== 状态处理 ====================
    
    /**
     * 处理所有状态
     */
    private String processStates(TurnContext context) {
        TurnState state = TurnState.RESTORE;
        
        while (!state.isTerminal()) {
            logger.debug("Processing state: {} for session: {}", 
                        state, context.getSessionKey());
            
            try {
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
        Optional<List<Map<String, Object>>> history = 
            sessionManager.loadHistory(sessionKey);
        
        if (history.isPresent()) {
            for (Map<String, Object> msg : history.get()) {
                context.addMessage(msg);
            }
            logger.info("Restored {} messages for session: {}", 
                        history.get().size(), sessionKey);
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
    private TurnState doCompact(TurnContext context) {
        // TODO: 实现历史压缩逻辑
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
                String result = skillManager.executeSkill(
                    skillCall.skillName(), 
                    java.util.Map.of(), 
                    skillCall.args()
                );
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
                String skillsHelp = skillManager != null ? 
                    skillManager.getRegistry().getHelp() : "技能系统未启用。";
                context.setFinalContent(skillsHelp);
                yield TurnState.DONE;
            }
            case "/rules" -> {
                // 显示规则摘要
                String rulesSummary = ruleManager != null ? 
                    ruleManager.getRulesSummary() : "规则系统未启用。";
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
                你是 my-nanobot，一个强大的 AI 助手。
                
                你的任务是帮助用户解决问题，回答问题，执行任务。
                
                当前日期：""" + currentDate + """
                
                """);
        }
        
        // 根据 useSearch 参数决定是否启用工具调用
        if (useSearch) {
            systemPrompt.append("""
                你可以调用工具来完成各种任务，包括网页搜索。
                如果用户的问题需要最新信息，请使用 web_search 工具进行搜索。
                
                """);
        } else {
            systemPrompt.append("""
                请直接回答用户的问题，不要调用任何工具。
                如果用户的问题需要最新信息，你可以告知用户可以使用"联网查"功能获取最新信息。
                
                """);
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
        logger.info("doRun: streamMode={}, requestId={}", streamMode, requestId);
        
        // 创建流式回调（仅在流式模式启用时）
        Consumer<String> onDelta = null;
        final String sessionId = context.getMessage().getChatId();
        
        if (streamMode && config.getChannels().isSendProgress()) {
            onDelta = delta -> {
                OutboundMessage.Builder builder = OutboundMessage.builder()
                    .channel(context.getMessage().getChannel())
                    .chatId(sessionId)
                    .content(delta)
                    .addMetadata("_stream_delta", true)
                    .addMetadata("_progress", true);
                
                if (connectionId != null) {
                    builder.connectionId(connectionId);
                }
                
                publishProgress(builder.build());
                
                if (streamResponseCallback != null && requestId != null) {
                    streamResponseCallback.onStreamData(sessionId, requestId, delta);
                }
            };
        }
        
        // 执行 Runner
        try {
            String result = runner.run(context, context.getMessages(), onDelta).join();
            context.setFinalContent(result);
            
            // 发送流式结束标记（仅在流式模式启用时）
            if (streamMode && onDelta != null) {
                // WebSocket 结束标记
                if (connectionId != null) {
                    OutboundMessage streamEnd = OutboundMessage.builder()
                        .channel(context.getMessage().getChannel())
                        .chatId(sessionId)
                        .content("")
                        .connectionId(connectionId)
                        .addMetadata("_stream_end", true)
                        .build();
                    publishProgress(streamEnd);
                }
                
                // SSE 结束标记
                if (streamResponseCallback != null && requestId != null) {
                    streamResponseCallback.onStreamComplete(sessionId, requestId);
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
        OutboundMessage response = OutboundMessage.builder()
            .channel(context.getMessage().getChannel())
            .chatId(context.getMessage().getChatId())
            .content(content)
            .requestId(requestId)
            .build();
        
        try {
            messageBus.publishOutbound(response);
            logger.info("Response sent for chatId: {}, requestId: {}, content length: {}", 
                       context.getMessage().getChatId(), requestId, content.length());
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
        
        OutboundMessage response = OutboundMessage.builder()
            .channel(message.getChannel())
            .chatId(message.getChatId())
            .content(content)
            .build();
        
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
        try {
            messageBus.publishOutbound(progress);
        } catch (Exception e) {
            logger.warn("Failed to publish progress: {}", e.getMessage());
        }
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
    public void setStreamResponseCallback(StreamResponseCallback callback) {
        this.streamResponseCallback = callback;
    }
}
