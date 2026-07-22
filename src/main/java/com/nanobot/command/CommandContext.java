package com.nanobot.command;

import com.nanobot.core.AgentLoop;
import com.nanobot.security.PermissionManager;
import com.nanobot.tools.ToolRegistry;

/**
 * 命令执行上下文 — 提供命令所需的依赖。
 * 这个太重要了，这些都是当前正在运行的上下文
 * 这样就可以通过命令实时改变对应的状态了！！！
 */
public record CommandContext(
        /**
         * 当前会话的tools管理器
         */
        ToolRegistry toolRegistry,
        /**
         * 权限管理对象，可实时被外部命令修改！！！
         */
        PermissionManager permissionManager,
        /**
         * 整个loop对象都传进来啦！
         */
        AgentLoop agentLoop,
        /**
         * 当前会话id
         */
        String sessionId,
        Runnable shutdown
) {
}
