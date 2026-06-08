package com.nanobot.config;

import com.nanobot.bus.MessageBus;
import com.nanobot.core.AgentLoop;
import com.nanobot.mcp.MCPManager;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.impl.DeepSeekProvider;
import com.nanobot.providers.impl.OpenAIProvider;
import com.nanobot.rules.RuleManager;
import com.nanobot.session.SessionManager;
import com.nanobot.skill.SkillManager;
import com.nanobot.tools.ToolRegistry;
import com.nanobot.tools.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Nanobot Spring 配置
 * ==================
 * 
 * 配置所有 Nanobot 核心组件作为 Spring Bean
 */
@Configuration
public class NanobotConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(NanobotConfig.class);
    
    @Bean
    public Config config() {
        return ConfigLoader.load();
    }
    
    @Bean
    public ToolRegistry toolRegistry(Config config) {
        ToolRegistry toolRegistry = new ToolRegistry();
        registerTools(toolRegistry, config);
        return toolRegistry;
    }
    
    private void registerTools(ToolRegistry toolRegistry, Config config) {
        String workspace = config.getAgents().getDefaults().getWorkspace();
        
        // 文件工具
        toolRegistry.register(new ReadFileTool(workspace));
        toolRegistry.register(new WriteFileTool(workspace));
        toolRegistry.register(new EditFileTool(workspace));
        toolRegistry.register(new ListDirTool(workspace));
        
        // 搜索工具
        toolRegistry.register(new GlobTool(workspace));
        toolRegistry.register(new GrepTool(workspace));
        
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
        
        logger.info("Registered {} tools: {}", 
                   toolRegistry.size(), 
                   String.join(", ", toolRegistry.getToolNames()));
    }
    
    @Bean
    public LLMProvider llmProvider(Config config) {
        Config.ProviderConfig deepseekConfig = config.getProviders().getDeepseek();
        String model = config.getAgents().getDefaults().getModel();
        return new DeepSeekProvider(
            deepseekConfig.getApiKey(),
            model,
            deepseekConfig.getApiBase()
        );
    }
    
    @Bean
    public SessionManager sessionManager(Config config) {
        return new SessionManager(config);
    }
    
    @Bean
    public SkillManager skillManager(Config config) {
        SkillManager skillManager = new SkillManager(config);
        skillManager.loadSkills();
        logger.info("Loaded {} skills", skillManager.getRegistry().size());
        return skillManager;
    }
    
    @Bean
    public RuleManager ruleManager(Config config) {
        RuleManager ruleManager = new RuleManager(config);
        ruleManager.loadRules();
        logger.info("Loaded {} rules", ruleManager.getRegistry().size());
        return ruleManager;
    }
    
    @Bean
    public MCPManager mcpManager(Config config, ToolRegistry toolRegistry) {
        MCPManager mcpManager = new MCPManager();
        mcpManager.initialize(config, toolRegistry);
        logger.info("Initialized {} MCP servers", mcpManager.getClientCount());
        return mcpManager;
    }
    
    @Bean(destroyMethod = "stop")
    public AgentLoop agentLoop(
            MessageBus messageBus,
            LLMProvider llmProvider,
            ToolRegistry toolRegistry,
            SessionManager sessionManager,
            SkillManager skillManager,
            RuleManager ruleManager,
            Config config) {
        AgentLoop agentLoop = new AgentLoop(
            messageBus,
            llmProvider,
            toolRegistry,
            sessionManager,
            config,
            ruleManager,
            skillManager
        );
        // 启动 AgentLoop
        agentLoop.start();
        return agentLoop;
    }
}
