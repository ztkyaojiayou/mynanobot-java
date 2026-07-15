package com.nanobot.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.Map;

/**
 * 根配置类 - 系统配置的入口点
 * ==================================
 *
 * 本类定义了 Nanobot-Java 的所有配置项，采用分层设计：
 * - agents: Agent 行为配置
 * - providers: LLM 提供商配置
 * - channels: 聊天通道配置
 * - tools: 工具系统配置
 * - memory: 内存系统配置
 *
 * **设计思想**：
 *
 * 1. **强类型**：
 *    - 使用 Java 类定义配置结构
 *    - 提供类型安全和 IDE 自动补全
 *    - 便于配置验证
 *
 * 2. **默认值**：
 *    - 所有配置都有合理的默认值
 *    - 可以只配置需要的部分
 *    - 简化配置文件的编写
 *
 * 3. **灵活性**：
 *    - 支持 JSON 和 YAML 格式
 *    - 支持环境变量覆盖
 *    - 支持配置合并和继承
 *
 * **配置层次**：
 *
 * ```
 * Config (根配置)
 * ├── agents
 * │   ├── defaults
 * │   │   ├── model: "anthropic/claude-sonnet-4"
 * │   │   ├── maxTokens: 8192
 * │   │   └── ...
 * │   └── agents
 * └── providers
 *     ├── anthropic
 *     │   ├── apiKey: "sk-xxx"
 *     │   └── ...
 *     ├── openai
 *     │   └── ...
 *     └── ...
 * ```
 *
 * **使用示例**：
 *
 * ```java
 * // 加载配置
 * Config config = ConfigLoader.load("config.yaml");
 *
 * // 访问配置
 * String model = config.getAgents().getDefaults().getModel();
 * String apiKey = config.getProviders().getAnthropic().getApiKey();
 *
 * // 修改配置（返回副本）
 * Config modified = config.withAgentDefaults(defaults -> {
 *     defaults.setModel("anthropic/claude-opus-4");
 * });
 * ```
 */
@Data
@lombok.NoArgsConstructor
public class Config {

    // ==================== Agent 配置 ====================

    /**
     * Agent 配置
     */
    private AgentsConfig agents = new AgentsConfig();

    /**
     * 提供商配置
     */
    private ProvidersConfig providers = new ProvidersConfig();

    /**
     * 通道配置
     */
    private ChannelsConfig channels = new ChannelsConfig();

    /**
     * 工具配置
     */
    private ToolsConfig tools = new ToolsConfig();

    /**
     * MCP 服务器配置
     */
    private java.util.Map<String, MCPServerConfig> mcpServers = new java.util.HashMap<>();

    /**
     * 内存配置
     */
    private MemoryConfig memory = new MemoryConfig();

    /**
     * 技能配置
     */
    private SkillsConfig skills = new SkillsConfig();

    /**
     * 钩子配置
     */
    private HooksConfig hooks = new HooksConfig();

    // ==================== 构造函数 ====================

    public Config(AgentsConfig agents, ProvidersConfig providers,
                  ChannelsConfig channels, ToolsConfig tools, MemoryConfig memory) {
        this.agents = agents != null ? agents : new AgentsConfig();
        this.providers = providers != null ? providers : new ProvidersConfig();
        this.channels = channels != null ? channels : new ChannelsConfig();
        this.tools = tools != null ? tools : new ToolsConfig();
        this.memory = memory != null ? memory : new MemoryConfig();
    }

    // ==================== Agent 配置类 ====================

    /**
     * Agent 配置
     */
    @Data
    public static class AgentsConfig {

        /** 默认 Agent 配置 */
        private AgentDefaults defaults = new AgentDefaults();
    }

    /**
     * Agent 默认配置
     */
    @Data
    public static class AgentDefaults {

        /** 工作空间目录（相对于项目根目录） */
        private String workspace = ".nanobot/workspace";

        /** 默认模型 */
        private String model = "anthropic/claude-sonnet-4-20250514";

        /** 提供商名称，设为 "auto" 自动检测 */
        private String provider = "auto";

