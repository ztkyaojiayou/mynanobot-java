package com.nanobot.session;

import com.nanobot.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器 — 会话锁定 + 业务逻辑。
 * 存储操作委托给 {@link SessionStore}。
 */
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    private final SessionStore store;
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public SessionManager(Config config) {
        this.store = new SessionStore(Paths.get(config.getWorkspacePath(), "sessions"));
    }

    public SessionManager(String baseDir) {
        this.store = new SessionStore(Paths.get(baseDir, "sessions"));
    }

    // ── 历史管理（带锁） ──

    private Object lock(String sessionKey) {
        return sessionLocks.computeIfAbsent(sessionKey, k -> new Object());
    }

    public void saveHistory(String sessionKey, List<Map<String, Object>> messages) {
        synchronized (lock(sessionKey)) {
            try { store.saveHistory(sessionKey, messages); }
            catch (Exception e) { logger.error("Failed to save history: {}", sessionKey, e); }
        }
    }

    public Optional<List<Map<String, Object>>> loadHistory(String sessionKey) {
        synchronized (lock(sessionKey)) {
            try { return store.loadHistory(sessionKey); }
            catch (Exception e) { logger.error("Failed to load history: {}", sessionKey, e); return Optional.empty(); }
        }
    }

    public void clearSession(String sessionKey) {
        store.deleteHistory(sessionKey);
        logger.info("Cleared session history: {}", sessionKey);
    }

    // ── 元数据 ──

    public void saveMetadata(String sessionKey, Map<String, Object> metadata) {
        store.saveMetadata(sessionKey, metadata);
    }

    public Optional<Map<String, Object>> loadMetadata(String sessionKey) {
        return store.loadMetadata(sessionKey);
    }

    // ── 会话列表 / 删除 / 重命名 / 统计 ──

    public List<String> listSessions() { return store.listSessions(); }

    public List<SessionInfo> listSessionDetails() { return store.listSessionDetails(); }

    public boolean deleteSession(String sessionKey) {
        boolean ok = store.deleteSession(sessionKey);
        if (ok) sessionLocks.remove(sessionKey);
        return ok;
    }

    public boolean renameSession(String sessionKey, String name) {
        Map<String, Object> meta = loadMetadata(sessionKey).orElse(new HashMap<>());
        meta.put("name", name != null && !name.isBlank() ? name.trim() : null);
        saveMetadata(sessionKey, meta);
        logger.info("Renamed session: {} -> \"{}\"", sessionKey, name);
        return true;
    }

    public int getSessionCount() { return store.sessionCount(); }

    public record SessionInfo(String key, String name, int messageCount, long lastModified) {}
}
