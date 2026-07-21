package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.skill.Skill;
import com.nanobot.skill.SkillRegistry;
import com.nanobot.tools.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 技能激活元工具 — 一个工具管理所有 Skill。
 *
 * <h2>设计思路</h2>
 * <pre>
 *   不用为每个 Skill 注册独立 tool（tools 数组会膨胀，LLM 选择准确率下降）。
 *   而是注册一个 use_skill 元工具，通过 name 参数分发到对应 Skill。
 *
 *   LLM 流程：
 *   ① System Prompt 中看到技能目录（名称+描述）
 *   ② 判断当前任务需要某个技能 → 调用 use_skill(name="code-review")
 *   ③ execute() 从 SkillRegistry 查到 SKILL.md 全文并返回
 *   ④ LLM 拿到技能全文指令 → 按指令执行任务
 * </pre>
 *
 * <h2>与普通工具的区别</h2>
 * 普通工具：execute() 调外部 API / 读文件 / 执行命令
 * 本工具：execute() 返回一段预制的提示词文本（SKILL.md 全文）
 * 对 LLM 来说两者完全一样——都是调 tool → 拿结果 → 继续。
 */
public class UseSkillTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final SkillRegistry registry;

    public UseSkillTool(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getName() {
        return "use_skill";
    }

    @Override
    public String getDescription() {
        return "激活一个可用技能，获取该技能的详细执行指令。" +
               "当你需要对代码做全面审查、生成文档、重构代码等操作时，" +
               "先调用此工具获取对应技能的指令指南，然后严格按照指南执行。" +
               "可用技能列表见 System Prompt 中的「可用技能」章节。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();
        ObjectNode nameProp = mapper.createObjectNode();
        nameProp.put("type", "string");
        nameProp.put("description", "要激活的技能名称。" +
                getAllSkillNames() + "。请从 System Prompt 的「可用技能」列表中确认技能名。");
        properties.set("name", nameProp);
        params.set("properties", properties);

        var required = mapper.createArrayNode();
        required.add("name");
        params.set("required", required);

        return params;
    }

    @Override
    public boolean isReadOnly() {
        return true; // 只返回提示词，不修改任何东西
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String name = (String) params.get("name");
            if (name == null || name.isBlank()) {
                return "❌ 请指定技能名称。可用: " + getAllSkillNames();
            }

            Skill skill = registry.get(name.trim());
            if (skill == null) {
                return "❌ 未知技能: " + name + "。可用: " + getAllSkillNames() +
                       "\n请从 System Prompt 的「可用技能」列表中选择。";
            }

            StringBuilder result = new StringBuilder();
            result.append("【技能已激活: ").append(skill.getName()).append("】\n\n");
            result.append("请严格按照以下指令执行任务：\n\n");
            result.append("---\n\n");
            result.append(skill.getContent());
            result.append("\n---\n\n");
            result.append("【执行要求】以上是该技能的完整指南。" +
                          "请逐一遵循指南中的每个步骤，完成用户的任务。");

            return result.toString();
        });
    }

    private String getAllSkillNames() {
        List<Skill> skills = registry.getAllSkills();
        if (skills.isEmpty()) return "（无可用技能）";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < skills.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(skills.get(i).getName());
        }
        return sb.toString();
    }
}
