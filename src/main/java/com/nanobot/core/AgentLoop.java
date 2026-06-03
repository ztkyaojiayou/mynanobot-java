package com.nanobot.core;

import com.nanobot.bus.*;
import com.nanobot.config.Config;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import com.nanobot.session.SessionManager;
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
    
    // ==================== 运行时 ====================
    
    /** Agent Runner */
    private final AgentRunner runner;
    
    /** 运行状态 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    /** 执行线程 */
    private ExecutorService executor;
    
    /** 进度回调 */
    private Consumer<OutboundMessage> progressCallback;
    
    // ==================== 构造函数 ====================
    
    public AgentLoop(
            MessageBus messageBus,
            LLMProvider provider,
            ToolRegistry registry,
            SessionManager sessionManager,
            Config config) {
        
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus cannot be null");
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        
        this.runner = new AgentRunner(provider, registry);
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
                context.getMessages().add(msg);
            }
            logger.debug("Restored {} messages for session: {}", 
                        history.get().size(), sessionKey);
        }
        
        // 添加用户消息
        String content = context.getMessage().getContent();
        if (content != null && !content.isBlank()) {
            context.addUserMessage(content);
        }
        
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
            default -> TurnState.BUILD;
        };
    }
    
    /**
     * BUILD: 构建上下文
     */
    private TurnState doBuild(TurnContext context) {
        // TODO: 添加系统提示、记忆内容等
        // 目前只使用用户消息
        return TurnState.RUN;
    }
    
    /**
     * RUN: 运行 LLM
     */
    private TurnState doRun(TurnContext context) {
        // 创建流式回调
        Consumer<String> onDelta = null;
        if (config.getChannels().isSendProgress()) {
            onDelta = delta -> {
                OutboundMessage progress = OutboundMessage.progress(
                    context.getMessage().getChannel(),
                    context.getMessage().getChatId(),
                    delta
                );
                publishProgress(progress);
            };
        }
        
        // 执行 Runner
        try {
            String result = runner.run(context, context.getMessages(), onDelta).join();
            context.setFinalContent(result);
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
        // 保存会话历史
        sessionManager.saveHistory(context.getSessionKey(), context.getMessages());
        return TurnState.RESPOND;
    }
    
    /**
     * RESPOND: 发送响应
     */
    private TurnState doRespond(TurnContext context) {
        // 响应已在 doRun 中通过 progress 发送
        // 这里可以发送最终确认
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
}
