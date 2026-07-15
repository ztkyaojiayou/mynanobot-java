package com.nanobot.tools.impl;

import com.nanobot.tools.annotation.ToolDef;
import com.nanobot.tools.annotation.ToolParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 内置常用工具 — 通过 @ToolDef 方法级别注解自动注册。
 */
public class BuiltinTools {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy年MM月dd日 EEEE");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ═══════════ 日期时间 ═══════════

    @ToolDef(description = "获取今天的日期（年月日+星期）。当需要知道今天是什么日期、星期几时使用此工具")
    public String getToday() {
        LocalDate today = LocalDate.now();
        return "今天是 " + today.format(DATE_FMT);
    }

    @ToolDef(description = "获取当前精确时间（含时区）。当需要知道现在几点几分时使用此工具")
    public String getCurrentTime(
            @ToolParam(description = "时区，如 Asia/Shanghai，默认 Asia/Shanghai") String timezone) {
        ZoneId zone;
        try { zone = ZoneId.of(timezone); } catch (Exception e) { zone = ZoneId.of("Asia/Shanghai"); }
        ZonedDateTime now = ZonedDateTime.now(zone);
        return "当前时间: " + now.format(TIME_FMT) + " (" + zone + ")";
    }

    // ═══════════ 数学计算 ═══════════

    @ToolDef(description = "执行四则运算：加法")
    public String add(
            @ToolParam(description = "第一个数") double a,
            @ToolParam(description = "第二个数") double b) {
        return a + " + " + b + " = " + formatNum(a + b);
    }

    @ToolDef(description = "执行四则运算：减法")
    public String subtract(
            @ToolParam(description = "被减数") double a,
            @ToolParam(description = "减数") double b) {
        return a + " - " + b + " = " + formatNum(a - b);
    }

    @ToolDef(description = "执行四则运算：乘法")
    public String multiply(
            @ToolParam(description = "第一个数") double a,
            @ToolParam(description = "第二个数") double b) {
        return a + " × " + b + " = " + formatNum(a * b);
    }

    @ToolDef(description = "执行四则运算：除法")
    public String divide(
            @ToolParam(description = "被除数") double a,
            @ToolParam(description = "除数") double b) {
        if (b == 0) return "错误: 除数不能为零";
        return a + " ÷ " + b + " = " + formatNum(a / b);
    }

    @ToolDef(description = "生成指定范围内的随机整数")
    public String randomInt(
            @ToolParam(description = "最小值（包含）") int min,
            @ToolParam(description = "最大值（包含）") int max) {
        if (min > max) { int t = min; min = max; max = t; }
        int result = min + (int) (Math.random() * (max - min + 1));
        return "随机数 [" + min + ", " + max + "]: " + result;
    }

    // ═══════════ 文本处理 ═══════════

    @ToolDef(description = "统计文本的字符数、单词数、行数")
    public String countText(
            @ToolParam(description = "要统计的文本", required = true) String text) {
        int chars = text.length();
        int words = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
        int lines = text.isEmpty() ? 0 : text.split("\n").length;
        return String.format("字符数: %d, 单词数: %d, 行数: %d", chars, words, lines);
    }

    @ToolDef(description = "对文本进行 Base64 编码")
    public String base64Encode(
            @ToolParam(description = "要编码的文本", required = true) String text) {
        return java.util.Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @ToolDef(description = "对 Base64 字符串进行解码")
    public String base64Decode(
            @ToolParam(description = "要解码的 Base64 字符串", required = true) String encoded) {
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "解码失败: " + e.getMessage();
        }
    }

    private String formatNum(double n) {
        return n == Math.floor(n) && !Double.isInfinite(n) ? String.valueOf((long) n) : String.format("%.4f", n);
    }
}
