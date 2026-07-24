package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.subagent.TaskStore;
import com.nanobot.tools.Tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 更新任务 — 修改状态、设置依赖关系。
 */
public class TaskUpdateTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final TaskStore store;

    public TaskUpdateTool(TaskStore store) { this.store = store; }

    @Override public String getName() { return "task_update"; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public String getDescription() {
        return "Update a task's status or dependencies. "
             + "Status: pending, in_progress, completed. "
             + "Only one task should be in_progress at a time.";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.putObject("taskId").put("type", "string")
                .put("description", "Task ID (e.g. task-1)");
        props.putObject("status").put("type", "string")
                .put("description", "New status: pending, in_progress, completed");
        props.putObject("blockedBy").put("type", "string")
                .put("description", "Comma-separated task IDs this task depends on");
        root.set("properties", props);
        root.set("required", mapper.createArrayNode().add("taskId"));
        return root;
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        String taskId = (String) params.get("taskId");
        String status = (String) params.get("status");
        String blockedByStr = (String) params.get("blockedBy");

        if (taskId == null) return CompletableFuture.completedFuture("Error: taskId required");

        List<String> blockedBy = (blockedByStr != null && !blockedByStr.isBlank())
                ? List.of(blockedByStr.split("\\s*,\\s*")) : null;

        Optional<TaskStore.Task> updated = store.update("default", taskId, status, blockedBy, null);
        Object result = updated.<Object>map(t -> "Task updated: [" + t.id() + "] " + t.subject()
                + " → " + t.status())
                .orElse("Error: task " + taskId + " not found");
        return CompletableFuture.completedFuture(result);
    }
}