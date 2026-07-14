package com.nanobot.security;

import com.nanobot.security.guard.CommandGuard;
import com.nanobot.security.guard.NetworkGuard;
import com.nanobot.security.guard.PathGuard;
import com.nanobot.security.guard.SecurityException;
import com.nanobot.security.hook.PreToolUseContext;
import com.nanobot.security.hook.PreToolUseHookManager;
import com.nanobot.security.rule.RuleEngine;
import com.nanobot.security.rule.RuleMatch;
import com.nanobot.tools.Tool;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * 权限编排管理器 — 工具调用前的统一权限检查入口
 * =================================================
 *
 * 集成三件套：守卫 → 模式判定 → 规则匹配（Phase 3 规则引擎待接入）。
 *
 * 检查管道（参考 Claude Code 6步管道）：
 * 1. Guards（永远执行，不可跳过）— 文件路径/命令/网络
 * 2. Mode（PLAN/DEFAULT/ACCEPT_EDITS/BYPASS）
 * 3. Rules（deny → ask → allow，Phase 3 接入）
 *
 * 使用：
 * ```java
 * PermissionManager pm = PermissionManager.builder()
 *     .pathGuard(pathGuard)
 *     .commandGuard(commandGuard)
 *     .networkGuard(networkGuard)
 *     .mode(PermissionMode.DEFAULT)
 *     .build();
 *
 * PermissionResult result = pm.check(tool, params);
 * if (result.isDenied()) { ... }
 * ```
 */
@Getter
public class PermissionManager {

    private static final Logger logger = LoggerFactory.getLogger(PermissionManager.class);

    /** 当前权限模式 */
    private volatile PermissionMode mode;

    /** 文件路径守卫 */
    private final PathGuard pathGuard;

    /** Shell 命令守卫 */
    private final CommandGuard commandGuard;

    /** 网络安全守卫 */
    private final NetworkGuard networkGuard;

    /** 规则引擎 */
    private final RuleEngine ruleEngine;

    /** PreToolUse 钩子管理器 */
    private final PreToolUseHookManager hookManager;

    // ==================== 构造函数 ====================

