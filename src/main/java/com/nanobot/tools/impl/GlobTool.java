package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.tools.Tool;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Glob 文件匹配工具
 * ==================
 * <p>
 * 使用通配符匹配文件路径。
 * <p>
 * 参数：
 * - pattern: 文件模式（必填）
 * - basePath: 搜索基础路径（可选）
 */
public class GlobTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final java.util.Set<String> IGNORED_DIRS = java.util.Set.of(
            ".git", "node_modules", "__pycache__", ".venv", "venv",
            ".idea", ".vscode", "target", "build", "dist", ".next");

    public GlobTool() {}

    @Override
    public String getName() {
        return "glob";
    }

    @Override
    public String getDescription() {
        return "Find files matching a glob pattern (e.g. **/*.java, src/**/*.ts). "
             + "Automatically skips .git, node_modules, __pycache__, target, and similar dirs.";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode props = mapper.createObjectNode();
        props.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();
        properties.putObject("pattern")
                .put("type", "string")
                .put("description", "Glob pattern (e.g., ** / *.java)");

        properties.putObject("basePath")
                .put("type", "string")
                .put("description", "Base path to search from");

        props.set("properties", properties);
        // pattern 有默认值 "*"，不标记为 required

        return props;
    }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String pattern = (String) params.getOrDefault("pattern", "*");
            String basePathStr = (String) params.getOrDefault("basePath", ".");

            try {
                Path searchPath = Paths.get(basePathStr);  // Path already validated & resolved by ToolRegistry/PathGuard

                if (!Files.exists(searchPath)) {
                    return "Error: Path not found: " + basePathStr;
                }

                List<Path> matches = new ArrayList<>();

                try (var stream = Files.walk(searchPath)) {
                    stream.filter(path -> {
                        // 跳过常见忽略目录
                        if (Files.isDirectory(path) && IGNORED_DIRS.contains(
                                path.getFileName().toString())) return false;
                        // 匹配 glob 模式
                        try {
                            PathMatcher matcher = FileSystems.getDefault()
                                    .getPathMatcher("glob:" + pattern);
                            return matcher.matches(searchPath.relativize(path));
                        } catch (Exception e) {
                            return false;
                        }
                    }).forEach(matches::add);
                }

                if (matches.isEmpty()) {
                    return "No files found matching pattern: " + pattern;
                }

                matches.sort(Comparator.comparing(Path::toString));

                StringBuilder result = new StringBuilder();
                result.append("Found ").append(matches.size()).append(" files:\n");

                for (Path match : matches) {
                    result.append("- ").append(searchPath.relativize(match)).append("\n");
                }

                return result.toString();

            } catch (Exception e) {
                //当工具执行失败时，我们也要把失败信息返回给模型。为什么?
                //下用户说「帮我读-下 config.yaml」，模型调了 ReadFile，但文件不存在。
                //如果你把这个当成程序错误处理，Agent 循环可能直接中断，给用户弹一个「内部错误」。
                //但如果你把「文件不存在」作为 ToolResult 返回给模型，
                //模型拿到这个信息之后可能会说:「config.yam 不存在，让我找找看有没有类似的配置文件」，然后调 Glob 搜索。
                //工具执行失败对模型来说是有价值的反馈信息，它会引导模型调整策略。 只有真正的系统级错误(比如内存不足。
                //程序崩溃)才应该作为程序级 error 上报。
                return "Error searching files: " + e.getMessage();
            }
        });
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    /*
     * Path resolution is handled centrally by PathGuard in ToolRegistry.execute().
     */
}
