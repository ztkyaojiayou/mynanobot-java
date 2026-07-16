package com.nanobot.v2.controller;

import com.nanobot.NanobotRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 会话管理 REST API。
 *
 * GET  /api/sessions         列出所有会话（按最近活动倒序）
 * DELETE /api/sessions/{key}  删除指定会话
 */
@RestController
@RequestMapping("/api")
public class SessionController {

    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> listSessions() {
        var sm = NanobotRunner.getSessionManager();
        if (sm == null) return ResponseEntity.ok(List.of());

        List<Map<String, Object>> list = new ArrayList<>();
        for (var info : sm.listSessionDetails()) {
            Map<String, Object> item = new HashMap<>();
            item.put("key", info.key());
            item.put("name", info.name() != null ? info.name() : info.key());
            item.put("messageCount", info.messageCount());
            item.put("lastModified", info.lastModified());
            list.add(item);
        }
        return ResponseEntity.ok(list);
    }

    /** 获取指定会话的完整消息历史 */
    @GetMapping("/sessions/{key}")
    public ResponseEntity<?> getSession(@PathVariable("key") String key) {
        var sm = NanobotRunner.getSessionManager();
        if (sm == null) return ResponseEntity.notFound().build();

        var history = sm.loadHistory(key);
        if (history.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> result = new HashMap<>();
        result.put("key", key);
        result.put("messages", history.get());
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/sessions/{key}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable("key") String key) {
        var sm = NanobotRunner.getSessionManager();
        if (sm == null) return ResponseEntity.notFound().build();

        boolean deleted = sm.deleteSession(key);
        Map<String, Object> result = new HashMap<>();
        result.put("key", key);
        result.put("deleted", deleted);
        return ResponseEntity.ok(result);
    }

    /** PATCH /api/sessions/{key} — 重命名会话，body: {"name": "新名称"} */
    @PatchMapping("/sessions/{key}")
    public ResponseEntity<Map<String, Object>> renameSession(@PathVariable("key") String key,
                                                              @RequestBody Map<String, Object> body) {
        var sm = NanobotRunner.getSessionManager();
        if (sm == null) return ResponseEntity.notFound().build();

        String name = (String) body.get("name");
        boolean ok = sm.renameSession(key, name);
        Map<String, Object> result = new HashMap<>();
        result.put("key", key);
        result.put("name", name);
        result.put("ok", ok);
        return ResponseEntity.ok(result);
    }
}
