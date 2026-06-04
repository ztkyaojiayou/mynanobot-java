package com.nanobot.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Rule 注册中心
 * ==============
 * 
 * 管理所有已注册的规则，支持：
 * - 规则注册与注销
 * - 按优先级排序的规则列表
 * - 按标签筛选规则
 * - 获取规则提示词（合并所有规则内容）
 */
public class RuleRegistry {
    
    // 规则存储：名称 -> 规则实例
    private final Map<String, Rule> rules = new ConcurrentHashMap<>();
    
    /**
     * 注册规则
     */
    public void register(Rule rule) {
        String name = rule.getName().toLowerCase();
        rules.put(name, rule);
    }
    
    /**
     * 注销规则
     */
    public void unregister(String name) {
        rules.remove(name.toLowerCase());
    }
    
    /**
     * 根据名称获取规则
     */
    public Rule get(String name) {
        return rules.get(name.toLowerCase());
    }
    
    /**
     * 检查规则是否存在
     */
    public boolean contains(String name) {
        return rules.containsKey(name.toLowerCase());
    }
    
    /**
     * 获取所有规则名称
     */
    public List<String> getRuleNames() {
        return new ArrayList<>(rules.keySet());
    }
    
    /**
     * 获取所有规则（按优先级排序）
     */
    public List<Rule> getAllRulesSorted() {
        return rules.values().stream()
            .filter(Rule::isEnabled)
            .sorted(Comparator.comparingInt(Rule::getPriority))
            .collect(Collectors.toList());
    }
    
    /**
     * 获取所有规则
     */
    public List<Rule> getAllRules() {
        return new ArrayList<>(rules.values());
    }
    
    /**
     * 根据类型获取规则
     */
    public List<Rule> getRulesByType(Rule.RuleType type) {
        return rules.values().stream()
            .filter(r -> r.getType() == type)
            .filter(Rule::isEnabled)
            .sorted(Comparator.comparingInt(Rule::getPriority))
            .collect(Collectors.toList());
    }
    
    /**
     * 根据标签获取规则
     */
    public List<Rule> getRulesByTag(String tag) {
        String lowerTag = tag.toLowerCase();
        return rules.values().stream()
            .filter(r -> r.isEnabled())
            .filter(r -> r.getTags().stream()
                .anyMatch(t -> t.toLowerCase().contains(lowerTag)))
            .sorted(Comparator.comparingInt(Rule::getPriority))
            .collect(Collectors.toList());
    }
    
    /**
     * 获取规则数量
     */
    public int size() {
        return rules.size();
    }
    
    /**
     * 获取规则提示词（合并所有规则内容）
     * 
     * @return 适合注入到系统提示词的规则文本
     */
    public String getRulesPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("## 行为规则\n\n");
        prompt.append("你必须遵守以下规则：\n\n");
        
        List<Rule> sortedRules = getAllRulesSorted();
        for (int i = 0; i < sortedRules.size(); i++) {
            Rule rule = sortedRules.get(i);
            prompt.append(i + 1).append(". **").append(rule.getName()).append("**");
            if (!rule.getDescription().isEmpty()) {
                prompt.append(" - ").append(rule.getDescription());
            }
            prompt.append("\n");
            prompt.append(rule.getContent().trim()).append("\n\n");
        }
        
        return prompt.toString();
    }
    
    /**
     * 获取规则摘要（仅规则名称和描述）
     */
    public String getRulesSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("已加载规则：\n");
        
        for (Rule rule : getAllRulesSorted()) {
            summary.append("- ").append(rule.getName());
            if (!rule.getDescription().isEmpty()) {
                summary.append(" (").append(rule.getDescription()).append(")");
            }
            summary.append("\n");
        }
        
        return summary.toString();
    }
    
    /**
     * 清空所有规则
     */
    public void clear() {
        rules.clear();
    }
}