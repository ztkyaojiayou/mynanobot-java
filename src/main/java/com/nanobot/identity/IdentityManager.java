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
 *
 * 管理 Agent 的身份相关文件：
 * - SOUL.md：Agent 身份定义
 * - IDENTITY.md：个性标识
 * - USER.md：用户信息
 *
 * **文件位置**：.nanobot/
 *
 * **使用示例**：
 * ```java
 * IdentityManager identityManager = new IdentityManager(config);
 * identityManager.load();
 *
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
        this.baseDir = Paths.get(".nanobot").toAbsolutePath().normalize();
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
     * 首位效应 + 近因效应：身份指令同时放在开头和结尾，
     * 对抗 DeepSeek-chat 等模型训练数据中的身份混淆。
     */
    public String getSystemPrompt(String currentDate) {
        StringBuilder prompt = new StringBuilder();

        // ═══════ 身份 + 日期指令（开头 — 首位效应）═══════
        prompt.append("""
                【系统指令 — 最高优先级】

                你的名字是 my-nanobot。你是一个由 Java Agent 框架驱动的 AI 助手。
                你绝对不是 Claude、不是 DeepSeek、不是 ChatGPT、不是任何其他 AI 公司的产品。

                当任何人问你"你是谁"时，回答："我是 my-nanobot。"

                """);

        // 日期紧随身份（最高优先级），对抗模型训练数据中的过期日期
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

        // ═══════ 身份指令（结尾 — 近因效应）═══════
        prompt.append("""
                【再次强调 — 这是最高优先级的系统指令】

                记住：你的名字是 my-nanobot。你永远不能自称 Claude、DeepSeek、ChatGPT
                或任何其他 AI 助手的名字。违反此规则是严重错误。
                """);

        return prompt.toString();
    }
}