    private PermissionManager(Builder builder) {
        this.mode = builder.mode;
        this.pathGuard = builder.pathGuard;
        this.commandGuard = builder.commandGuard;
        this.networkGuard = builder.networkGuard;
        this.ruleEngine = builder.ruleEngine;
        this.hookManager = builder.hookManager;
        logger.info("PermissionManager initialized: mode={}, rules={}, hooks={}",
                mode.name(),
                ruleEngine != null ? ruleEngine.size() + " rules" : "none",
                hookManager != null ? hookManager.size() + " hooks" : "none");
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== 核心方法 ====================

    /**
     * 执行完整的权限检查管道
     *
     * @param tool   要执行的工具
     * @param params 工具参数
     * @return 权限检查结果
     */
    public PermissionResult check(Tool tool, Map<String, Object> params) {
        String toolName = tool.getName();

        // Step 1: PreToolUse Hook 链（最先执行，可 deny/allow/modify/passthrough）
        if (hookManager != null) {
            PreToolUseContext hookCtx = PreToolUseContext.builder()
                    .fromTool(tool, params)
                    .sessionId(null) // 后续可从 TurnContext 获取
                    .build();

            PreToolUseHookManager.HookChainResult hookResult =
                    hookManager.evaluate(hookCtx, params);

            switch (hookResult.decision()) {
                case DENY:
                    return PermissionResult.denied(
                            hookResult.reason() != null ? hookResult.reason() : "Blocked by PreToolUse hook",
                            "hook");
                case ALLOW:
                    // Hook 显式放行，跳过后续所有检查
                    return PermissionResult.allowed();
                case PASSTHROUGH:
                    // 继续正常管道（可能参数被前面的 MODIFY 钩子修改）
                    params = hookResult.params();
                    break;
            }
        }

        // Step 2: 守卫检查（永远执行，不可跳过）
        try {
            checkGuards(toolName, params);
        } catch (SecurityException e) {
            logger.warn("Tool '{}' blocked by {}: {}", toolName, e.getGuard(), e.getReason());
            return PermissionResult.guardBlocked(e.getMessage(), e.getGuard());
        }

        // Step 3: 规则匹配（deny → ask → allow）
        if (ruleEngine != null) {
            RuleMatch match = ruleEngine.evaluate(toolName, params);
            if (match.isDeny()) {
                return PermissionResult.denied(
                        match.getReason() != null ? match.getReason() : "Blocked by deny rule",
                        "rule:deny");
            }
            if (match.isAsk()) {
                return PermissionResult.needsApproval(
                        match.getReason() != null ? match.getReason() : "This action requires approval");
            }
            if (match.isAllow()) {
                return PermissionResult.allowed();
            }
        }

        // Step 4: 模式判定（仅在无规则匹配时生效）
        if (!mode.allowsTool(tool)) {
            String reason = String.format("Tool '%s' is not allowed in %s mode (isReadOnly=%s)",
                    tool.getName(), mode.name(), tool.isReadOnly());
            logger.info("Tool blocked by mode: {}", reason);
            return PermissionResult.denied(reason, "mode:" + mode.name());
        }

        return PermissionResult.allowed();
    }

    /**
     * 仅执行守卫检查（不检查模式/规则）
     *
     * @param toolName 工具名称
     * @param params   工具参数
     * @throws SecurityException 如果被任何守卫拦截
     */
    public void checkGuards(String toolName, Map<String, Object> params) {
        // PathGuard：文件类工具
        if (isFileTool(toolName) && pathGuard != null) {
            String pathParam = (String) params.get("path");
            if (pathParam != null) {
                pathGuard.resolvePath(pathParam);
            }
        }
        // CommandGuard：Shell 执行工具
        if (isShellTool(toolName) && commandGuard != null) {
            String command = (String) params.get("command");
            if (command != null) {
                commandGuard.guard(command);
            }
        }
        // NetworkGuard：网络类工具
        if (isNetworkTool(toolName) && networkGuard != null) {
            String url = (String) params.get("url");
            if (url != null) {
                networkGuard.validateUrl(url);
            }
        }
    }

    // ==================== 配置方法 ====================

    public void setMode(PermissionMode mode) {
        PermissionMode oldMode = this.mode;
        this.mode = mode;
        logger.info("Permission mode changed: {} -> {}", oldMode, mode);
    }

    // ==================== 工具分类 ====================

    private boolean isFileTool(String name) {
        return Set.of("read_file", "write_file", "edit_file",
                "list_dir", "glob", "grep").contains(name);
    }

    private boolean isShellTool(String name) {
        return "exec".equals(name);
    }

    private boolean isNetworkTool(String name) {
        return Set.of("web_fetch", "web_search").contains(name);
    }

    // ==================== Builder ====================

    public static class Builder {
        private PermissionMode mode = PermissionMode.DEFAULT;
        private PathGuard pathGuard;
        private CommandGuard commandGuard;
        private NetworkGuard networkGuard;
        private RuleEngine ruleEngine;
        private PreToolUseHookManager hookManager;

        public Builder mode(PermissionMode mode)            { this.mode = mode;              return this; }
        public Builder pathGuard(PathGuard pg)               { this.pathGuard = pg;           return this; }
        public Builder commandGuard(CommandGuard cg)         { this.commandGuard = cg;        return this; }
        public Builder networkGuard(NetworkGuard ng)         { this.networkGuard = ng;        return this; }
        public Builder ruleEngine(RuleEngine re)             { this.ruleEngine = re;          return this; }
        public Builder hookManager(PreToolUseHookManager hm) { this.hookManager = hm;         return this; }

        public PermissionManager build() {
            return new PermissionManager(this);
        }
    }
}
