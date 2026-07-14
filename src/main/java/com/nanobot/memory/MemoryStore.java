package com.nanobot.memory;

import com.nanobot.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * 内存存储 - 长期记忆管理
 * =========================
 * 
 * 本类负责管理 Agent 的长期记忆，包括：
 * 1. MEMORY.md - 事实知识
 * 2. SOUL.md - 核心指令和人格
 * 3. USER.md - 用户配置
 * 
 * **设计思想**：
 * 
 * 1. **分层存储**：
 *    - 不同的文件存储不同类型的记忆
 *    - 便于管理和更新
 * 
 * 2. **文件格式**：
 *    - Markdown 格式，利于 LLM 理解和编辑
 *    - 支持结构化内容
 * 
 * **文件结构**：
 * 
 * ```
 * ~/.nanobot/
 * ├── memory/
 * │   ├── MEMORY.md     # 长期记忆
 * │   ├── SOUL.md       # 灵魂/指令
 * │   └── USER.md       # 用户信息
 * └── sessions/         # 会话历史
 * ```
 * 
 * **MEMORY.md 示例**：
 * 
 * ```markdown
 * # 记忆
 * 
 * ## 关于项目
 * - 这是一个 Python 项目
 * - 使用 FastAPI 框架
 * - 需要 PostgreSQL 数据库
 * 
 * ## 重要决策
 * - 使用 Redis 缓存
 * - 部署在 Kubernetes
 * ```
 */
public class MemoryStore {
    
    // ==================== 日志 ====================
    
    private static final Logger logger = LoggerFactory.getLogger(MemoryStore.class);
    
    // ==================== 路径 ====================
    
    private final Path memoryDir;
    private final Path memoryFile;
    private final Path soulFile;
    private final Path userFile;
    
    // ==================== 构造函数 ====================
    
    public MemoryStore(Config config) {
        String workspace = config.getWorkspacePath();
        this.memoryDir = Paths.get(workspace, "memory");
        this.memoryFile = memoryDir.resolve("MEMORY.md");
        this.soulFile = memoryDir.resolve("SOUL.md");
        this.userFile = memoryDir.resolve("USER.md");
        
        initMemory();
    }
    
    public MemoryStore(String basePath) {
        this.memoryDir = Paths.get(basePath, "memory");
        this.memoryFile = memoryDir.resolve("MEMORY.md");
        this.soulFile = memoryDir.resolve("SOUL.md");
        this.userFile = memoryDir.resolve("USER.md");
        
        initMemory();
    }
    
    // ==================== 初始化 ====================
    
    /**
     * 初始化记忆目录
     */
    private void initMemory() {
        try {
            Files.createDirectories(memoryDir);
            
            // 创建默认文件（如果不存在）
            if (!Files.exists(memoryFile)) {
                Files.writeString(memoryFile, getDefaultMemoryContent(), StandardCharsets.UTF_8);
            }
            if (!Files.exists(soulFile)) {
                Files.writeString(soulFile, getDefaultSoulContent(), StandardCharsets.UTF_8);
            }
            if (!Files.exists(userFile)) {
                Files.writeString(userFile, getDefaultUserContent(), StandardCharsets.UTF_8);
            }
            
            logger.info("Memory store initialized at: {}", memoryDir);
            
        } catch (IOException e) {
            logger.error("Failed to initialize memory store", e);
            throw new RuntimeException("Failed to initialize memory store", e);
        }
    }
    
    // ==================== 读取方法 ====================
    
    /**
     * 读取记忆内容
     */
    public String readMemory() {
        return readFile(memoryFile);
    }
    
    /**
     * 读取灵魂文件
     */
    public String readSoul() {
        return readFile(soulFile);
    }
    
    /**
     * 读取用户文件
     */
    public String readUser() {
        return readFile(userFile);
    }
    
