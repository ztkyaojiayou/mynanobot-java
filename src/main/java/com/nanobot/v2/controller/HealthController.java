package com.nanobot.v2.controller;

import com.nanobot.NanobotRunner;
import com.nanobot.bus.MessageBus;
import com.nanobot.core.AgentLoop;
import com.nanobot.session.SessionManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查和系统信息 Controller
 */
@RestController
@RequestMapping("/actuator")
public class HealthController {
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new HashMap<>();
        
        // 基本状态
        result.put("status", "UP");
        result.put("service", "nanobot-java");
        result.put("timestamp", System.currentTimeMillis());
        
        // 组件状态
        Map<String, Boolean> components = new HashMap<>();
        components.put("messageBus", NanobotRunner.getMessageBus() != null);
        components.put("agentLoop", NanobotRunner.getAgentLoop() != null);
        components.put("sessionManager", NanobotRunner.getSessionManager() != null);
        components.put("config", NanobotRunner.getConfig() != null);
        result.put("components", components);
        
        // 所有组件都健康才返回 UP
        boolean allHealthy = components.values().stream().allMatch(v -> v);
        result.put("status", allHealthy ? "UP" : "DOWN");
        
        return ResponseEntity.ok(result);
    }
    
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> result = new HashMap<>();
        
        result.put("service", "nanobot-java");
        result.put("version", "2.0.0-SNAPSHOT");
        result.put("description", "AI Agent Framework with Spring Boot");
        
        // 添加配置信息（脱敏后）
        if (NanobotRunner.getConfig() != null) {
            Map<String, Object> config = new HashMap<>();
            config.put("model", NanobotRunner.getConfig().getAgents().getDefaults().getModel());
            config.put("workspace", NanobotRunner.getConfig().getAgents().getDefaults().getWorkspace());
            result.put("config", config);
        }
        
        return ResponseEntity.ok(result);
    }
    
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        Map<String, Object> result = new HashMap<>();
        
        // 内存信息
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        Map<String, Object> memory = new HashMap<>();
        memory.put("heapUsed", memoryBean.getHeapMemoryUsage().getUsed());
        memory.put("heapMax", memoryBean.getHeapMemoryUsage().getMax());
        memory.put("heapCommitted", memoryBean.getHeapMemoryUsage().getCommitted());
        memory.put("nonHeapUsed", memoryBean.getNonHeapMemoryUsage().getUsed());
        result.put("memory", memory);
        
        // 运行时信息
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> runtime = new HashMap<>();
        runtime.put("uptime", runtimeBean.getUptime());
        runtime.put("startTime", runtimeBean.getStartTime());
        runtime.put("vmName", runtimeBean.getVmName());
        runtime.put("vmVersion", runtimeBean.getVmVersion());
        result.put("runtime", runtime);
        
        // Agent 统计
        if (NanobotRunner.getAgentLoop() != null) {
            Map<String, Object> agent = new HashMap<>();
            agent.put("running", NanobotRunner.getAgentLoop().isRunning());
            result.put("agent", agent);
        }
        
        // Session 统计
        if (NanobotRunner.getSessionManager() != null) {
            Map<String, Object> sessions = new HashMap<>();
            sessions.put("activeCount", NanobotRunner.getSessionManager().getSessionCount());
            result.put("sessions", sessions);
        }
        
        return ResponseEntity.ok(result);
    }
}
