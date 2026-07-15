package com.nanobot.security.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Shell 命令守卫 — 危险命令过滤
 * ===============================
 *
 * 参考 Nanobot (Python) 的 {@code _guard_command()} 设计。
 *
 * 核心逻辑：
 * 1. 先匹配 allowPatterns → 匹配到白名单则直接放行（跳过 deny 检查）
 * 2. 再匹配 denyPatterns → 匹配到黑名单则抛出 SecurityException
 * 3. 如果启用了 restrictToWorkspace，提取命令中的文件路径进行验证
 *
 * Nanobot 设计要点：allowPatterns 优先于 denyPatterns
 * (参考: HKUDS/nanobot PR #3594)
 *
 * 使用示例：
 * ```java
 * CommandGuard guard = new CommandGuard();
 * guard.addAllowPattern("git\\s+status");
 * guard.addDenyPattern("rm\\s+-rf\\s+/");
 * guard.guard("git status");   // 放行
 * guard.guard("rm -rf /");     // 抛 SecurityException
 * ```
 */
public class CommandGuard {

    private static final Logger logger = LoggerFactory.getLogger(CommandGuard.class);

    /** 白名单正则列表（匹配即放行，跳过 deny 检查） */
    private final List<Pattern> allowPatterns;

    /** 黑名单正则列表（匹配即拦截） */
    private final List<Pattern> denyPatterns;

    /** 是否提取命令中的路径并用 PathGuard 验证 */
    private boolean restrictPathsToWorkspace;

    /** 关联的 PathGuard（用于路径验证） */
    private PathGuard pathGuard;

    // ==================== 构造函数 ====================

    public CommandGuard() {
        this.allowPatterns = new ArrayList<>();
        this.denyPatterns = new ArrayList<>();
        this.restrictPathsToWorkspace = false;
    }

    /**
     * 创建带有默认内置危险命令黑名单的 CommandGuard
     */
    public static CommandGuard withDefaults() {
        CommandGuard guard = new CommandGuard();
        guard.addDefaultDenyPatterns();
        return guard;
    }

    // ==================== 配置方法 ====================

    public void setRestrictPathsToWorkspace(boolean restrict) {
        this.restrictPathsToWorkspace = restrict;
    }

    public void setPathGuard(PathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    /**
     * 添加白名单模式（优先于黑名单）
     */
    public void addAllowPattern(String regex) {
        try {
            allowPatterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            logger.debug("Added allow pattern: {}", regex);
        } catch (PatternSyntaxException e) {
            logger.error("Invalid allow pattern: {} — {}", regex, e.getMessage());
        }
    }

    /**
     * 添加黑名单模式
     */
    public void addDenyPattern(String regex) {
        try {
            denyPatterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            logger.debug("Added deny pattern: {}", regex);
        } catch (PatternSyntaxException e) {
            logger.error("Invalid deny pattern: {} — {}", regex, e.getMessage());
        }
    }

    /**
     * 加载内置默认黑名单（参考 Nanobot _guard_command）
     */
    private void addDefaultDenyPatterns() {
        // 注意: rm -rf 不在 deny 列表中，而是通过 RuleEngine ASK 规则触发交互确认
        // Fork bomb（Unix + 变体）
        addDenyPattern(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;:");
        // 磁盘格式化
        addDenyPattern("mkfs\\.");
        // Windows 磁盘分区
        addDenyPattern("diskpart");
        // 裸磁盘写入
        addDenyPattern("dd\\s+if=");
        // 覆盖 history.jsonl
        addDenyPattern(">>?\\s*.*history\\.jsonl");
        // 提权命令
        addDenyPattern("\\bsudo\\b");
        addDenyPattern("\\bsu\\s");
        // 系统关机/重启
        addDenyPattern("\\b(shutdown|reboot|halt)\\b");
        // chmod 777（权限全开）
        addDenyPattern("chmod\\s+777");
        // 读取系统敏感文件
        addDenyPattern("/etc/(passwd|shadow|sudoers)");
        // 防火墙操作
        addDenyPattern("\\b(iptables|ufw|firewall-cmd)\\b");
        // pipe-to-shell 注入
        addDenyPattern("wget.*\\|.*(sh|bash)");
        addDenyPattern("curl.*\\|.*(sh|bash)");
        logger.info("Loaded {} default deny patterns", denyPatterns.size());
    }

    // ==================== 核心方法 ====================

    /**
     * 守卫命令 — 检查命令是否安全
     *
     * @param command 要执行的 shell 命令
     * @throws SecurityException 如果命令被拦截
     */
    public void guard(String command) {
        if (command == null || command.isBlank()) {
            return;
        }

        // Step 1: allowPatterns 优先（Nanobot 设计：白名单优先于黑名单）
        for (Pattern p : allowPatterns) {
            if (p.matcher(command).find()) {
                logger.debug("Command allowed by allow pattern: {}", p.pattern());
                return;
            }
        }

        // Step 2: denyPatterns 检查
        for (Pattern p : denyPatterns) {
            java.util.regex.Matcher m = p.matcher(command);
            if (m.find()) {
                String reason = String.format("Command blocked by pattern '%s': %s", p.pattern(), command);
                logger.warn("Command blocked: {}", reason);
                throw new SecurityException("CommandGuard", reason);
            }
        }

        // Step 3: workspace 路径检查（如果启用）
        if (restrictPathsToWorkspace && pathGuard != null) {
            List<String> paths = extractPaths(command);
            for (String path : paths) {
                pathGuard.resolvePath(path);
            }
        }

        logger.debug("Command passed guard: {}", command);
    }

    /**
     * 检查命令，返回布尔结果（不抛异常）
     *
     * @return true 表示命令安全，false 表示被拦截
     */
    public boolean isSafe(String command) {
        try {
            guard(command);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    // ==================== 路径提取 ====================

    /**
     * 从命令中提取可能引用的文件路径
     *
     * 简单启发式：提取空格分隔的、看起来像路径的字符串
     * （后续可扩展为更智能的解析器）
     */
    protected List<String> extractPaths(String command) {
        List<String> paths = new ArrayList<>();
        String[] tokens = command.split("\\s+");
        for (String token : tokens) {
            // 移除引号
            String cleaned = token.replaceAll("^['\"]|['\"]$", "");
            // 看起来像路径：包含 / 或 \ 或 .
            if (cleaned.contains("/") || cleaned.contains("\\") || cleaned.startsWith(".")) {
                // 不是明显的参数标志
                if (!cleaned.startsWith("-")) {
                    paths.add(cleaned);
                }
            }
        }
        return paths;
    }

    // ==================== 查询方法 ====================
    // 注意：以下 getter 包含自定义转换逻辑（Pattern → String、派生计数），
    // 无法用 Lombok @Getter 直接替代，因此保留为手动实现。

    public List<String> getAllowPatterns() {
        return allowPatterns.stream().map(Pattern::pattern).collect(Collectors.toList());
    }

    public List<String> getDenyPatterns() {
        return denyPatterns.stream().map(Pattern::pattern).collect(Collectors.toList());
    }

    public int getAllowPatternCount() {
        return allowPatterns.size();
    }

    public int getDenyPatternCount() {
        return denyPatterns.size();
    }
}
