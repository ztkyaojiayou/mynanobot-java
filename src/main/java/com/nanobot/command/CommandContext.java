package com.nanobot.command;

import com.nanobot.core.AgentLoop;
import com.nanobot.security.PermissionManager;
import com.nanobot.tools.ToolRegistry;

/**
 * 命令执行上下文 — 提供命令所需的依赖。
 */
public record CommandContext(
        ToolRegistry toolRegistry,
        PermissionManager permissionManager,
        AgentLoop agentLoop,
        String sessionId,
        Runnable shutdown
) {}
