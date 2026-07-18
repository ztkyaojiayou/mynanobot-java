package com.nanobot.session;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 会话存储层 — 纯文件 I/O，不包含锁和业务逻辑。
 *
 * 路径: {baseDir}/{safeKey}/history.jsonl + metadata.json
 */
class SessionStore {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path baseDir;
    private final Map<String, Path> dirCache = new HashMap<>();

    SessionStore(Path baseDir) {
        this.baseDir = baseDir;
        try { Files.createDirectories(baseDir); } catch (IOException e) {
            throw new RuntimeException("Failed to create sessions directory: " + baseDir, e);
        }
    }

    // ── 目录管理 ──

    Path getSessionDir(String sessionKey) {
        return dirCache.computeIfAbsent(sessionKey, key -> {
            String safeKey = key.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path dir = baseDir.resolve(safeKey);
            try { Files.createDirectories(dir); } catch (IOException e) {
                throw new RuntimeException("Failed to create session dir: " + dir, e);
            }
            return dir;
        });
    }

    // ── 历史读写 ──

    void saveHistory(String sessionKey, List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return;
        Path file = getSessionDir(sessionKey).resolve("history.jsonl");

        int existing = lineCount(file);
        if (messages.size() <= existing) return;

        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (int i = existing; i < messages.size(); i++) {
                w.write(toJson(messages.get(i)));
                w.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save history: " + sessionKey, e);
        }
    }

    Optional<List<Map<String, Object>>> loadHistory(String sessionKey) {
        Path file = getSessionDir(sessionKey).resolve("history.jsonl");
        if (!Files.exists(file)) return Optional.empty();

        List<Map<String, Object>> messages = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) messages.add(fromJson(line));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load history: " + sessionKey, e);
        }
        return messages.isEmpty() ? Optional.empty() : Optional.of(messages);
    }

    void deleteHistory(String sessionKey) {
        try { Files.deleteIfExists(getSessionDir(sessionKey).resolve("history.jsonl")); }
        catch (IOException ignored) {}
    }

    // ── 元数据读写 ──

    void saveMetadata(String sessionKey, Map<String, Object> metadata) {
        try {
            Files.writeString(getSessionDir(sessionKey).resolve("metadata.json"),
                    toJson(metadata), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save metadata: " + sessionKey, e);
        }
    }

    Optional<Map<String, Object>> loadMetadata(String sessionKey) {
        Path file = getSessionDir(sessionKey).resolve("metadata.json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            Map<String, Object> meta = fromJson(Files.readString(file, StandardCharsets.UTF_8));
            return Optional.ofNullable(meta);
        } catch (IOException e) { return Optional.empty(); }
    }

    // ── 会话列表与删除 ──

    List<String> listSessions() {
        List<String> sessions = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(baseDir)) {
            for (Path p : s) if (Files.isDirectory(p)) sessions.add(p.getFileName().toString());
        } catch (IOException ignored) {}
        return sessions;
    }

    List<SessionManager.SessionInfo> listSessionDetails() {
        List<SessionManager.SessionInfo> list = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(baseDir)) {
            for (Path dir : s) {
                if (!Files.isDirectory(dir)) continue;
                String key = dir.getFileName().toString();
                Path hf = dir.resolve("history.jsonl");
                int count = lineCount(hf);
                long lastMod = Files.exists(hf)
                        ? Files.getLastModifiedTime(hf).toMillis()
                        : Files.getLastModifiedTime(dir).toMillis();
                String name = loadMetadata(key).map(m -> (String) m.get("name")).orElse(null);
                list.add(new SessionManager.SessionInfo(key, name, count, lastMod));
            }
        } catch (IOException ignored) {}
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    boolean deleteSession(String sessionKey) {
        Path dir = getSessionDir(sessionKey);
        if (!Files.exists(dir)) return false;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) { return false; }
        dirCache.remove(sessionKey);
        return true;
    }

    int sessionCount() {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(baseDir)) {
            int count = 0;
            for (@SuppressWarnings("unused") Path p : s) count++;
            return count;
        } catch (IOException ignored) { return 0; }
    }

    // ── 工具方法 ──

    private static int lineCount(Path file) {
        if (!Files.exists(file)) return 0;
        try (BufferedReader r = Files.newBufferedReader(file)) {
            int count = 0;
            while (r.readLine() != null) count++;
            return count;
        } catch (IOException e) { return 0; }
    }

    static String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fromJson(String json) {
        try { return mapper.readValue(json, Map.class); } catch (Exception e) { return Map.of(); }
    }
}