        /** 最大输出 token 数 */
        @JsonProperty("maxTokens")
        private int maxTokens = 8192;

        /** 上下文窗口大小（token） */
        @JsonProperty("contextWindowTokens")
        private int contextWindowTokens = 200_000;

        /** 温度参数 */
        private float temperature = 0.7f;

        /** 最大工具迭代次数 */
        @JsonProperty("maxToolIterations")
        private int maxToolIterations = 100;

        /** 最大工具结果字符数 */
        @JsonProperty("maxToolResultChars")
        private int maxToolResultChars = 16_000;

        /** 工具提示最大长度 */
        @JsonProperty("toolHintMaxLength")
        private int toolHintMaxLength = 40;

        /** 推理努力程度（low/medium/high） */
        @JsonProperty("reasoningEffort")
        private String reasoningEffort = null;

        /** 时区 */
        private String timezone = "UTC";

        /** 是否启用统一会话 */
        @JsonProperty("unifiedSession")
        private boolean unifiedSession = false;

        /** 系统提示词 */
        @JsonProperty("systemPrompt")
        private String systemPrompt = """
            你是 Nanobot，一个强大的 AI 助手。

            你的任务是帮助用户解决问题，回答问题，执行任务。

            你具有以下能力：
            1. 可以调用工具来完成各种任务
            2. 可以进行自然语言对话
            3. 可以访问和操作文件系统

            请用友好、专业的方式回答用户的问题。
            """;

        /** 禁用技能列表 */
        @JsonProperty("disabledSkills")
        private java.util.List<String> disabledSkills = new java.util.ArrayList<>();
    }

    // ==================== 提供商配置类 ====================

    /**
     * 提供商配置
     */
    @Data
    public static class ProvidersConfig {

        private ProviderConfig anthropic = new ProviderConfig();
        private ProviderConfig openai = new ProviderConfig();
        private ProviderConfig openrouter = new ProviderConfig();
        private ProviderConfig deepseek = new ProviderConfig();
        private ProviderConfig groq = new ProviderConfig();
        private ProviderConfig ollama = new ProviderConfig();
    }

    /**
     * 提供商基础配置
     */
    @Data
    public static class ProviderConfig {

        /** API 密钥 */
        @JsonProperty("apiKey")
        private String apiKey = "";

        /** API 基础 URL */
        @JsonProperty("apiBase")
        private String apiBase = "";

        /** 额外请求头 */
        @JsonProperty("extraHeaders")
        private java.util.Map<String, String> extraHeaders = new java.util.HashMap<>();

        /** 额外请求体字段 */
        @JsonProperty("extraBody")
        private java.util.Map<String, Object> extraBody = new java.util.HashMap<>();

        /**
         * 检查是否已配置
         */
        @JsonIgnore
        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    // ==================== 通道配置类 ====================

    /**
     * 通道配置
     */
    @Data
    public static class ChannelsConfig {

        /** 是否发送进度 */
        @JsonProperty("sendProgress")
        private boolean sendProgress = true;

        /** 是否发送工具提示 */
        @JsonProperty("sendToolHints")
        private boolean sendToolHints = false;

        /** 最大发送重试次数 */
        @JsonProperty("sendMaxRetries")
        private int sendMaxRetries = 3;

        /** 转录提供商 */
        @JsonProperty("transcriptionProvider")
        private String transcriptionProvider = "groq";

        /** WebSocket 通道配置 */
        private JsonNode websocket;

        /** Telegram 通道配置 */
        private JsonNode telegram;

        /** Discord 通道配置 */
        private JsonNode discord;

        /** 通道级访问控制 */
        @JsonProperty("acl")
        private ChannelAclConfig acl = new ChannelAclConfig();

        /** HTTP/WebSocket 服务器配置 */
        private ServerConfig server = new ServerConfig();
    }

    /**
     * HTTP/WebSocket 服务器配置
     */
    @Data
    public static class ServerConfig {

        /** 是否启用服务器 */
        private boolean enable = true;

        /** 监听端口 */
        private int port = 8080;

        /** 绑定地址 */
        private String host = "0.0.0.0";
    }

