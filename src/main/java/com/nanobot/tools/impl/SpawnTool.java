package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.core.subagent.AgentCoordinator;
import com.nanobot.tools.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Spawn 工具 — 启动子 Agent 执行子任务。
 *
 * LLM 在判断当前任务复杂、需要分解为多个子任务时调用此工具。
 * 内部委托给 AgentCoordinator 根据能力匹配选择合适的子 Agent 执行。
 */
public class SpawnTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 子 Agent 协调器，由 NanobotConfig 注入 */
    private final AgentCoordinator coordinator;

    public SpawnTool(AgentCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public String getName() { return "spawn"; }

    @Override
    public String getDescription() {
        return "启动一个子 Agent 来执行独立子任务。"
             + "当你需要同时处理多个独立任务，或某个任务可被拆分时使用。"
             + "子 Agent 有不同能力：web_search(搜索)、summarization(总结)、code(编程)、calculation(计算)。"
             + "如果子任务需要搜索，请在 task 中明确说明需要 web_search 能力。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.putObject("task").put("type", "string")
                .put("description", "分配给子 Agent 的任务描述。包含需要的子Agent能力提示，如'搜索XXX'");
        root.set("properties", props);
        root.set("required", mapper.createArrayNode().add("task"));
        return root;
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        String task = (String) params.get("task");
        if (task == null || task.isBlank())
            return CompletableFuture.completedFuture("Error: task is required");

        if (coordinator.getSubagentCount() == 0)
            return CompletableFuture.completedFuture("[spawn] 没有可用的子 Agent，任务未执行: " + task);

        return coordinator.assignTask(task, null)
                .thenApply(result -> (Object) ("[子Agent结果]\n" + (result != null ? result : "(无结果)")))
                .exceptionally(e -> "Error: 子Agent执行失败: " + e.getMessage());
    }
}
