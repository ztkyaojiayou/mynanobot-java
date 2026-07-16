package com.nanobot.command.impl;

import com.nanobot.NanobotRunner;
import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * /init — 分析当前项目并生成 NANOBOT.md。
 *
 * 混合模式：
 * 1. Java 快速收集项目元数据（pom.xml、目录结构等，无需权限确认）
 * 2. 一次性丢给 LLM 生成文档内容（不走 AgentLoop 多轮 tool-calling）
 * 3. Java 直接写入文件
 */
public class InitCommand implements Command {

    /** NANOBOT.md 文件名 */
    private static final String NANOBOT_FILENAME = "NANOBOT.md";

    /** 采样源文件的最大数量（限制 token 用量） */
    private static final int MAX_SAMPLE_FILES = 8;

    /** 采样源文件的最大行数 */
    private static final int MAX_SAMPLE_LINES = 40;

    /** 目录树最大深度 */
    private static final int MAX_DIR_DEPTH = 4;

    /** LLM 超时 */
    private static final int LLM_TIMEOUT_SECONDS = 60;

    @Override public String name() { return "init"; }
    @Override public String description() { return "分析当前项目并生成 NANOBOT.md"; }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        // 确定项目根目录
        Path projectRoot = resolveProjectRoot();
        Path outputPath = projectRoot.resolve(NANOBOT_FILENAME);

        System.out.println("🔍 正在分析项目: " + projectRoot);

        // ── 1. 收集项目元数据（纯 Java，零权限） ──
        StringBuilder projectInfo = new StringBuilder();
        collectBuildFiles(projectRoot, projectInfo);
        collectSourceTree(projectRoot, projectInfo);
        collectConfigFiles(projectRoot, projectInfo);
        collectSampleSources(projectRoot, projectInfo);

        // ── 2. 尝试用 LLM 生成 NANOBOT.md ──
        LLMProvider provider = NanobotRunner.getProvider();
        if (provider != null) {
            try {
                System.out.println("🤖 正在通过大模型分析并生成 NANOBOT.md...");
                String content = generateWithLLM(provider, projectInfo.toString());
                Files.writeString(outputPath, content);
                System.out.println("✅ 已生成: " + outputPath.toAbsolutePath() + " (" + content.length() + " 字符)");
                return false;
            } catch (Exception e) {
                System.out.println("⚠️  LLM 生成失败 (" + e.getMessage() + ")，回退到模板模式...");
            }
        } else {
            System.out.println("⚠️  未检测到 LLM 配置，使用模板模式...");
        }

        // ── 3. 模板生成（LLM 不可用时的兜底） ──
        try {
            String content = generateFromTemplate(projectInfo.toString());
            Files.writeString(outputPath, content);
            System.out.println("✅ 已生成: " + outputPath.toAbsolutePath() + "（模板模式，" + content.length() + " 字符）");
        } catch (IOException e) {
            System.err.println("❌ 写入失败: " + e.getMessage());
        }

