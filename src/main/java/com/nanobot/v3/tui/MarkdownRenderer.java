package com.nanobot.v3.tui;

import java.util.regex.Pattern;

/**
 * 终端 Markdown 渲染器 — 纯 ANSI 转义码，零依赖。
 *
 * 支持: **粗体** *斜体* `行内代码` ```代码块``` [链接](url) ### 标题 - 列表
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    // ═══════════ ANSI 样式 ═══════════
    private static final String R = "\033[0m";
    private static final String B = "\033[1m";
    private static final String I = "\033[3m";
    private static final String U = "\033[4m";
    private static final String PURPLE = "\033[38;5;99m";
    private static final String CYAN   = "\033[38;5;80m";
    private static final String GREEN  = "\033[38;5;78m";
    private static final String YELLOW = "\033[38;5;214m";
    private static final String GRAY   = "\033[38;5;242m";
    private static final String DARK   = "\033[48;5;236m";

    private static final Pattern BOLD_PATTERN   = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<![*])\\*(.+?)\\*(?![*])");
    private static final Pattern CODE_PATTERN   = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK_PATTERN   = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");

    /** 终端宽度（默认80） */
    public static String render(String markdown) {
        return render(markdown, 80);
    }

    /** 渲染 Markdown 到 ANSI 终端字符串 */
    public static String render(String markdown, int width) {
        if (markdown == null || markdown.isEmpty()) return "";

        var sb = new StringBuilder();
        var lines = markdown.split("\n", -1);
        boolean inCodeBlock = false;
        String lang = "";

        for (String line : lines) {
            // 代码块开始/结束
            if (line.startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    lang = line.length() > 3 ? line.substring(3).trim() : "";
                    sb.append(GRAY).append(" ╭─").append(lang.isEmpty() ? "" : " " + lang).append(" ")
                            .append("─".repeat(Math.max(width - 8 - lang.length(), 8)))
                            .append(R).append("\n");
                } else {
                    inCodeBlock = false;
                    sb.append(GRAY).append(" ╰").append("─".repeat(Math.max(width - 4, 8)))
                            .append(R).append("\n");
                }
                continue;
            }

            if (inCodeBlock) {
                // 代码行：灰色背景
                sb.append(DARK).append(" ").append(fixWidth(line, width - 2))
                        .append(" ").append(R).append("\n");
                continue;
            }

            // 标题
            if (line.startsWith("### ")) {
                sb.append(B).append(PURPLE).append(line.substring(4)).append(R).append("\n");
                continue;
            }
            if (line.startsWith("## ")) {
                sb.append(B).append(CYAN).append(line.substring(3)).append(R).append("\n");
                continue;
            }
            if (line.startsWith("# ")) {
                sb.append(B).append(GREEN).append(line.substring(2)).append(R).append("\n");
                continue;
            }

            // 列表项
            String rendered = line;
            if (rendered.startsWith("- ") || rendered.startsWith("* ")) {
                rendered = "  " + GREEN + "•" + R + " " + rendered.substring(2);
            } else if (rendered.matches("^\\d+\\.\\s.*")) {
                rendered = rendered.replaceFirst("^(\\d+\\.)(\\s)", GREEN + "$1" + R + " ");
            }

            // 行内样式
            rendered = BOLD_PATTERN.matcher(rendered).replaceAll(B + "$1" + R);
            rendered = ITALIC_PATTERN.matcher(rendered).replaceAll(I + "$1" + R);
            rendered = CODE_PATTERN.matcher(rendered).replaceAll(GRAY + DARK + "$1" + R);
            rendered = LINK_PATTERN.matcher(rendered).replaceAll(CYAN + U + "$1" + R + GRAY + " ($2)" + R);

            // 水平线
            if (rendered.equals("---") || rendered.equals("***")) {
                sb.append(GRAY).append("─".repeat(width)).append(R).append("\n");
                continue;
            }

            sb.append(rendered).append("\n");
        }
        return sb.toString();
    }

    /** 纯文本输出（不渲染 Markdown） — 用于流式增量 */
    public static String renderStreaming(String delta) {
        if (delta == null) return "";
        String s = delta;
        s = BOLD_PATTERN.matcher(s).replaceAll(B + "$1" + R);
        s = ITALIC_PATTERN.matcher(s).replaceAll(I + "$1" + R);
        s = CODE_PATTERN.matcher(s).replaceAll(GRAY + DARK + "$1" + R);
        return s;
    }

    private static String fixWidth(String s, int w) {
        if (s.length() > w) return s.substring(0, w - 1) + "…";
        return s + " ".repeat(Math.max(0, w - s.length()));
    }
}
