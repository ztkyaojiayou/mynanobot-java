package com.nanobot.skill;

import java.util.Map;

/**
 * Skill 接口
 * ===========
 * 
 * 技能接口定义了一个可复用的工作流/指令集。
 * Skills 是 Claude Code 风格的扩展机制，允许用户定义可复用的工作流。
 * 
 * **设计理念**：
 * - 技能是可复用的指令集，可以被 Agent 自动发现和使用
 * - 支持手动调用（通过 /skill-name）和自动触发（基于场景匹配）
 * - 技能可以包含模板、脚本和参考资料
 * 
 * **使用示例**：
 * ```java
 * Skill skill = Skill.loadFromDirectory(Paths.get(".claude/skills/my-skill"));
 * skill.execute(agentContext, "参数");
 * ```
 */
public interface Skill {
    
    /**
     * 获取技能名称（用于斜杠命令调用）
     */
    String getName();
    
    /**
     * 获取技能描述（用于自动触发匹配和帮助信息）
     */
    String getDescription();
    
    /**
     * 获取参数提示
     */
    String getArgumentHint();
    
    /**
     * 是否自动触发
     */
    boolean isAutoTrigger();
    
    /**
     * 获取技能内容（Markdown 格式的指令）
     */
    String getContent();
    
    /**
     * 执行技能
     * 
     * @param context 执行上下文
     * @param args 调用参数
     * @return 执行结果
     */
    String execute(Map<String, Object> context, String... args);
}