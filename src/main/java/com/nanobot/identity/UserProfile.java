package com.nanobot.identity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * UserProfile - 用户信息
 * ======================
 * 
 * USER.md 文件解析器，存储和管理用户信息：
 * - 用户基本信息
 * - 用户偏好设置
 * - 用户历史记录摘要
 * - 用户目标和需求
 * 
 * **文件位置**：.nanobot/USER.md
 * 
 * **文件格式**：
 * ```markdown
 * ---
 * name: 张三
 * role: 开发者
 * expertise: Java, Python
 * goals: 学习 AI 开发
 * ---
 * # 用户信息
 * 
 * ## 基本信息
 * - 姓名：张三
 * - 职业：软件工程师
 * - 工作年限：5年
 * 
 * ## 技术专长
 * - Java 后端开发
 * - 微服务架构
 * - AI 应用开发
 * 
 * ## 学习目标
 * - 掌握 AI Agent 开发
 * - 学习大语言模型应用
 * 
 * ## 偏好设置
 * - 喜欢简洁的回答
 * - 偏好中文交流
 * ```
 */
public interface UserProfile {
    
    /**
     * 获取用户名
     */
    String getName();
    
    /**
     * 获取用户角色/职业
     */
    String getRole();
    
    /**
     * 获取专业领域
     */
    List<String> getExpertise();
    
    /**
     * 获取用户目标
     */
    List<String> getGoals();
    
    /**
     * 获取用户偏好设置
     */
    Map<String, String> getPreferences();
    
    /**
     * 获取用户信息正文
     */
    String getDescription();
    
    /**
     * 获取完整的用户信息提示词
     */
    String getPrompt();
    
    /**
     * 获取源文件路径
     */
    Path getSourcePath();
}