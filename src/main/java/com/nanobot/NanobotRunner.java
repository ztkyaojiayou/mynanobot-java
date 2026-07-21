package com.nanobot;

import com.nanobot.bus.MessageBus;
import com.nanobot.config.Config;
import com.nanobot.config.ConfigLoader;
import com.nanobot.core.AgentLoop;
import com.nanobot.mcp.MCPManager;
import com.nanobot.memory.Dream;
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
 * Nanobot 核心启动器 — 整个 Agent 系统的"组装车间"。
 *
 * <h2>定位</h2>
 * 本类是整个项目的<b>组件编排中心</b>。它不实现任何业务逻辑，
 * 而是负责按正确的顺序创建、注入、启动和关闭所有核心组件。
 * 可以把它理解为 Spring 容器的"Agent 专用初始化器"。
 *
 * <h2>为什么需要这个类？</h2>
 * Spring Boot 通过 {@code @Bean} 创建了所有组件实例（见 {@code NanobotConfig}），
 * 但有些初始化操作不适合放在 {@code @Bean} 方法里：
 * <ul>
 *   <li><b>有顺序依赖</b>：必须先加载 Config，才能初始化 Provider/Dream</li>
 *   <li><b>需要运行时注入</b>：Dream 需要在 AgentLoop 创建后动态注入</li>
 *   <li><b>需要优雅关闭</b>：JVM 退出时要按正确顺序关闭组件</li>
 * </ul>
 * {@code ApplicationRunner.run()} 在 Spring 容器就绪后执行，
 * 此时所有 {@code @Bean} 已创建、{@code @Autowired} 注入已完成，
 * 是执行这些"二次编排"的最佳时机。
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
 * <h2>初始化顺序（run() 方法）</h2>
 * <ol>
 *   <li>{@code initConfig()}         — 加载配置（最先，其他都依赖它）</li>
 *   <li>{@code initIdentity()}       — 加载身份文件（SOUL/IDENTITY/USER）</li>
 *   <li>{@code initRules()}          — 加载规则（编码规范、安全约束）</li>
 *   <li>{@code initSkills()}         — 加载技能（可复用的专业提示词）</li>
 *   <li>{@code initProvider()}       — 创建 LLM 提供者（策略工厂匹配模型）</li>
 *   <li>{@code initDream()}          — 初始化长期记忆引擎（需要 provider 调 LLM）</li>
 *   <li>注入 Dream → AgentLoop       — 让状态机在 BUILD/SAVE 状态中自动使用记忆</li>
 * </ol>
 *
 * <h2>为什么用 static 字段 + @Autowired setter？</h2>
 * Controller（如 ChatController）和其他非 Spring 管理的类需要访问核心组件。
 * 通过 static getter 暴露组件，避免到处 {@code @Autowired}。
 * 这本质是一个"服务定位器"（Service Locator）模式。
 *
 * @see com.nanobot.v2.NanobotConfig  Spring Bean 定义
 * @see com.nanobot.core.AgentLoop   状态机引擎
 */
