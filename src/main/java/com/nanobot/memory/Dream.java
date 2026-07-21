package com.nanobot.memory;

import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 长期记忆系统 (Dream)
 * =====================
 * 
 * 本类实现了 AI Agent 的长期记忆功能，允许 Agent 存储和检索长期信息。
 * 
 * **核心功能**：
 * 1. 记忆存储 - 将对话中的关键信息保存到长期记忆
 * 2. 记忆检索 - 根据当前上下文检索相关记忆
 * 3. 记忆整合 - 将新信息与现有记忆融合
 * 4. 记忆清理 - 移除过时或重复的记忆
 * 
 * **记忆结构**：
 * - id: 唯一标识
 * - content: 记忆内容
 * - keywords: 关键词标签
 * - timestamp: 创建时间
 * - importance: 重要性分数 (0-1)
 * - source: 来源会话 ID
 */
public class Dream {
    
    private static final Logger logger = LoggerFactory.getLogger(Dream.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /** 两次提取之间最少新增字符数（≈ 1500 tokens），避免每轮对话都调 LLM */
    private static final int EXTRACTION_MIN_NEW_CHARS = 5000;

    private final LLMProvider llmProvider;
    private final Map<String, MemoryEntry> memories = new ConcurrentHashMap<>();
    private final int maxMemories;
    private final Path memoryDir;

    /** 记录每个 session 上次提取时的总字符数，用于增量节流 */
    private final Map<String, Integer> lastExtractionCharCount = new ConcurrentHashMap<>();

    /**
     * 创建长期记忆系统
     *
     * @param llmProvider LLM 提供商，用于记忆分析和检索
     * @param maxMemories 最大记忆数量限制
     * @param memoryDir 记忆文件存储目录（如 .nanobot/memory）
     */
    public Dream(LLMProvider llmProvider, int maxMemories, Path memoryDir) {
        this.llmProvider = llmProvider;
        this.maxMemories = maxMemories;
        this.memoryDir = memoryDir.toAbsolutePath().normalize();
        logger.info("Dream long-term memory system initialized (max: {}, dir: {})", maxMemories, this.memoryDir);
    }
    
    /**
     * 从 MEMORY.md 文件加载长期记忆
     * 
     * @param baseDir 基础目录路径
     */
    public void loadFromMemoryFile(Path baseDir) {
        List<MemoryEntry> entries = MemoryLoader.loadFromFile(baseDir);
        for (MemoryEntry entry : entries) {
            if (memories.size() < maxMemories) {
                memories.put(entry.getId(), entry);
            }
        }
        logger.info("Loaded {} memories from MEMORY.md", entries.size());
    }
    
    /**
     * 保存所有记忆到 MEMORY.md 文件
     * 
     * @param baseDir 基础目录路径
     */
    public void saveToMemoryFile(Path baseDir) {
        List<MemoryEntry> entries = new ArrayList<>(memories.values());
        MemoryLoader.saveToFile(baseDir, entries);
    }
    
    /**
     * 从对话中提取并存储记忆（增量节流：新增字符不足阈值则跳过）。
     *
     * @param sessionId 会话 ID
     * @param messages 消息列表
     * @return 存储的记忆条目列表（跳过时返回空列表）
     */
    public CompletableFuture<List<MemoryEntry>> extractAndStore(String sessionId,
                                                               List<Map<String, Object>> messages) {
        // ── 增量节流：新增内容不够就不提取 ──
        int currentChars = countTotalChars(messages);
        int lastChars = lastExtractionCharCount.getOrDefault(sessionId, 0);
        int newChars = currentChars - lastChars;

        if (newChars < EXTRACTION_MIN_NEW_CHARS && lastChars > 0) {
            // 首次提取 (lastChars==0) 不跳过；后续增量不足才跳过
            logger.debug("Dream extraction skipped: only {} new chars (need {}), session={}",
                    newChars, EXTRACTION_MIN_NEW_CHARS, sessionId);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        logger.info("Dream extracting: {} new chars since last extraction, session={}", newChars, sessionId);
        lastExtractionCharCount.put(sessionId, currentChars);

        return analyzeMessages(messages)
            .thenApply(entries -> {
                List<MemoryEntry> stored = new ArrayList<>();
                for (MemoryEntry entry : entries) {
                    if (storeMemory(sessionId, entry)) {
                        stored.add(entry);
                    }
                }
                if (!stored.isEmpty()) {
                    // 自动持久化到 .nanobot/memory/MEMORY.md
                    saveToMemoryFile(memoryDir);
                }
                logger.info("Extracted {} memories from session {}", stored.size(), sessionId);
                return stored;
            });
    }

    /** 估算消息列表的总字符数（所有 role + content 的字符累计） */
    private static int countTotalChars(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (content instanceof String s) {
                total += s.length();
            }
        }
        return total;
    }
    
    /**
     * 检索相关记忆
     * 
     * @param query 查询文本
     * @param limit 返回数量限制
     * @return 相关记忆列表
     */
    public CompletableFuture<List<MemoryEntry>> retrieve(String query, int limit) {
        if (memories.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        
        return rankMemories(query)
            .thenApply(ranked -> ranked.stream()
                .limit(limit)
                .map(r -> r.entry)
                .collect(Collectors.toList()));
    }
    
    /**
     * 整合新信息到现有记忆
     * 
     * @param newMemory 新记忆
     * @return 整合后的记忆
     */
    public CompletableFuture<MemoryEntry> consolidate(MemoryEntry newMemory) {
        // 查找相关的现有记忆
        return findRelatedMemories(newMemory)
            .thenApply(related -> {
                if (related.isEmpty()) {
                    // 没有相关记忆，直接存储
                    storeMemory(newMemory.getSource(), newMemory);
                    return newMemory;
                }
                
                // 合并相关记忆
                MemoryEntry merged = mergeMemories(newMemory, related);
                storeMemory(merged.getSource(), merged);
                
                // 删除被合并的旧记忆
                for (MemoryEntry old : related) {
                    memories.remove(old.getId());
                }
                
                logger.info("Consolidated {} memories into one", related.size() + 1);
                return merged;
            });
    }
    
    /**
     * 清理过时记忆
     */
    public void cleanup() {
        // 移除过期记忆（超过 30 天）
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        memories.entrySet().removeIf(entry -> 
            entry.getValue().getTimestamp().isBefore(cutoff)
        );
        
        // 如果超过最大限制，按重要性排序并移除最不重要的
        while (memories.size() > maxMemories) {
            String leastImportant = memories.values().stream()
                .min(Comparator.comparingDouble(MemoryEntry::getImportance))
                .map(MemoryEntry::getId)
                .orElse(null);
            
            if (leastImportant != null) {
                memories.remove(leastImportant);
            }
        }
        
        logger.info("Cleanup completed, {} memories remaining", memories.size());
    }
    
    /**
     * 获取所有记忆
     */
    public List<MemoryEntry> getAllMemories() {
        return new ArrayList<>(memories.values());
    }
    
    /**
     * 获取记忆数量
     */
    public int getMemoryCount() {
        return memories.size();
    }
    
    /**
     * 分析消息并提取记忆条目
     */
    private CompletableFuture<List<MemoryEntry>> analyzeMessages(List<Map<String, Object>> messages) {
        StringBuilder content = new StringBuilder();
        content.append("请从以下对话中提取值得长期记忆的信息：\n\n");
        
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            String msgContent = (String) msg.get("content");
            content.append(role).append(": ").append(msgContent).append("\n");
        }
        
        content.append("\n请以 JSON 格式输出记忆条目，每个条目包含：");
        content.append("\n- content: 记忆内容");
        content.append("\n- keywords: 关键词数组");
        content.append("\n- importance: 重要性(0-1)");
        
        List<LLMProvider.Message> llmMessages = List.of(
            LLMProvider.Message.ofUser(content.toString())
        );
        
        return llmProvider.chat(llmMessages, Collections.emptyList())
            .thenApply(response -> parseMemoryResponse(response.getContent()));
    }
    
    /**
     * 解析 LLM 返回的记忆响应（JSON 数组 → MemoryEntry 列表）
     */
    private List<MemoryEntry> parseMemoryResponse(String response) {
        try {
            if (response == null || response.isBlank()) {
                return Collections.emptyList();
            }

            // 提取 JSON 数组
            int start = response.indexOf('[');
            int end = response.lastIndexOf(']');

            if (start == -1 || end == -1 || start >= end) {
                // 非 JSON 格式，将全文作为单条记忆
                MemoryEntry entry = new MemoryEntry();
                entry.setId(UUID.randomUUID().toString());
                entry.setContent(response.trim());
                entry.setKeywords(List.of("general"));
                entry.setImportance(0.5);
                entry.setTimestamp(LocalDateTime.now());
                return List.of(entry);
            }

            String jsonArray = response.substring(start, end + 1);
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> rawList = mapper.readValue(
                    jsonArray,
                    new TypeReference<List<Map<String, Object>>>() {});

            List<MemoryEntry> entries = new ArrayList<>();
            for (Map<String, Object> raw : rawList) {
                MemoryEntry entry = new MemoryEntry();
                entry.setId(UUID.randomUUID().toString());
                entry.setContent(Objects.toString(raw.get("content"), "").trim());
                entry.setImportance(parseDouble(raw.get("importance"), 0.5));
                entry.setTimestamp(LocalDateTime.now());

                // 解析 keywords
                Object kwObj = raw.get("keywords");
                List<String> keywords = new ArrayList<>();
                if (kwObj instanceof List<?> kwList) {
                    for (Object item : kwList) {
                        keywords.add(Objects.toString(item, ""));
                    }
                }
                entry.setKeywords(keywords);

                if (!entry.getContent().isBlank()) {
                    entries.add(entry);
                }
            }
            return entries;

        } catch (Exception e) {
            logger.warn("Failed to parse memory response, treating as plain text: {}", e.getMessage());
            // 降级：将全文作为单条记忆
            MemoryEntry entry = new MemoryEntry();
            entry.setId(UUID.randomUUID().toString());
            entry.setContent(response != null ? response.trim() : "");
            entry.setKeywords(List.of("general"));
            entry.setImportance(0.3);
            entry.setTimestamp(LocalDateTime.now());
            return entry.getContent().isBlank() ? Collections.emptyList() : List.of(entry);
        }
    }

    private static double parseDouble(Object val, double defaultVal) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }
    
