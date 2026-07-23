package com.nanobot.v2;

import com.nanobot.config.Config;
import com.nanobot.config.ConfigLoader;
import com.nanobot.subagent.TaskStore;
import com.nanobot.subagent.AgentCoordinator;
import com.nanobot.identity.IdentityManager;
import com.nanobot.mcp.MCPManager;
import com.nanobot.memory.Consolidator;
import com.nanobot.memory.Dream;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.ProviderFactory;
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
 * Nanobot 核心配置 — 整个 Agent 系统的"组装蓝图".
 *
 * <h2>这个类在项目中的角色</h2>
 * 如果把 Nanobot 比作一辆车，这个类就是总装线：所有零件（Bean）在这里按依赖顺序创建、
 * 注入、启动，最终产出一台能跑的 Agent 引擎.
 *
 * <h2>⚠️ 关键认知：所有 Bean 都是全局单例</h2>
 * 这里创建的每一个组件都是 <b>应用级单例</b>——它们不是"每次对话新建一份"，
 * 而是"整个 JVM 生命周期只有一份". 对话过程中只是按需调用这些组件的方法.
 * 比如：
 * <ul>
 *   <li>{@code SessionManager} 全局只有一个，管理所有会话的读写</li>
 *   <li>{@code ToolRegistry} 全局只有一个，17+ 工具对所有对话可见</li>
 *   <li>{@code Dream} 全局只有一个，所有会话的记忆存在同一个 MEMORY.md</li>
 * </ul>
 *
 * <h2>启动时的一次性流程（Bean 创建顺序即依赖顺序）</h2>
 * <pre>
 *   Config
 *     +-- 安全三守卫: PathGuard / CommandGuard / NetworkGuard
 *     |     +-- PermissionManager (编排三守卫 + RuleEngine)
 *     |
 *     +-- 上下文注入三件套:
 *     |     +-- IdentityManager  -> SOUL.md / IDENTITY.md / USER.md
 *     |     +-- RuleManager      -> .nanobot/rules/*.md
 *     |     +-- SkillManager     -> .nanobot/skills/{name}/SKILL.md
 *     |
 *     +-- LLM 通道:
 *     |     +-- LLMProvider -> DeepSeek (HTTP + SSE streaming)
 *     |
 *     +-- 工具系统:
 *     |     +-- ToolRegistry -> 17+ built-in tools + MCP tools
 *     |     +-- AgentCoordinator -> sub-agent dispatch
 *     |     +-- MCPManager -> external MCP servers
 *     |
 *     +-- 记忆系统:
 *     |     +-- Consolidator -> conversation compaction
 *     |     +-- Dream -> long-term memory extraction/retrieval
 *     |     +-- SessionManager -> session persistence
 *     |
 *     +-- 核心引擎（独立配置类）:
 *           +-- AgentLoopConfig -> 8 状态 State 模式引擎 (see {@link com.nanobot.v2.AgentLoopConfig})
 * </pre>
 *
 * <h2>每次对话的运行时流程</h2>
 * <pre>
 * User sends message (CLI / HTTP / WebSocket)
 *   -> MessageBus.publishInbound(message)
 *     -> AgentLoop consumes
 *       -> RESTORE: SessionManager restores history
 *       -> COMPACT: Consolidator compacts if over token budget
 *       -> COMMAND: handle /stop /clear /compact /remember etc.
 *       -> BUILD: IdentityManager + Dream + NANOBOT.md + Rules -> System Prompt
 *       -> RUN: LLMProvider.chat() + ToolRegistry tool-call loop
 *       -> SAVE: SessionManager saves + Dream.extractAndStore() async
 *       -> RESPOND: MessageBus.publishOutbound(response)
 * </pre>
 *
 * @see com.nanobot.v2.AgentLoopConfig
 * @see com.nanobot.v2.MessageBusConfig
 * @see com.nanobot.NanobotRunner
 */
@Configuration
public class NanobotConfig {

    private static final Logger logger = LoggerFactory.getLogger(NanobotConfig.class);

