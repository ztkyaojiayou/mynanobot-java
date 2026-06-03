package com.nanobot.session;

import com.nanobot.config.Config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器 - 会话的创建、存储和加载
 * ======================================
 * 
 * 本类负责管理用户会话，包括：
 * 1. 会话历史存储
 * 2. 会话状态管理
 * 3. 并发会话控制
 * 
 * **设计思想**：
 * 
 * 1. **文件存储**：
 *    - 每个会话一个目录
 *    - 使用 JSONL 格式存储历史
 *    - 支持大容量会话
 * 
 * 2. **会话隔离**：
 *    - 每个会话独立的文件
 *    - 支持并发处理多个会话
 *    - 防止会话数据混淆
 * 
 * **文件结构**：
 * 
 * ```
 * ~/.nanobot/sessions/
 * ├── session_123456/
 * │   ├── history.jsonl    # 对话历史
 * │   ├── metadata.json   # 元数据
 * │   └── context.json     # 上下文数据
 * └── session_789012/
 *     └── ...
 * ```
 * 
 * **history.jsonl 格式**：
 * 
 * ```json
 * {"role": "user", "content": "Hello", "timestamp": "2024-01-01T10:00:00Z"}
 * {"role": "assistant", "content": "Hi!", "timestamp": "2024-01-01T10:00:05Z"}
 * ```
 */
public class SessionManager {
    
    // ==================== 配置 ====================
    
    /** 基础目录 */
    private final Path baseDir;
    
    /** 最大历史消息数 */
    private final int maxHistorySize;
    
    /** 会话目录映射 */
    private final Map<String, Path> sessionDirs = new ConcurrentHashMap<>();
    
    // ==================== 构造函数 ====================
    
    public SessionManager(Config config) {
        String workspace = config.getAgents().getDefaults().getWorkspace();
        this.baseDir = Paths.get(workspace, "sessions");
        this.maxHistorySize = 1000;  // 可配置
        
        initStorage();
    }
    
    public SessionManager(String baseDir) {
        this.baseDir = Paths.get(baseDir, "sessions");
        this.maxHistorySize = 1000;
        
        initStorage();
    }
    
    // ==================== 初始化 ====================
    
    /**
     * 初始化存储目录
     */
    private void initStorage() {
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create sessions directory", e);
        }
    }
    
    // ==================== 会话目录管理 ====================
    
    /**
     * 获取会话目录
     */
    private Path getSessionDir(String sessionKey) {
        return sessionDirs.computeIfAbsent(sessionKey, key -> {
            // 使用安全的目录名
            String safeKey = key.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path dir = baseDir.resolve(safeKey);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create session directory: " + dir, e);
            }
            return dir;
        });
    }
    
    // ==================== 历史管理 ====================
    
    /**
     * 保存历史
     */
    public void saveHistory(String sessionKey, List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        Path historyFile = getSessionDir(sessionKey).resolve("history.jsonl");
        
        try {
            // 追加模式
            try (BufferedWriter writer = Files.newBufferedWriter(
                    historyFile, 
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                
                for (Map<String, Object> msg : messages) {
                    String json = toJson(msg);
                    writer.write(json);
                    writer.newLine();
                }
            }
            
            logger.debug("Saved {} messages to session: {}", messages.size(), sessionKey);
            
        } catch (IOException e) {
            logger.error("Failed to save history for session: {}", sessionKey, e);
        }
    }
    
    /**
     * 加载历史
     */
    public Optional<List<Map<String, Object>>> loadHistory(String sessionKey) {
        Path historyFile = getSessionDir(sessionKey).resolve("history.jsonl");
        
        if (!Files.exists(historyFile)) {
            return Optional.empty();
        }
        
        List<Map<String, Object>> messages = new ArrayList<>();
        
        try (BufferedReader reader = Files.newBufferedReader(
                historyFile, StandardCharsets.UTF_8)) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    Map<String, Object> msg = fromJson(line);
                    if (msg != null) {
                        messages.add(msg);
                    }
                }
            }
            
            logger.debug("Loaded {} messages from session: {}", messages.size(), sessionKey);
            
        } catch (IOException e) {
            logger.error("Failed to load history for session: {}", sessionKey, e);
            return Optional.empty();
        }
        
        return Optional.of(messages);
    }
    
    /**
     * 清空会话历史
     */
    public void clearSession(String sessionKey) {
        Path sessionDir = sessionDirs.get(sessionKey);
        if (sessionDir == null) {
            return;
        }
        
        Path historyFile = sessionDir.resolve("history.jsonl");
        try {
            Files.deleteIfExists(historyFile);
            logger.info("Cleared session history: {}", sessionKey);
        } catch (IOException e) {
            logger.error("Failed to clear session: {}", sessionKey, e);
        }
    }
    
    /**
     * 删除会话
     */
    public void deleteSession(String sessionKey) {
        Path sessionDir = sessionDirs.remove(sessionKey);
        if (sessionDir == null) {
            return;
        }
        
        try {
            Files.walk(sessionDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        logger.warn("Failed to delete: {}", path);
                    }
                });
            logger.info("Deleted session: {}", sessionKey);
        } catch (IOException e) {
            logger.error("Failed to delete session: {}", sessionKey, e);
        }
    }
    
    // ==================== 元数据管理 ====================
    
    /**
     * 保存会话元数据
     */
    public void saveMetadata(String sessionKey, Map<String, Object> metadata) {
        Path metadataFile = getSessionDir(sessionKey).resolve("metadata.json");
        
        try {
            String json = toJson(metadata);
            Files.writeString(metadataFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Failed to save metadata for session: {}", sessionKey, e);
        }
    }
    
    /**
     * 加载会话元数据
     */
    public Optional<Map<String, Object>> loadMetadata(String sessionKey) {
        Path metadataFile = getSessionDir(sessionKey).resolve("metadata.json");
        
        if (!Files.exists(metadataFile)) {
            return Optional.empty();
        }
        
        try {
            String json = Files.readString(metadataFile, StandardCharsets.UTF_8);
            Map<String, Object> metadata = fromJson(json);
            return Optional.ofNullable(metadata);
        } catch (IOException e) {
            logger.error("Failed to load metadata for session: {}", sessionKey, e);
            return Optional.empty();
        }
    }
    
    // ==================== 会话列表 ====================
    
    /**
     * 获取所有会话
     */
    public List<String> listSessions() {
        List<String> sessions = new ArrayList<>();
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    sessions.add(entry.getFileName().toString());
                }
            }
        } catch (IOException e) {
            logger.error("Failed to list sessions", e);
        }
        
        return sessions;
    }
    
    /**
     * 获取会话数量
     */
    public int getSessionCount() {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    count++;
                }
            }
        } catch (IOException e) {
            logger.error("Failed to count sessions", e);
        }
        return count;
    }
    
    // ==================== JSON 工具 ====================
    
    /**
     * 对象转 JSON
     */
    private static String toJson(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = 
                new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
    
    /**
     * JSON 转 Map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fromJson(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = 
                new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
    
    // ==================== 日志 ====================
    
    private static final org.slf4j.Logger logger = 
        org.slf4j.LoggerFactory.getLogger(SessionManager.class);
}
