package com.nanocode.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置加载器 - 配置文件的读取和管理
 * =====================================
 * 
 * 本类负责从各种来源加载配置：
 * 1. YAML 配置文件
 * 2. JSON 配置文件
 * 3. 环境变量
 * 4. 默认值
 * 
 * **设计思想**：
 * 
 * 1. **多格式支持**：
 *    - 同时支持 JSON 和 YAML
 *    - 自动检测文件格式
 *    - 提供统一的加载接口
 * 
 * 2. **配置合并**：
 *    - 支持多层配置合并
 *    - 后加载的配置覆盖先加载的
 *    - 环境变量最高优先级
 * 
 * 3. **热重载**：
 *    - 支持配置热重载
 *    - 监控配置文件变化
 *    - 增量更新配置
 * 
 * **配置加载顺序**：
 * 
 * 1. 类路径中的默认配置（如果存在）
 * 2. 用户配置文件（config.yaml 或 config.json）
 * 3. 环境变量覆盖
 * 
 * **使用示例**：
 * 
 * ```java
 * // 1. 加载默认配置
 * Config config = ConfigLoader.load();
 * 
 * // 2. 从指定路径加载
 * Config config = ConfigLoader.load(Paths.get("/path/to/config.yaml"));
 * 
 * // 3. 加载并验证
 * Config config = ConfigLoader.load();
 * List<String> errors = config.validate();
 * if (!errors.isEmpty()) {
 *     errors.forEach(System.err::println);
 *     System.exit(1);
 * }
 * 
 * // 4. 保存配置
 * ConfigLoader.save(config, Paths.get("/path/to/config.yaml"));
 * 
 * // 5. 从输入流加载
 * try (InputStream is = ...) {
 *     Config config = ConfigLoader.load(is);
 * }
 * ```
 */
public class ConfigLoader {
    
    // ==================== 日志 ====================
    
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    
    // ==================== 默认配置位置 ====================
    
    /** 默认配置文件名 */
    private static final String DEFAULT_CONFIG_FILE = "config.yaml";
    
    /** 用户配置目录（品牌升级后为 .nanocode，旧 .nanobot 作 fallback） */
    private static final String USER_CONFIG_DIR = ".nanocode";
    
    /** 类路径中的默认配置 */
    private static final String CLASSPATH_DEFAULT_CONFIG = "config/default.yaml";
    
    /** 类路径中的主配置 */
    private static final String CLASSPATH_CONFIG = "config/config.yaml";
    
    // ==================== 配置缓存 ====================
    
    /** 已加载的配置缓存 */
    private static final Map<String, Config> configCache = new ConcurrentHashMap<>();
    
    // ==================== ObjectMapper 缓存 ====================
    
    /** JSON ObjectMapper */
    private static final ObjectMapper jsonMapper;
    
    /** YAML ObjectMapper */
    private static final ObjectMapper yamlMapper;
    
