package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.tools.Tool;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 获取当前时间工具
 * ================
 *
 * 返回当前准确的日期和时间（含星期），解决模型训练数据日期偏差问题。
 *
 * 参数：
 * - timezone: 时区（可选，默认 Asia/Shanghai）
 */
public class GetCurrentTimeTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy年MM月dd日 EEEE");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    @Override
    public String getName() { return "get_current_time"; }

    @Override
    public String getDescription() {
        return "获取当前准确的日期和时间。当需要知道今天是什么日期、星期几、现在几点时使用此工具。"
             + "注意：你的训练数据中的日期很可能已经过时，必须通过此工具获取真实日期。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode props = mapper.createObjectNode();
        props.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();
        properties.putObject("timezone")
                .put("type", "string")
                .put("description", "时区，如 Asia/Shanghai，默认 Asia/Shanghai");

        props.set("properties", properties);
        return props;
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String tz = (String) params.getOrDefault("timezone", "Asia/Shanghai");
            ZoneId zone;
            try {
                zone = ZoneId.of(tz);
            } catch (Exception e) {
                zone = ZoneId.of("Asia/Shanghai");
            }
            ZonedDateTime now = ZonedDateTime.now(zone);
            LocalDate today = now.toLocalDate();

            StringBuilder sb = new StringBuilder();
            sb.append("【当前真实时间】\n");
            sb.append("日期: ").append(today.format(DATE_FMT)).append("\n");
            sb.append("时间: ").append(now.format(TIME_FMT)).append("\n");
            sb.append("时区: ").append(zone).append("\n");
            sb.append("ISO:  ").append(now.format(ISO_FMT)).append("\n");
            sb.append("\n⚠️ 请使用以上日期，不要使用你训练数据中的日期。");
            return sb.toString();
        });
    }

    @Override
    public boolean isReadOnly() { return true; }
}
