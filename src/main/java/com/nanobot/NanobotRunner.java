package com.nanobot;

import com.nanobot.bus.MessageBus;
import com.nanobot.config.Config;
import com.nanobot.core.AgentLoop;
import com.nanobot.mcp.MCPManager;
import com.nanobot.providers.LLMProvider;
import com.nanobot.identity.IdentityManager;
import com.nanobot.rules.RuleManager;
import com.nanobot.session.SessionManager;
import com.nanobot.skill.SkillManager;
import com.nanobot.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Nanobot 核心启动器 — 服务定位 + 收尾工作。
 *
 * <h2>定位</h2>
 * 本类<b>不负责创建组件</b>（组件的创建由 {@code NanobotConfig} 的 {@code @Bean} 统一管理）。
 * 它只做两件事：
 * <ol>
 *   <li><b>服务定位</b>：通过 {@code @Autowired} 收集各组件引用，对外暴露 static getter，
 *       供 Controller / CLI / WebSocket 等非 Spring 管理的类使用。</li>
 *   <li><b>收尾</b>：Spring 容器就绪后验证注入完整性 + 注册 JVM 关闭钩子。</li>
 * </ol>
 *
 * <h2>为什么用 static 字段 + @Autowired setter？</h2>
 * ChatController、CliChannel、NanobotWebSocketEndpoint 等类需要访问核心组件，
 * 但它们不全是 Spring Bean，无法用 {@code @Autowired}。
 * static getter 本质是一个"服务定位器"（Service Locator），让整个应用都能访问。
 *
 * <h2>组件全景图</h2>
 * <pre>
 *                          ┌─────────────┐
 *                          │   Config    │  ← 一切配置的源头(config.yaml + secret.yaml)
 *                          └──────┬──────┘
 *                                 │
 *            ┌────────────────────┼────────────────────┐
 *            ▼                    ▼                    ▼
 *    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
 *    │IdentityManager│    │  RuleManager │    │ SkillManager │  ← "上下文注入三件套"
 *    │ (SOUL/ID/USER)│    │(编码/安全规范)│    │ (可复用技能)  │
 *    └──────────────┘    └──────────────┘    └──────────────┘
 *                                 │
 *                    ┌────────────▼────────────┐
 *                    │    ProviderFactory      │  ← 策略工厂: deepseek*→DeepSeek, gpt-*→OpenAI
 *                    │         ↓               │
 *                    │    LLMProvider          │  ← 唯一与 LLM API 通信的组件
 *                    └────────────┬────────────┘
 *                                 │
 *               ┌─────────────────┼─────────────────┐
 *               ▼                 ▼                  ▼
 *       ┌──────────┐     ┌──────────────┐   ┌──────────────┐
 *       │  Dream   │     │ AgentLoop    │   │ToolRegistry  │
 *       │(长期记忆) │◄───│(状态机引擎)   │──▶│(17+内置工具) │
 *       └──────────┘     └──────┬───────┘   └──────┬───────┘
 *                              │                  │
 *                      ┌───────▼───────┐  ┌───────▼───────┐
 *                      │  MessageBus  │  │  MCPManager   │
 *                      │(异步消息中枢) │  │(外部工具接入)  │
 *                      └──────────────┘  └───────────────┘
 * </pre>
 *
 * @see com.nanobot.v2.NanobotConfig  Spring Bean 定义（所有组件的创建入口）
 * @see com.nanobot.core.AgentLoop   状态机引擎
 */