    static {
        // 初始化 JSON ObjectMapper
        jsonMapper = new ObjectMapper();
        jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        jsonMapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        // 支持注解如 @JsonProperty
        jsonMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        
        // 初始化 YAML ObjectMapper
        YAMLFactory yamlFactory = new YAMLFactory();
        yamlFactory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        yamlMapper = new ObjectMapper(yamlFactory);
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 优先使用字段上的 @JsonProperty 注解（而非 getter 方法名）
        yamlMapper.setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
                com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);
        yamlMapper.setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.GETTER,
                com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE);
        yamlMapper.setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.IS_GETTER,
                com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE);
    }
    
    // ==================== 加载方法 ====================
    
    /**
     * 加载默认配置
     * 
     * 搜索顺序：
     * 1. 用户目录 ~/.nanobot/config.yaml
     * 2. 当前目录 config.yaml
     * 3. 类路径中的 config/config.yaml
     * 4. 类路径中的默认配置
     * 
     * @return 加载的配置
     */
    /**
     * 统一加载链：workspace/.nanobot/ > ~/.nanobot/ > cwd > classpath
     *
     * 每个来源的 config.yaml 找到后，通过 mergeSecretKeys 合并密钥，
     * 再通过 mergeWithDefaults 补全缺失字段。
     */
    public static Config load() {
        // ① workspace/.nanocode/config.yaml（项目专属配置，最高优先级；兼容旧 .nanobot）
        String ws = NanoCodeEnv.getProperty("nanocode.workspace", "nanobot.workspace");
        if (ws != null && !ws.isBlank()) {
            Path wsConfig = NanoCodeEnv.resolveRuntimeDir(ws).resolve("config.yaml");
            if (Files.exists(wsConfig)) {
                logger.info("Loading from workspace: {}", wsConfig);
                return load(wsConfig);
            }
        }

        // ② ~/.nanocode/config.yaml（用户全局配置；兼容旧 ~/.nanobot）
        Path userConfig = getUserConfigPath();
        if (Files.exists(userConfig)) {
            logger.info("Loading from user home: {}", userConfig);
            return load(userConfig);
        }

        // ③ ./config.yaml（cwd — 开发/命令行兼容）
        Path localConfig = Paths.get(DEFAULT_CONFIG_FILE);
        if (Files.exists(localConfig)) {
            logger.info("Loading from cwd: {}", localConfig.toAbsolutePath());
            return load(localConfig);
        }

        // ④ classpath:config/config.yaml（jar 内置默认）
        Config base = null;
        try (InputStream is = ConfigLoader.class.getClassLoader()
                .getResourceAsStream(CLASSPATH_CONFIG)) {
            if (is != null) {
                logger.info("Loading from classpath: {}", CLASSPATH_CONFIG);
                base = load(is);
            }
        } catch (IOException e) {
            logger.warn("Failed to load classpath config", e);
        }
        if (base == null) {
            logger.info("No config found, using defaults");
            base = Config.createDefault();
        } else {
            // classpath 配置也需合并密钥
            mergeSecretKeys(null, base);
        }

        // 应用环境变量覆盖
        base = applyEnvironmentOverrides(base);
        configCache.put("_effective", base);
        return base;
    }

    /**
     * 从文件加载配置
     * 
     * @param path 配置文件路径
     * @return 加载的配置
     */
    public static Config load(Path path) {
        logger.info("Loading configuration from: {}", path);

        try {
            String content = Files.readString(path);
            Config config = parse(content, path.toString());

            // 合并 classpath 默认配置（补全 partial config 中缺失的值）
            Config defaults = loadClasspathDefaults();
            if (defaults != null) {
                config = mergeWithDefaults(config, defaults);
                logger.info("Merged partial config with classpath defaults, model={}",
                        config.getAgents().getDefaults().getModel());
            } else {
                logger.warn("No classpath defaults found, using partial config as-is");
            }

            // 合并 secret.yaml（独立管理的 API Key 文件，不提交 Git）
            // 相对路径可能 getParent()=null → 用绝对路径确保能找到同目录的 secret.yaml
            Path configDir = path.getParent();
            if (configDir == null) configDir = path.toAbsolutePath().getParent();
            mergeSecretKeys(configDir, config);

            // 应用环境变量覆盖
            config = applyEnvironmentOverrides(config);

            // 缓存配置
            configCache.put(path.toString(), config);

            return config;
        } catch (IOException e) {
            logger.error("Failed to load configuration from {}", path, e);
            throw new RuntimeException("Failed to load configuration", e);
        }
    }

    /**
     * 密钥优先级：环境变量 > workspace/.nanobot/ > ~/.nanobot/ > 文件目录 > classpath
     */
    private static void mergeSecretKeys(Path fileDir, Config config) {
        // ① 环境变量（最高优先级）
        if (applyEnvApiKeys(config)) return;

        // ② workspace/.nanobot/secret.yaml
        if (applyWorkspaceSecret(config)) return;

        // ③ ~/.nanocode/secret.yaml（用户全局；兼容旧 ~/.nanobot）
        Path globalDir = NanoCodeEnv.resolveRuntimeDir(System.getProperty("user.home", "."), USER_CONFIG_DIR);
        Path globalSecret = globalDir.resolve("secret.yaml");
        if (Files.exists(globalSecret)) { mergeFromFile(globalSecret, config); return; }

        // ④ config.yaml 所在目录（fileDir 可能为 null）
        if (fileDir != null) {
            Path f = fileDir.resolve("secret.yaml");
            if (Files.exists(f)) { mergeFromFile(f, config); return; }
        }

        // ⑤ classpath 内置 secret.yaml（默认空模板）
        try (InputStream is = ConfigLoader.class.getClassLoader()
                .getResourceAsStream("config/secret.yaml")) {
            if (is != null) {
                applySecretKeys(load(is), config);
            }
        } catch (IOException ignored) {}
    }

    private static boolean applyEnvApiKeys(Config config) {
        boolean found = false;
        String deepseekKey = System.getenv("DEEPSEEK_API_KEY");
        if (deepseekKey != null && !deepseekKey.isBlank()) {
            config.getProviders().getDeepseek().setApiKey(deepseekKey);
            logger.info("Using API key from DEEPSEEK_API_KEY env");
            found = true;
        }
        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isBlank()) {
            config.getProviders().getOpenai().setApiKey(openaiKey);
            logger.info("Using API key from OPENAI_API_KEY env");
            found = true;
        }
        // 兼容：NANOCODE_API_KEY 优先，旧 NANOBOT_API_KEY fallback
        String nanoKey = NanoCodeEnv.getEnv("NANOCODE_API_KEY", "NANOBOT_API_KEY");
        if (nanoKey != null && !nanoKey.isBlank()) {
            config.getProviders().getDeepseek().setApiKey(nanoKey);
            logger.info("Using API key from NANOCODE_API_KEY/NANOBOT_API_KEY env");
            found = true;
        }
        return found;
    }

    private static boolean applyWorkspaceSecret(Config config) {
        String ws = NanoCodeEnv.getProperty("nanocode.workspace", "nanobot.workspace");
        if (ws == null || ws.isBlank()) return false;
        Path f = NanoCodeEnv.resolveRuntimeDir(ws).resolve("secret.yaml");
        if (Files.exists(f)) { mergeFromFile(f, config); return true; }
        return false;
    }

    private static void mergeFromFile(Path file, Config config) {
        try {
            Config secret = parse(Files.readString(file), file.toString());
            applySecretKeys(secret, config);
            logger.debug("Merged API keys from {}", file);
        } catch (IOException e) {
            logger.warn("Failed to read {}: {}", file, e.getMessage());
        }
    }

    private static void applySecretKeys(Config secret, Config config) {
        // LLM Provider keys
        var sp = secret.getProviders();
        var cp = config.getProviders();
        if (sp.getDeepseek().isConfigured()) cp.getDeepseek().setApiKey(sp.getDeepseek().getApiKey());
        if (sp.getOpenai().isConfigured()) cp.getOpenai().setApiKey(sp.getOpenai().getApiKey());

        // Web search keys（per-provider + 通用回退）
        if (secret.getTools() != null && secret.getTools().getWeb() != null) {
            var ss = secret.getTools().getWeb().getSearch();
            var cs = config.getTools().getWeb().getSearch();
            if (ss.getBaiduKey() != null && !ss.getBaiduKey().isBlank()) cs.setBaiduKey(ss.getBaiduKey());
            if (ss.getBingKey() != null && !ss.getBingKey().isBlank()) cs.setBingKey(ss.getBingKey());
            if (ss.getBraveKey() != null && !ss.getBraveKey().isBlank()) cs.setBraveKey(ss.getBraveKey());
            if (ss.getApiKey() != null && !ss.getApiKey().isBlank()) cs.setApiKey(ss.getApiKey());
        }
    }
    
    /**
     * 从输入流加载配置
     * 
     * @param inputStream 配置内容输入流
     * @return 加载的配置
     */
    public static Config load(InputStream inputStream) {
        try {
            String content = new String(inputStream.readAllBytes());
            return parse(content, "input-stream");
        } catch (IOException e) {
            logger.error("Failed to load configuration from input stream", e);
            throw new RuntimeException("Failed to load configuration", e);
        }
    }
    
    /**
     * 从字符串加载配置
     * 
     * @param content 配置内容
     * @return 加载的配置
     */
    public static Config loadFromString(String content) {
        return parse(content, "string");
    }
    
    /**
     * 解析配置内容
     * 
     * 自动检测格式（JSON 或 YAML）。
     * 
     * @param content 配置内容
     * @param source 来源标识（用于日志）
     * @return 解析后的配置
     */
    public static Config parse(String content, String source) {
        if (content == null || content.isBlank()) {
            return Config.createDefault();
        }
        
        String trimmed = content.trim();
        
        try {
            if (trimmed.startsWith("{")) {
                // JSON 格式
                return jsonMapper.readValue(trimmed, Config.class);
            } else {
                // YAML 格式
                return yamlMapper.readValue(trimmed, Config.class);
            }
        } catch (IOException e) {
            logger.error("Failed to parse configuration from {}", source, e);
            throw new RuntimeException("Failed to parse configuration: " + e.getMessage(), e);
        }
    }
    
    // ==================== 保存方法 ====================
    
    /**
     * 保存配置到文件
     * 
     * @param config 要保存的配置
     * @param path 目标文件路径
     */
    public static void save(Config config, Path path) {
        save(config, path, path.toString().endsWith(".json"));
    }
    
    /**
     * 保存配置到文件
     * 
     * @param config 要保存的配置
     * @param path 目标文件路径
     * @param asJson 是否保存为 JSON 格式（否则为 YAML）
     */
    public static void save(Config config, Path path, boolean asJson) {
        logger.info("Saving configuration to: {}", path);
        
        try {
            // 确保父目录存在
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            
            ObjectMapper mapper = asJson ? jsonMapper : yamlMapper;
            String content = mapper.writeValueAsString(config);
            
            Files.writeString(path, content);
            logger.info("Configuration saved successfully");
            
            // 更新缓存
            configCache.put(path.toString(), config);
            
        } catch (IOException e) {
            logger.error("Failed to save configuration to {}", path, e);
            throw new RuntimeException("Failed to save configuration", e);
        }
    }
    
    /**
     * 保存配置到输出流
     * 
     * @param config 要保存的配置
     * @param outputStream 目标输出流
     * @param asJson 是否保存为 JSON 格式
     */
    public static void save(Config config, OutputStream outputStream, boolean asJson) {
        try {
            ObjectMapper mapper = asJson ? jsonMapper : yamlMapper;
            mapper.writeValue(outputStream, config);
        } catch (IOException e) {
            logger.error("Failed to save configuration to output stream", e);
            throw new RuntimeException("Failed to save configuration", e);
        }
    }
    
    // ==================== 环境变量覆盖 ====================
    
    /**
     * 应用环境变量覆盖
     * 
     * 环境变量格式：
     * - NANOCODE_AGENTS__DEFAULTS__MODEL=anthropic/claude-opus-4
     * - NANOCODE_PROVIDERS__ANTHROPIC__API_KEY=sk-xxx
     * 
     * 使用双下划线（__）表示嵌套结构。
     * 
     * @param config 原始配置
     * @return 应用环境变量后的配置
     */
    public static Config applyEnvironmentOverrides(Config config) {
        // 这是简化实现
        // 完整的实现需要反射或表达式语言
        
        // 示例：检查 NANOCODE_MODEL 环境变量（兼容旧 NANOBOT_MODEL）
        String modelEnv = NanoCodeEnv.getEnv("NANOCODE_MODEL", "NANOBOT_MODEL");
        if (modelEnv != null && !modelEnv.isBlank()) {
            config.getAgents().getDefaults().setModel(modelEnv);
            logger.info("Model overridden by environment: {}", modelEnv);
        }

        // 示例：检查 NANOCODE_API_KEY 环境变量（兼容旧 NANOBOT_API_KEY）
        String apiKeyEnv = NanoCodeEnv.getEnv("NANOCODE_API_KEY", "NANOBOT_API_KEY");
        if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
            config.getProviders().getAnthropic().setApiKey(apiKeyEnv);
            logger.info("API key overridden by environment");
        }
        
        return config;
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 获取用户配置路径
     * 
     * @return 用户配置目录下的 config.yaml 路径
     */
    public static Path getUserConfigPath() {
        String home = System.getProperty("user.home");
        // 优先 ~/.nanocode，若不存在且 ~/.nanobot 存在则回退旧目录
        Path dir = NanoCodeEnv.resolveRuntimeDir(home, USER_CONFIG_DIR);
        return dir.resolve(DEFAULT_CONFIG_FILE);
    }
    
    /**
     * 获取配置缓存
     * 
     * @param path 配置路径
     * @return 缓存的配置，如果没有则返回 null
     */
    public static Config getCached(Path path) {
        return configCache.get(path.toString());
    }
    
    /**
     * 清除配置缓存
     */
    public static void clearCache() {
        configCache.clear();
        logger.info("Configuration cache cleared");
    }
    
    /**
     * 创建默认配置文件
     * 
     * 在指定位置创建一个包含默认值的配置文件。
     * 
     * @param path 配置文件路径
     */
    public static void createDefaultConfig(Path path) {
        Config config = Config.createDefault();
        save(config, path);
        logger.info("Created default configuration at: {}", path);
    }
    
    /**
     * 生成配置示例文件
     * 
     * 创建一个包含所有配置项及其注释的示例文件。
     * 
     * @param path 输出路径
     */
    /** 示例配置模板（YAML 格式） */
    private static final String EXAMPLE_CONFIG_YAML = """
            # NanoCode 配置文件
            # =====================
            #
            # 本文件是 NanoCode 的配置文件模板
            # 复制到 ~/.nanobot/config.yaml 并修改相应值

            # Agent 配置
            agents:
              defaults:
                # 工作空间目录
                workspace: "~/.nanobot"

                # 默认模型（支持格式：provider/model）
                model: "anthropic/claude-sonnet-4-20250514"

                # 提供商（设为 "auto" 自动检测）
                provider: "auto"

                # 最大输出 token 数
                maxTokens: 8192

                # 上下文窗口大小
                contextWindowTokens: 200000

                # 温度参数（0.0 - 2.0）
                temperature: 0.7

                # 最大工具迭代次数
                maxToolIterations: 100

                # 时区
                timezone: "UTC"

            # LLM 提供商配置
            providers:
              anthropic:
                # Anthropic API 密钥
                apiKey: "sk-ant-xxxxx"
                # 可选：自定义 API 端点
                # apiBase: "https://api.anthropic.com"

              openai:
                apiKey: ""
                apiBase: ""

              openrouter:
                apiKey: ""
                apiBase: "https://openrouter.ai/api/v1"

            # 工具配置
            tools:
              # 是否限制工具只能访问工作空间
              restrictToWorkspace: false

              exec:
                # 是否启用命令执行
                enable: true
                # 执行超时（秒）
                timeout: 60

              web:
                enable: true
                search:
                  provider: "duckduckgo"
                  maxResults: 5
                  timeout: 30

            # 内存配置
            memory:
              dream:
                # Dream 执行间隔（小时）
                intervalHours: 2
                # 每批最大条目数
                maxBatchSize: 20
            """;

    public static void generateExampleConfig(Path path) {
        try {
            Files.writeString(path, EXAMPLE_CONFIG_YAML);
            logger.info("Generated example configuration at: {}", path);
        } catch (IOException e) {
            logger.error("Failed to generate example configuration", e);
            throw new RuntimeException("Failed to generate example configuration", e);
        }
    }

    // ═══════════ 配置合并（partial config 补全默认值） ═══════════

    /** 从 classpath 加载默认配置（不应用文件系统覆盖） */
    private static Config loadClasspathDefaults() {
        try (InputStream is = ConfigLoader.class.getClassLoader()
                .getResourceAsStream(CLASSPATH_CONFIG)) {
            if (is != null) {
                String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                return parse(content, "classpath-defaults");
            }
        } catch (Exception e) {
            logger.debug("No classpath defaults to merge: {}", e.getMessage());
        }
        return null;
    }

    /** 将 partial 配置与默认值合并：partial 中的 null/empty 字段用 defaults 补全 */
    private static Config mergeWithDefaults(Config partial, Config defaults) {
        if (partial.getAgents() == null) partial.setAgents(defaults.getAgents());
        else if (partial.getAgents().getDefaults() == null)
            partial.getAgents().setDefaults(defaults.getAgents().getDefaults());

        if (partial.getProviders() == null || !hasAnyProvider(partial))
            partial.setProviders(defaults.getProviders());
        else mergeProviders(partial.getProviders(), defaults.getProviders());

        if (partial.getTools() == null) partial.setTools(defaults.getTools());
        if (partial.getMemory() == null) partial.setMemory(defaults.getMemory());
        if (partial.getChannels() == null) partial.setChannels(defaults.getChannels());
        if (partial.getHooks() == null) partial.setHooks(defaults.getHooks());

        logger.debug("Merged partial config with classpath defaults");
        return partial;
    }

    private static boolean hasAnyProvider(Config c) {
        var p = c.getProviders();
        return p.getDeepseek().isConfigured() || p.getOpenai().isConfigured()
                || p.getAnthropic().isConfigured() || p.getGroq().isConfigured()
                || p.getOllama().isConfigured();
    }

    private static void mergeProviders(Config.ProvidersConfig partial, Config.ProvidersConfig defaults) {
        if (!partial.getDeepseek().isConfigured()) partial.setDeepseek(defaults.getDeepseek());
        if (!partial.getOpenai().isConfigured()) partial.setOpenai(defaults.getOpenai());
        if (!partial.getAnthropic().isConfigured()) partial.setAnthropic(defaults.getAnthropic());
    }
}