    // ==================== 工具配置类 ====================

    /**
     * 工具配置
     */
    @Data
    public static class ToolsConfig {

        /** Web 工具配置 */
        private WebToolsConfig web = new WebToolsConfig();

        /** 执行工具配置 */
        private ExecToolConfig exec = new ExecToolConfig();

        /** MCP 服务器配置 */
        @JsonProperty("mcp_servers")
        private Map<String, MCPServerConfig> mcpServers = new java.util.HashMap<>();

        /** 是否限制在工作空间 */
        @JsonProperty("restrictToWorkspace")
        private boolean restrictToWorkspace = false;

        /** @ToolDef 注解扫描的包路径（逗号分隔） */
        @JsonProperty("toolScanPackages")
        private String toolScanPackages = "com.nanobot.tools.impl";
    }

    /**
     * Web 工具配置
     */
    @Data
    public static class WebToolsConfig {

        /** 是否启用 */
        private boolean enable = true;

        /** 代理 URL */
        private String proxy = "";

        /** 用户代理 */
        private String userAgent = "";

        /** 搜索配置 */
        private SearchConfig search = new SearchConfig();
    }

    /**
     * 搜索配置
     */
    @Data
    public static class SearchConfig {

        /** 搜索提供商 */
        private String provider = "duckduckgo";

        /** API 密钥 */
        private String apiKey = "";

        /** 最大结果数 */
        private int maxResults = 5;

        /** 超时（秒） */
        private int timeout = 30;
    }

    /**
     * 执行工具配置
     */
    @Data
    public static class ExecToolConfig {

        /** 是否启用 */
        private boolean enable = true;

        /** 执行超时（秒） */
        private int timeout = 60;

        /** 路径追加 */
        private String pathAppend = "";

        /** 沙箱类型（空表示不启用） */
        private String sandbox = "";
    }

    // ==================== 内存配置类 ====================

    /**
     * 内存配置
     */
    @Data
    public static class MemoryConfig {

        /** Dream 配置 */
        private DreamConfig dream = new DreamConfig();
    }

    /**
     * Dream（记忆巩固）配置
     */
    @Data
    public static class DreamConfig {

        /** 执行间隔（小时） */
        private int intervalHours = 2;

        /** 最大批次大小 */
        private int maxBatchSize = 20;

        /** 最大迭代次数 */
        private int maxIterations = 15;

        /** 是否启用行年龄注释 */
        private boolean annotateLineAges = true;

        /** 最大记忆数量 */
        private int maxMemories = 1000;
    }

    // ==================== MCP 配置类 ====================

    /**
     * MCP 服务器配置
     *
     * MCP (Model Context Protocol) 是 Cursor 编辑器提出的标准化协议，用于连接 AI Agent 与外部工具/服务。
     *
     * 支持的传输类型：
     * - stdio: 通过标准输入输出与 MCP 服务器进程通信（默认）
     * - sse: Server-Sent Events 方式
     * - streamableHttp: HTTP 流式传输
     */
    @Data
    public static class MCPServerConfig {

        /**
         * 传输类型：stdio、sse、streamableHttp
         */
        private String type = "stdio";

        /**
         * stdio 模式：命令路径
         */
        private String command = "";

        /**
         * stdio 模式：命令参数
         */
        private java.util.List<String> args = new java.util.ArrayList<>();

        /**
         * HTTP 模式：服务器 URL
         */
        private String url = "";

        /**
         * HTTP 模式：请求头
         */
        private java.util.Map<String, String> headers = new java.util.HashMap<>();

        /**
         * 工具调用超时时间（秒）
         */
        @JsonProperty("tool_timeout")
        private int toolTimeout = 30;

        /**
         * 启用的工具列表，"*" 表示启用所有工具
         */
        @JsonProperty("enabled_tools")
        private java.util.List<String> enabledTools = new java.util.ArrayList<>(java.util.List.of("*"));

        /**
         * 是否启用此 MCP 服务器
         */
        private boolean enable = true;

