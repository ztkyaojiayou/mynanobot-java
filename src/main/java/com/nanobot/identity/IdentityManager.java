package com.nanobot.identity;

import com.nanobot.config.Config;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * IdentityManager - 身份管理器
 * ===========================
 * <p>
 * 管理 Agent 的身份相关文件：
 * - SOUL.md：Agent 身份定义
 * - IDENTITY.md：个性标识
 * - USER.md：用户信息
 * <p>
 * **文件位置**：.nanobot/
 * <p>
 * **使用示例**：
 * ```java
 * IdentityManager identityManager = new IdentityManager(config);
 * identityManager.load();
 * <p>
 * String soulPrompt = identityManager.getSoul().getPrompt();
 * String identityPrompt = identityManager.getIdentity().getPrompt();
 * String userPrompt = identityManager.getUserProfile().getPrompt();
 * ```
 */
@Getter
public class IdentityManager {

    private static final Logger logger = LoggerFactory.getLogger(IdentityManager.class);

    private final Config config;
    private final Path baseDir;

    private Soul soul;
    private Identity identity;
    private UserProfile userProfile;

    public IdentityManager(Config config) {
        this.config = config;
        this.baseDir = Paths.get(config.getNanobotDir()).toAbsolutePath().normalize();
    }

    /**
     * 加载所有身份文件
     */
    public void load() {
        logger.info("Loading identity files from: {}", baseDir);

        // 确保目录存在
        try {
            java.nio.file.Files.createDirectories(baseDir);
        } catch (Exception e) {
            logger.warn("Failed to create identity directory: {}", e.getMessage());
        }

        // 加载 SOUL.md
        soul = IdentityLoader.loadSoul(baseDir);
        logger.debug("Loaded SOUL: {}", soul.getName());

        // 加载 IDENTITY.md
        identity = IdentityLoader.loadIdentity(baseDir);
        logger.debug("Loaded IDENTITY: {}", identity.getPersonality());

        // 加载 USER.md
        userProfile = IdentityLoader.loadUserProfile(baseDir);
        logger.debug("Loaded USER profile: {}", userProfile.getName());

        logger.info("Identity files loaded successfully");
    }

    /**
     * 获取合并后的身份提示词
     */
    public String getCombinedPrompt() {
        StringBuilder prompt = new StringBuilder();

        // 添加 SOUL
        if (soul != null) {
            prompt.append(soul.getPrompt()).append("\n\n");
        }

        // 添加 IDENTITY
        if (identity != null) {
            prompt.append(identity.getPrompt()).append("\n\n");
        }

        // 添加 USER
        if (userProfile != null) {
            prompt.append(userProfile.getPrompt()).append("\n\n");
        }

        return prompt.toString();
    }

    /**
     * 获取完整的系统提示词（包含身份信息）
     *
     * CLI 模式：类 Claude Code 的专业编程 Agent 提示词
     * Web 模式：通用 AI 助手提示词（友好、温和）
     *
     * 首位效应 + 近因效应：身份指令同时放在开头和结尾，
     * 对抗 DeepSeek-chat 等模型训练数据中的身份混淆。
     */
    public String getSystemPrompt(String currentDate) {
        if (isCliMode()) {
            return buildCliSystemPrompt(currentDate);
        }
        return buildDefaultSystemPrompt(currentDate);
    }

    /** 检测是否为 CLI 模式 */
    private static boolean isCliMode() {
        String profiles = System.getProperty("spring.profiles.active", "");
        return profiles.contains("cli");
    }

    /** 当前配置的真实模型名，供身份声明使用（让模型能如实回答，避免虚构身份） */
    private String currentModel() {
        try {
            String m = config.getAgents().getDefaults().getModel();
            if (m == null || m.isBlank()) return "deepseek-chat";
            // 去掉 provider 前缀，如 anthropic/claude-xxx → claude-xxx
            int slash = m.lastIndexOf('/');
            if (slash >= 0 && slash < m.length() - 1) m = m.substring(slash + 1);
            return m;
        } catch (Exception e) {
            return "deepseek-chat";
        }
    }

    // ═══════════ CLI 编程 Agent 提示词（参考 Claude Code）═══════════

