package com.nanobot.v3.tui;

import java.util.regex.Pattern;

/**
 * 终端 Markdown 渲染器 — 纯 ANSI 转义码，零依赖。
 *
 * 支持: **粗体** *斜体* `行内代码` ```代码块``` [链接](url) ### 标题 - 列表
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    // ═══════════ ANSI 样式（TerminalStyle.disable() 后自动清空）═══════════
    private static String RESET  = "\033[0m";
    private static String BOLD   = "\033[1m";
    private static String ITALIC = "\033[3m";
    private static String UNDERLINE = "\033[4m";
    private static String PURPLE = "\033[38;5;99m";
    private static String CYAN   = "\033[38;5;80m";
    private static String GREEN  = "\033[38;5;78m";
    private static String YELLOW = "\033[38;5;214m";
    private static String GRAY   = "\033[38;5;242m";
    private static String DARK   = "\033[48;5;236m";

    /** TerminalStyle.disable() 调用后同步关闭 MarkdownRenderer 的 ANSI */
    public static void disableAnsi() {
        RESET = ""; BOLD = ""; ITALIC = ""; UNDERLINE = "";
        PURPLE = ""; CYAN = ""; GREEN = ""; YELLOW = ""; GRAY = ""; DARK = "";
    }

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
                            .append(RESET).append("\n");
                } else {
                    inCodeBlock = false;
                    sb.append(GRAY).append(" ╰").append("─".repeat(Math.max(width - 4, 8)))
                            .append(RESET).append("\n");
                }
                continue;
            }

            if (inCodeBlock) {
                // 代码行：灰色背景
                sb.append(DARK).append(" ").append(fixWidth(line, width - 2))
                        .append(" ").append(RESET).append("\n");
                continue;
            }

            // 标题（兼容"### heading"和"###heading"两种写法）
            if (line.startsWith("###") && !line.startsWith("####")) {
                String text = stripHeadingMarker(line, 3);
                sb.append(BOLD).append(PURPLE).append(text).append(RESET).append("\n");
                continue;
            }
            if (line.startsWith("##") && !line.startsWith("###")) {
                String text = stripHeadingMarker(line, 2);
                sb.append(BOLD).append(CYAN).append(text).append(RESET).append("\n");
                continue;
            }
            if (line.startsWith("#") && !line.startsWith("##")) {
                String text = stripHeadingMarker(line, 1);
                sb.append(BOLD).append(GREEN).append(text).append(RESET).append("\n");
                continue;
            }

            // 列表项
            String rendered = line;
            if (rendered.startsWith("- ") || rendered.startsWith("* ")) {
                rendered = "  " + GREEN + "•" + RESET + " " + rendered.substring(2);
            } else if (rendered.matches("^\\d+\\.\\s.*")) {
                rendered = rendered.replaceFirst("^(\\d+\\.)(\\s)", GREEN + "$1" + RESET + " ");
            }

            // 行内样式
            rendered = BOLD_PATTERN.matcher(rendered).replaceAll(BOLD + "$1" + RESET);
            rendered = ITALIC_PATTERN.matcher(rendered).replaceAll(ITALIC + "$1" + RESET);
            rendered = CODE_PATTERN.matcher(rendered).replaceAll(GRAY + DARK + "$1" + RESET);
            rendered = LINK_PATTERN.matcher(rendered).replaceAll(CYAN + UNDERLINE + "$1" + RESET + GRAY + " ($2)" + RESET);

            // 水平线
            if (rendered.equals("---") || rendered.equals("***")) {
                sb.append(GRAY).append("─".repeat(width)).append(RESET).append("\n");
                continue;
            }

            sb.append(rendered).append("\n");
        }
        return sb.toString();
    }

    /** 纯文本输出（不渲染 Markdown） — 用于流式增量 */
    public static String renderStreaming(String delta) {
        if (delta == null) return "";
        if (delta.indexOf('\n') < 0) {
            return renderStreamingLine(delta);
        }
        // 多行 delta：按行分别处理，只有行首的 # 才是标题标记
        String[] lines = delta.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            sb.append(renderStreamingLine(lines[i]));
        }
        return sb.toString();
    }

    /** 处理单行 delta（标题仅本行开头有效，去掉 # 标记符保留正文） */
    private static String renderStreamingLine(String s) {
        // 流式标题 — 用 stripHeadingMarker 去掉 # 前缀，与 render() 行为一致
        if (s.startsWith("###") && !s.startsWith("####")) {
            s = BOLD + PURPLE + stripHeadingMarker(s, 3) + RESET;
        } else if (s.startsWith("##") && !s.startsWith("###")) {
            s = BOLD + CYAN + stripHeadingMarker(s, 2) + RESET;
        } else if (s.startsWith("#") && !s.startsWith("##")) {
            s = BOLD + GREEN + stripHeadingMarker(s, 1) + RESET;
        }

        s = BOLD_PATTERN.matcher(s).replaceAll(BOLD + "$1" + RESET);
        s = ITALIC_PATTERN.matcher(s).replaceAll(ITALIC + "$1" + RESET);
        s = CODE_PATTERN.matcher(s).replaceAll(GRAY + DARK + "$1" + RESET);
        return s;
    }

    private static String fixWidth(String s, int w) {
        if (s.length() > w) return s.substring(0, w - 1) + "…";
        return s + " ".repeat(Math.max(0, w - s.length()));
    }

    /** 去掉行首的 # 标记符和紧随的空格（兼容"### heading"和"###heading"） */
    private static String stripHeadingMarker(String line, int hashCount) {
        String text = line.substring(hashCount);
        return text.startsWith(" ") ? text.substring(1) : text;
    }
}
