package com.nanobot;

import com.nanobot.bus.MessageBus;
import com.nanobot.channels.ChannelServer;
import com.nanobot.config.Config;
import com.nanobot.config.ConfigLoader;
import com.nanobot.core.AgentLoop;
import com.nanobot.mcp.MCPManager;
import com.nanobot.memory.MemoryStore;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.impl.DeepSeekProvider;
import com.nanobot.providers.impl.OpenAIProvider;
import com.nanobot.session.SessionManager;
import com.nanobot.tools.ToolRegistry;
import com.nanobot.tools.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Nanobot 主入口类
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
 * java -jar nanobot-java.jar
 * 
 * # 指定配置
 * java -jar nanobot-java.jar --config /path/to/config.yaml
 * 
 * # 生成示例配置
 * java -jar nanobot-java.jar --generate-config
 * ```
 */
public class Nanobot {
    
    // ==================== 日志 ====================
    
    private static final Logger logger = LoggerFactory.getLogger(Nanobot.class);
    
    // ==================== 组件 ====================
    
    private Config config;
    private MessageBus messageBus;
    private LLMProvider provider;
    private ToolRegistry toolRegistry;
    private SessionManager sessionManager;
    private MemoryStore memoryStore;
    private AgentLoop agentLoop;
    private MCPManager mcpManager;
    private ChannelServer channelServer;
    
    // ==================== 状态 ====================
    
    private volatile boolean running = false;
    
    // ==================== 入口方法 ====================
    
    /**
     * 主入口
     */
    public static void main(String[] args) {
        Nanobot nanobot = new Nanobot();
        
        try {
            // 解析命令行参数
            String configPath = parseArgs(args);
            
            // 初始化
            nanobot.initialize(configPath);
            
            // 启动
            nanobot.start();
            
            // 注册关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                nanobot.stop();
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
        logger.info("Initializing Nanobot...");
        
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
        
        // 5. 初始化内存存储
        memoryStore = new MemoryStore(config);
        
        // 6. 初始化 LLM 提供商
        provider = createProvider();
        
        logger.info("Initialization complete");
    }
    
    /**
     * 注册工具
     */
    private void registerTools() {
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
        
        // Web 工具（联网查询）
        if (config.getTools().getWeb().isEnable()) {
            toolRegistry.register(new WebSearchTool());
            toolRegistry.register(new WebFetchTool());
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
            logger.warn("Nanobot is already running");
            return;
        }
        
        logger.info("Starting Nanobot...");
        
        // 创建 Agent Loop
        agentLoop = new AgentLoop(
            messageBus,
            provider,
            toolRegistry,
            sessionManager,
            config
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
        
        logger.info("Nanobot started successfully");
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
        
        logger.info("Stopping Nanobot...");
        
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
        
        logger.info("Nanobot stopped");
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
            Nanobot-Java - AI Agent
            
            Usage:
              java -jar nanobot-java.jar [options]
            
            Options:
              --config, -c <path>   Specify config file path
              --generate-config, -g Generate example config file
              --help, -h            Show this help message
            
            Environment variables:
              OPENAI_API_KEY       OpenAI API key
              NANOBOT_API_KEY      Anthropic API key
              NANOBOT_MODEL        Default model
            
            Examples:
              java -jar nanobot-java.jar
              java -jar nanobot-java.jar --config ~/.nanobot/config.yaml
              java -jar nanobot-java.jar --generate-config
            """);
    }
    
    /**
     * 生成示例配置
     */
    private static void generateConfig() {
        try {
            var path = java.nio.file.Paths.get(System.getProperty("user.home"), 
                                              ".nanobot", "config.yaml");
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
}
