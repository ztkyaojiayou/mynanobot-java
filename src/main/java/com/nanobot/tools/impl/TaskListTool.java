package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.subagent.TaskStore;
import com.nanobot.tools.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 列出任务 — 查看当前所有任务及其状态。
 */
public class TaskListTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final TaskStore store;

    public TaskListTool(TaskStore store) { this.store = store; }

    @Override public String getName() { return "task_list"; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public String getDescription() {
        return "List all tracked tasks with their status. Use to review progress.";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.putObject("status").put("type", "string")
                .put("description", "Filter by status: pending, in_progress, completed (empty=all)");
        root.set("properties", props);
        return root;
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        String filter = (String) params.get("status");
        List<TaskStore.Task> tasks = store.list("default", filter);
        if (tasks.isEmpty()) return CompletableFuture.completedFuture("No tasks yet.");

        StringBuilder sb = new StringBuilder("Tasks:\n");
        String[] labels = {"pending", "in_progress", "completed"};
        String[] icons = {"⬜", "🔄", "✅"};
        for (TaskStore.Task t : tasks) {
            int si = t.status().equals("in_progress") ? 1 : t.status().equals("completed") ? 2 : 0;
            sb.append("  ").append(icons[si]).append(" [").append(t.id()).append("] ")
                    .append(t.subject());
            if (!t.blockedBy().isEmpty()) sb.append(" (blocked by: ").append(String.join(",", t.blockedBy())).append(")");
            sb.append("\n");
        }
        String summary = labels[0] + ": " + tasks.stream().filter(t -> t.status().equals("pending")).count()
                + "  " + labels[1] + ": " + tasks.stream().filter(t -> t.status().equals("in_progress")).count()
                + "  " + labels[2] + ": " + tasks.stream().filter(t -> t.status().equals("completed")).count();
        sb.append("\n").append(summary);
        return CompletableFuture.completedFuture(sb.toString());
    }
}