package com.nanobot.identity;

import java.nio.file.Path;

/**
 * Soul - Agent 身份定义
 * ======================
 * 
 * SOUL.md 文件解析器，定义 Agent 的核心身份：
 * - 我是谁（角色定位）
 * - 我的目标和使命
 * - 我的价值观
 * 
 * **文件位置**：.nanobot/SOUL.md
 * 
 * **文件格式**：
 * ```markdown
 * ---
 * name: my-nanobot
 * role: AI 助手
 * version: 1.0.0
 * ---
 * # 我的身份
 * 
 * 我是 my-nanobot，一个强大的 AI 助手。
 * 
 * ## 我的使命
 * 帮助用户解决问题，提供有价值的信息和建议。
 * 
 * ## 我的价值观
 * - 诚实正直
 * - 乐于助人
 * - 持续学习
 * ```
 */
public interface Soul {
    
    /**
     * 获取 Agent 名称
     */
    String getName();
    
    /**
     * 获取角色定位
     */
    String getRole();
    
    /**
     * 获取版本号
     */
    String getVersion();
    
    /**
     * 获取身份描述（SOUL.md 正文内容）
     */
    String getDescription();
    
    /**
     * 获取完整的身份提示词
     */
    String getPrompt();
    
    /**
     * 获取源文件路径
     */
    Path getSourcePath();
}