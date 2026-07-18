package com.nanobot.v2;

import com.nanobot.bus.MessageBus;
import com.nanobot.config.Config;
import com.nanobot.config.ConfigLoader;
import com.nanobot.core.AgentLoop;
import com.nanobot.core.TaskStore;
import com.nanobot.core.subagent.AgentCoordinator;
import com.nanobot.cron.CronScheduler;
import com.nanobot.identity.IdentityManager;
import com.nanobot.mcp.MCPManager;
import com.nanobot.memory.Consolidator;
import com.nanobot.memory.MemoryStore;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.impl.DeepSeekProvider;
import com.nanobot.providers.impl.OpenAIProvider;
import com.nanobot.rules.RuleManager;
import com.nanobot.security.PermissionManager;
import com.nanobot.security.PermissionMode;
import com.nanobot.security.guard.CommandGuard;
import com.nanobot.security.guard.NetworkGuard;
import com.nanobot.security.guard.PathGuard;
import com.nanobot.session.SessionManager;
import com.nanobot.skill.SkillManager;
import com.nanobot.tools.ToolRegistry;
import com.nanobot.tools.annotation.ToolScanner;
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
    public PathGuard pathGuard(Config config) {
        String workspace = config.getWorkspacePath();
        PathGuard guard = new PathGuard(workspace);
        return guard;
    }

    @Bean
    public CommandGuard commandGuard() {
        return CommandGuard.withDefaults();
    }

    @Bean
    public NetworkGuard networkGuard() {
        return NetworkGuard.withDefaults();
    }

    @Bean
    public Config.ChannelAclConfig channelAcl(Config config) {
        return config.getChannels().getAcl();
    }

    @Bean
    public PermissionManager permissionManager(PathGuard pathGuard,
                                                CommandGuard commandGuard,
                                                NetworkGuard networkGuard) {
        // 默认规则
        com.nanobot.security.rule.RuleEngine ruleEngine = new com.nanobot.security.rule.RuleEngine();
        // Shell 命令需要用户确认
        ruleEngine.addRule(com.nanobot.security.rule.RuleType.ASK, "exec", null, null,
                "Shell 命令执行需要您的确认");
        // rm -rf 需特别确认
        ruleEngine.addRule(com.nanobot.security.rule.RuleType.ASK, "exec", "command", "rm -rf.*",
                "递归删除操作需要确认");

        return PermissionManager.builder()
                .mode(PermissionMode.DEFAULT)
                .pathGuard(pathGuard)
                .commandGuard(commandGuard)
                .networkGuard(networkGuard)
                .ruleEngine(ruleEngine)
                .build();
    }

    @Bean
    public ToolRegistry toolRegistry(Config config, PermissionManager permissionManager,
                                      AgentCoordinator agentCoordinator) {
        ToolRegistry toolRegistry = new ToolRegistry();
        registerTools(toolRegistry, config, agentCoordinator);

        // 注入权限管理器（统一入口）
        toolRegistry.setPermissionManager(permissionManager);

        return toolRegistry;
    }

    private void registerTools(ToolRegistry toolRegistry, Config config,
                                AgentCoordinator agentCoordinator) {
        // 文件工具（路径验证由 ToolRegistry 中的 PathGuard 统一处理）
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new EditFileTool());
        toolRegistry.register(new ListDirTool());

        // 搜索工具
        toolRegistry.register(new GlobTool());
        toolRegistry.register(new GrepTool());

        // 时间工具（解决模型训练数据日期偏差）
        toolRegistry.register(new GetCurrentTimeTool());

        // AskUser 工具（LLM 可向用户提问）
        toolRegistry.register(new AskUserTool());

        // Task 追踪工具（LLM 自主分解追踪复杂任务）
        TaskStore taskStore = new TaskStore();
        toolRegistry.register(new TaskCreateTool(taskStore));
        toolRegistry.register(new TaskListTool(taskStore));
        toolRegistry.register(new TaskUpdateTool(taskStore));

        // 子 Agent spawn 工具（LLM 可调用 spawn 分解复杂任务）
        // 注: agentCoordinator 已在上面创建，这里通过 Spring 的依赖注入获取
        // SpawnTool 在下面单独注册

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

        // 子 Agent spawn 工具
        toolRegistry.register(new SpawnTool(agentCoordinator));
        toolRegistry.register(new SpawnCheckTool());

        // 自动扫描 @ToolDef 注解的工具（包路径可通过 config.tools.toolScanPackages 配置）
        String scanPkgs = config.getTools().getToolScanPackages();
        if (scanPkgs != null && !scanPkgs.isBlank()) {
            new ToolScanner().scanAndRegister(toolRegistry, scanPkgs.split("\\s*,\\s*"));
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
    public MemoryStore memoryStore(Config config) {
        return new MemoryStore(config);
    }

    @Bean
    public IdentityManager identityManager(Config config) {
        IdentityManager identityManager = new IdentityManager(config);
        identityManager.load();
        logger.info("Loaded identity: soul={}", identityManager.getSoul().getName());
        return identityManager;
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
    
    /**
     * 记忆压缩器 — 对话 token 数超过 contextWindowTokens 的 90% 时触发压缩。
     * 使用 LLM 将旧消息总结为 system 消息，防止上下文窗口溢出。
     */
    /**
     * 定时任务调度器 — 基于 cron 表达式的任务调度。
     * 可在任何地方注入后调用 scheduler.schedule("0 * * * *", task) 注册任务。
     */
    /** 子 Agent 协调器 — 管理子 Agent 的注册、分配、执行 */
    @Bean
    public AgentCoordinator agentCoordinator(LLMProvider llmProvider,
                                              @org.springframework.context.annotation.Lazy ToolRegistry toolRegistry) {
        AgentCoordinator coordinator = new AgentCoordinator(llmProvider, toolRegistry);
        // 注册默认子 Agent（各有不同能力）
        coordinator.registerSubagent("searcher",  "搜索助手",
                java.util.Map.of("web_search", true));
        coordinator.registerSubagent("summarizer", "总结助手",
                java.util.Map.of("summarization", true));
        coordinator.registerSubagent("coder", "编程助手",
                java.util.Map.of("code", true));
        coordinator.registerSubagent("calculator", "计算助手",
                java.util.Map.of("calculation", true));
        coordinator.startAll();
        return coordinator;
    }

    @Bean
    public CronScheduler cronScheduler(MessageBus messageBus) {
        return new CronScheduler(messageBus);
    }

    @Bean
    public Consolidator consolidator(LLMProvider llmProvider, Config config) {
        int budget = config.getAgents().getDefaults().getContextWindowTokens();
        return new Consolidator(llmProvider, budget);
    }

    @Bean(destroyMethod = "stop")
    public AgentLoop agentLoop(
            MessageBus messageBus,
            LLMProvider llmProvider,
            ToolRegistry toolRegistry,
            SessionManager sessionManager,
            SkillManager skillManager,
            RuleManager ruleManager,
            IdentityManager identityManager,
            Consolidator consolidator,
            Config config) {
        AgentLoop agentLoop = new AgentLoop(
            messageBus,
            llmProvider,
            toolRegistry,
            sessionManager,
            config,
            ruleManager,
            skillManager,
            identityManager
        );
        agentLoop.setConsolidator(consolidator);
        // 启动 AgentLoop
        agentLoop.start();
        return agentLoop;
    }
}