    /**
     * 读取文件（安全方法）
     */
    private String readFile(Path path) {
        if (!Files.exists(path)) {
            return "";
        }
        
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Failed to read file: {}", path, e);
            return "";
        }
    }
    
    // ==================== 写入方法 ====================
    
    /**
     * 写入记忆内容
     */
    public void writeMemory(String content) {
        writeFile(memoryFile, content);
    }
    
    /**
     * 写入灵魂文件
     */
    public void writeSoul(String content) {
        writeFile(soulFile, content);
    }
    
    /**
     * 写入用户文件
     */
    public void writeUser(String content) {
        writeFile(userFile, content);
    }
    
    /**
     * 写入文件（原子操作）
     */
    private void writeFile(Path path, String content) {
        try {
            // 先写入临时文件
            Path temp = Files.createTempFile(
                path.getParent(), 
                ".tmp", 
                ".md"
            );
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            
            // 原子替换
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            
            logger.debug("Written to file: {}", path);
            
        } catch (IOException e) {
            logger.error("Failed to write file: {}", path, e);
            throw new RuntimeException("Failed to write file: " + path, e);
        }
    }
    
    // ==================== 追加方法 ====================
    
    /**
     * 追加记忆
     */
    public void appendMemory(String entry) {
        appendFile(memoryFile, entry);
    }
    
    /**
     * 追加文件
     */
    private void appendFile(Path path, String entry) {
        try {
            String content = Files.exists(path) 
                ? Files.readString(path, StandardCharsets.UTF_8) 
                : "";
            
            String newContent = content.isEmpty() 
                ? entry 
                : content + "\n\n" + entry;
            
            writeFile(path, newContent);
            
        } catch (IOException e) {
            logger.error("Failed to append to file: {}", path, e);
        }
    }
    
    // ==================== 删除方法 ====================
    
    /**
     * 删除记忆条目
     */
    public boolean deleteMemoryEntry(String marker) {
        try {
            String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
            
            // 简单实现：删除包含标记的行
            String[] lines = content.split("\n");
            StringBuilder newContent = new StringBuilder();
            boolean skip = false;
            
            for (String line : lines) {
                if (line.contains(marker)) {
                    skip = true;
                    continue;
                }
                if (skip && line.startsWith("#")) {
                    skip = false;
                }
                if (!skip) {
                    newContent.append(line).append("\n");
                }
            }
            
            writeFile(memoryFile, newContent.toString().trim());
            return true;
            
        } catch (IOException e) {
            logger.error("Failed to delete memory entry", e);
            return false;
        }
    }
    
    // ==================== 搜索方法 ====================
    
    /**
     * 搜索记忆
     */
    public List<String> searchMemory(String query) {
        List<String> results = new ArrayList<>();
        
        try {
            String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            
            boolean inResult = false;
            StringBuilder result = new StringBuilder();
            
            for (String line : lines) {
                if (line.toLowerCase().contains(query.toLowerCase())) {
                    inResult = true;
                    result = new StringBuilder();
                }
                
                if (inResult) {
                    result.append(line).append("\n");
                    
                    if (line.startsWith("#") && result.length() > line.length() + 1) {
                        // 遇到下一个标题，结束
                        results.add(result.toString().trim());
                        inResult = false;
                    }
                }
            }
            
            if (inResult && result.length() > 0) {
                results.add(result.toString().trim());
            }
            
        } catch (IOException e) {
            logger.error("Failed to search memory", e);
        }
        
        return results;
    }
    
    // ==================== 统计方法 ====================
    
    /**
     * 获取记忆统计
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            String memory = Files.readString(memoryFile, StandardCharsets.UTF_8);
            String soul = Files.readString(soulFile, StandardCharsets.UTF_8);
            
            stats.put("memorySize", memory.length());
            stats.put("memoryLines", memory.split("\n").length);
            stats.put("soulSize", soul.length());
            stats.put("memoryDir", memoryDir.toString());
            stats.put("lastModified", getLastModified());
            
        } catch (IOException e) {
            logger.error("Failed to get stats", e);
        }
        
        return stats;
    }
    
    /**
     * 获取最后修改时间
     */
    public Instant getLastModified() {
        try {
            if (Files.exists(memoryFile)) {
                return Files.getLastModifiedTime(memoryFile).toInstant();
            }
        } catch (IOException e) {
            // 忽略
        }
        return Instant.now();
    }
    
    // ==================== 默认内容 ====================
    
    private String getDefaultMemoryContent() {
        return """
            # 记忆
            
            ## 关于项目
            - 项目名称：[待填写]
            - 技术栈：[待填写]
            - 目标：[待填写]
            
            ## 重要信息
            - [使用此区域存储重要事实和决策]
            
            ## 学习
            - [记录学到的知识]
            """;
    }
    
    private String getDefaultSoulContent() {
        return """
            # 灵魂
            
            你是一个有帮助的 AI 助手。
            
            ## 核心原则
            1. 提供准确、有用的信息
            2. 保持友好和专业
            3. 承认错误并改正
            
            ## 工作方式
            - 理解用户需求
            - 提供清晰的解释
            - 必要时使用工具
            """;
    }
    
    private String getDefaultUserContent() {
        return """
            # 用户信息
            
            ## 偏好
            - 编程语言：[待填写]
            - 沟通风格：[待填写]
            
            ## 上下文
            - 当前项目：[待填写]
            - 目标：[待填写]
            """;
    }
    
    // ==================== 生命周期 ====================
    
    /**
     * 清理旧记忆
     */
    public int cleanupOldEntries(int maxAgeDays) {
        // TODO: 实现基于时间的清理
        return 0;
    }
    
    /**
     * 压缩记忆
     */
    public void compress() {
        // TODO: 实现记忆压缩
    }
}
