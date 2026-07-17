package com.nanobot.command.impl;

import com.nanobot.NanobotRunner;
import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;
import com.nanobot.session.SessionManager;

import java.util.List;

/**
 * /resume — 列出或恢复历史会话。
 *
 * /resume            列出最近会话
 * /resume cli-xxx    恢复到指定会话（通过修改 chatId 重建上下文）
 */
public class ResumeCommand implements Command {

    private final Runnable onResume; // 回调：通知 CliChannel 切换 sessionId

    public ResumeCommand(Runnable onResume) { this.onResume = onResume; }

    @Override public String name() { return "resume"; }
    @Override public String description() { return "列出或恢复历史会话"; }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        SessionManager sm = NanobotRunner.getSessionManager();
        if (sm == null) { System.out.println("会话管理器未就绪"); return false; }

        String arg = input.length() > 7 ? input.substring(8).trim() : "";
        if (!arg.isEmpty()) {
            // 恢复到指定会话
            System.out.println("已切换到会话: " + arg + "（发送新消息将恢复上下文）");
            onResume.run(); // 通知 CliChannel 更新 sessionId 的逻辑在此
            return false;
        }

        // 列出最近5个会话
        List<SessionManager.SessionInfo> sessions = sm.listSessionDetails();
        if (sessions.isEmpty()) { System.out.println("暂无历史会话"); return false; }

        System.out.println("最近会话（/resume <key> 恢复）:");
        int count = 0;
        for (var s : sessions) {
            if (++count > 5) break;
            System.out.printf("  %-40s %4d 条消息  %s%n",
                    s.key(), s.messageCount(),
                    new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(s.lastModified())));
        }
        return false;
    }
}
