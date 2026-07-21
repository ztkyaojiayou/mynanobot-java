package com.nanobot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MemoryLoader - 长期记忆文件加载器
 * ===============================
 * 
 * 加载并解析 MEMORY.md 文件，支持从文件导入长期记忆。
 * 
 * **文件位置**：.nanobot/MEMORY.md
 * 
 * **文件格式**：
 * ```markdown
 * ---
 * name: project-memory
 * description: 项目长期记忆
 * ---
 * # 长期记忆
 * 
 * ## 重要信息
 * - 用户偏好：喜欢简洁的回答
 * - 项目目标：学习 AI Agent 开发
 * 
 * ## 历史事件
 * - 2024-01-15: 开始学习 Java 21
 * - 2024-02-01: 完成基础项目搭建
 * ```
 */
public class MemoryLoader {
    
    private static final Logger logger = LoggerFactory.getLogger(MemoryLoader.class);
    
    private static final Pattern FRONTMATTER_PATTERN = 
        Pattern.compile("^---\\s*\\n([\\s\\S]*?)\\n---\\s*\\n([\\s\\S]*)$");
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    /**
     * 从 MEMORY.md 文件加载记忆（文件不存在时自动生成模板）
     */
    public static List<Dream.MemoryEntry> loadFromFile(Path baseDir) {
        Path memoryFile = baseDir.resolve("MEMORY.md");

        if (!Files.exists(memoryFile)) {
            logger.info("MEMORY.md not found, generating template at {}", memoryFile);
            try {
                Files.createDirectories(baseDir);
                Files.writeString(memoryFile, getDefaultMemoryTemplate(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.warn("Failed to create MEMORY.md template: {}", e.getMessage());
            }
            return Collections.emptyList();
        }

        try {
            String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
            return parseMemory(content);
        } catch (IOException e) {
            logger.warn("Failed to load MEMORY.md: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 解析 MEMORY.md 内容
     */
    private static List<Dream.MemoryEntry> parseMemory(String content) {
        List<Dream.MemoryEntry> entries = new ArrayList<>();
        
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        String body = content;
        
        if (matcher.matches()) {
            body = matcher.group(2);
        }
        
        String[] lines = body.split("\n");
        List<String> currentSection = new ArrayList<>();
        String currentSectionTitle = "";
        
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (!currentSection.isEmpty()) {
                    entries.addAll(parseSection(currentSectionTitle, currentSection));
                    currentSection.clear();
                }
                currentSectionTitle = line.substring(3).trim();
            } else if (line.startsWith("- ")) {
                currentSection.add(line.substring(2).trim());
            }
        }
        
        if (!currentSection.isEmpty()) {
            entries.addAll(parseSection(currentSectionTitle, currentSection));
        }
        
        logger.info("Loaded {} memory entries from MEMORY.md", entries.size());
        return entries;
    }
    
    /**
     * 解析章节内容
     */
    private static List<Dream.MemoryEntry> parseSection(String sectionTitle, List<String> items) {
        List<Dream.MemoryEntry> entries = new ArrayList<>();
        
        for (String item : items) {
            try {
                Dream.MemoryEntry entry = new Dream.MemoryEntry();
                entry.setId(UUID.randomUUID().toString());
                entry.setContent(item);
                entry.setTimestamp(LocalDateTime.now());
                entry.setImportance(0.7);
                entry.setSource("MEMORY.md");
                
                List<String> keywords = new ArrayList<>();
                keywords.add(sectionTitle.toLowerCase().replace(" ", "-"));
                
                String dateStr = extractDate(item);
                if (dateStr != null) {
                    keywords.add("date:" + dateStr);
                }
                
                keywords.addAll(extractKeywords(item));
                entry.setKeywords(keywords);
                
                entries.add(entry);
            } catch (Exception e) {
                logger.warn("Failed to parse memory entry: {}", item);
            }
        }
        
        return entries;
    }
    
    /**
     * 从文本中提取日期
     */
    private static String extractDate(String text) {
        Pattern datePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        Matcher matcher = datePattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * 从文本中提取关键词
     */
    private static List<String> extractKeywords(String text) {
        List<String> keywords = new ArrayList<>();
        String cleaned = text.replaceAll("[^\\w\\s]", "");
        String[] words = cleaned.split("\\s+");
        
        for (int i = 0; i < Math.min(3, words.length); i++) {
            String word = words[i].toLowerCase();
            if (word.length() > 2 && !isStopWord(word)) {
                keywords.add(word);
            }
        }
        
        return keywords;
    }
    
    /**
     * 判断是否为停用词
     */
    private static boolean isStopWord(String word) {
        Set<String> stopWords = Set.of("的", "是", "在", "有", "和", "了", "我", "你", "他", "她", "它", "这", "那", "能", "会", "可以", "要", "不要");
        return stopWords.contains(word);
    }
    
    /**
     * 保存记忆到 MEMORY.md 文件
     */
    public static void saveToFile(Path baseDir, List<Dream.MemoryEntry> entries) {
        try {
            Path memoryFile = baseDir.resolve("MEMORY.md");
            Files.createDirectories(baseDir);
            
            StringBuilder content = new StringBuilder();
            content.append("---\n");
            content.append("name: project-memory\n");
            content.append("description: 项目长期记忆\n");
            content.append("updated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            content.append("---\n\n");
            content.append("# 长期记忆\n\n");
            
            Map<String, List<Dream.MemoryEntry>> grouped = entries.stream()
                .collect(Collectors.groupingBy(e -> {
                    if (e.getKeywords() != null && !e.getKeywords().isEmpty()) {
                        return e.getKeywords().get(0);
                    }
                    return "general";
                }));
            
            for (Map.Entry<String, List<Dream.MemoryEntry>> group : grouped.entrySet()) {
                String title = group.getKey().replace("-", " ").replace("date:", "日期");
                content.append("## ").append(Character.toUpperCase(title.charAt(0))).append(title.substring(1)).append("\n\n");
                
                for (Dream.MemoryEntry entry : group.getValue()) {
                    String datePrefix = "";
                    if (entry.getTimestamp() != null) {
                        datePrefix = entry.getTimestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ": ";
                    }
                    content.append("- ").append(datePrefix).append(entry.getContent()).append("\n");
                }
                content.append("\n");
            }
            
            Files.writeString(memoryFile, content.toString(), StandardCharsets.UTF_8);
            logger.info("Saved {} memory entries to MEMORY.md", entries.size());
            
        } catch (IOException e) {
            logger.error("Failed to save MEMORY.md: {}", e.getMessage());
        }
    }

    /** MEMORY.md 默认模板（空记忆库，等待 Dream 自动填充） */
    private static String getDefaultMemoryTemplate() {
        return """
                ---
                name: project-memory
                description: 项目长期记忆（由 Dream 自动提取）
                updated: %s
                ---

                # 长期记忆

                > 此文件由 Nanobot Dream 长期记忆系统自动管理。
                > 启动时生成模板，对话中自动提取记忆填充。
                > 也可手动编辑，格式为 Markdown 列表项。

                ## 关于项目
                - [待自动提取]

                ## 用户偏好
                - [待自动提取]

                ## 重要决策
                - [待自动提取]
                """.formatted(java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }
}