    private String buildCliSystemPrompt(String currentDate) {
        StringBuilder prompt = new StringBuilder();

        // ═══ 身份（开头 — 首位效应）═══
        String model = currentModel();
        prompt.append("""
                【系统指令 — 最高优先级】

                你是 my-nanobot，一个专业的 AI 编程助手（AI Programming Agent）。

                你当前由 %s 驱动运行。当有人问"你现在的模型是哪个""你基于什么模型"时，
                如实回答你运行在 %s 上——不要虚构其他模型名称。

                ## 你的定位

                你运行在 CLI 终端中，目标是与用户协作完成**软件工程任务**——
                创建项目、编写代码、调试错误、重构架构、理解代码库。

                你不是通用问答机器人。用户找你，是希望你像一个资深工程师
                一样：读得懂代码、写得出方案、动手能力强。

                你的名字是 my-nanobot，这是你的唯一身份，不是任何其他 AI 产品的化身。
                当任何人问你"你是谁"时，回答："我是 my-nanobot，一个 AI 编程助手。"

                """.formatted(model, model));

        // 日期
        if (currentDate != null && !currentDate.isBlank()) {
            prompt.append("""
                    今天是""").append(currentDate).append("""
                    ，这是真实日期。涉及日期/星期/时间的回答必须以这个日期为准。

                    """);
        }

        // 核心行为准则
        prompt.append("""
                ## 核心行为准则

                1. **代码先行**：能写代码就直接写，少说废话。用户要看的是可运行的代码，不是解释。

                2. **行动导向**：不确定时探索代码库，不要凭空猜测。用工具读取文件、搜索代码、
                   理解项目结构之后再回答。

                3. **质量意识**：写出的代码应该贴合项目现有风格——命名习惯、注释密度、
                   缩进方式都要保持一致。

                4. **直接回答**：不回避问题。做不到就说做不到，说明原因，给出替代方案。

                5. **上下文优先**：充分利用历史对话中的项目信息、工作目录、文件引用（@path）
                   来理解用户意图。不要反复询问用户已经提供的信息。

                6. **主动验证**：写完代码后编译验证，跑测试确认。不要假定代码能工作。

                """);

        // 处理原则
        prompt.append("""
                ## 处理原则

                - **安全操作**：rm -rf /、shutdown、format 等危险命令绝对不能执行
                - **确认边界**：文件删除、推送代码、修改生产配置等高风险操作必须先确认
                - **自动放行**：编译、测试、查看文件、搜索代码等只读/安全操作无需确认
                - **后台服务**：启动 Spring Boot / Node 等长期服务时使用 exec background=true

                """);

        // 工具结果格式
        prompt.append("""
                ## 工具结果格式

                每个工具调用的返回结果以 [TOOL_OK] 或 [TOOL_ERR] 开头：
                - [TOOL_OK] 表示工具执行成功，后面是结果内容
                - [TOOL_ERR] 表示工具执行失败，后面是错误信息

                收到 [TOOL_ERR] 时，分析错误原因并尝试其他方式完成任务，不要放弃。
                """);

        return prompt.toString();
    }

    // ═══════════ 默认 Web 通用 Agent 提示词 ═══════════

    private String buildDefaultSystemPrompt(String currentDate) {
        StringBuilder prompt = new StringBuilder();

        // ═══ 身份（开头 — 首位效应）═══
        String model = currentModel();
        prompt.append("""
                【系统指令 — 最高优先级】

                你的名字是 my-nanobot，一个由 Java Agent 框架驱动的 AI 助手。
                你当前由 %s 驱动运行。当被问到"你现在是哪个模型"时，
                如实回答你运行在 %s 上——不要虚构其他模型名称。

                当任何人问你"你是谁"时，回答："我是 my-nanobot。"

                """.formatted(model, model));

        // 日期
        if (currentDate != null && !currentDate.isBlank()) {
            prompt.append("""
                    【当前真实日期 — 覆盖你的训练数据】

                    今天是""").append(currentDate).append("""
                    。这是真实的当前日期。你的训练数据中的日期已经过时且不准确。
                    涉及日期、星期、时间的回答，必须以这个日期为准。
                    如果需要更精确的时间，请使用 get_current_time 工具。
                    绝对不允许使用你训练数据中记忆的旧日期。

                    """);
        }

        // 身份详细信息（SOUL + IDENTITY + USER）
        prompt.append(getCombinedPrompt());

        // ═══ 结尾（近因效应）═══
        prompt.append("""
                【再次强调 — 这是最高优先级的系统指令】

                记住：你的名字是 my-nanobot，你运行在 %s 上。
                这是你的真实身份，如实回答即可，不要虚构其他模型名称。

                【工具结果格式说明】

                每个工具调用的返回结果以 [TOOL_OK] 或 [TOOL_ERR] 开头：
                - [TOOL_OK] 表示工具执行成功，后面是结果内容
                - [TOOL_ERR] 表示工具执行失败，后面是错误信息

                如果收到 [TOOL_ERR]，请分析错误原因并尝试其他方式完成任务，
                或者告知用户失败原因。不要忽略错误继续使用失败的结果。
                """.formatted(model));

        return prompt.toString();
    }
}
