package com.nanobot.core.state;

import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import com.nanobot.identity.IdentityManager;
import com.nanobot.memory.Dream;
import com.nanobot.rules.RuleManager;
import com.nanobot.skill.Skill;
import com.nanobot.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * BUILD — 构建系统提示词。
 * 注入身份信息、长期记忆、技能目录、NANOBOT.md、Plan Mode、Rules。
 */
public class BuildState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(BuildState.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
    private static final int MEMORY_RETRIEVAL_LIMIT = 5; // 每次注入的最多记忆条数

    private final IdentityManager identityManager;
    private final RuleManager ruleManager;
    private final BooleanSupplier planModeSupplier; // 支持运行时查询 planMode
    private final Dream dream; // 可为 null
    private final SkillRegistry skillRegistry; // 可为 null — 用于注入技能目录
    private final String workspacePath; // 工作区路径，用于读取 NANOBOT.md

    public BuildState(IdentityManager identityManager, RuleManager ruleManager,
                      BooleanSupplier planModeSupplier, Dream dream,
                      SkillRegistry skillRegistry, String workspacePath) {
        this.identityManager = identityManager;
        this.ruleManager = ruleManager;
        this.planModeSupplier = planModeSupplier;
        this.dream = dream;
        this.skillRegistry = skillRegistry;
        this.workspacePath = workspacePath;
    }

    public BuildState(IdentityManager identityManager, RuleManager ruleManager,
                      BooleanSupplier planModeSupplier, Dream dream,
                      SkillRegistry skillRegistry) {
        this(identityManager, ruleManager, planModeSupplier, dream, skillRegistry, ".");
    }

    @Override
    public TurnState execute(TurnContext ctx) {
        String currentDate = LocalDate.now().format(DATE_FMT);

        boolean useSearch = extractUseSearch(ctx);
        logger.info("Building context: useSearch={}", useSearch);

        StringBuilder systemPrompt = new StringBuilder();

        // 1. 身份信息注入
        appendIdentity(systemPrompt, currentDate);
        // 2. 联网搜索开关
        appendSearchHint(systemPrompt, useSearch);
        // 3. 长期记忆注入（Dream — 从过往对话中提取的相关记忆）
        appendMemories(systemPrompt, ctx);
        // 4. NANOBOT.md 项目记忆
        appendNanobotMd(systemPrompt);
        // 5. Plan Mode 规划模式
        appendPlanMode(systemPrompt);
        // 6. 技能目录（名称+描述，让 LLM 知道有哪些技能可用）
        appendSkillCatalog(systemPrompt);
        // 7. Rules 规则
        appendRules(systemPrompt);

        // 添加到消息列表
        List<Map<String, Object>> messages = ctx.getMessages();
        if (!messages.isEmpty() && "system".equals(messages.get(0).get("role"))) {
            messages.set(0, Map.of("role", "system", "content", systemPrompt.toString()));
        } else {
            messages.add(0, Map.of("role", "system", "content", systemPrompt.toString()));
        }

        return TurnState.RUN;
    }

    // ── helpers ──

    private static boolean extractUseSearch(TurnContext ctx) {
        if (ctx.getMessage() == null || ctx.getMessage().getMetadata() == null) return false;
        Object val = ctx.getMessage().getMetadata().get("useSearch");
        return val instanceof Boolean b ? b : val instanceof String s && Boolean.parseBoolean(s);
    }

    private void appendIdentity(StringBuilder sb, String currentDate) {
        if (identityManager != null) {
            sb.append(identityManager.getSystemPrompt(currentDate));
        } else {
            sb.append("""
                    你是 my-nanobot，一个基于 Java 实现的轻量级 AI Agent 框架驱动的智能助手。

                    ⚠️ 重要：你不是 Claude、DeepSeek 或任何其他 AI 产品。你的名字是 my-nanobot。
                    当用户问"你是谁"时，必须回答你是 my-nanobot，不要自称 Claude 或任何其他名字。

                    你的任务是帮助用户解决问题，回答问题，执行任务。

                    【当前真实日期 — 覆盖你的训练数据】
                    今天是""" + currentDate + "，这是真实日期。训练数据中日期已过时。\n"
                    + "涉及日期/星期/时间的回答必须以这个日期为准。\n\n");
        }
    }

    private static void appendSearchHint(StringBuilder sb, boolean useSearch) {
        if (!useSearch) {
            sb.append("""
                    当前未启用联网搜索，不要使用 web_search 和 web_fetch 工具。
                    如果用户的问题需要最新信息，告知用户可以使用"联网查"功能。

                    """);
        }
    }

    private void appendMemories(StringBuilder sb, TurnContext ctx) {
        if (dream == null || dream.getMemoryCount() == 0) return;
        try {
            String query = ctx.getMessage() != null ? ctx.getMessage().getContent() : "";
            if (query == null || query.isBlank()) return;

            List<Dream.MemoryEntry> relevant = dream.retrieve(query, MEMORY_RETRIEVAL_LIMIT).join();
            if (relevant.isEmpty()) return;

            sb.append("\n【长期记忆 — 从过往对话中自动提取】\n");
            for (Dream.MemoryEntry entry : relevant) {
                sb.append("- ").append(entry.getContent()).append("\n");
            }
            sb.append("\n");
            logger.debug("Injected {} relevant memories into context", relevant.size());
        } catch (Exception e) {
            logger.debug("Memory retrieval skipped: {}", e.getMessage());
        }
    }

    private void appendNanobotMd(StringBuilder sb) {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(workspacePath, "NANOBOT.md");
            if (java.nio.file.Files.exists(path)) {
                String content = java.nio.file.Files.readString(path);
                sb.append("\n\n【项目上下文 — 来自 NANOBOT.md】\n").append(content).append("\n");
                logger.debug("Loaded NANOBOT.md ({} chars)", content.length());
            }
        } catch (Exception e) {
            logger.debug("NANOBOT.md not found or unreadable: {}", e.getMessage());
        }
    }

    private void appendPlanMode(StringBuilder sb) {
        if (!planModeSupplier.getAsBoolean()) return;
        String cwd = System.getProperty("user.dir", ".");
        sb.append("""

                ⚠️ 你当前处于 **规划模式（Plan Mode）**。关键规则：

                【禁止事项】
                - ❌ 禁止写任何具体代码实现（不要贴代码）
                - ❌ 禁止直接给出最终答案
                - ❌ 禁止修改文件或执行命令

                【你必须做的】
                - ✅ 使用只读工具探索项目结构和现有代码
                - ✅ 向用户提问以澄清不明确的需求
                - ✅ 输出一个**结构化的实现计划**，包含：
                  · ## 需求理解（一句话确认你要做什么）
                  · ## 影响范围（列出要修改/创建的文件清单）
                  · ## 实现步骤（每一步做什么，不要写代码）
                  · ## 注意事项（风险、依赖、边界情况）
                - ✅ 计划末尾加上一句"确认后请回复 /plan approve 开始执行"

                【注意】用户在当前阶段只想看到计划，不想看到具体代码！
                等用户输入 /plan approve 之后你才能开始写代码。

                【工作目录】""" + cwd + """
                探索建议：先调用 list_dir 了解目录结构。
                """);
    }

    /**
     * 注入技能目录（仅名称+描述，不注入全文）。
     *
     * 这是 Claude Code 式的"渐进式加载"：
     * - 第一层（这里）：只告诉 LLM 有哪些技能可用，省 token
     * - 第二层（use_skill 工具）：LLM 调用后返回 SKILL.md 全文
     */
    private void appendSkillCatalog(StringBuilder sb) {
        if (skillRegistry == null) return;
        var skills = skillRegistry.getAllSkills();
        if (skills.isEmpty()) return;

        sb.append("\n\n## 可用技能（Skills）\n\n");
        sb.append("以下技能可通过 use_skill 工具激活。");
        sb.append("如果你判断当前任务需要某个技能，请先调用 use_skill 获取该技能的详细指令，");
        sb.append("然后严格按指令执行。\n\n");

        for (Skill s : skills) {
            sb.append("- **").append(s.getName()).append("**");
            if (s.getDescription() != null && !s.getDescription().isBlank()) {
                sb.append(" — ").append(s.getDescription());
            }
            sb.append("\n");
        }
    }

    private void appendRules(StringBuilder sb) {
        if (ruleManager != null) {
            String prompt = ruleManager.getRulesPrompt();
            if (prompt != null && !prompt.isBlank()) sb.append("\n\n").append(prompt);
        }
    }
}
