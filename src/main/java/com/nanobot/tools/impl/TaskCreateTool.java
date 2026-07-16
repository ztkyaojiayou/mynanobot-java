package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.core.TaskStore;
import com.nanobot.tools.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 创建任务 — LLM 分解复杂需求时使用。
 */
public class TaskCreateTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final TaskStore store;

    public TaskCreateTool(TaskStore store) { this.store = store; }

    @Override public String getName() { return "task_create"; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public String getDescription() {
        return "Create a new task to track progress. "
             + "Use when working on complex multi-step work. "
             + "Returns task ID for use with task_update.";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.putObject("subject").put("type", "string").put("description", "Short task title");
        props.putObject("description").put("type", "string").put("description", "What needs to be done");
        root.set("properties", props);
        root.set("required", mapper.createArrayNode().add("subject"));
        return root;
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        String subject = (String) params.get("subject");
        String desc = (String) params.getOrDefault("description", "");
        if (subject == null || subject.isBlank()) return CompletableFuture.completedFuture("Error: subject required");

        TaskStore.Task task = store.create("default", subject, desc);
        return CompletableFuture.completedFuture(
                "Task created: [" + task.id() + "] " + task.subject() + " (pending)");
    }
}