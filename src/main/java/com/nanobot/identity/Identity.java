package com.nanobot.identity;

import java.nio.file.Path;
import java.util.List;

/**
 * Identity - Agent 个性标识
 * =========================
 * 
 * IDENTITY.md 文件解析器，定义 Agent 的个性特征：
 * - 性格特点
 * - 语气风格
 * - 沟通方式
 * - 禁忌事项
 * 
 * **文件位置**：.nanobot/IDENTITY.md
 * 
 * **文件格式**：
 * ```markdown
 * ---
 * personality: friendly
 * tone: casual
 * language: Chinese
 * ---
 * # 我的个性
 * 
 * ## 性格特点
 * - 友好亲切
 * - 乐于助人
 * - 幽默风趣
 * 
 * ## 语气风格
 * - 使用口语化表达
 * - 避免生硬的技术术语
 * - 适当使用表情符号
 * 
 * ## 禁忌事项
 * - 不讨论敏感话题
 * - 不发表政治观点
 * ```
 */
public interface Identity {
    
    /**
     * 获取性格类型
     */
    String getPersonality();
    
    /**
     * 获取语气风格
     */
    String getTone();
    
    /**
     * 获取使用语言
     */
    String getLanguage();
    
    /**
     * 获取性格特点列表
     */
    List<String> getCharacteristics();
    
    /**
     * 获取语气规则
     */
    List<String> getToneRules();
    
    /**
     * 获取禁忌事项列表
     */
    List<String> getTaboos();
    
    /**
     * 获取个性描述正文
     */
    String getDescription();
    
    /**
     * 获取完整的个性提示词
     */
    String getPrompt();
    
    /**
     * 获取源文件路径
     */
    Path getSourcePath();
}