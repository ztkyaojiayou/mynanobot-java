package com.nanocode.command.impl;

import com.nanocode.NanobotRunner;
import com.nanocode.command.Command;
import com.nanocode.command.CommandContext;
import com.nanocode.session.SessionManager;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * /resume — 列出或恢复历史会话。
 *
 * /resume            列出最近会话
 * /resume cli-xxx    恢复到指定会话（切换 sessionId + 加载历史上下文）
 */
public class ResumeCommand implements Command {

    private final Consumer<String> onResume; // 回调：通知 CliChannel 切换 sessionId

    public ResumeCommand(Consumer<String> onResume) { this.onResume = onResume; }

    @Override public String name() { return "resume"; }
    @Override public String description() { return "列出或恢复历史会话"; }

    @Override
    public String usage() {
        return "  用法: /resume [会话key]\n"
             + "  无参数: 列出最近 5 个会话\n"
             + "  /resume 会话key: 恢复指定会话的上下文";
    }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        SessionManager sm = NanobotRunner.getSessionManager();
        if (sm == null) { System.out.println("会话管理器未就绪"); return false; }
        //提取命令中的参数，对于命令的处理其实就当做是一个接口请求即可！
        String arg = input.length() > 7 ? input.substring(8).trim() : "";
        if (!arg.isEmpty()) {
            // 校验会话是否存在
            if (sm.loadHistory(arg).isEmpty()) {
                System.out.println("会话不存在: " + arg + "（输入 /resume 查看可用会话）");
                return false;
            }
            System.out.println("已切换到会话: " + arg + "（发送新消息将恢复上下文）");
            onResume.accept(arg);
            return false;
        }

        // 列出最近5个会话
        List<SessionManager.SessionInfo> sessions = sm.listSessionDetails();
        if (sessions.isEmpty()) { System.out.println("暂无历史会话"); return false; }

        System.out.println("最近会话（/resume <key> 恢复）:");
        int count = 0;
        for (var s : sessions) {
            if (++count > 5) break;
            String time = DateTimeFormatter.ofPattern("MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(s.lastModified()));
            System.out.printf("  %-40s %4d 条消息  %s%n",
                    s.key(), s.messageCount(), time);
        }
        return false;
    }
}
