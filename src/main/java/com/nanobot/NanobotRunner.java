package com.nanobot;

import com.nanobot.bus.MessageBus;
import com.nanobot.config.Config;
import com.nanobot.config.ConfigLoader;
import com.nanobot.core.AgentLoop;
import com.nanobot.mcp.MCPManager;
import com.nanobot.memory.Dream;
import com.nanobot.memory.MemoryStore;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.impl.DeepSeekProvider;
import com.nanobot.providers.impl.OpenAIProvider;
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

        // 1. 加载配置
        config = ConfigLoader.load();
        var errors = config.validate();
        if (!errors.isEmpty()) {
            logger.error("Configuration errors:");
            errors.forEach(e -> logger.error("  - {}", e));
            throw new IllegalStateException("Invalid configuration");
        }
        logger.info("Configuration loaded");

        // 2. 初始化身份管理器
        identityManager = new IdentityManager(config);
        identityManager.load();
        logger.info("Identity files loaded");

        // 3. 初始化规则管理器
        ruleManager = new RuleManager(config);
        ruleManager.loadRules();
        logger.info("Loaded {} rules", ruleManager.getRegistry().size());

        // 4. 初始化技能管理器
        skillManager = new SkillManager(config);
        skillManager.loadSkills();
        logger.info("Loaded {} skills", skillManager.getRegistry().size());

        // 5. 初始化 LLM 提供商
        provider = createProvider();

        // 6. 初始化长期记忆系统
        int maxMemories = config.getMemory().getDream().getMaxMemories();
        dream = new Dream(provider, maxMemories);
        Path baseDir = Paths.get(".nanobot").toAbsolutePath().normalize();
        dream.loadFromMemoryFile(baseDir);
        logger.info("Dream long-term memory initialized");

        // 7. AgentLoop / ToolRegistry / SessionManager / MessageBus
        //    由 Spring Bean (NanobotConfig) 统一创建和管理
        //    通过 @Autowired setter 注入到静态字段
        logger.info("AgentLoop injected from Spring: {}", agentLoop != null ? "OK" : "MISSING");
        logger.info("MessageBus injected from Spring: {}", messageBus != null ? "OK" : "MISSING");

        logger.info("Nanobot components initialized successfully");
        logger.info("Model: {}", config.getAgents().getDefaults().getModel());

        // 注册关闭钩子
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
            String searchApiKey = config.getTools().getWeb().getSearch().getApiKey();
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
     * 创建 LLM 提供商
     */
    private LLMProvider createProvider() {
        Config.AgentDefaults defaults = config.getAgents().getDefaults();
        String model = defaults.getModel();
        
        if (model.startsWith("gpt-") || model.startsWith("o1") || model.startsWith("o3")) {
            Config.ProviderConfig openaiConfig = config.getProviders().getOpenai();
            if (!openaiConfig.isConfigured()) {
                String apiKey = System.getenv("OPENAI_API_KEY");
                if (apiKey != null) {
                    openaiConfig.setApiKey(apiKey);
                }
            }
            return new OpenAIProvider(openaiConfig.getApiKey(), model);
        }
        
        if (model.startsWith("deepseek")) {
            Config.ProviderConfig deepseekConfig = config.getProviders().getDeepseek();
            if (!deepseekConfig.isConfigured()) {
                String apiKey = System.getenv("DEEPSEEK_API_KEY");
                if (apiKey != null) {
                    deepseekConfig.setApiKey(apiKey);
                }
            }
            if (!deepseekConfig.isConfigured()) {
                throw new IllegalStateException("DeepSeek API key not configured");
            }
            return new DeepSeekProvider(
                deepseekConfig.getApiKey(),
                model,
                deepseekConfig.getApiBase()
            );
        }
        
        Config.ProviderConfig providerConfig = config.getProviders().getOpenai();
        String apiKey = providerConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("OPENAI_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("No API key configured");
        }
        return new OpenAIProvider(apiKey, model);
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
