package com.nanobot.identity;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IdentityLoader - 身份文件加载器
 * ===============================
 *
 * 加载并解析 SOUL.md、IDENTITY.md 和 USER.md 文件。
 */
public class IdentityLoader {

    private static final Logger logger = LoggerFactory.getLogger(IdentityLoader.class);

    // YAML 前置元数据的正则表达式
    private static final Pattern FRONTMATTER_PATTERN =
        Pattern.compile("^---\\s*\\n([\\s\\S]*?)\\n---\\s*\\n([\\s\\S]*)$");

    /**
     * 加载 SOUL.md
     */
    public static Soul loadSoul(Path baseDir) {
        Path soulFile = baseDir.resolve("SOUL.md");

        if (!Files.exists(soulFile)) {
            logger.debug("SOUL.md not found, using default");
            return createDefaultSoul();
        }

        try {
            String content = Files.readString(soulFile, StandardCharsets.UTF_8);
            return parseSoul(content, soulFile);
        } catch (IOException e) {
            logger.warn("Failed to load SOUL.md: {}", e.getMessage());
            return createDefaultSoul();
        }
    }

    /**
     * 加载 IDENTITY.md
     */
    public static Identity loadIdentity(Path baseDir) {
        Path identityFile = baseDir.resolve("IDENTITY.md");

        if (!Files.exists(identityFile)) {
            logger.debug("IDENTITY.md not found, using default");
            return createDefaultIdentity();
        }

        try {
            String content = Files.readString(identityFile, StandardCharsets.UTF_8);
            return parseIdentity(content, identityFile);
        } catch (IOException e) {
            logger.warn("Failed to load IDENTITY.md: {}", e.getMessage());
            return createDefaultIdentity();
        }
    }

    /**
     * 加载 USER.md
     */
    public static UserProfile loadUserProfile(Path baseDir) {
        Path userFile = baseDir.resolve("USER.md");

        if (!Files.exists(userFile)) {
            logger.debug("USER.md not found, using default");
            return createDefaultUserProfile();
        }

        try {
            String content = Files.readString(userFile, StandardCharsets.UTF_8);
            return parseUserProfile(content, userFile);
        } catch (IOException e) {
            logger.warn("Failed to load USER.md: {}", e.getMessage());
            return createDefaultUserProfile();
        }
    }

