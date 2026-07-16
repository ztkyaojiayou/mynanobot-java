package com.nanobot.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务存储器 — 会话级别任务追踪，持久化到 JSON 文件。
 *
 * 存储位置: .nanobot/workspace/tasks/{sessionKey}.json
 */
public class TaskStore {

    private static final Logger logger = LoggerFactory.getLogger(TaskStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path baseDir;
    private final ConcurrentHashMap<String, LinkedHashMap<String, Task>> cache = new ConcurrentHashMap<>();

    public TaskStore() {
        this(Path.of(".nanobot", "workspace", "tasks"));
    }

    public TaskStore(Path baseDir) {
        this.baseDir = baseDir;
        try { Files.createDirectories(baseDir); } catch (IOException ignored) {}
    }

    /** 获取或加载会话的任务列表 */
    @SuppressWarnings("unchecked")
    private LinkedHashMap<String, Task> getTasks(String sessionKey) {
        return cache.computeIfAbsent(sessionKey, k -> {
            Path file = baseDir.resolve(sanitize(sessionKey) + ".json");
            if (Files.exists(file)) {
                try {
                    List<Map<String, Object>> list = mapper.readValue(file.toFile(),
                            new TypeReference<List<Map<String, Object>>>() {});
                    LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();
                    for (Map<String, Object> m : list) {
                        Task t = new Task(
                                (String) m.get("id"), (String) m.get("subject"),
                                (String) m.get("description"), (String) m.get("status"),
                                (List<String>) m.getOrDefault("blockedBy", List.of()),
                                (List<String>) m.getOrDefault("blocks", List.of()));
                        tasks.put(t.id(), t);
                    }
                    return tasks;
                } catch (IOException e) { logger.warn("Failed to load tasks: {}", e.getMessage()); }
            }
            return new LinkedHashMap<>();
        });
    }

    private void save(String sessionKey, LinkedHashMap<String, Task> tasks) {
        try {
            Files.createDirectories(baseDir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    baseDir.resolve(sanitize(sessionKey) + ".json").toFile(),
                    tasks.values());
        } catch (IOException e) { logger.error("Failed to save tasks: {}", e.getMessage()); }
    }

    public Task create(String sessionKey, String subject, String description) {
        LinkedHashMap<String, Task> tasks = getTasks(sessionKey);
        String id = "task-" + (tasks.size() + 1);
        Task task = new Task(id, subject, description, "pending", List.of(), List.of());
        tasks.put(id, task);
        save(sessionKey, tasks);
        return task;
    }

    public List<Task> list(String sessionKey, String statusFilter) {
        LinkedHashMap<String, Task> tasks = getTasks(sessionKey);
        if (statusFilter == null || statusFilter.isBlank()) return List.copyOf(tasks.values());
        return tasks.values().stream()
                .filter(t -> t.status().equalsIgnoreCase(statusFilter)).toList();
    }

    public Optional<Task> update(String sessionKey, String taskId, String status,
                                  List<String> blockedBy, List<String> blocks) {
        LinkedHashMap<String, Task> tasks = getTasks(sessionKey);
        Task old = tasks.get(taskId);
        if (old == null) return Optional.empty();
        Task updated = new Task(old.id(), old.subject(), old.description(),
                status != null ? status : old.status(),
                blockedBy != null ? blockedBy : old.blockedBy(),
                blocks != null ? blocks : old.blocks());
        tasks.put(taskId, updated);
        save(sessionKey, tasks);
        return Optional.of(updated);
    }

    public void clear(String sessionKey) {
        cache.remove(sessionKey);
        try { Files.deleteIfExists(baseDir.resolve(sanitize(sessionKey) + ".json")); } catch (IOException ignored) {}
    }

    private static String sanitize(String key) {
        return key.replaceAll("[^a-zA-Z0-9_\\-.:]", "_");
    }

    /** 任务记录 */
    public record Task(String id, String subject, String description,
                        String status, List<String> blockedBy, List<String> blocks) {}
}