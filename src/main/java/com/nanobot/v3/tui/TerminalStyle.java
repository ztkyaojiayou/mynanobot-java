package com.nanobot.v3.tui;

/**
 * 终端 ANSI 样式常量 — CLI 所有彩色输出的统一入口。
 *
 * 使用示例：
 * <pre>
 * System.out.println(TerminalStyle.RED + "✗ 错误消息" + TerminalStyle.R);
 * System.out.println(TerminalStyle.success("✓ 编译通过"));
 * </pre>
 */
public final class TerminalStyle {

    private TerminalStyle() {}

    // ═══════ 基础码 ═══════
    public static final String R    = "\033[0m";
    public static final String B    = "\033[1m";
    public static final String D    = "\033[2m";
    public static final String I    = "\033[3m";

    // ═══════ 前景色 ═══════
    public static final String RED     = "\033[38;5;196m";
    public static final String GREEN   = "\033[38;5;82m";
    public static final String YELLOW  = "\033[38;5;220m";
    public static final String BLUE    = "\033[38;5;75m";
    public static final String CYAN    = "\033[38;5;51m";
    public static final String MAGENTA = "\033[38;5;201m";
    public static final String ORANGE  = "\033[38;5;208m";
    public static final String GRAY    = "\033[38;5;242m";

    // ═══════ 语义色 ═══════
    public static final String ERR  = RED + B;
    public static final String OK   = GREEN;
    public static final String WARN = YELLOW;
    public static final String INFO = BLUE;

    // ═══════ 前缀 ═══════
    public static final String P_ERR  = RED + B + "✗ " + R;
    public static final String P_OK   = GREEN + "✓ " + R;
    public static final String P_WARN = YELLOW + B + "! " + R;
    public static final String P_INFO = BLUE + "ℹ " + R;

    // ═══════ 静态方法 ═══════

    /** 绿色成功消息 */
    public static String success(String msg) { return P_OK + msg; }

    /** 红色错误消息 */
    public static String error(String msg) { return P_ERR + RED + msg + R; }

    /** 黄色警告 */
    public static String warn(String msg) { return P_WARN + YELLOW + msg + R; }

    /** 蓝色信息 */
    public static String info(String msg) { return P_INFO + msg; }

    /** 灰色次要文本 */
    public static String dim(String msg) { return GRAY + msg + R; }

    /** 粗体 */
    public static String bold(String msg) { return B + msg + R; }

    /** 橙色高亮 */
    public static String highlight(String msg) { return ORANGE + B + msg + R; }

    /** 用特定颜色包裹 */
    public static String color(String color, String msg) { return color + msg + R; }
}
