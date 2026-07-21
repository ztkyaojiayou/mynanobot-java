package com.nanobot;

import com.nanobot.bus.MessageBus;
import com.nanobot.config.Config;
import com.nanobot.config.ConfigLoader;
import com.nanobot.core.AgentLoop;
import com.nanobot.mcp.MCPManager;
import com.nanobot.memory.Dream;
import com.nanobot.memory.MemoryStore;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.ProviderFactory;
import com.nanobot.identity.IdentityManager;
import com.nanobot.rules.RuleManager;
import com.nanobot.session.SessionManager;
import com.nanobot.skill.SkillManager;
import com.nanobot.tools.ToolRegistry;
import com.nanobot.tools.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Nanobot 核心组件初始化 Runner
 * 
 * 在 Spring Boot 启动后初始化所有 Nanobot 核心组件：
 * - MessageBus
 * - ToolRegistry
 * - SessionManager
 * - MemoryStore
 * - AgentLoop
 * - 等
 */
@Component
public class NanobotRunner implements ApplicationRunner {
    
    private static final Logger logger = LoggerFactory.getLogger(NanobotRunner.class);
    
    // Spring Bean - 将在 setMessageBus 中注入
    private static MessageBus messageBus;
    private static ToolRegistry toolRegistry;
    private static SessionManager sessionManager;
    private static MemoryStore memoryStore;
    private static AgentLoop agentLoop;
    private static MCPManager mcpManager;
    private static Dream dream;
    private static Config config;
    private static RuleManager ruleManager;
    private static SkillManager skillManager;
    private static IdentityManager identityManager;
    private static LLMProvider provider;
    
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
    private void setMemoryStore(MemoryStore memoryStore) {
        NanobotRunner.memoryStore = memoryStore;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        logger.info("Initializing Nanobot components...");

        config = initConfig();
        identityManager = initIdentity();
        ruleManager = initRules();
        skillManager = initSkills();
        provider = initProvider();
        dream = initDream();
        // 注入长期记忆引擎到 AgentLoop
        if (agentLoop != null && dream != null) {
            agentLoop.setDream(dream);
            logger.info("Dream injected into AgentLoop");
        }
        verifySpringBeans();
        registerShutdownHook();

        logger.info("Nanobot components initialized successfully");
        logger.info("Model: {}", config.getAgents().getDefaults().getModel());
    }

    private Config initConfig() {
        Config c = ConfigLoader.load();
        var errors = c.validate();
        if (!errors.isEmpty()) {
            logger.error("Configuration errors:");
            errors.forEach(e -> logger.error("  - {}", e));
            throw new IllegalStateException("Invalid configuration");
        }
        return c;
    }

    private IdentityManager initIdentity() {
        var m = new IdentityManager(config);
        m.load();
        return m;
    }

    private RuleManager initRules() {
        var m = new RuleManager(config);
        m.loadRules();
        logger.info("Loaded {} rules", m.getRegistry().size());
        return m;
    }

    private SkillManager initSkills() {
        var m = new SkillManager(config);
        m.loadSkills();
        logger.info("Loaded {} skills", m.getRegistry().size());
        return m;
    }

    private LLMProvider initProvider() {
        return new ProviderFactory().create(config);
    }

    private Dream initDream() {
        int maxMemories = config.getMemory().getDream().getMaxMemories();
        Path memoryDir = Paths.get(".nanobot", "memory").toAbsolutePath().normalize();
        Dream d = new Dream(provider, maxMemories, memoryDir);
        d.loadFromMemoryFile(memoryDir);
        return d;
    }

    private void verifySpringBeans() {
        logger.info("AgentLoop injected from Spring: {}", agentLoop != null ? "OK" : "MISSING");
        logger.info("MessageBus injected from Spring: {}", messageBus != null ? "OK" : "MISSING");
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down Nanobot...");
            shutdown();
        }));
    }
    
    /**
     * 注册工具
     */
    private void registerTools() {
        // 文件工具（路径验证由 ToolRegistry 中的 PathGuard 统一处理）
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new EditFileTool());
        toolRegistry.register(new ListDirTool());

        // 搜索工具
        toolRegistry.register(new GlobTool());
        toolRegistry.register(new GrepTool());
        
        // Shell 工具
        if (config.getTools().getExec().isEnable()) {
            toolRegistry.register(new ExecTool());
        }
        
        // Web 工具
        if (config.getTools().getWeb().isEnable()) {
            String searchProvider = config.getTools().getWeb().getSearch().getProvider();
            String searchApiKey = config.getTools().getWeb().getSearch().getActiveApiKey();
            toolRegistry.register(new WebSearchTool(searchProvider, searchApiKey));
        }
        
        // MCP 工具
        registerMCPTools();
        
        logger.info("Registered {} tools: {}", 
                   toolRegistry.size(), 
                   String.join(", ", toolRegistry.getToolNames()));
    }
    
    /**
     * 注册 MCP 工具
     */
    private void registerMCPTools() {
        mcpManager = new MCPManager();
        try {
            mcpManager.initialize(config, toolRegistry);
            logger.info("MCP initialization complete. {} servers, {} total tools", 
                       mcpManager.getClientCount(), 
                       toolRegistry.size());
        } catch (Exception e) {
            logger.warn("Failed to initialize MCP: {}", e.getMessage());
        }
    }
    
    /**
     * 关闭所有组件
     */
    private void shutdown() {
        try {
            if (mcpManager != null) {
                mcpManager.close();
            }
            if (agentLoop != null) {
                agentLoop.stop();
            }
            if (messageBus != null) {
                messageBus.shutdown(5, TimeUnit.SECONDS);
            }
            if (toolRegistry != null) {
                toolRegistry.shutdown();
            }
            logger.info("Nanobot shutdown complete");
        } catch (Exception e) {
            logger.error("Error during shutdown", e);
        }
    }
    
    // ==================== Getter 方法（供 Controller 使用） ====================
    
    public static MessageBus getMessageBus() {
        return messageBus;
    }
    
    public static ToolRegistry getToolRegistry() {
        return toolRegistry;
    }
    
    public static SessionManager getSessionManager() {
        return sessionManager;
    }
    
    public static MemoryStore getMemoryStore() {
        return memoryStore;
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
