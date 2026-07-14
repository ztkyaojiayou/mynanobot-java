package com.nanobot.security.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PreToolUse 钩子管理器 — 链式执行钩子
 * =====================================
 *
 * 管理一组 PreToolUseHook，按注册顺序执行：
 * - 第一个返回 DENY 的钩子短路后续所有钩子
 * - MODIFY 结果会被传递：钩子 N 的输出成为钩子 N+1 的输入
 * - 所有钩子返回 PASSTHROUGH 或 ALLOW 则继续权限检查管道
 *
 * 参考 Claude Code 的 PreToolUse 评估管道。
 */
public class PreToolUseHookManager {

    private static final Logger logger = LoggerFactory.getLogger(PreToolUseHookManager.class);

    private final List<PreToolUseHook> hooks;

    public PreToolUseHookManager() {
        this.hooks = new ArrayList<>();
    }

    // ==================== 注册 ====================

    public void register(PreToolUseHook hook) {
        hooks.add(hook);
        logger.debug("Registered PreToolUse hook: {}", hook.getClass().getSimpleName());
    }

    public void unregister(PreToolUseHook hook) {
        hooks.remove(hook);
    }

    public void clear() {
        hooks.clear();
    }

    public int size() {
        return hooks.size();
    }

    // ==================== 评估 ====================

    /**
     * 按注册顺序执行所有钩子，返回首个非 PASSTHROUGH 的决策
     *
     * @param context    钩子上下文
     * @param toolParams 当前参数（可能被前面的钩子修改）
     * @return 钩子决策 + 可能被修改的参数
     */
    public HookChainResult evaluate(PreToolUseContext context, Map<String, Object> toolParams) {
        if (hooks.isEmpty()) {
            return HookChainResult.passthrough(toolParams);
        }

        Map<String, Object> currentParams = toolParams;

        for (PreToolUseHook hook : hooks) {
            // 每次迭代更新上下文参数（供后续钩子看到修改后的参数）
            PreToolUseContext ctx = (currentParams == toolParams) ? context
                    : PreToolUseContext.builder()
                        .toolName(context.getToolName())
                        .toolDescription(context.getToolDescription())
                        .isReadOnly(context.isReadOnly())
                        .toolCategory(context.getToolCategory())
                        .params(currentParams)
                        .sessionId(context.getSessionId())
                        .build();

            PreToolUseResult result;
            try {
                result = hook.beforeToolUse(ctx);
            } catch (Exception e) {
                logger.error("PreToolUse hook '{}' threw exception: {}",
                        hook.getClass().getSimpleName(), e.getMessage(), e);
                // 钩子异常 → 拒绝（fail-closed）
                return HookChainResult.deny(
                        "Hook error: " + hook.getClass().getSimpleName() + " - " + e.getMessage(),
                        toolParams);
            }

            if (result == null) {
                logger.warn("PreToolUse hook '{}' returned null, treating as passthrough",
                        hook.getClass().getSimpleName());
                continue;
            }

            switch (result.getDecision()) {
                case DENY:
                    logger.info("Tool '{}' denied by hook '{}': {}",
                            context.getToolName(),
                            hook.getClass().getSimpleName(),
                            result.getReason());
                    return HookChainResult.deny(result.getReason(), currentParams);

                case ALLOW:
                    logger.debug("Tool '{}' allowed by hook '{}'",
                            context.getToolName(),
                            hook.getClass().getSimpleName());
                    return HookChainResult.allow(currentParams);

                case MODIFY:
                    logger.debug("Tool '{}' params modified by hook '{}'",
                            context.getToolName(),
                            hook.getClass().getSimpleName());
                    currentParams = result.getModifiedParams();
                    // 继续给下一个钩子
                    break;

                case PASSTHROUGH:
                    // 继续下一个钩子
                    break;
            }
        }

        // 所有钩子都透传
        return HookChainResult.passthrough(currentParams);
    }

    // ==================== 内部类 ====================

    /**
     * 钩子链评估结果
     */
    public record HookChainResult(
            Decision decision,
            String reason,
            Map<String, Object> params
    ) {
        public enum Decision { ALLOW, DENY, PASSTHROUGH }

        static HookChainResult allow(Map<String, Object> params) {
            return new HookChainResult(Decision.ALLOW, null, params);
        }
        static HookChainResult deny(String reason, Map<String, Object> params) {
            return new HookChainResult(Decision.DENY, reason, params);
        }
        static HookChainResult passthrough(Map<String, Object> params) {
            return new HookChainResult(Decision.PASSTHROUGH, null, params);
        }
    }
}
