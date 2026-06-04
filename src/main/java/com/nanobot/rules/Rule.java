package com.nanobot.rules;

import java.util.Collections;
import java.util.List;

/**
 * Rule 接口
 * ===========
 * 
 * 规则接口定义了一个可复用的行为规范。
 * Rules 是参考 Claude Code 的设计理念，通过自然语言指令定义 Agent 的行为规范。
 * 
 * **设计理念**：
 * - 规则是用自然语言写的指令文本
 * - 告诉模型"在这个项目中你应该遵循什么规范"
 * - 支持项目级和全局级规则
 * 
 * **使用方式**：
 * 在项目根目录创建 CLAUDE.md 或 .nanobot/rules/*.md 文件
 */
public interface Rule {
    
    /**
     * 获取规则名称
     */
    String getName();
    
    /**
     * 获取规则描述
     */
    String getDescription();
    
    /**
     * 获取规则内容（自然语言指令）
     */
    String getContent();
    
    /**
     * 获取规则类型
     */
    RuleType getType();
    
    /**
     * 获取规则优先级（数字越小优先级越高）
     */
    int getPriority();
    
    /**
     * 是否启用
     */
    boolean isEnabled();
    
    /**
     * 获取规则适用的上下文标签
     */
    default List<String> getTags() {
        return Collections.emptyList();
    }
    
    /**
     * 获取规则来源路径
     */
    String getSourcePath();
    
    /**
     * 规则类型枚举
     */
    enum RuleType {
        /** 项目级规则 */
        PROJECT,
        /** 用户级规则 */
        USER,
        /** 全局默认规则 */
        GLOBAL,
        /** 内置规则 */
        BUILTIN
    }
}