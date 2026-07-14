package com.nanobot.skill;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 注册中心
 * ==============
 * 
 * 管理所有已注册的技能，支持：
 * - 技能注册与注销
 * - 按名称查找技能
 * - 获取所有技能列表
 * - 搜索匹配的技能（用于自动触发）
 */
@Getter
public class SkillRegistry {
    
    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);
    
    // 技能存储：名称 -> 技能实例
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    
    /**
     * 注册技能
     */
    public void register(Skill skill) {
        String name = skill.getName().toLowerCase();
        skills.put(name, skill);
        logger.debug("Skill registered: {}", name);
    }
    
    /**
     * 注销技能
     */
    public void unregister(String name) {
        skills.remove(name.toLowerCase());
        logger.debug("Skill unregistered: {}", name);
    }
    
    /**
     * 根据名称获取技能
     */
    public Skill get(String name) {
        return skills.get(name.toLowerCase());
    }
    
    /**
     * 检查技能是否存在
     */
    public boolean contains(String name) {
        return skills.containsKey(name.toLowerCase());
    }
    
    /**
     * 获取所有技能名称
     */
    public List<String> getSkillNames() {
        return new ArrayList<>(skills.keySet());
    }
    
    /**
     * 获取所有技能
     */
    public List<Skill> getAllSkills() {
        return new ArrayList<>(skills.values());
    }
    
    /**
     * 获取技能数量
     */
    public int size() {
        return skills.size();
    }
    
    /**
     * 根据描述匹配技能（用于自动触发）
     * 
     * @param query 用户查询文本
     * @param threshold 匹配阈值（0-1）
     * @return 匹配的技能列表
     */
    public List<Skill> findMatchingSkills(String query, double threshold) {
        List<Skill> matches = new ArrayList<>();
        
        for (Skill skill : skills.values()) {
            if (!skill.isAutoTrigger()) continue;
            
            String description = skill.getDescription();
            if (description == null) continue;
            
            double score = calculateSimilarity(query.toLowerCase(), description.toLowerCase());
            if (score >= threshold) {
                matches.add(skill);
            }
        }
        
        return matches;
    }
    
    /**
     * 简单的文本相似度计算（基于词重叠）
     */
    private double calculateSimilarity(String query, String description) {
        String[] queryWords = query.split("\\s+");
        String[] descWords = description.split("\\s+");
        
        int matches = 0;
        for (String qWord : queryWords) {
            for (String dWord : descWords) {
                if (dWord.contains(qWord) || qWord.contains(dWord)) {
                    matches++;
                    break;
                }
            }
        }
        
        return (double) matches / Math.max(queryWords.length, 1);
    }
    
    /**
     * 搜索技能（简化版：检查关键词是否在描述中）
     */
    public List<Skill> searchByKeyword(String keyword) {
        List<Skill> matches = new ArrayList<>();
        String lowerKeyword = keyword.toLowerCase();
        
        for (Skill skill : skills.values()) {
            if (skill.getName().toLowerCase().contains(lowerKeyword)) {
                matches.add(skill);
            } else if (skill.getDescription() != null && 
                       skill.getDescription().toLowerCase().contains(lowerKeyword)) {
                matches.add(skill);
            }
        }
        
        return matches;
    }
    
    /**
     * 获取技能帮助信息
     */
    public String getHelp() {
        StringBuilder help = new StringBuilder();
        help.append("可用技能（Skills）:\n\n");
        
        for (Skill skill : skills.values()) {
            help.append("/").append(skill.getName());
            if (skill.getArgumentHint() != null && !skill.getArgumentHint().isBlank()) {
                help.append(" ").append(skill.getArgumentHint());
            }
            help.append("\n");
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                help.append("  - ").append(skill.getDescription()).append("\n");
            }
            help.append("\n");
        }
        
        return help.toString();
    }
    
    /**
     * 清空所有技能
     */
    public void clear() {
        skills.clear();
    }
}