package com.nanobot.skill;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill 元数据 — 从 SKILL.md 的 YAML 前置元数据中解析。
 */
@Data
@NoArgsConstructor
public class SkillMetadata {

    private String name;
    private String description;
    private String argumentHint;
    private boolean autoTrigger = true;
    private Map<String, String> properties = new HashMap<>();

    public void addProperty(String key, String value) {
        this.properties.put(key, value);
    }
}
