package com.nanobot.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

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
    
    // ==================== 构造函数 ====================
    
    public Config() {
        // 使用默认配置
    }
    
    public Config(AgentsConfig agents, ProvidersConfig providers, 
                  ChannelsConfig channels, ToolsConfig tools, MemoryConfig memory) {
        this.agents = agents != null ? agents : new AgentsConfig();
        this.providers = providers != null ? providers : new ProvidersConfig();
        this.channels = channels != null ? channels : new ChannelsConfig();
        this.tools = tools != null ? tools : new ToolsConfig();
        this.memory = memory != null ? memory : new MemoryConfig();
    }
    
    // ==================== Getter 和 Setter ====================
    
    public AgentsConfig getAgents() {
        return agents;
    }
    
    public void setAgents(AgentsConfig agents) {
        this.agents = agents;
    }
    
    public ProvidersConfig getProviders() {
        return providers;
    }
    
    public void setProviders(ProvidersConfig providers) {
        this.providers = providers;
    }
    
    public ChannelsConfig getChannels() {
        return channels;
    }
    
    public void setChannels(ChannelsConfig channels) {
        this.channels = channels;
    }
    
    public ToolsConfig getTools() {
        return tools;
    }
    
    public void setTools(ToolsConfig tools) {
        this.tools = tools;
    }
    
    public java.util.Map<String, MCPServerConfig> getMcpServers() {
        return mcpServers;
    }
    
    public void setMcpServers(java.util.Map<String, MCPServerConfig> mcpServers) {
        this.mcpServers = mcpServers;
    }
    
    public MemoryConfig getMemory() {
        return memory;
    }
    
    public void setMemory(MemoryConfig memory) {
        this.memory = memory;
    }
    
    // ==================== Agent 配置类 ====================
    
    /**
     * Agent 配置
     */
    public static class AgentsConfig {
        
        /** 默认 Agent 配置 */
        private AgentDefaults defaults = new AgentDefaults();
        
        public AgentDefaults getDefaults() {
            return defaults;
        }
        
        public void setDefaults(AgentDefaults defaults) {
            this.defaults = defaults;
        }
    }
    
    /**
     * Agent 默认配置
     */
    public static class AgentDefaults {
        
        /** 工作空间目录 */
        private String workspace = "~/.nanobot/workspace";
        
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
        
        /** 禁用技能列表 */
        @JsonProperty("disabledSkills")
        private java.util.List<String> disabledSkills = new java.util.ArrayList<>();
        
        // Getter 和 Setter
        public String getWorkspace() { return workspace; }
        public void setWorkspace(String workspace) { this.workspace = workspace; }
        
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        
        public int getContextWindowTokens() { return contextWindowTokens; }
        public void setContextWindowTokens(int contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }
        
        public float getTemperature() { return temperature; }
        public void setTemperature(float temperature) { this.temperature = temperature; }
        
        public int getMaxToolIterations() { return maxToolIterations; }
        public void setMaxToolIterations(int maxToolIterations) { this.maxToolIterations = maxToolIterations; }
        
        public int getMaxToolResultChars() { return maxToolResultChars; }
        public void setMaxToolResultChars(int maxToolResultChars) { this.maxToolResultChars = maxToolResultChars; }
        
        public int getToolHintMaxLength() { return toolHintMaxLength; }
        public void setToolHintMaxLength(int toolHintMaxLength) { this.toolHintMaxLength = toolHintMaxLength; }
        
        public String getReasoningEffort() { return reasoningEffort; }
        public void setReasoningEffort(String reasoningerfort) { this.reasoningEffort = reasoningerfort; }
        
        public String getTimezone() { return timezone; }
        public void setTimezone(String timezone) { this.timezone = timezone; }
        
        public boolean isUnifiedSession() { return unifiedSession; }
        public void setUnifiedSession(boolean unifiedSession) { this.unifiedSession = unifiedSession; }
        
        public java.util.List<String> getDisabledSkills() { return disabledSkills; }
        public void setDisabledSkills(java.util.List<String> disabledSkills) { this.disabledSkills = disabledSkills; }
    }
    
    // ==================== 提供商配置类 ====================
    
    /**
     * 提供商配置
     */
    public static class ProvidersConfig {
        
        private ProviderConfig anthropic = new ProviderConfig();
        private ProviderConfig openai = new ProviderConfig();
        private ProviderConfig openrouter = new ProviderConfig();
        private ProviderConfig deepseek = new ProviderConfig();
        private ProviderConfig groq = new ProviderConfig();
        private ProviderConfig ollama = new ProviderConfig();
        
        public ProviderConfig getAnthropic() { return anthropic; }
        public void setAnthropic(ProviderConfig anthropic) { this.anthropic = anthropic; }
        
        public ProviderConfig getOpenai() { return openai; }
        public void setOpenai(ProviderConfig openai) { this.openai = openai; }
        
        public ProviderConfig getOpenrouter() { return openrouter; }
        public void setOpenrouter(ProviderConfig openrouter) { this.openrouter = openrouter; }
        
        public ProviderConfig getDeepseek() { return deepseek; }
        public void setDeepseek(ProviderConfig deepseek) { this.deepseek = deepseek; }
        
        public ProviderConfig getGroq() { return groq; }
        public void setGroq(ProviderConfig groq) { this.groq = groq; }
        
        public ProviderConfig getOllama() { return ollama; }
        public void setOllama(ProviderConfig ollama) { this.ollama = ollama; }
    }
    
    /**
     * 提供商基础配置
     */
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
        
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        
        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }
        
        public java.util.Map<String, String> getExtraHeaders() { return extraHeaders; }
        public void setExtraHeaders(java.util.Map<String, String> extraHeaders) { this.extraHeaders = extraHeaders; }
        
        public java.util.Map<String, Object> getExtraBody() { return extraBody; }
        public void setExtraBody(java.util.Map<String, Object> extraBody) { this.extraBody = extraBody; }
        
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
        
        public boolean isSendProgress() { return sendProgress; }
        public void setSendProgress(boolean sendProgress) { this.sendProgress = sendProgress; }
        
        public boolean isSendToolHints() { return sendToolHints; }
        public void setSendToolHints(boolean sendToolHints) { this.sendToolHints = sendToolHints; }
        
        public int getSendMaxRetries() { return sendMaxRetries; }
        public void setSendMaxRetries(int sendMaxRetries) { this.sendMaxRetries = sendMaxRetries; }
        
        public String getTranscriptionProvider() { return transcriptionProvider; }
        public void setTranscriptionProvider(String transcriptionProvider) { this.transcriptionProvider = transcriptionProvider; }
        
        public JsonNode getWebsocket() { return websocket; }
        public void setWebsocket(JsonNode websocket) { this.websocket = websocket; }
        
        public JsonNode getTelegram() { return telegram; }
        public void setTelegram(JsonNode telegram) { this.telegram = telegram; }
        
        public JsonNode getDiscord() { return discord; }
        public void setDiscord(JsonNode discord) { this.discord = discord; }
    }
    
    // ==================== 工具配置类 ====================
    
    /**
     * 工具配置
     */
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
        
        public WebToolsConfig getWeb() { return web; }
        public void setWeb(WebToolsConfig web) { this.web = web; }
        
        public ExecToolConfig getExec() { return exec; }
        public void setExec(ExecToolConfig exec) { this.exec = exec; }
        
        public Map<String, MCPServerConfig> getMcpServers() { return mcpServers; }
        public void setMcpServers(Map<String, MCPServerConfig> mcpServers) { this.mcpServers = mcpServers; }
        
        public boolean isRestrictToWorkspace() { return restrictToWorkspace; }
        public void setRestrictToWorkspace(boolean restrictToWorkspace) { this.restrictToWorkspace = restrictToWorkspace; }
    }
    
    /**
     * Web 工具配置
     */
    public static class WebToolsConfig {
        
        /** 是否启用 */
        private boolean enable = true;
        
        /** 代理 URL */
        private String proxy = "";
        
        /** 用户代理 */
        private String userAgent = "";
        
        /** 搜索配置 */
        private SearchConfig search = new SearchConfig();
        
        public boolean isEnable() { return enable; }
        public void setEnable(boolean enable) { this.enable = enable; }
        
        public String getProxy() { return proxy; }
        public void setProxy(String proxy) { this.proxy = proxy; }
        
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        
        public SearchConfig getSearch() { return search; }
        public void setSearch(SearchConfig search) { this.search = search; }
    }
    
    /**
     * 搜索配置
     */
    public static class SearchConfig {
        
        /** 搜索提供商 */
        private String provider = "duckduckgo";
        
        /** API 密钥 */
        private String apiKey = "";
        
        /** 最大结果数 */
        private int maxResults = 5;
        
        /** 超时（秒） */
        private int timeout = 30;
        
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
    }
    
    /**
     * 执行工具配置
     */
    public static class ExecToolConfig {
        
        /** 是否启用 */
        private boolean enable = true;
        
        /** 执行超时（秒） */
        private int timeout = 60;
        
        /** 路径追加 */
        private String pathAppend = "";
        
        /** 沙箱类型（空表示不启用） */
        private String sandbox = "";
        
        public boolean isEnable() { return enable; }
        public void setEnable(boolean enable) { this.enable = enable; }
        
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
        
        public String getPathAppend() { return pathAppend; }
        public void setPathAppend(String pathAppend) { this.pathAppend = pathAppend; }
        
        public String getSandbox() { return sandbox; }
        public void setSandbox(String sandbox) { this.sandbox = sandbox; }
    }
    
    // ==================== 内存配置类 ====================
    
    /**
     * 内存配置
     */
    public static class MemoryConfig {
        
        /** Dream 配置 */
        private DreamConfig dream = new DreamConfig();
        
        public DreamConfig getDream() { return dream; }
        public void setDream(DreamConfig dream) { this.dream = dream; }
    }
    
    /**
     * Dream（记忆巩固）配置
     */
    public static class DreamConfig {
        
        /** 执行间隔（小时） */
        private int intervalHours = 2;
        
        /** 最大批次大小 */
        private int maxBatchSize = 20;
        
        /** 最大迭代次数 */
        private int maxIterations = 15;
        
        /** 是否启用行年龄注释 */
        private boolean annotateLineAges = true;
        
        public int getIntervalHours() { return intervalHours; }
        public void setIntervalHours(int intervalHours) { this.intervalHours = intervalHours; }
        
        public int getMaxBatchSize() { return maxBatchSize; }
        public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
        
        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
        
        public boolean isAnnotateLineAges() { return annotateLineAges; }
        public void setAnnotateLineAges(boolean annotateLineAges) { this.annotateLineAges = annotateLineAges; }
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
        
        // Getter 和 Setter
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        
        public java.util.List<String> getArgs() { return args; }
        public void setArgs(java.util.List<String> args) { this.args = args; }
        
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        
        public java.util.Map<String, String> getHeaders() { return headers; }
        public void setHeaders(java.util.Map<String, String> headers) { this.headers = headers; }
        
        public int getToolTimeout() { return toolTimeout; }
        public void setToolTimeout(int toolTimeout) { this.toolTimeout = toolTimeout; }
        
        public java.util.List<String> getEnabledTools() { return enabledTools; }
        public void setEnabledTools(java.util.List<String> enabledTools) { this.enabledTools = enabledTools; }
        
        public boolean isEnable() { return enable; }
        public void setEnable(boolean enable) { this.enable = enable; }
        
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
}
