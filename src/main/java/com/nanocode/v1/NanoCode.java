package com.nanocode.v1;

import com.nanocode.bus.MessageBus;
import com.nanocode.v1.channel.ChannelServer;
import com.nanocode.config.Config;
import com.nanocode.config.ConfigLoader;
import com.nanocode.core.AgentLoop;
import com.nanocode.mcp.MCPManager;
import com.nanocode.providers.LLMProvider;
import com.nanocode.providers.impl.DeepSeekProvider;
import com.nanocode.providers.impl.OpenAIProvider;
import com.nanocode.identity.IdentityManager;
import com.nanocode.rules.RuleManager;
import com.nanocode.session.SessionManager;
import com.nanocode.skill.SkillManager;
import com.nanocode.tools.ToolRegistry;
import com.nanocode.tools.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * NanoCode 主入口类
 * ===================
 * 
 * 本类是整个应用程序的入口点，负责：
 * 1. 加载配置
 * 2. 初始化组件
 * 3. 启动 Agent Loop
 * 4. 处理优雅关闭
 * 
 * **使用示例**：
 * 
 * ```bash
 * # 运行
 * java -jar nanocode.jar
 *
 * # 指定配置
 * java -jar nanocode.jar --config /path/to/config.yaml
 *
 * # 生成示例配置
 * java -jar nanocode.jar --generate-config
 * ```
 */
public class NanoCode {
    
    // ==================== 日志 ====================
    
    private static final Logger logger = LoggerFactory.getLogger(NanoCode.class);
    
    // ==================== 组件 ====================
    
    private Config config;
    private MessageBus messageBus;
    private LLMProvider provider;
    private ToolRegistry toolRegistry;
    private SessionManager sessionManager;
    private AgentLoop agentLoop;
    private MCPManager mcpManager;
    private ChannelServer channelServer;
    private SkillManager skillManager;
    private RuleManager ruleManager;
    private IdentityManager identityManager;
    private com.nanocode.memory.Dream dream;
    
    // ==================== 状态 ====================
    
    private volatile boolean running = false;
    
    // ==================== 入口方法 ====================
    
