package com.nanobot.identity;

import com.nanobot.config.Config;
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
     */
    public String getSystemPrompt(String currentDate) {
        StringBuilder prompt = new StringBuilder();
        
        // 基础身份信息
        prompt.append(getCombinedPrompt());
        
        // 添加日期
        if (currentDate != null && !currentDate.isBlank()) {
            prompt.append("【当前日期】\n");
            prompt.append(currentDate).append("\n\n");
        }
        
        return prompt.toString();
    }
    
    // ==================== Getters ====================
    
    public Soul getSoul() {
        return soul;
    }
    
    public Identity getIdentity() {
        return identity;
    }
    
    public UserProfile getUserProfile() {
        return userProfile;
    }
    
    public Path getBaseDir() {
        return baseDir;
    }
}