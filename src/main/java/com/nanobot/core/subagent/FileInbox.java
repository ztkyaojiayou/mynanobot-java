package com.nanobot.core.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * 文件邮箱 — 子 Agent 异步通信。
 *
 * 目录结构:
 *   .nanobot/inbox/
 *     {taskId}.json       ← 任务文件（Lead 写入）
 *     {taskId}.result.json ← 结果文件（子Agent 写入）
 *
 * 同步模式（默认）: agent.execute(task).join() 阻塞等待
 * 异步模式: 写文件 → 返回 → 子Agent 轮询 → 写结果 → Lead 轮询结果
 */
public class FileInbox {

    private static final Logger logger = LoggerFactory.getLogger(FileInbox.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path inboxDir;

    public FileInbox() {
        this(Path.of(".nanobot", "inbox"));
    }

    public FileInbox(Path inboxDir) {
        this.inboxDir = inboxDir;
        try { Files.createDirectories(inboxDir); } catch (IOException ignored) {}
        logger.debug("FileInbox initialized: {}", inboxDir);
    }

    /** 写入任务 */
    public void writeTask(String taskId, String task, Map<String, Object> context) {
        try {
            var data = new HashMap<String, Object>();
            data.put("id", taskId);
            data.put("task", task);
            data.put("metadata", context);
            data.put("status", "pending");
            data.put("createdAt", Instant.now().toString());
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    inboxDir.resolve(taskId + ".json").toFile(), data);
            logger.debug("Task written: {}", taskId);
        } catch (IOException e) {
            logger.error("Failed to write task: {}", e.getMessage());
        }
    }

    /** 写入结果 */
    public void writeResult(String taskId, String result) {
        try {
            var data = new HashMap<String, Object>();
            data.put("id", taskId);
            data.put("result", result);
            data.put("completedAt", Instant.now().toString());
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    inboxDir.resolve(taskId + ".result.json").toFile(), data);
            // 删除任务文件，表示已完成
            Files.deleteIfExists(inboxDir.resolve(taskId + ".json"));
            logger.debug("Result written: {}", taskId);
        } catch (IOException e) {
            logger.error("Failed to write result: {}", e.getMessage());
        }
    }

    /** 列出待处理任务（pending 状态） */
    public List<String> listPendingTasks() {
        List<String> tasks = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inboxDir, "*.json")) {
            for (Path f : ds) {
                if (f.toString().endsWith(".result.json")) continue;
                tasks.add(f.getFileName().toString().replace(".json", ""));
            }
        } catch (IOException ignored) {}
        return tasks;
    }

    /** 读取任务文件 */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> readTask(String taskId) {
        Path file = inboxDir.resolve(taskId + ".json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(file.toFile(), Map.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** 轮询等待结果（最多 waitMs 毫秒，间隔 sleepMs） */
    public Optional<String> waitForResult(String taskId, long waitMs, long sleepMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            Path resultFile = inboxDir.resolve(taskId + ".result.json");
            if (Files.exists(resultFile)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = mapper.readValue(resultFile.toFile(), Map.class);
                    Files.deleteIfExists(resultFile);
                    return Optional.ofNullable((String) data.get("result"));
                } catch (IOException e) {
                    return Optional.empty();
                }
            }
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return Optional.empty();
    }

    /** 清理旧文件 */
    public void clean() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inboxDir)) {
            for (Path f : ds) Files.deleteIfExists(f);
        } catch (IOException ignored) {}
    }
}
