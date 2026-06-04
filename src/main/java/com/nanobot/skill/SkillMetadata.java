package com.nanobot.skill;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill 元数据
 * ============
 * 
 * 技能的元数据信息，从 SKILL.md 的 YAML 前置元数据中解析。
 */
public class SkillMetadata {
    
    private String name;
    private String description;
    private String argumentHint;
    private boolean autoTrigger = true;
    private Map<String, String> properties = new HashMap<>();
    
    public SkillMetadata() {}
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getArgumentHint() {
        return argumentHint;
    }
    
    public void setArgumentHint(String argumentHint) {
        this.argumentHint = argumentHint;
    }
    
    public boolean isAutoTrigger() {
        return autoTrigger;
    }
    
    public void setAutoTrigger(boolean autoTrigger) {
        this.autoTrigger = autoTrigger;
    }
    
    public Map<String, String> getProperties() {
        return properties;
    }
    
    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }
    
    public void addProperty(String key, String value) {
        this.properties.put(key, value);
    }
}