        /**
         * 检查是否已配置
         */
        public boolean isConfigured() {
            if (!enable) {
                return false;
            }

            if ("stdio".equalsIgnoreCase(type)) {
                return command != null && !command.isBlank();
            } else if ("sse".equalsIgnoreCase(type) || "streamableHttp".equalsIgnoreCase(type)) {
                return url != null && !url.isBlank();
            }

            return false;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取完整的工作空间路径
     */
    public String getWorkspacePath() {
        String path = agents.getDefaults().getWorkspace();
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
            return java.nio.file.Paths.get(path).toAbsolutePath().normalize().toString();
        }
        // 相对路径：从 classpath 推断项目根目录（target/classes 的父目录的父目录）
        if (!java.nio.file.Paths.get(path).isAbsolute()) {
            try {
                java.net.URL classUrl = Config.class.getProtectionDomain().getCodeSource().getLocation();
                java.nio.file.Path classesDir = java.nio.file.Paths.get(classUrl.toURI());
                // classesDir = .../target/classes → 向上两级 = 项目根
                java.nio.file.Path projectRoot = classesDir.getParent().getParent();
                return projectRoot.resolve(path).normalize().toString();
            } catch (Exception e) {
                // fallback: 使用 user.dir
                return java.nio.file.Paths.get(path).toAbsolutePath().normalize().toString();
            }
        }
        return path;
    }

    /**
     * 验证配置
     *
     * @return 验证错误列表
     */
    public java.util.List<String> validate() {
        java.util.List<String> errors = new java.util.ArrayList<>();

        // 检查必要的配置
        if (agents == null) {
            errors.add("agents configuration is required");
        }
        if (providers == null) {
            errors.add("providers configuration is required");
        }

        // 检查 API 密钥（至少需要一个）
        boolean hasApiKey = false;
        if (providers != null) {
            hasApiKey = providers.getAnthropic().isConfigured() ||
                        providers.getOpenai().isConfigured() ||
                        providers.getOpenrouter().isConfigured() ||
                        providers.getDeepseek().isConfigured();
        }

        if (!hasApiKey) {
            errors.add("At least one LLM provider must be configured with an API key");
        }

        return errors;
    }

    /**
     * 创建默认配置
     */
    public static Config createDefault() {
        return new Config();
    }

    // ==================== Skills 配置类 ====================

    /**
     * Skills 配置
     */
    @Data
    public static class SkillsConfig {

        /** 技能搜索路径列表 */
        private java.util.List<String> paths = new java.util.ArrayList<>();

        /** 是否启用自动触发 */
        private boolean autoTrigger = true;

        /** 匹配阈值 (0-1) */
        private double matchThreshold = 0.3;
    }

    // ==================== Hooks 配置类 ====================

    /**
     * Hooks 配置
     */
    @Data
    public static class HooksConfig {

        /** 是否启用钩子系统 */
        private boolean enabled = true;

        /** 钩子列表 */
        private java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
    }

    // ==================== 通道 ACL 配置类 ====================

    /**
     * 通道级访问控制配置 — 参考 Nanobot 的 allowFrom 设计
     */
    @Data
    public static class ChannelAclConfig {

        /** 通道 ACL 映射：channelName → ChannelConfig */
        private java.util.Map<String, ChannelConfig> channels = new java.util.HashMap<>();

        /**
         * 检查指定通道是否允许指定用户访问
         * 空 allowFrom = 拒绝所有（secure by default）
         */
        public boolean isAllowed(String channelName, String userId) {
            if (channels.isEmpty()) {
                return true; // 未配置 ACL 则全部放行
            }
            ChannelConfig cfg = channels.get(channelName);
            if (cfg == null || !cfg.isEnabled()) {
                return false;
            }
            if (cfg.getAllowFrom().isEmpty()) {
                return false; // 空列表 = 拒绝所有
            }
            return cfg.getAllowFrom().contains(userId);
        }
    }

    /**
     * 单个通道的访问配置
     */
    @Data
    public static class ChannelConfig {

        /** 是否启用此通道 */
        private boolean enabled = true;

        /** 允许的用户/ID 列表 */
        private java.util.List<String> allowFrom = new java.util.ArrayList<>();
    }
}
