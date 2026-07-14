package com.nanobot.security.rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 权限规则引擎 — 按优先级评估规则链
 * =====================================
 *
 * 参考 Claude Code 的 deny → ask → allow 优先级链：
 * 1. DENY 规则最先评估，一旦匹配立即返回（最高优先级）
 * 2. ASK 规则其次
 * 3. ALLOW 规则最后
 * 4. 同类型中，先注册的规则优先
 * 5. 无规则匹配时返回 null（交给 PermissionMode 默认判定）
 *
 * 重要设计决策：Deny 不可被 Allow 覆写（与 Claude Code 一致）。
 *
 * 使用示例：
 * ```java
 * RuleEngine engine = new RuleEngine();
 * engine.addRule(PermissionRule.DENY, "exec", "command", "rm -rf.*", "危险命令");
 * engine.addRule(PermissionRule.ALLOW, "exec", "command", "git status", null);
 *
 * RuleMatch match = engine.evaluate("exec", Map.of("command", "rm -rf /"));
 * // → DENY (即使后续有 allow，deny 优先)
 * ```
 */
public class RuleEngine {

    private static final Logger logger = LoggerFactory.getLogger(RuleEngine.class);

    /** 按优先级排序的规则列表 */
    private final List<PermissionRule> rules;

    public RuleEngine() {
        this.rules = new ArrayList<>();
    }

    // ==================== 规则管理 ====================

    /**
     * 添加一条规则
     */
    public void addRule(PermissionRule rule) {
        rules.add(rule);
        logger.debug("Rule added: {}", rule);
    }

    /**
     * 便捷方法：添加规则
     *
     * @param type         规则类型
     * @param toolPattern  工具名正则
     * @param paramName    参数名（可选）
     * @param valuePattern 参数值正则（可选）
     * @param reason       拦截原因
     */
    public void addRule(RuleType type, String toolPattern,
                         String paramName, String valuePattern, String reason) {
        addRule(new PermissionRule(type, toolPattern, paramName, valuePattern, reason));
    }

    /**
     * 移除所有规则
     */
    public void clearRules() {
        rules.clear();
    }

    /**
     * 获取规则数量
     */
    public int size() {
        return rules.size();
    }

    // ==================== 规则评估 ====================

    /**
     * 评估工具调用，返回匹配结果
     *
     * @param toolName 工具名称
     * @param params   工具参数
     * @return 匹配的规则结果，或 RuleMatch.NO_MATCH 如果没有规则匹配
     */
    public RuleMatch evaluate(String toolName, Map<String, Object> params) {
        // 1. 先检查 DENY（最高优先级）
        for (PermissionRule rule : rules) {
            if (rule.type() == RuleType.DENY && rule.matches(toolName, params)) {
                logger.debug("Rule match DENY: {} for tool '{}'", rule, toolName);
                return RuleMatch.deny(rule);
            }
        }

        // 2. 再检查 ASK
        for (PermissionRule rule : rules) {
            if (rule.type() == RuleType.ASK && rule.matches(toolName, params)) {
                logger.debug("Rule match ASK: {} for tool '{}'", rule, toolName);
                return RuleMatch.ask(rule);
            }
        }

        // 3. 最后检查 ALLOW
        for (PermissionRule rule : rules) {
            if (rule.type() == RuleType.ALLOW && rule.matches(toolName, params)) {
                logger.debug("Rule match ALLOW: {} for tool '{}'", rule, toolName);
                return RuleMatch.allow(rule);
            }
        }

        // 4. 无匹配
        return RuleMatch.NO_MATCH;
    }

    /**
     * 仅检查是否有 deny 规则匹配（用于快速拦截）
     */
    public boolean hasDenyMatch(String toolName, Map<String, Object> params) {
        return rules.stream()
                .filter(r -> r.type() == RuleType.DENY)
                .anyMatch(r -> r.matches(toolName, params));
    }

    // ==================== 查询 ====================

    public List<PermissionRule> getRules() {
        return List.copyOf(rules);
    }

    public List<PermissionRule> getRulesByType(RuleType type) {
        return rules.stream()
                .filter(r -> r.type() == type)
                .toList();
    }
}
