package com.nanobot.command;

import com.nanobot.core.AgentLoop;
import com.nanobot.security.PermissionManager;
import com.nanobot.tools.ToolRegistry;

import java.io.PrintStream;

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
        /**
         * 会话存储 key（已算好的完整 key：CLI 为 "cli:"+sessionId，Web 为裸 sessionId）。
         * 命令一律用它读写 SessionManager，禁止自己拼前缀——否则会误清错误的历史。
         */
        String sessionKey,
        /**
         * 消息来源通道（"cli" / "ws" / "http"），/clear 发布 _session_cleared 事件时使用
         */
        String channel,
        /**
         * 命令输出目标。CLI 传 System.out；Web（CommandState）传收集 buffer，
         * 执行完把内容作为最终响应返回给前端。
         */
        PrintStream out,
        Runnable shutdown
) {
}
