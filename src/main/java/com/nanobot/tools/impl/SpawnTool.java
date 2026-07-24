package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.subagent.AgentCoordinator;
import com.nanobot.subagent.FileInbox;
import com.nanobot.tools.Tool;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Spawn 工具 — 启动子 Agent 执行子任务。
 *
 * 支持两种模式：
 * - sync（默认）: 阻塞等待结果返回
 * - async: 写任务到文件 inbox，立即返回，结果通过文件回传
 */
public class SpawnTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final AgentCoordinator coordinator;
    private final FileInbox inbox;

    public SpawnTool(AgentCoordinator coordinator) {
        this.coordinator = coordinator;
        this.inbox = new FileInbox();
    }

    @Override public String getName() { return "spawn"; }

    @Override
    public String getDescription() {
        return "Launch a sub-agent for an independent task. "
             + "Use async=true for long tasks that you'll check back on later. "
             + "Available capabilities: web_search, summarization, code, calculation.";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.putObject("task").put("type", "string")
                .put("description", "Task description for the sub-agent");
        props.putObject("async").put("type", "boolean")
                .put("description", "If true, dispatch via file inbox and return immediately (default: false)");
        root.set("properties", props);
        root.set("required", mapper.createArrayNode().add("task"));
        return root;
    }

    @Override public boolean isReadOnly() { return true; }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        String task = (String) params.get("task");
        if (task == null || task.isBlank())
            return CompletableFuture.completedFuture("Error: task is required");

        if (coordinator.getSubagentCount() == 0)
            return CompletableFuture.completedFuture("[spawn] 没有可用的子 Agent");

        boolean async = params.get("async") instanceof Boolean b && b;

        if (async) {
            // 异步模式：写 inbox 文件，立即返回
            String taskId = "spawn-" + UUID.randomUUID().toString().substring(0, 8);
            inbox.writeTask(taskId, task, params);

            // 异步执行（不阻塞）
            CompletableFuture.runAsync(() -> {
                try {
                    String result = coordinator.assignTask(task, null).join();
                    inbox.writeResult(taskId, result);
                } catch (Exception e) {
                    inbox.writeResult(taskId, "Error: " + e.getMessage());
                }
            });

            return CompletableFuture.completedFuture(
                    "[spawn:async] 任务已分发 (" + taskId + ")，用 spawn_check(" + taskId + ") 查看结果");
        }

        // 同步模式（默认）：阻塞等待
        return coordinator.assignTask(task, null)
                .thenApply(result -> (Object) ("[子Agent结果]\n" + (result != null ? result : "(无结果)")))
                .exceptionally(e -> "Error: 子Agent执行失败: " + e.getMessage());
    }
}