    // ═══════════════════════════════════════════════════════════════════
    // 第 1 层：配置 + 安全
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 全局配置 — 一切 Bean 的"参数源头".
     *
     * <h3>加载优先级</h3>
     * {@code ~/.nanobot/config.yaml} → {@code ./config.yaml} → classpath 默认值.
     * ConfigLoader 自动合并 config.yaml（默认配置）和 secret.yaml（API Key）.
     *
     * <h3>启动即校验</h3>
     * 配置不合法直接抛异常，阻止系统在错误状态下启动.
     */
    @Bean
    public Config config() {
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
     * 路径守卫 — 所有文件工具（ReadFile / WriteFile / EditFile 等）的"栅栏".
     *
     * 强制所有文件操作必须在 workspace 目录内，防止 LLM 越权读取系统敏感文件.
     * 被 {@link PermissionManager} 编排调用，在每次工具执行前检查.
     */
    @Bean
    public PathGuard pathGuard(Config config) {
        String workspace = config.getWorkspacePath();
        return new PathGuard(workspace);
    }

    /**
     * 命令守卫 — Shell 执行的"黑名单".
     *
     * 内置 {@code rm -rf /}、{@code shutdown}、{@code format} 等危险命令拦截.
     */
    @Bean
    public CommandGuard commandGuard() {
        return CommandGuard.withDefaults();
    }

    /**
     * 网络守卫 — HTTP 请求的"防火墙".
     *
     * 禁止访问内网地址（192.168.x.x、10.x.x.x、localhost 敏感端口等）.
     */
    @Bean
    public NetworkGuard networkGuard() {
        return NetworkGuard.withDefaults();
    }

    @Bean
    public Config.ChannelAclConfig channelAcl(Config config) {
        return config.getChannels().getAcl();
    }

    /**
     * 权限编排器 — 工具调用的"安检入口".
     *
     * 三层管道（Guard → Mode → Rule）：
     * <ol>
     *   <li><b>Guard 层</b>：硬拦截，违反即拒绝（路径越界 / 危险命令 / 敏感网络）</li>
     *   <li><b>Mode 层</b>：4 种模式（PLAN 只读 / DEFAULT 确认 / ACCEPT_EDITS 编辑放行 / BYPASS 全放行）</li>
     *   <li><b>Rule 层</b>：基于工具名+参数的规则匹配（如"exec 工具需要确认"）</li>
     * </ol>
     *
     * 每次 LLM 调用工具前，ToolRegistry 都会走一遍这个管道.
     */
    @Bean
    public PermissionManager permissionManager(PathGuard pathGuard,
                                                CommandGuard commandGuard,
                                                NetworkGuard networkGuard) {
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

    // ═══════════════════════════════════════════════════════════════════
    // 第 2 层：工具系统 — LLM 的手和脚
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 工具注册中心 — LLM 能调用的所有工具的"黄页".
     *
     * 每次 LLM 调用时，BuildState 将 toolDefinitions 注入 System Prompt.
     * LLM 返回 tool_calls → AgentRunner 从这里查找 Tool 实例 → 执行.
     *
     * 内置工具清单（17+）：
     * <ul>
     *   <li>文件: ReadFile / WriteFile / EditFile / ListDir</li>
     *   <li>搜索: Glob / Grep</li>
     *   <li>Shell: Exec（可配置开关）</li>
     *   <li>Web: WebSearch（可配置开关）</li>
     *   <li>时间: GetCurrentTime（对抗模型训练数据日期偏差）</li>
     *   <li>交互: AskUser（LLM 可向用户提问）</li>
     *   <li>任务: TaskCreate / TaskList / TaskUpdate（LLM 自主分解追踪复杂任务）</li>
     *   <li>子Agent: SpawnTool / SpawnCheckTool（异步委派子 Agent）</li>
     *   <li>技能: UseSkillTool（一个工具管所有 Skill，避免 tools 数组膨胀）</li>
     *   <li>扩展: @ToolDef 注解扫描 + MCP 外部工具</li>
     * </ul>
     */
    @Bean
    public ToolRegistry toolRegistry(Config config, PermissionManager permissionManager,
                                      AgentCoordinator agentCoordinator,
                                      SkillManager skillManager) {
        ToolRegistry toolRegistry = new ToolRegistry();
        registerTools(toolRegistry, config, agentCoordinator, skillManager);
        toolRegistry.setPermissionManager(permissionManager);
        return toolRegistry;
    }

    private void registerTools(ToolRegistry toolRegistry, Config config,
                                AgentCoordinator agentCoordinator,
                                SkillManager skillManager) {
        // ── 文件工具 ──
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new EditFileTool());
        toolRegistry.register(new ListDirTool());

        // ── 搜索工具 ──
        toolRegistry.register(new GlobTool());
        toolRegistry.register(new GrepTool());

        // ── 时间工具（告诉 LLM 今天是几号，对抗训练数据过期）──
        toolRegistry.register(new GetCurrentTimeTool());

        // ── AskUser 工具（LLM 不确定时反问用户，而非瞎猜）──
        toolRegistry.register(new AskUserTool());

        // ── Task 追踪（LLM 可自主创建/更新/列出任务，应对复杂多步需求）──
        TaskStore taskStore = new TaskStore();
        toolRegistry.register(new TaskCreateTool(taskStore));
        toolRegistry.register(new TaskListTool(taskStore));
        toolRegistry.register(new TaskUpdateTool(taskStore));

        // ── Shell ──
        if (config.getTools().getExec().isEnable()) {
            toolRegistry.register(new ExecTool());
        }

        // ── Web ──
        if (config.getTools().getWeb().isEnable()) {
            String searchProvider = config.getTools().getWeb().getSearch().getProvider();
            String searchApiKey = config.getTools().getWeb().getSearch().getActiveApiKey();
            toolRegistry.register(new WebSearchTool(searchProvider, searchApiKey));
        }

        // ── 子 Agent（异步分解任务到独立 Agent）──
        toolRegistry.register(new SpawnTool(agentCoordinator));
        toolRegistry.register(new SpawnCheckTool());

        // ── 技能元工具（一个 tool 管所有 Skill，LLM 调用时传入技能名）──
        if (skillManager != null) {
            toolRegistry.register(new UseSkillTool(skillManager.getRegistry()));
        }

        // ── @ToolDef 注解扫描（项目自定义工具零代码注册）──
        String scanPkgs = config.getTools().getToolScanPackages();
        if (scanPkgs != null && !scanPkgs.isBlank()) {
            ToolScanner.scanAndRegister(toolRegistry, scanPkgs.split("\\s*,\\s*"));
        }

        logger.info("Registered {} tools: {}",
                toolRegistry.size(),
                String.join(", ", toolRegistry.getToolNames()));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 第 3 层：LLM 通道 — Agent 的大脑
    // ═══════════════════════════════════════════════════════════════════

    /**
     * LLM 提供者 — Agent 与 AI 模型之间的唯一通信通道.
     *
     * <h3>策略匹配</h3>
     * ProviderFactory 根据 model 名前缀自动匹配：
     * <ul>
     *   <li>{@code deepseek*} → DeepSeekProvider</li>
     *   <li>{@code gpt-*} / {@code o1/o3/o4} → OpenAIProvider</li>
     *   <li>其他 → OpenAIProvider（兜底）</li>
     * </ul>
     * 换模型只需改 config.yaml 的 model 字段，不用修改代码.
     *
     * <h3>API Key 来源</h3>
     * secret.yaml > 环境变量
     */
    @Bean
    public LLMProvider llmProvider(Config config) {
        return ProviderFactory.create(config);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 第 4 层：上下文注入三件套 — 每次 BUILD 阶段注入 System Prompt
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 身份管理器 — 定义 Agent "是谁".
     *
     * 加载 {@code .nanobot/SOUL.md}（人设）+ {@code IDENTITY.md}（个性）+ {@code USER.md}（用户画像）.
     * 文件不存在时自动生成默认模板.
     *
     * 每次对话 BUILD 阶段，BuildState 调用 {@code identityManager.getSystemPrompt()} 注入.
     */
    @Bean
    public IdentityManager identityManager(Config config) {
        IdentityManager identityManager = new IdentityManager(config);
        identityManager.load();
        logger.info("Loaded identity: soul={}", identityManager.getSoul().getName());
        return identityManager;
    }

    /**
     * 规则管理器 — 定义 Agent "不能做什么"和"应该怎么做".
     *
     * 加载来源（按优先级）：
     * <ol>
     *   <li>{@code NANOBOT.md} — 项目根目录，项目级事实和约定</li>
     *   <li>{@code .nanobot/rules/*.md} — 项目级规则（编码规范、安全红线、Git 工作流）</li>
     *   <li>{@code ~/.nanobot/rules/*.md} — 用户级规则（跨所有项目生效）</li>
     * </ol>
     *
     * 每次对话 BUILD 阶段，BuildState 将规则注入 System Prompt 末尾.
     */
    @Bean
    public RuleManager ruleManager(Config config) {
        RuleManager ruleManager = new RuleManager(config);
        ruleManager.loadRules();
        logger.info("Loaded {} rules", ruleManager.getRegistry().size());
        return ruleManager;
    }

    /**
     * 技能管理器 — 定义 Agent "有哪些可复用的专业能力".
     *
     * 加载 {@code .nanobot/skills/<name>/SKILL.md}，每个子目录一个技能.
     * LLM 通过 {@code /skill-name} 或 {@code use_skill} 工具激活技能.
     *
     * 每次对话 BUILD 阶段，BuildState 将技能目录（仅名称+描述，省 token）注入 System Prompt.
     */
    @Bean
    public SkillManager skillManager(Config config) {
        SkillManager skillManager = new SkillManager(config);
        skillManager.loadSkills();
        logger.info("Loaded {} skills", skillManager.getRegistry().size());
        return skillManager;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 第 5 层：记忆系统 — 让 Agent 拥有"过去"
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 会话管理器 — 对话历史的"存档柜".
     *
     * 每次对话 RESTORE 阶段从 {@code .nanobot/sessions/{key}/history.jsonl} 恢复历史，
     * SAVE 阶段增量追加写入.
     *
     * 锁策略：每个 sessionKey 一把独立锁，不同会话互不阻塞.
     */
    @Bean
    public SessionManager sessionManager(Config config) {
        return new SessionManager(config);
    }

    /**
     * 对话压缩器 — 防止上下文窗口溢出.
     *
     * 每次对话 COMPACT 阶段检查：如果当前 token 数超过 contextWindowTokens 的 90%，
     * 调 LLM 将旧消息总结为一段摘要，替换原文，减少 token 占用.
     *
     * 也可通过 {@code /compact} 命令手动触发.
     */
    @Bean
    public Consolidator consolidator(LLMProvider llmProvider, Config config) {
        int budget = config.getAgents().getDefaults().getContextWindowTokens();
        return new Consolidator(llmProvider, budget);
    }

    /**
     * 长期记忆引擎 — 跨对话的"海马体"，自动从对话中提取关键信息.
     *
     * <h3>完整闭环（全自动）</h3>
     * <ol>
     *   <li><b>提取（异步 fire-and-forget）</b>：SAVE 状态调 LLM 分析对话，
     *       增量节流——对话新增不足 5000 字符直接跳过（省 LLM 费用）</li>
     *   <li><b>存储</b>：Jackson 解析 LLM 返回的 JSON → 写入 {@code .nanobot/memory/MEMORY.md}</li>
     *   <li><b>检索（同步）</b>：BUILD 阶段关键词匹配 + 重要性加权，取 top 5 注入 System Prompt</li>
     *   <li><b>手动触发</b>：{@code /remember} 命令强行提取，无视节流阈值</li>
     * </ol>
     *
     * <h3>设计要点</h3>
     * 所有会话共享同一个 MEMORY.md，Dream 加载时恢复已有记忆，
     * 新记忆合并去重后全量写回.
     */
    @Bean
    public Dream dream(LLMProvider llmProvider, Config config) {
        int maxMemories = config.getMemory().getDream().getMaxMemories();
        java.nio.file.Path memoryDir = java.nio.file.Paths.get(".nanobot", "memory").toAbsolutePath().normalize();
        Dream d = new Dream(llmProvider, maxMemories, memoryDir);
        d.loadFromMemoryFile(memoryDir);  // 恢复之前持久化的记忆
        return d;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 第 6 层：扩展能力 — MCP + 子 Agent
    // ═══════════════════════════════════════════════════════════════════

    /**
     * MCP 管理器 — 接入外部工具服务器.
     *
     * 管理 MCP 客户端（Stdio/HTTP），将其工具注册到 ToolRegistry.
     * 启动时初始化，关闭时自动断开.
     */
    @Bean
    public MCPManager mcpManager(Config config, ToolRegistry toolRegistry) {
        MCPManager mcpManager = new MCPManager();
        mcpManager.initialize(config, toolRegistry);
        logger.info("Initialized {} MCP servers", mcpManager.getClientCount());
        return mcpManager;
    }

    /**
     * 子 Agent 协调器 — 并行分解复杂任务.
     *
     * 注册了 searcher / summarizer / coder / calculator 四个默认子 Agent.
     * LLM 可通过 SpawnTool 异步委派任务给子 Agent（同 JVM 内运行，文件 inbox 通信）.
     */
    @Bean
    public AgentCoordinator agentCoordinator(LLMProvider llmProvider,
                                              @org.springframework.context.annotation.Lazy ToolRegistry toolRegistry) {
        AgentCoordinator coordinator = new AgentCoordinator(llmProvider, toolRegistry);
        coordinator.registerSubagent("searcher", "搜索助手",
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

}
