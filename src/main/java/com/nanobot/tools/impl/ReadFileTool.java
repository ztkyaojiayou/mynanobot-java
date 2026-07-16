package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.tools.Tool;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 读取文件工具。
 *
 * 参数：
 * - path: 文件路径（必填）
 * - offset: 起始行号（1-based，默认1）
 * - limit: 读取行数（默认2000，设0表示全部）
 */
public class ReadFileTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 2000;

    public ReadFileTool() {}

    @Override public String getName() { return "read_file"; }

    @Override
    public String getDescription() {
        return "Read a file with line numbers. Default reads up to " + DEFAULT_LIMIT
             + " lines from the beginning. Use offset and limit for large files.";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.putObject("path").put("type", "string").put("description", "File path to read");
        props.putObject("offset").put("type", "integer")
                .put("description", "Start line number (1-based, default 1)");
        props.putObject("limit").put("type", "integer")
                .put("description", "Max lines to read (default " + DEFAULT_LIMIT + ", 0=unlimited)");
        root.set("properties", props);
        root.set("required", mapper.createArrayNode().add("path"));
        return root;
    }

    @Override public boolean isReadOnly() { return true; }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String pathStr = (String) params.get("path");
            if (pathStr == null) return "Error: path is required";

            try {
                Path filePath = Paths.get(pathStr);
                if (!Files.exists(filePath))
                    return "Error: file not found: " + pathStr;
                if (Files.isDirectory(filePath))
                    return "Error: path is a directory: " + pathStr;

                List<String> allLines = Files.readAllLines(filePath);
                int totalLines = allLines.size();

                int offset = params.containsKey("offset")
                        ? Math.max(1, ((Number) params.get("offset")).intValue()) : 1;
                int limit = params.containsKey("limit")
                        ? ((Number) params.get("limit")).intValue() : DEFAULT_LIMIT;
                if (limit <= 0) limit = Integer.MAX_VALUE;

                int start = Math.min(offset - 1, totalLines);
                int end = Math.min(start + limit, totalLines);
                List<String> lines = allLines.subList(start, end);

                StringBuilder sb = new StringBuilder();
                sb.append("File: ").append(filePath)
                        .append(" (").append(totalLines).append(" lines total");
                if (end < totalLines || start > 0)
                    sb.append(", showing lines ").append(start + 1).append("-").append(end);
                sb.append(")\n\n");

                for (int i = 0; i < lines.size(); i++)
                    sb.append(String.format("%6d  %s\n", start + i + 1, lines.get(i)));

                if (totalLines > end)
                    sb.append("\n... (use offset=").append(end + 1)
                            .append(" to read more)");

                return sb.toString();
            } catch (IOException e) {
                return "Error reading file: " + e.getMessage();
            }
        });
    }
}