@Component
public class NanobotRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(NanobotRunner.class);

    // ═══════════════════════════════════════════════════════════════
    // 核心组件（static 持有 → 通过 getter 暴露给整个应用）
    // ═══════════════════════════════════════════════════════════════

    private static MessageBus messageBus;
    private static ToolRegistry toolRegistry;
    private static SessionManager sessionManager;
    private static AgentLoop agentLoop;
    private static MCPManager mcpManager;
    private static Config config;
    private static RuleManager ruleManager;
    private static SkillManager skillManager;
    private static IdentityManager identityManager;
    private static LLMProvider provider;

    // ═══════════════════════════════════════════════════════════════
    // Spring Bean 注入（全部由 NanobotConfig @Bean 创建，这里只收集引用）
    // ═══════════════════════════════════════════════════════════════

    @Autowired
    private void setMessageBus(MessageBus messageBus) {
        NanobotRunner.messageBus = messageBus;
    }

    @Autowired
    private void setAgentLoop(AgentLoop agentLoop) {
        NanobotRunner.agentLoop = agentLoop;
    }

    @Autowired
    private void setToolRegistry(ToolRegistry toolRegistry) {
        NanobotRunner.toolRegistry = toolRegistry;
    }

    @Autowired
    private void setSessionManager(SessionManager sessionManager) {
        NanobotRunner.sessionManager = sessionManager;
    }

    @Autowired
    private void setConfig(Config config) {
        NanobotRunner.config = config;
    }

    @Autowired
    private void setIdentityManager(IdentityManager identityManager) {
        NanobotRunner.identityManager = identityManager;
    }

    @Autowired
    private void setRuleManager(RuleManager ruleManager) {
        NanobotRunner.ruleManager = ruleManager;
    }

    @Autowired
    private void setSkillManager(SkillManager skillManager) {
        NanobotRunner.skillManager = skillManager;
    }

    @Autowired
    private void setLlmProvider(LLMProvider provider) {
        NanobotRunner.provider = provider;
    }

    @Autowired
    private void setMcpManager(MCPManager mcpManager) {
        NanobotRunner.mcpManager = mcpManager;
    }

    // ═══════════════════════════════════════════════════════════════
    // 启动收尾（Spring 容器就绪后自动调用）
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void run(ApplicationArguments args) {
        // 所有 Bean 注入完成，验证 + 启动 + 注册收尾钩子
        verifySpringBeans();

        // 启动 AgentLoop 的 daemon 线程，开始消费 MessageBus 中的消息
        agentLoop.start();

       // 注册 JVM 关闭钩子 — 按正确顺序关闭组件，避免数据丢失.
        registerShutdownHook();

        logger.info("Nanobot ready. Workspace: {}, Model: {}",
                config.getWorkspacePath(), config.getAgents().getDefaults().getModel());
    }

    /**
     * 验证 Spring 注入的关键 Bean 是否到位.
     */
    private void verifySpringBeans() {
        logger.info("AgentLoop injected:          {}", agentLoop != null ? "OK" : "MISSING");
        logger.info("MessageBus injected:        {}", messageBus != null ? "OK" : "MISSING");
        logger.info("ToolRegistry injected:      {}", toolRegistry != null ? "OK" : "MISSING");
        logger.info("Config injected:            {}", config != null ? "OK" : "MISSING");
        logger.info("LLMProvider injected:       {}", provider != null ? "OK" : "MISSING");
    }

    /**
     * 注册 JVM 关闭钩子 — 按正确顺序关闭组件，避免数据丢失.
     *
     * <h3>关闭顺序</h3>
     * <ol>
     *   <li>MCPManager — 先断开外部连接</li>
     *   <li>AgentLoop — 停止接收新消息</li>
     *   <li>MessageBus — 排空队列，等待进行中的消息处理完</li>
     *   <li>ToolRegistry — 释放工具资源</li>
     * </ol>
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down Nanobot...");
            try {
                if (mcpManager != null) mcpManager.close();
                if (agentLoop != null) agentLoop.stop();
                if (messageBus != null) messageBus.shutdown(5, TimeUnit.SECONDS);
                if (toolRegistry != null) toolRegistry.shutdown();
                logger.info("Nanobot shutdown complete");
            } catch (Exception e) {
                logger.error("Error during shutdown", e);
            }
        }));
    }

    // ═══════════════════════════════════════════════════════════════
    // 静态 Getter（服务定位器 — 供 Controller / CLI / WS 等外部类使用）
    // ═══════════════════════════════════════════════════════════════

    public static MessageBus getMessageBus() {
        return messageBus;
    }

    public static ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public static SessionManager getSessionManager() {
        return sessionManager;
    }

    public static AgentLoop getAgentLoop() {
        return agentLoop;
    }

    public static Config getConfig() {
        return config;
    }

    public static RuleManager getRuleManager() {
        return ruleManager;
    }

    public static SkillManager getSkillManager() {
        return skillManager;
    }

    public static IdentityManager getIdentityManager() {
        return identityManager;
    }

    public static LLMProvider getProvider() {
        return provider;
    }
}