    /**
     * 存储记忆
     */
    private boolean storeMemory(String sessionId, MemoryEntry entry) {
        if (memories.size() >= maxMemories) {
            cleanup();
        }
        
        if (memories.size() >= maxMemories) {
            logger.warn("Memory limit reached, cannot store new memory");
            return false;
        }
        
        entry.setId(UUID.randomUUID().toString());
        entry.setSource(sessionId);
        entry.setTimestamp(LocalDateTime.now());
        
        memories.put(entry.getId(), entry);
        logger.debug("Stored memory: {}", entry.getContent());
        return true;
    }
    
    /**
     * 对记忆进行排序（基于相关性）
     */
    private CompletableFuture<List<MemoryRank>> rankMemories(String query) {
        List<MemoryRank> ranks = new ArrayList<>();
        
        for (MemoryEntry entry : memories.values()) {
            double score = calculateSimilarity(query, entry);
            ranks.add(new MemoryRank(entry, score));
        }
        
        ranks.sort((a, b) -> Double.compare(b.score, a.score));
        
        return CompletableFuture.completedFuture(ranks);
    }
    
    /**
     * 计算查询与记忆的相似度
     */
    private double calculateSimilarity(String query, MemoryEntry entry) {
        double score = 0;
        
        // 内容相似度
        String content = entry.getContent().toLowerCase();
        String queryLower = query.toLowerCase();
        
        for (String word : queryLower.split("\\s+")) {
            if (content.contains(word)) {
                score += 0.2;
            }
        }
        
        // 关键词匹配
        for (String keyword : entry.getKeywords()) {
            if (queryLower.contains(keyword.toLowerCase())) {
                score += 0.3;
            }
        }
        
        // 重要性加成
        score += entry.getImportance() * 0.5;
        
        return Math.min(1.0, score);
    }
    