    /**
     * 主入口
     */
    public static void main(String[] args) {
        // 打印启动 Banner
        printBanner();
        
        NanoCode nanocode = new NanoCode();
        
        try {
            // 解析命令行参数
            String configPath = parseArgs(args);
            
            // 初始化
            nanocode.initialize(configPath);
            
            // 启动
            nanocode.start();
            
            // 注册关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                nanocode.stop();
            }));
            
            // 主线程等待
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.error("Fatal error", e);
            System.exit(1);
        }
    }
    
    // ==================== 初始化 ====================
    
    /**
     * 初始化所有组件
     */
    public void initialize(String configPath) {
        logger.info("Initializing NanoCode...");
        
        // 1. 加载配置
        if (configPath != null) {
            config = ConfigLoader.load(java.nio.file.Paths.get(configPath));
        } else {
            config = ConfigLoader.load();
        }
        
        // 验证配置
        var errors = config.validate();
        if (!errors.isEmpty()) {
            logger.error("Configuration errors:");
            errors.forEach(e -> logger.error("  - {}", e));
            throw new IllegalStateException("Invalid configuration");
        }
        
        logger.info("Configuration loaded");
        
        // 2. 初始化消息总线
        messageBus = new MessageBus();
        
        // 3. 初始化工具注册中心
        toolRegistry = new ToolRegistry();
        registerTools();
        
        // 4. 初始化会话管理器
        sessionManager = new SessionManager(config);
        
        // 5. 初始化身份管理器（SOUL, IDENTITY, USER）
        identityManager = new IdentityManager(config);
        identityManager.load();
        logger.info("Identity files loaded");
        
        // 6. 初始化规则管理器（Rules）
        ruleManager = new RuleManager(config);
        ruleManager.loadRules();
        logger.info("Loaded {} rules", ruleManager.getRegistry().size());
        
        // 7. 初始化技能管理器（Skills）
        skillManager = new SkillManager(config);
        skillManager.loadSkills();
        logger.info("Loaded {} skills", skillManager.getRegistry().size());
        
        // 8. 初始化 LLM 提供商
        provider = createProvider();
        
        // 9. 初始化长期记忆系统（Dream）
        int maxMemories = config.getMemory().getDream().getMaxMemories();
        java.nio.file.Path memoryDir = java.nio.file.Paths.get(".nanocode", "memory").toAbsolutePath().normalize();
        dream = new com.nanocode.memory.Dream(provider, maxMemories, memoryDir);
        dream.loadFromMemoryFile(memoryDir);
        logger.info("Dream long-term memory initialized");
        
        logger.info("Initialization complete");
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

        // 时间工具
        toolRegistry.register(new GetCurrentTimeTool());

        // Shell 工具
        if (config.getTools().getExec().isEnable()) {
            toolRegistry.register(new ExecTool(new java.io.File(config.getWorkspacePath())));
        }
        
        // Web 工具（联网查询）
        if (config.getTools().getWeb().isEnable()) {
            // 从配置文件读取搜索配置
            String searchProvider = config.getTools().getWeb().getSearch().getProvider();
            String searchApiKey = config.getTools().getWeb().getSearch().getApiKey();
            
            toolRegistry.register(new WebSearchTool(searchProvider, searchApiKey));
            // 禁用 web_fetch 工具，避免抓取外部网页（如维基百科）
            // toolRegistry.register(new WebFetchTool());
        }
        
        // MCP 工具
        registerMCPTools();
        
        logger.info("Registered {} tools: {}", 
                   toolRegistry.size(), 
                   String.join(", ", toolRegistry.getToolNames()));
    }
    
    /**
     * 注册 MCP 工具
     * 
     * MCP (Model Context Protocol) 是 Cursor 编辑器提出的标准化协议，
     * 允许动态加载和使用第三方工具。
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
        
        // 根据模型名称自动选择提供商
        if (model.startsWith("gpt-") || model.startsWith("o1") || model.startsWith("o3")) {
            // OpenAI
            Config.ProviderConfig openaiConfig = config.getProviders().getOpenai();
            if (!openaiConfig.isConfigured()) {
                String apiKey = System.getenv("OPENAI_API_KEY");
                if (apiKey != null) {
                    openaiConfig.setApiKey(apiKey);
                }
            }
            return new OpenAIProvider(openaiConfig.getApiKey(), model);
        }
        
        // DeepSeek 模型
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
        
        // 默认使用 OpenAI
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
    
    // ==================== 生命周期 ====================
    
    /**
     * 启动 Agent
     */
    public void start() {
        if (running) {
            logger.warn("NanoCode is already running");
            return;
        }
        
        logger.info("Starting NanoCode...");
        
        // 创建 Agent Loop（集成 Identity、Skills 和 Rules）
        agentLoop = new AgentLoop(
            messageBus,
            provider,
            toolRegistry,
            sessionManager,
            config,
            ruleManager,
            skillManager,
            identityManager,
            null  // v1 不启用 hook 系统
        );
        
        // 启动 Agent Loop
        agentLoop.start();
        
        // 启动 HTTP/WebSocket 服务器
        Config.ServerConfig serverConfig = config.getChannels().getServer();
        if (serverConfig.isEnable()) {
            try {
                channelServer = new ChannelServer(messageBus, serverConfig.getPort());
                channelServer.start();
                logger.info("ChannelServer started on http://{}:{}", 
                           serverConfig.getHost(), serverConfig.getPort());
            } catch (java.io.IOException e) {
                logger.error("Failed to start ChannelServer: {}", e.getMessage());
            }
            
        }
        
        running = true;
        
        logger.info("NanoCode started successfully");
        logger.info("Session storage: {}", config.getAgents().getDefaults().getWorkspace());
        logger.info("Model: {}", config.getAgents().getDefaults().getModel());
    }
    
    /**
     * 停止 Agent
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        logger.info("Stopping NanoCode...");
        
        running = false;
        
        // 停止组件
        if (channelServer != null) {
            channelServer.stop();
        }
        
        if (agentLoop != null) {
            agentLoop.stop();
        }
        
        if (mcpManager != null) {
            mcpManager.close();
        }
        
        if (messageBus != null) {
            messageBus.shutdown(5, TimeUnit.SECONDS);
        }
        
        if (toolRegistry != null) {
            toolRegistry.shutdown();
        }
        
        logger.info("NanoCode stopped");
    }
    
    // ==================== 命令行参数 ====================
    
    /**
     * 解析命令行参数
     */
    private static String parseArgs(String[] args) {
        String configPath = null;
        
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config", "-c" -> {
                    if (i + 1 < args.length) {
                        configPath = args[++i];
                    }
                }
                case "--generate-config", "-g" -> {
                    generateConfig();
                    System.exit(0);
                }
                case "--help", "-h" -> {
                    printHelp();
                    System.exit(0);
                }
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    printHelp();
                    System.exit(1);
                }
            }
        }
        
        return configPath;
    }
    
    /**
     * 打印帮助信息
     */
    private static void printHelp() {
        System.out.println("""
            NanoCode - AI Agent
            
            Usage:
              java -jar nanocode.jar [options]
            
            Options:
              --config, -c <path>   Specify config file path
              --generate-config, -g Generate example config file
              --help, -h            Show this help message
            
            Environment variables:
              OPENAI_API_KEY       OpenAI API key
              NANOCODE_API_KEY      Anthropic API key
              NANOCODE_MODEL        Default model
            
            Examples:
              java -jar nanocode.jar
              java -jar nanocode.jar --config ~/.nanocode/config.yaml
              java -jar nanocode.jar --generate-config
            """);
    }
    
    /**
     * 生成示例配置
     */
    private static void generateConfig() {
        try {
            var path = com.nanocode.config.NanoCodeEnv.resolveRuntimeDir(
                    System.getProperty("user.home"), ".nanocode").resolve("config.yaml");
            ConfigLoader.generateExampleConfig(path);
            System.out.println("Example config generated at: " + path);
        } catch (Exception e) {
            System.err.println("Failed to generate config: " + e.getMessage());
            System.exit(1);
        }
    }
    
    // ==================== 状态查询 ====================
    
    /**
     * 检查是否运行中
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * 获取配置
     */
    public Config getConfig() {
        return config;
    }
    
    /**
     * 获取会话管理器
     */
    public SessionManager getSessionManager() {
        return sessionManager;
    }
    
    /**
     * 获取工具注册中心
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }
    
    // ==================== Banner ====================
    
    /**
     * 打印启动 Banner
     */
    private static void printBanner() {
        String banner = """
            ╔══════════════════════════════════════════════════════════════════════════════╗
            ║                                                                              ║
            ║    ███╗   ███╗ ██████╗  ███╗   ██╗   ██████╗  █████╗  ███╗   ██╗          ║
            ║    ████╗ ████║ ██╔══██╗ ████╗  ██║   ██╔══██╗██╔══██╗████╗  ██║          ║
            ║    ██╔████╔██║ ██████╔╝ ██╔██╗ ██║   ██████╔╝███████║██╔██╗ ██║          ║
            ║    ██║╚██╔╝██║ ██╔═══╝  ██║╚██╗██║   ██╔═══╝ ██╔══██║██║╚██╗██║          ║
            ║    ██║ ╚═╝ ██║ ██║       ██║ ╚████║   ██║     ██║  ██║██║ ╚████║          ║
            ║    ╚═╝     ╚═╝ ╚═╝       ╚═╝  ╚═══╝   ╚═╝     ╚═╝  ╚═╝╚═╝  ╚═══╝          ║
            ║                                                                              ║
            ║                             nanocode v1.0.0                                   ║
            ║              A lightweight AI Agent Framework for Java                        ║
            ║                                                                              ║
            ║    Features:  • Agent Loop    • Memory Management    • Tool System           ║
            ║               • Multi-Channel  • MCP Support         • Web Search            ║
            ║                                                                              ║
            ╚══════════════════════════════════════════════════════════════════════════════╝
            """;
        
        System.out.println();
        System.out.println(banner);
        System.out.println();
    }
}