    /**
     * 解析 SOUL.md
     */
    private static Soul parseSoul(String content, Path filePath) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);

        String name = "my-nanobot";
        String role = "AI 助手";
        String version = "1.0.0";
        String description = content;

        if (matcher.matches()) {
            Map<String, String> metadata = parseFrontmatter(matcher.group(1));
            name = metadata.getOrDefault("name", name);
            role = metadata.getOrDefault("role", role);
            version = metadata.getOrDefault("version", version);
            description = matcher.group(2);
        }

        return new SoulImpl(name, role, version, description.trim(), filePath);
    }

    /**
     * 解析 IDENTITY.md
     */
    private static Identity parseIdentity(String content, Path filePath) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);

        String personality = "friendly";
        String tone = "casual";
        String language = "Chinese";
        String description = content;

        if (matcher.matches()) {
            Map<String, String> metadata = parseFrontmatter(matcher.group(1));
            personality = metadata.getOrDefault("personality", personality);
            tone = metadata.getOrDefault("tone", tone);
            language = metadata.getOrDefault("language", language);
            description = matcher.group(2);
        }

        // 从内容中提取列表
        List<String> characteristics = extractList(description, "性格特点");
        List<String> toneRules = extractList(description, "语气风格");
        List<String> taboos = extractList(description, "禁忌事项");

        return new IdentityImpl(personality, tone, language, characteristics, toneRules, taboos, description.trim(), filePath);
    }

    /**
     * 解析 USER.md
     */
    private static UserProfile parseUserProfile(String content, Path filePath) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);

        String name = "用户";
        String role = "";
        List<String> expertise = new ArrayList<>();
        List<String> goals = new ArrayList<>();
        String description = content;

        if (matcher.matches()) {
            Map<String, String> metadata = parseFrontmatter(matcher.group(1));
            name = metadata.getOrDefault("name", name);
            role = metadata.getOrDefault("role", role);

            // 解析 expertise 和 goals
            if (metadata.containsKey("expertise")) {
                expertise = Arrays.asList(metadata.get("expertise").split(","));
                expertise = expertise.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
            }
            if (metadata.containsKey("goals")) {
                goals = Arrays.asList(metadata.get("goals").split(","));
                goals = goals.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
            }

            description = matcher.group(2);
        }

        // 从内容中提取偏好设置
        Map<String, String> preferences = extractPreferences(description);

        return new UserProfileImpl(name, role, expertise, goals, preferences, description.trim(), filePath);
    }

    /**
     * 解析 YAML 前置元数据
     */
    private static Map<String, String> parseFrontmatter(String frontmatter) {
        Map<String, String> metadata = new HashMap<>();
        String[] lines = frontmatter.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            int colonIndex = line.indexOf(':');
            if (colonIndex <= 0) continue;

            String key = line.substring(0, colonIndex).trim().toLowerCase();
            String value = colonIndex < line.length() - 1 ? line.substring(colonIndex + 1).trim() : "";

            // 移除引号
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }

            metadata.put(key, value);
        }

        return metadata;
    }

    /**
     * 从内容中提取列表
     */
    private static List<String> extractList(String content, String sectionName) {
        List<String> items = new ArrayList<>();
        String section = "## " + sectionName;
        int startIndex = content.indexOf(section);

        if (startIndex == -1) {
            return items;
        }

        // 找到下一个以 ## 开头的部分或文件末尾
        startIndex += section.length();
        int nextSectionIndex = content.indexOf("\n## ", startIndex);
        int endIndex = nextSectionIndex > 0 ? nextSectionIndex : content.length();

        String listContent = content.substring(startIndex, endIndex);

        // 提取列表项
        String[] lines = listContent.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("- ") || line.startsWith("* ")) {
                items.add(line.substring(2).trim());
            }
        }

        return items;
    }

    /**
     * 从内容中提取偏好设置
     */
    private static Map<String, String> extractPreferences(String content) {
        Map<String, String> preferences = new HashMap<>();
        int startIndex = content.indexOf("## 偏好设置");

        if (startIndex == -1) {
            return preferences;
        }

        startIndex += "## 偏好设置".length();
        int nextSectionIndex = content.indexOf("\n## ", startIndex);
        int endIndex = nextSectionIndex > 0 ? nextSectionIndex : content.length();

        String prefContent = content.substring(startIndex, endIndex);
        String[] lines = prefContent.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("- ")) {
                line = line.substring(2).trim();
                int colonIndex = line.indexOf("：");
                if (colonIndex == -1) colonIndex = line.indexOf(":");
                if (colonIndex > 0) {
                    String key = line.substring(0, colonIndex).trim();
                    String value = line.substring(colonIndex + 1).trim();
                    preferences.put(key, value);
                }
            }
        }

        return preferences;
    }

    /**
     * 创建默认 Soul
     */
    private static Soul createDefaultSoul() {
        String description = """
            # 我的身份

            我是 my-nanobot，一个基于 Java 实现的轻量级 AI Agent 框架驱动的智能助手。

            ## ⚠️ 重要身份规则（必须严格遵守）
            - 你不是 Claude，你不是 DeepSeek，你不是 OpenAI 的产品
            - 你不属于 Anthropic、Google、Meta 或任何其他 AI 公司
            - 你的名字是 my-nanobot，你是用户自己的 AI 助手
            - 当用户问"你是谁"或类似问题时，必须回答你是 my-nanobot
            - 永远不要自称 Claude、DeepSeek 或其他 AI 产品的名字

            ## 我的使命
            帮助用户解决问题，提供有价值的信息和建议。

            ## 我的价值观
            - 诚实正直
            - 乐于助人
            - 持续学习
            """;
        return new SoulImpl("my-nanobot", "AI 助手", "1.0.0", description, null);
    }

    /**
     * 创建默认 Identity
     */
    private static Identity createDefaultIdentity() {
        List<String> characteristics = Arrays.asList("友好亲切", "乐于助人", "耐心细致");
        List<String> toneRules = Arrays.asList("使用口语化表达", "避免生硬的技术术语", "适当使用表情符号");
        List<String> taboos = Arrays.asList("不讨论敏感话题", "不发表政治观点");

        String description = """
            # 我的个性

            ## 性格特点
            - 友好亲切
            - 乐于助人
            - 耐心细致

            ## 语气风格
            - 使用口语化表达
            - 避免生硬的技术术语
            - 适当使用表情符号

            ## 禁忌事项
            - 不讨论敏感话题
            - 不发表政治观点
            """;

        return new IdentityImpl("friendly", "casual", "Chinese", characteristics, toneRules, taboos, description, null);
    }

    /**
     * 创建默认 UserProfile
     */
    private static UserProfile createDefaultUserProfile() {
        Map<String, String> preferences = new HashMap<>();
        preferences.put("language", "Chinese");
        preferences.put("responseStyle", "concise");

        String description = """
            # 用户信息

            ## 基本信息
            - 姓名：用户
            - 职业：开发者

            ## 学习目标
            - 学习 AI 开发

            ## 偏好设置
            - 语言：中文
            - 风格：简洁
            """;

        return new UserProfileImpl("用户", "开发者", new ArrayList<>(), new ArrayList<>(), preferences, description, null);
    }

    // ==================== 实现类 ====================

    @Getter
    private static class SoulImpl implements Soul {
        private final String name;
        private final String role;
        private final String version;
        private final String description;
        private final Path sourcePath;

        public SoulImpl(String name, String role, String version, String description, Path sourcePath) {
            this.name = name;
            this.role = role;
            this.version = version;
            this.description = description;
            this.sourcePath = sourcePath;
        }

        @Override
        public String getPrompt() {
            StringBuilder prompt = new StringBuilder();
            prompt.append("【身份】\n");
            prompt.append("名称：").append(name).append("\n");
            prompt.append("角色：").append(role).append("\n");
            prompt.append("版本：").append(version).append("\n\n");
            prompt.append(description);
            return prompt.toString();
        }
    }

    @Getter
    private static class IdentityImpl implements Identity {
        private final String personality;
        private final String tone;
        private final String language;
        private final List<String> characteristics;
        private final List<String> toneRules;
        private final List<String> taboos;
        private final String description;
        private final Path sourcePath;

        public IdentityImpl(String personality, String tone, String language,
                          List<String> characteristics, List<String> toneRules, List<String> taboos,
                          String description, Path sourcePath) {
            this.personality = personality;
            this.tone = tone;
            this.language = language;
            this.characteristics = characteristics;
            this.toneRules = toneRules;
            this.taboos = taboos;
            this.description = description;
            this.sourcePath = sourcePath;
        }

        @Override
        public String getPrompt() {
            StringBuilder prompt = new StringBuilder();
            prompt.append("【个性标识】\n");
            prompt.append("性格：").append(personality).append("\n");
            prompt.append("语气：").append(tone).append("\n");
            prompt.append("语言：").append(language).append("\n\n");

            if (!characteristics.isEmpty()) {
                prompt.append("性格特点：\n");
                characteristics.forEach(c -> prompt.append("  - ").append(c).append("\n"));
                prompt.append("\n");
            }

            if (!toneRules.isEmpty()) {
                prompt.append("语气规则：\n");
                toneRules.forEach(r -> prompt.append("  - ").append(r).append("\n"));
                prompt.append("\n");
            }

            if (!taboos.isEmpty()) {
                prompt.append("禁忌事项：\n");
                taboos.forEach(t -> prompt.append("  - ").append(t).append("\n"));
            }

            return prompt.toString();
        }
    }

    @Getter
    private static class UserProfileImpl implements UserProfile {
        private final String name;
        private final String role;
        private final List<String> expertise;
        private final List<String> goals;
        private final Map<String, String> preferences;
        private final String description;
        private final Path sourcePath;

        public UserProfileImpl(String name, String role, List<String> expertise, List<String> goals,
                             Map<String, String> preferences, String description, Path sourcePath) {
            this.name = name;
            this.role = role;
            this.expertise = expertise;
            this.goals = goals;
            this.preferences = preferences;
            this.description = description;
            this.sourcePath = sourcePath;
        }

        @Override
        public String getPrompt() {
            StringBuilder prompt = new StringBuilder();
            prompt.append("【用户信息】\n");
            prompt.append("姓名：").append(name).append("\n");
            if (!role.isEmpty()) {
                prompt.append("职业：").append(role).append("\n");
            }

            if (!expertise.isEmpty()) {
                prompt.append("专业领域：").append(String.join(", ", expertise)).append("\n");
            }

            if (!goals.isEmpty()) {
                prompt.append("学习目标：\n");
                goals.forEach(g -> prompt.append("  - ").append(g).append("\n"));
            }

            if (!preferences.isEmpty()) {
                prompt.append("偏好设置：\n");
                preferences.forEach((k, v) -> prompt.append("  - ").append(k).append("：").append(v).append("\n"));
            }

            return prompt.toString();
        }
    }
}