    /**
     * 查找相关记忆
     */
    private CompletableFuture<List<MemoryEntry>> findRelatedMemories(MemoryEntry newMemory) {
        List<MemoryEntry> related = new ArrayList<>();
        
        for (MemoryEntry existing : memories.values()) {
            double similarity = calculateSimilarity(newMemory.getContent(), existing);
            if (similarity > 0.3) {
                related.add(existing);
            }
        }
        
        return CompletableFuture.completedFuture(related);
    }
    
    /**
     * 合并多个记忆
     */
    private MemoryEntry mergeMemories(MemoryEntry newMemory, List<MemoryEntry> existing) {
        MemoryEntry merged = new MemoryEntry();
        merged.setId(UUID.randomUUID().toString());
        
        // 合并内容
        StringBuilder content = new StringBuilder();
        content.append(newMemory.getContent());
        for (MemoryEntry e : existing) {
            content.append(" | ").append(e.getContent());
        }
        merged.setContent(content.toString());
        
        // 合并关键词
        Set<String> keywords = new HashSet<>(newMemory.getKeywords());
        for (MemoryEntry e : existing) {
            keywords.addAll(e.getKeywords());
        }
        merged.setKeywords(new ArrayList<>(keywords));
        
        // 计算平均重要性
        double avgImportance = (newMemory.getImportance() + 
            existing.stream().mapToDouble(MemoryEntry::getImportance).sum()) / 
            (existing.size() + 1);
        merged.setImportance(avgImportance);
        
        merged.setTimestamp(LocalDateTime.now());
        merged.setSource(newMemory.getSource());
        
        return merged;
    }
    
    /**
     * 记忆条目
     */
    public static class MemoryEntry {
        private String id;
        private String content;
        private List<String> keywords = new ArrayList<>();
        private LocalDateTime timestamp;
        private double importance = 0.5;
        private String source;
        
        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        
        public List<String> getKeywords() { return keywords; }
        public void setKeywords(List<String> keywords) { this.keywords = keywords; }
        
        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
        
        public double getImportance() { return importance; }
        public void setImportance(double importance) { this.importance = importance; }
        
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        
        @Override
        public String toString() {
            return String.format("MemoryEntry{id='%s', content='%s', timestamp=%s, importance=%.2f}",
                id, content, timestamp != null ? timestamp.format(FORMATTER) : "null", importance);
        }
    }
    
    /**
     * 记忆排序辅助类
     */
    private static class MemoryRank {
        final MemoryEntry entry;
        final double score;
        
        MemoryRank(MemoryEntry entry, double score) {
            this.entry = entry;
            this.score = score;
        }
    }
}
