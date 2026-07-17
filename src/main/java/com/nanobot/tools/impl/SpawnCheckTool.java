package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.core.subagent.FileInbox;
import com.nanobot.tools.Tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 检查异步 spawn 任务的结果。
 */
public class SpawnCheckTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final FileInbox inbox;

    public SpawnCheckTool() { this.inbox = new FileInbox(); }

    @Override public String getName() { return "spawn_check"; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public String getDescription() {
        return "Check the result of an async spawn task. Use after spawn(..., async=true). "
             + "Omit taskId to list all pending tasks.";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.putObject("taskId").put("type", "string")
                .put("description", "Task ID from spawn (omit to list pending)");
        root.set("properties", props);
        return root;
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        String taskId = (String) params.get("taskId");

        if (taskId == null || taskId.isBlank()) {
            // 列出待处理任务
            List<String> pending = inbox.listPendingTasks();
            if (pending.isEmpty()) return CompletableFuture.completedFuture("无待处理任务");
            return CompletableFuture.completedFuture("待处理任务(" + pending.size() + "): " + String.join(", ", pending));
        }

        // 等待结果（最多30秒）
        Optional<String> result = inbox.waitForResult(taskId, 30_000, 500);
        return result.<CompletableFuture<Object>>map(
                r -> CompletableFuture.completedFuture("[spawn结果: " + taskId + "]\n" + r))
                .orElseGet(() -> CompletableFuture.completedFuture("任务 " + taskId + " 尚未完成（或不存在）"));
    }
}
