package com.nanobot.rules;

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
 * Rule 加载器
 * ============
 * 
 * 从文件系统加载规则定义。
 * 
 * **规则文件位置**：
 * 1. 项目级：NANOBOT.md（项目根目录）
 * 2. 项目级：.nanobot/rules/*.md
 * 3. 用户级：~/.nanobot/rules/*.md
 * 4. 全局级：内置默认规则
 * 
 * **规则文件格式**：
 * ```markdown
 * ---
 * name: coding-style
 * description: 代码风格规范
 * priority: 10
 * enabled: true
 * tags: [java, coding]
 * ---
 * # 代码风格规范
 * 
 * 1. 使用 PascalCase 命名类名
 * 2. 使用 camelCase 命名方法和变量
 * 3. 每行代码不超过 120 字符
 * ```
 */
public class RuleLoader {
    
    private static final Logger logger = LoggerFactory.getLogger(RuleLoader.class);
    
    // YAML 前置元数据的正则表达式
    private static final Pattern FRONTMATTER_PATTERN = 
        Pattern.compile("^---\\s*\\n([\\s\\S]*?)\\n---\\s*\\n([\\s\\S]*)$");
    
    /**
     * 从文件加载规则
     */
    public static Rule loadFromFile(Path filePath, Rule.RuleType type) throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        return parseRule(content, filePath.toString(), type);
    }
    
    /**
     * 解析规则内容
     */
    private static Rule parseRule(String content, String sourcePath, Rule.RuleType type) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        
        String name = Paths.get(sourcePath).getFileName().toString().replace(".md", "");
        String description = "";
        int priority = 100;
        boolean enabled = true;
        String ruleContent = content;
        
        if (matcher.matches()) {
            String frontmatter = matcher.group(1);
            ruleContent = matcher.group(2);
            
            // 解析 YAML 元数据（简化版解析）
            Map<String, String> metadata = parseFrontmatter(frontmatter);
            if (metadata.containsKey("name")) {
                name = metadata.get("name");
            }
            if (metadata.containsKey("description")) {
                description = metadata.get("description");
            }
            if (metadata.containsKey("priority")) {
                try {
                    priority = Integer.parseInt(metadata.get("priority"));
                } catch (NumberFormatException e) {
                    logger.warn("Invalid priority value: {}", metadata.get("priority"));
                }
            }
            if (metadata.containsKey("enabled")) {
                enabled = Boolean.parseBoolean(metadata.get("enabled"));
            }
        }
        
        return new FileRule(name, description, ruleContent, type, priority, enabled, sourcePath);
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
     * 文件规则实现
     */
    private static class FileRule implements Rule {
        
        private final String name;
        private final String description;
        private final String content;
        private final Rule.RuleType type;
        private final int priority;
        private final boolean enabled;
        private final String sourcePath;
        
        public FileRule(String name, String description, String content, 
                       Rule.RuleType type, int priority, boolean enabled, String sourcePath) {
            this.name = name;
            this.description = description;
            this.content = content;
            this.type = type;
            this.priority = priority;
            this.enabled = enabled;
            this.sourcePath = sourcePath;
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        @Override
        public String getDescription() {
            return description;
        }
        
        @Override
        public String getContent() {
            return content;
        }
        
        @Override
        public Rule.RuleType getType() {
            return type;
        }
        
        @Override
        public int getPriority() {
            return priority;
        }
        
        @Override
        public boolean isEnabled() {
            return enabled;
        }
        
        @Override
        public String getSourcePath() {
            return sourcePath;
        }
    }
}