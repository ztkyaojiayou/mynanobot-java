package com.nanobot.skill;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill 加载器
 * ============
 * 
 * 从文件系统加载技能定义（SKILL.md 文件）。
 * 
 * **技能目录结构**：
 * ```
 * .nanobot/skills/my-skill/
 * ├── SKILL.md          # 必需：技能定义
 * ├── templates/        # 可选：模板文件
 * ├── scripts/          # 可选：辅助脚本
 * └── resources/        # 可选：资源文件
 * ```
 * 
 * **SKILL.md 格式**：
 * ```markdown
 * ---
 * name: my-skill
 * description: 技能描述，用于自动触发匹配
 * argument-hint: "[参数提示]"
 * auto-trigger: true
 * ---
 * # 技能内容
 * 这里是技能的详细指令...
 * ```
 */
public class SkillLoader {
    
    private static final Logger logger = LoggerFactory.getLogger(SkillLoader.class);
    
    // YAML 前置元数据的正则表达式
    private static final Pattern FRONTMATTER_PATTERN = 
        Pattern.compile("^---\\s*\\n([\\s\\S]*?)\\n---\\s*\\n([\\s\\S]*)$");
    
    /**
     * 从目录加载技能
     */
    public static Skill loadFromDirectory(Path skillDir) throws IOException {
        Path skillFile = skillDir.resolve("SKILL.md");
        
        if (!Files.exists(skillFile)) {
            throw new IOException("SKILL.md not found in " + skillDir);
        }
        
        String content = Files.readString(skillFile, StandardCharsets.UTF_8);
        return parseSkill(content, skillDir);
    }
    
    /**
     * 解析 SKILL.md 内容
     */
    private static Skill parseSkill(String content, Path skillDir) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        
        SkillMetadata metadata = new SkillMetadata();
        String skillContent = content;
        
        if (matcher.matches()) {
            String frontmatter = matcher.group(1);
            skillContent = matcher.group(2);
            
            // 解析 YAML 元数据（简化版解析）
            parseFrontmatter(frontmatter, metadata);
        }
        
        // 如果没有从元数据获取名称，从目录名推断
        if (metadata.getName() == null || metadata.getName().isBlank()) {
            metadata.setName(skillDir.getFileName().toString());
        }
        
        return new FileSkill(metadata, skillContent, skillDir);
    }
    
    /**
     * 解析 YAML 前置元数据（简化版）
     */
    private static void parseFrontmatter(String frontmatter, SkillMetadata metadata) {
        String[] lines = frontmatter.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            int colonIndex = line.indexOf(':');
            if (colonIndex <= 0) continue;
            
            String key = line.substring(0, colonIndex).trim().toLowerCase();
            String value = colonIndex < line.length() - 1 ? line.substring(colonIndex + 1).trim() : "";
            
            switch (key) {
                case "name" -> metadata.setName(value);
                case "description" -> metadata.setDescription(value);
                case "argument-hint" -> metadata.setArgumentHint(value);
                case "argument_hint" -> metadata.setArgumentHint(value);
                case "auto-trigger" -> metadata.setAutoTrigger(Boolean.parseBoolean(value));
                case "auto_trigger" -> metadata.setAutoTrigger(Boolean.parseBoolean(value));
                default -> metadata.addProperty(key, value);
            }
        }
    }
    
    /**
     * 文件技能实现
     */
    private static class FileSkill implements Skill {
        
        private final SkillMetadata metadata;
        private final String content;
        @Getter
        private final Path skillDir;
        
        public FileSkill(SkillMetadata metadata, String content, Path skillDir) {
            this.metadata = metadata;
            this.content = content;
            this.skillDir = skillDir;
        }
        
        @Override
        public String getName() {
            return metadata.getName();
        }
        
        @Override
        public String getDescription() {
            return metadata.getDescription();
        }
        
        @Override
        public String getArgumentHint() {
            return metadata.getArgumentHint();
        }
        
        @Override
        public boolean isAutoTrigger() {
            return metadata.isAutoTrigger();
        }
        
        @Override
        public String getContent() {
            return content;
        }
        
        @Override
        public String execute(Map<String, Object> context, String... args) {
            // 简单实现：返回技能内容作为提示词注入
            StringBuilder result = new StringBuilder();
            result.append("【技能: ").append(getName()).append("】\n\n");
            result.append(getContent());
            
            if (args.length > 0) {
                result.append("\n\n【参数】: ").append(String.join(", ", args));
            }
            
            return result.toString();
        }
        
    }
}