package com.nanocode.command;

import java.util.List;

/**
 * 命令接口 — 统一命令抽象。
 *
 * 所有交互通道（CLI/WebSocket/HTTP）通过 CommandRegistry 执行命令，
 * 而非各写一套解析逻辑。
 */
public interface Command {

    /** 命令名（如 "exit", "mode"） */
    String name();

    /** 别名（如 "exit" → "q", "quit"） */
    default List<String> aliases() { return List.of(); }

    /** 描述 */
    String description();

    /**
     * 详细用法说明（供 /help 命令 查询）。多行文本（\n 分隔），
     * 可包含参数、示例。默认无额外用法（/help 只显示 description）。
     */
    default String usage() { return ""; }

    /**
     * 执行命令。
     * @param input 原始输入行（如 "/mode plan"）
     * @return true 表示需要终止当前会话/进程
     */
    boolean execute(CommandContext ctx, String input);
}
