package com.nanobot.v3.tui;

/**
 * 终端 ANSI 样式常量 — CLI 所有彩色输出的统一入口。
 *
 * 支持降级：Windows CMD / dumb 终端下调用 {@link #disable()} 后，
 * 所有 ANSI 常量变为空字符串，自动输出纯文本。
 *
 * 使用示例：
 * <pre>
 * System.out.println(TerminalStyle.RED + "✗ 错误消息" + TerminalStyle.R);
 * System.out.println(TerminalStyle.success("✓ 编译通过"));
 * </pre>
 */
public final class TerminalStyle {

    private TerminalStyle() {}

    private static volatile boolean enabled = true;

    /** 关闭所有 ANSI 颜色（用于 dumb 终端 / Windows CMD） */
    public static void disable() {
        if (!enabled) return;
        enabled = false;
        R = ""; B = ""; D = ""; I = "";
        RED = ""; GREEN = ""; YELLOW = ""; BLUE = ""; CYAN = ""; MAGENTA = ""; ORANGE = ""; GRAY = "";
        ERR = ""; OK = ""; WARN = ""; INFO = "";
        P_ERR = "[!] "; P_OK = "  > "; P_WARN = "[W] "; P_INFO = "[i] ";
        // 同步关闭 MarkdownRenderer 的 ANSI
        MarkdownRenderer.disableAnsi();
    }

    /** 当前是否启用 ANSI */
    public static boolean isEnabled() { return enabled; }

    // ═══════ 基础码（非 final，disable() 可置空）═══════
    public static String R    = "\033[0m";
    public static String B    = "\033[1m";
    public static String D    = "\033[2m";
    public static String I    = "\033[3m";

    // ═══════ 前景色 ═══════
    public static String RED     = "\033[38;5;196m";
    public static String GREEN   = "\033[38;5;82m";
    public static String YELLOW  = "\033[38;5;220m";
    public static String BLUE    = "\033[38;5;75m";
    public static String CYAN    = "\033[38;5;51m";
    public static String MAGENTA = "\033[38;5;201m";
    public static String ORANGE  = "\033[38;5;208m";
    public static String GRAY    = "\033[38;5;242m";

    // ═══════ 语义色 ═══════
    public static String ERR  = RED + B;
    public static String OK   = GREEN;
    public static String WARN = YELLOW;
    public static String INFO = BLUE;

    // ═══════ 前缀 ═══════
    public static String P_ERR  = RED + B + "✗ " + R;
    public static String P_OK   = GREEN + "✓ " + R;
    public static String P_WARN = YELLOW + B + "! " + R;
    public static String P_INFO = BLUE + "ℹ " + R;

    // ═══════ ANSI 过滤器 ═══════

    /** 正则匹配所有 ANSI CSI 转义序列（用于 dumb 终端二次兜底过滤） */
    private static final java.util.regex.Pattern ANSI_PATTERN =
            java.util.regex.Pattern.compile(
                "\033\\[[0-9;]*[a-zA-Z]"    // CSI: ESC[ ... 字母
                + "|\033\\][^\\a]*\\a"       // OSC: ESC] ... BEL
                + "|\033\\([0-9B]"            // 字符集选择
                + "|\033[PX^_].*?\033\\\\"    // 其他转义序列
            );

    /** 清除字符串中的所有 ANSI 转义序列（用于 dumb 终端的二次兜底过滤） */
    public static String stripAnsi(String text) {
        if (text == null || text.isEmpty() || enabled) return text;
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    // ═══════ 静态方法 ═══════

    public static String success(String msg) { return P_OK + msg; }
    public static String error(String msg)   { return P_ERR + RED + msg + R; }
    public static String warn(String msg)    { return P_WARN + YELLOW + msg + R; }
    public static String info(String msg)    { return P_INFO + msg; }
    public static String dim(String msg)     { return GRAY + msg + R; }
    public static String bold(String msg)    { return B + msg + R; }
    public static String highlight(String msg) { return ORANGE + B + msg + R; }
    public static String color(String color, String msg) { return color + msg + R; }
}