@Component
public class NanobotRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(NanobotRunner.class);

    // ═══════════════════════════════════════════════════════════════
    // 核心组件（static 静态持有 → 通过 getter 暴露给整个应用）
    // ═══════════════════════════════════════════════════════════════

    /** 消息总线 — 系统的中枢神经。所有入口(CLI/HTTP/WS)通过它发消息给 AgentLoop */
    private static MessageBus messageBus;

    /** 工具注册中心 — 管理 17+ 内置工具 + MCP 动态工具。LLM 调用的每一个 tool 都从这里查找 */
    private static ToolRegistry toolRegistry;

    /** 会话管理器 — 业务层，管理会话的 CRUD、锁、并发控制 */
    private static SessionManager sessionManager;

    /** Agent 状态机引擎 — 核心！7 状态循环(RESTORE→...→RESPOND)，每条用户消息走一遍 */
    private static AgentLoop agentLoop;

    /** MCP 管理器 — 管理外部 MCP 服务器的生命周期，将其工具注册到 ToolRegistry */
    private static MCPManager mcpManager;

    /** 长期记忆引擎 — 自动从对话中提取关键信息、持久化到 MEMORY.md、注入 System Prompt */
    private static Dream dream;

    /** 全局配置 — config.yaml + secret.yaml 合并后的完整配置 */
    private static Config config;

    /** 规则管理器 — 加载 Rules 目录的编码/安全规范，注入 System Prompt */
    private static RuleManager ruleManager;

    /** 技能管理器 — 加载 Skills 目录的可复用提示词，LLM 可自主决定调用 */
    private static SkillManager skillManager;

    /** 身份管理器 — 加载 SOUL/IDENTITY/USER，定义 Agent 的"人格" */
    private static IdentityManager identityManager;

    /** LLM 提供者 — 封装 DeepSeek/OpenAI API 调用（同步+流式） */
    private static LLMProvider provider;

    // ═══════════════════════════════════════════════════════════════
    // Spring Bean 注入（@Autowired setter → static 字段）
    // 这些 Bean 在 NanobotConfig 中定义，Spring 容器创建后注入到这里
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

    // ═══════════════════════════════════════════════════════════════
    // 启动入口（Spring Boot 容器就绪后自动调用）
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void run(ApplicationArguments args) throws Exception {
        logger.info("Initializing Nanobot components...");

        // ── 第1步：加载配置（最优先，后续所有组件都依赖它）──
        config = initConfig();

        // ── 第2-4步：加载"上下文注入三件套"（不依赖 LLM，可以提前）──
        identityManager = initIdentity();   // Agent 人格: SOUL.md + IDENTITY.md + USER.md
        ruleManager = initRules();          // 约束规则: 编码规范、安全红线
        skillManager = initSkills();        // 可复用技能: 代码审查、部署验证等

        // ── 第5步：创建 LLM 提供者 ──
        // ProviderFactory 根据 config.model 自动匹配: deepseek* → DeepSeek, gpt-* → OpenAI
        provider = initProvider();

        // ── 第6步：初始化长期记忆引擎 ──
        // 需要 provider（内部可能调 LLM），所以放在 provider 之后
        dream = initDream();

        // ── 第7步：将 Dream 注入 AgentLoop ──
        // AgentLoop 是 Spring Bean（已在 NanobotConfig 中创建），
        // 但 Dream 是这里手动创建的，需要"补注入"进去
        if (agentLoop != null && dream != null) {
            agentLoop.setDream(dream);
            logger.info("Dream injected into AgentLoop");
        }

        // ── 验证 + 注册钩子 ──
        verifySpringBeans();
        registerShutdownHook();

        logger.info("Nanobot components initialized successfully");
        logger.info("Model: {}", config.getAgents().getDefaults().getModel());
    }

    // ═══════════════════════════════════════════════════════════════
    // 组件初始化方法（按依赖顺序排列）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 加载并校验配置。
     *
     * ConfigLoader 会合并 config.yaml（默认配置）和 secret.yaml（API Key），
     * 然后尝试多个路径查找这两个文件（项目目录、当前目录、classpath）。
     * 校验不通过直接抛异常，阻止系统在错误配置下启动。
     */
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

    /**
     * 加载 Agent 身份。
     *
     * 三个文件（优先级从高到低）：
     * SOUL.md → 核心性格、价值观、说话风格
     * IDENTITY.md → 角色定位、能力边界
     * USER.md → 用户偏好、个人习惯
     *
     * BuildState 会将它们注入 System Prompt，让 LLM "扮演"这个身份。
     */
    private IdentityManager initIdentity() {
        var m = new IdentityManager(config);
        m.load();
        return m;
    }

    /**
     * 加载规则系统。
     *
     * Rules 是<b>强制约束</b>（与 Skills 的"可选性"不同）。
     * 例如："4空格缩进""禁止吞异常""提交前必须 mvn test 通过"。
     * 规则内容在 BuildState 中注入 System Prompt。
     */
    private RuleManager initRules() {
        var m = new RuleManager(config);
        m.loadRules();
        logger.info("Loaded {} rules", m.getRegistry().size());
        return m;
    }

    /**
     * 加载技能系统。
     *
     * Skills 是<b>可选的专业提示词包</b>，LLM 根据上下文自主决定是否调用。
     * 例如：代码审查技能(code-review)、部署验证技能(deploy-verify)。
     * 与 Rules 的关键区别：Rules 是"必须遵守"，Skills 是"需要时使用"。
     */
    private SkillManager initSkills() {
        var m = new SkillManager(config);
        m.loadSkills();
        logger.info("Loaded {} skills", m.getRegistry().size());
        return m;
    }

    /**
     * 创建 LLM 提供者。
     *
     * ProviderFactory 是策略工厂：
     * model 以 "deepseek" 开头 → DeepSeekProvider
     * model 以 "gpt-" / "o1" / "o3" / "o4" 开头 → OpenAIProvider
     * 其他 → OpenAIProvider（兜底）
     *
     * API Key 来源优先级：secret.yaml > 环境变量
     */
    private LLMProvider initProvider() {
        return new ProviderFactory().create(config);
    }

    /**
     * 初始化长期记忆引擎。
     *
     * Dream 的工作流程（全自动闭环）：
     * 1. SAVE 状态 → 异步调用 extractAndStore()
     * 2. 增量节流：对话新增不足 5000 字符 → 跳过（省 LLM 费用）
     * 3. 达到阈值 → 调 LLM 提取关键信息 → 解析 JSON → 持久化到 MEMORY.md
     * 4. BUILD 状态 → 根据用户消息检索相关记忆 → 注入 System Prompt
     *
     * 记忆文件位置：.nanobot/memory/MEMORY.md
     */
    private Dream initDream() {
        int maxMemories = config.getMemory().getDream().getMaxMemories();
        Path memoryDir = Paths.get(".nanobot", "memory").toAbsolutePath().normalize();
        Dream d = new Dream(provider, maxMemories, memoryDir);
        d.loadFromMemoryFile(memoryDir);  // 恢复之前持久化的记忆
        return d;
    }

    /**
     * 验证 Spring 注入的关键 Bean 是否到位。
     * 如果不幸某个 Bean 没创建成功，至少日志里能看到。
     */
    private void verifySpringBeans() {
        logger.info("AgentLoop injected from Spring: {}", agentLoop != null ? "OK" : "MISSING");
        logger.info("MessageBus injected from Spring: {}", messageBus != null ? "OK" : "MISSING");
    }

    /**
     * 注册 JVM 关闭钩子。
     * 用户 Ctrl+C 或 kill 时，按<b>正确顺序</b>关闭组件，避免数据丢失。
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down Nanobot...");
            shutdown();
        }));
    }

    /**
     * 注册所有内置工具到 ToolRegistry。
     *
     * 注意：这个方法由 NanobotConfig.registerTools() 调用（Spring 启动阶段），
     * 不是 run() 调用。之所以放在这里而非 NanobotConfig，
     * 是因为需要 config 引用（Shell/Web 工具的可配置开关）。
     */
    private void registerTools() {
        // ── 文件工具 ──
        // 路径安全检查由 ToolRegistry 中的 PathGuard 统一拦截，
        // 每个工具不需要单独做路径校验
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new EditFileTool());
        toolRegistry.register(new ListDirTool());

        // ── 搜索工具 ──
        toolRegistry.register(new GlobTool());   // 文件名匹配: **/*.java
        toolRegistry.register(new GrepTool());   // 内容搜索: pattern → files

        // ── Shell 工具（可配置关闭）──
        // 生产环境如果不希望 Agent 执行任意命令，在 config.yaml 中设置 tools.exec.enable: false
        if (config.getTools().getExec().isEnable()) {
            toolRegistry.register(new ExecTool());
        }

        // ── Web 工具（可配置关闭）──
        // 支持多种搜索引擎: baidu(bge-m3)、bing、brave
        if (config.getTools().getWeb().isEnable()) {
            String searchProvider = config.getTools().getWeb().getSearch().getProvider();
            String searchApiKey = config.getTools().getWeb().getSearch().getActiveApiKey();
            toolRegistry.register(new WebSearchTool(searchProvider, searchApiKey));
        }

        // ── MCP 工具（外部进程/服务提供的工具）──
        registerMCPTools();

        logger.info("Registered {} tools: {}",
                   toolRegistry.size(),
                   String.join(", ", toolRegistry.getToolNames()));
    }

    /**
     * 注册 MCP（Model Context Protocol）工具。
     *
     * MCP 允许外部进程（如 git-mcp）或远程服务提供工具，
     * Nanobot 自动发现并注册为 mcp_{server}_{tool} 格式的工具。
     *
     * 配置方式：config.yaml 中 mcp_servers 段定义服务器列表。
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
     * 关闭所有组件（优雅停机）。
     *
     * 关闭顺序很重要：
     * 1. MCP 管理器 — 先断开外部连接
     * 2. AgentLoop — 停止接收新消息
     * 3. MessageBus — 排空队列，等待进行中的消息处理完
     * 4. ToolRegistry — 释放工具资源
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

    // ═══════════════════════════════════════════════════════════════
    // 静态 Getter（服务定位器模式 — 供 Controller 等外部类使用）
    // ═══════════════════════════════════════════════════════════════

    /** @return 消息总线 — 发送/接收消息的唯一入口 */
    public static MessageBus getMessageBus() { return messageBus; }

    /** @return 工具注册中心 — 查询/执行工具 */
    public static ToolRegistry getToolRegistry() { return toolRegistry; }

    /** @return 会话管理器 — 会话 CRUD */
    public static SessionManager getSessionManager() { return sessionManager; }

    /** @return Agent 状态机引擎 — 处理消息的核心 */
    public static AgentLoop getAgentLoop() { return agentLoop; }

    /** @return 全局配置 */
    public static Config getConfig() { return config; }

    /** @return 规则管理器 */
    public static RuleManager getRuleManager() { return ruleManager; }

    /** @return 技能管理器 */
    public static SkillManager getSkillManager() { return skillManager; }

    /** @return 身份管理器 */
    public static IdentityManager getIdentityManager() { return identityManager; }

    /** @return LLM 提供者 */
    public static LLMProvider getProvider() { return provider; }
}