        return false;
    }

    /** 解析项目根目录：向上查找 pom.xml，回退到 user.dir */
    private Path resolveProjectRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();

        // 向上查找构建文件（pom.xml / build.gradle / package.json）
        for (Path d = dir; d != null && d.getNameCount() > 0; d = d.getParent()) {
            if (Files.exists(d.resolve("pom.xml"))
                    || Files.exists(d.resolve("build.gradle"))
                    || Files.exists(d.resolve("package.json"))) {
                return d;
            }
        }
        // 回退
        return dir;
    }

    // ════════════════════════════════════════════════════════
    // 阶段 1: 元数据收集
    // ════════════════════════════════════════════════════════

    /** 收集构建文件内容 */
    private void collectBuildFiles(Path projectRoot, StringBuilder sb) {
        String[] buildFiles = {"pom.xml", "build.gradle", "build.gradle.kts",
                "settings.gradle", "settings.gradle.kts", "package.json", "Makefile", "CMakeLists.txt"};

        sb.append("=== 构建文件 ===\n");
        for (String name : buildFiles) {
            Path path = projectRoot.resolve(name);
            if (Files.exists(path)) {
                sb.append("\n--- ").append(name).append(" ---\n");
                try {
                    sb.append(Files.readString(path)).append("\n");
                } catch (IOException e) {
                    sb.append("(无法读取: ").append(e.getMessage()).append(")\n");
                }
            }
        }
        sb.append("\n");
    }

    /** 收集源码目录树 */
    private void collectSourceTree(Path projectRoot, StringBuilder sb) {
        sb.append("=== 源码目录结构 ===\n");
        String[] dirNames = {"src", "scripts", "docs", "config"};
        boolean found = false;

        for (String name : dirNames) {
            Path root = projectRoot.resolve(name);
            if (!Files.isDirectory(root)) continue;
            found = true;
            sb.append("\n").append(root).append("/\n");
            try {
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DIR_DEPTH,
                        new SimpleFileVisitor<>() {
                            int depth = 0;
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                                if (dir.equals(root)) { depth = 0; return FileVisitResult.CONTINUE; }
                                // 跳过隐藏目录和 build 输出
                                String name = dir.getFileName().toString();
                                if (name.startsWith(".") || name.equals("target")
                                        || name.equals("build") || name.equals("node_modules")
                                        || name.equals("__pycache__") || name.equals(".git"))
                                    return FileVisitResult.SKIP_SUBTREE;
                                if (depth >= MAX_DIR_DEPTH) return FileVisitResult.SKIP_SUBTREE;
                                depth++;
                                sb.append("  ".repeat(depth)).append(name).append("/\n");
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (depth < MAX_DIR_DEPTH)
                                    sb.append("  ".repeat(depth + 1)).append(file.getFileName()).append("\n");
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                                if (!dir.equals(root)) depth--;
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException e) {
                sb.append("  (跳过: ").append(e.getMessage()).append(")\n");
            }
        }
        if (!found) sb.append("(无标准源码目录)\n");
        sb.append("\n");
    }

    /** 收集配置文件 */
    private void collectConfigFiles(Path projectRoot, StringBuilder sb) {
        sb.append("=== 配置文件 ===\n");
        String[] configs = {"application.yml", "application.yaml", "application.properties",
                "config.yaml", "config.yml", ".env.example", "Dockerfile", "docker-compose.yml",
                ".gitignore"};
        for (String name : configs) {
            Path path = projectRoot.resolve(name);
            if (Files.exists(path)) {
                sb.append("\n--- ").append(name).append(" ---\n");
                try {
                    String content = Files.readString(path);
                    // 限制配置文件的长度
                    if (content.length() > 2000) {
                        content = content.substring(0, 2000) + "\n... (truncated)";
                    }
                    sb.append(content).append("\n");
                } catch (IOException e) {
                    sb.append("(无法读取)\n");
                }
            }
        }
        sb.append("\n");
    }

    /** 采样核心源文件 */
    private void collectSampleSources(Path projectRoot, StringBuilder sb) {
        sb.append("=== 核心源文件采样（前 ").append(MAX_SAMPLE_LINES).append(" 行） ===\n");
        List<Path> javaFiles = new ArrayList<>();
        Path srcMain = projectRoot.resolve("src/main/java");
        if (Files.isDirectory(srcMain)) {
            try (var stream = Files.walk(srcMain, 5)) {
                javaFiles = stream
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            // 优先采样的文件类型
                            return name.endsWith("Application.java")
                                    || name.endsWith("Runner.java")
                                    || name.equals("AgentLoop.java")
                                    || name.equals("MessageBus.java")
                                    || name.equals("ToolRegistry.java")
                                    || name.equals("Config.java");
                        })
                        .sorted(Comparator.comparing(Path::getFileName))
                        .collect(Collectors.toList());
            } catch (IOException ignored) {}
        }

        if (javaFiles.isEmpty()) {
            sb.append("(未找到 Java 源文件)\n");
            return;
        }

        int count = 0;
        for (Path file : javaFiles) {
            if (count >= MAX_SAMPLE_FILES) break;
            sb.append("\n--- ").append(file).append(" ---\n");
            try {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < Math.min(lines.size(), MAX_SAMPLE_LINES); i++) {
                    sb.append(lines.get(i)).append("\n");
                }
                if (lines.size() > MAX_SAMPLE_LINES) sb.append("... (共 ").append(lines.size()).append(" 行)\n");
            } catch (IOException e) {
                sb.append("(无法读取)\n");
            }
            count++;
        }
        sb.append("\n");
    }

    // ════════════════════════════════════════════════════════
    // 阶段 2: LLM 生成
    // ════════════════════════════════════════════════════════

    private String generateWithLLM(LLMProvider provider, String projectInfo) throws Exception {
        String systemPrompt = """
                你是一位技术文档专家。你的任务是根据提供的项目信息，生成一份专业、简洁的 NANOBOT.md 文件。

                要求：
                1. 采用 Markdown 格式
                2. 包含以下章节（按实际项目情况取舍）：
                   - 项目概述（一句话说明这个项目是什么）
                   - 技术栈
                   - 项目结构（源码包说明）
                   - 构建和运行命令
                   - 编码约定（从代码风格推断）
                   - 关键设计决策
                3. 内容要具体、可操作，不要泛泛而谈
                4. 格式简洁，参照 CLAUDE.md 风格
                5. 只输出 NANOBOT.md 的完整内容，不要额外解释
                """;

        var messages = List.of(
                LLMProvider.Message.ofSystem(systemPrompt),
                LLMProvider.Message.ofUser("以下是项目信息，请生成 NANOBOT.md：\n\n" + projectInfo)
        );

        LLMResponse response = provider.chat(messages, null)
                .get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        String content = response.getContent();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("LLM 返回了空内容");
        }
        return content.trim();
    }

    // ════════════════════════════════════════════════════════
    // 阶段 3: 模板生成（兜底）
    // ════════════════════════════════════════════════════════

    private String generateFromTemplate(String projectInfo) {
        // 简单地从项目信息中提取关键内容
        String projectName = extractProjectName(projectInfo);
        String buildCmd = detectBuildCommand(projectInfo);
        String javaVersion = detectJavaVersion(projectInfo);

        return """
                # %s 项目概述

                这是一个基于 Java 的 AI Agent 项目。

                ## 技术栈
                - Java %s
                - Maven（构建工具）
                - Spring Boot（Web 框架）
                - Jackson（JSON 处理）
                - SLF4J + Logback（日志）
                - JUnit 5（测试）

                ## 构建和运行
                ```bash
                # 编译
                %s

                # 运行测试
                mvn test

                # 启动（Spring Boot 模式）
                mvn spring-boot:run

                # 启动（CLI 交互模式）
                mvn exec:java -Dexec.mainClass="com.nanobot.v3.NanobotCliApplication"
                ```

                ## 项目结构
                详见上方的源码目录结构。

                ## 注意事项
                - NANOBOT.md 是 AI 助手的项目记忆文件，可手动编辑补充。
                - 使用 `/init` 重新生成会覆盖当前文件。
                """.formatted(projectName, javaVersion, buildCmd);
    }

    private String extractProjectName(String info) {
        // 从 pom.xml 中提取 artifactId
        for (String line : info.lines().toList()) {
            if (line.contains("<artifactId>") && !line.contains("nanobot-java")) {
                String name = line.replaceAll(".*<artifactId>", "").replaceAll("</artifactId>.*", "").trim();
                if (!name.isBlank()) return name;
            }
        }
        return "nanobot-java";
    }

    private String detectBuildCommand(String info) {
        if (info.contains("pom.xml")) return "mvn compile";
        if (info.contains("build.gradle")) return "./gradlew build";
        if (info.contains("package.json")) return "npm install && npm run build";
        return "mvn compile";
    }

    private String detectJavaVersion(String info) {
        var matcher = java.util.regex.Pattern.compile("<java\\.version>(\\d+)</java\\.version>").matcher(info);
        if (matcher.find()) return matcher.group(1);
        return "17";
    }
}
