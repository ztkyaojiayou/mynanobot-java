package com.nanocode.v3.tui;

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

    // ════════════════════════ 降级过滤 ════════════════════════

    /** 正则：匹配所有 ANSI SGR/CSI/OSC 转义序列（覆盖256色、真彩、光标控制等） */
    private static final java.util.regex.Pattern ANSI_PATTERN =
            java.util.regex.Pattern.compile(
                "\033\\[[0-9;]*[a-zA-Z]"    // CSI: ESC[1;38;5;196m 等
              + "|\033\\][^\\a]*\\a"         // OSC: ESC]0;title\a
              + "|\033\\][^\\a]*\033\\\\"    // OSC (ST终结): ESC]...ESC\
              + "|\033\\([0-9BK]"            // 字符集: ESC(B ESC(0
              + "|\033[=>]"                  // 模式切换: ESC> ESC=
              + "|\033[PX^_].*?\033\\\\"     // SOS/PM/APC: ESC X ... ESC\
              + "|\033[7-8]"                 // 反显/恢复: ESC7 ESC8
              + "|\033M|\033D"               // 反向索引: ESC M, 正向索引: ESC D
              + "|\033[FH]"                  // 光标定位: ESC H, ESC F
              + "|\033c"                      // 重置终端: ESC c
            );

    /** Unicode 框线 → ASCII 降级表 */
    private static final java.util.Map<Character, Character> BOX_TO_ASCII = new java.util.HashMap<>();
    static {
        // 单线框
        BOX_TO_ASCII.put('╭', '+'); BOX_TO_ASCII.put('╮', '+');
        BOX_TO_ASCII.put('╰', '+'); BOX_TO_ASCII.put('╯', '+');
        BOX_TO_ASCII.put('│', '|'); BOX_TO_ASCII.put('─', '-');
        BOX_TO_ASCII.put('├', '+'); BOX_TO_ASCII.put('┤', '+');
        BOX_TO_ASCII.put('┬', '+'); BOX_TO_ASCII.put('┴', '+');
        BOX_TO_ASCII.put('┼', '+');
        // 双线框
        BOX_TO_ASCII.put('╔', '+'); BOX_TO_ASCII.put('╗', '+');
        BOX_TO_ASCII.put('╚', '+'); BOX_TO_ASCII.put('╝', '+');
        BOX_TO_ASCII.put('║', '|'); BOX_TO_ASCII.put('═', '=');
        BOX_TO_ASCII.put('╠', '+'); BOX_TO_ASCII.put('╣', '+');
        BOX_TO_ASCII.put('╦', '+'); BOX_TO_ASCII.put('╩', '+');
        BOX_TO_ASCII.put('╬', '+');
        // 虚线/点线
        BOX_TO_ASCII.put('┄', '-'); BOX_TO_ASCII.put('┅', '-');
        BOX_TO_ASCII.put('┆', '|'); BOX_TO_ASCII.put('┇', '|');
        BOX_TO_ASCII.put('┈', '-'); BOX_TO_ASCII.put('┉', '-');
        BOX_TO_ASCII.put('┊', '|'); BOX_TO_ASCII.put('┋', '|');
        // 块状
        BOX_TO_ASCII.put('█', '#'); BOX_TO_ASCII.put('▓', '#');
        BOX_TO_ASCII.put('▒', '.'); BOX_TO_ASCII.put('░', ' ');
    }
    /** Unicode 特殊符号 → ASCII 降级 */
    private static final java.util.Map<Character, Character> SYMBOL_TO_ASCII = new java.util.HashMap<>();
    static {
        SYMBOL_TO_ASCII.put('✓', 'v'); SYMBOL_TO_ASCII.put('✗', 'x');
        SYMBOL_TO_ASCII.put('✏', '+'); SYMBOL_TO_ASCII.put('⚡', '!');
        SYMBOL_TO_ASCII.put('⚙', '*'); SYMBOL_TO_ASCII.put('📋', '=');
        SYMBOL_TO_ASCII.put('📁', '~'); SYMBOL_TO_ASCII.put('📄', '>');
        SYMBOL_TO_ASCII.put('📂', '+'); SYMBOL_TO_ASCII.put('⏱', 't');
        SYMBOL_TO_ASCII.put('ℹ', 'i'); SYMBOL_TO_ASCII.put('❓', '?');
        SYMBOL_TO_ASCII.put('💡', '*'); SYMBOL_TO_ASCII.put('⚠', '!');
    }

    /** 清除 ANSI 转义序列 */
    public static String stripAnsi(String text) {
        if (text == null || text.isEmpty()) return text;
        if (enabled) return text;  // 正常终端不处理
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    /** 将 Unicode 框线字符替换为 ASCII 等价 */
    public static String replaceBoxChars(String text) {
        if (text == null || text.isEmpty() || enabled) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character r = BOX_TO_ASCII.get(c);
            if (r != null) { sb.append(r); continue; }
            r = SYMBOL_TO_ASCII.get(c);
            if (r != null) { sb.append(r); continue; }
            // 非 ASCII Unicode 且不在 CJK 范围 → 替换为 ?
            if (c > 127 && !Character.isIdeographic(c)
                    && Character.UnicodeBlock.of(c) != Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    && Character.UnicodeBlock.of(c) != Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    && Character.UnicodeBlock.of(c) != Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION) {
                sb.append('?');
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** dumb 终端：一站式过滤（去 ANSI + 框线降级 + 符号降级） */
    public static String filter(String text) {
        if (text == null || text.isEmpty()) return text;
        if (enabled) return text;
        return replaceBoxChars(stripAnsi(text));
    }

    // ═══════ 工具颜色映射 ═══════

    /** 根据工具名返回对应颜色（写入/执行/只读/网络/系统各有区分） */
    public static String toolColor(String toolName) {
        if (toolName == null) return GRAY;
        return switch (toolName) {
            case "exec" -> RED + B;
            case "write_file", "edit_file" -> ORANGE + B;
            case "read_file", "list_dir", "glob", "grep" -> GREEN;
            case "web_search", "web_fetch" -> BLUE;
            case "task_create", "task_update", "task_list",
                 "spawn", "spawn_check", "use_skill" -> CYAN;
            default -> toolName.startsWith("mcp_") ? MAGENTA : GRAY;
        };
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
