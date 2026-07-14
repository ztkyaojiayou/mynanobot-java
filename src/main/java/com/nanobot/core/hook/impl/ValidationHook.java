package com.nanobot.core.hook.impl;

import com.nanobot.core.hook.AgentHook;
import com.nanobot.core.hook.AgentHookContext;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 内容验证钩子 - 敏感词过滤与内容合规检查
 * =========================================
 * 
 * 提供以下验证功能：
 * - 敏感词过滤
 * - 内容长度限制
 * - 格式验证
 * - 自定义规则验证
 * 
 * **验证规则配置**：
 * 
 * ```java
 * ValidationHook hook = new ValidationHook();
 * 
 * // 添加敏感词
 * hook.addSensitiveWords("敏感词1", "敏感词2", "敏感词3");
 * 
 * // 设置内容最大长度
 * hook.setMaxContentLength(4096);
 * 
 * // 添加自定义验证规则
 * hook.addRule("email", Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$"));
 * ```
 * 
 * **验证结果**：
 * - 如果内容包含敏感词，会被替换为 ***
 * - 如果内容过长，会被截断
 * - 如果不符合格式要求，会返回错误信息
 */
@Getter
public class ValidationHook implements AgentHook {
    
    private static final Logger logger = LoggerFactory.getLogger(ValidationHook.class);
    
    /** 敏感词列表 */
    private final Set<String> sensitiveWords = new HashSet<>();
    
    /** 内容最大长度 */
    private int maxContentLength = 4096;
    
    /** 是否启用敏感词过滤 */
    private boolean sensitiveWordFilterEnabled = true;
    
    /** 是否启用长度限制 */
    private boolean lengthLimitEnabled = true;
    
    /** 自定义验证规则 */
    private final Map<String, Pattern> customRules = new LinkedHashMap<>();
    
    /** 替换字符 */
    private static final String REPLACEMENT = "***";
    
    // ==================== 配置方法 ====================
    
    /**
     * 添加敏感词
     */
    public void addSensitiveWords(String... words) {
        if (words != null) {
            sensitiveWords.addAll(Arrays.asList(words));
        }
    }
    
    /**
     * 批量添加敏感词
     */
    public void addSensitiveWords(Collection<String> words) {
        if (words != null) {
            sensitiveWords.addAll(words);
        }
    }
    
    /**
     * 移除敏感词
     */
    public void removeSensitiveWords(String... words) {
        if (words != null) {
            sensitiveWords.removeAll(Arrays.asList(words));
        }
    }
    
    /**
     * 清空敏感词
     */
    public void clearSensitiveWords() {
        sensitiveWords.clear();
    }
    
    /**
     * 设置内容最大长度
     */
    public void setMaxContentLength(int maxLength) {
        this.maxContentLength = maxLength;
    }
    
    /**
     * 启用/禁用敏感词过滤
     */
    public void setSensitiveWordFilterEnabled(boolean enabled) {
        this.sensitiveWordFilterEnabled = enabled;
    }
    
    /**
     * 启用/禁用长度限制
     */
    public void setLengthLimitEnabled(boolean enabled) {
        this.lengthLimitEnabled = enabled;
    }
    
    /**
     * 添加自定义验证规则
     */
    public void addRule(String name, Pattern pattern) {
        if (name != null && pattern != null) {
            customRules.put(name, pattern);
        }
    }
    
    /**
     * 移除自定义验证规则
     */
    public void removeRule(String name) {
        customRules.remove(name);
    }
    
    /**
     * 清空自定义验证规则
     */
    public void clearRules() {
        customRules.clear();
    }
    
    // ==================== 钩子方法 ====================
    
    @Override
    public CompletableFuture<Void> beforeIteration(AgentHookContext context) {
        // 在迭代前验证用户输入
        List<Map<String, Object>> messages = context.getMessages();
        if (!messages.isEmpty()) {
            Map<String, Object> lastMessage = messages.get(messages.size() - 1);
            Object contentObj = lastMessage.get("content");
            if (contentObj instanceof String content) {
                String validated = validateInput(content);
                if (!content.equals(validated)) {
                    logger.warn("Input content modified during validation for session {}", 
                               context.getSessionKey());
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public String finalizeContent(AgentHookContext context, String content) {
        if (content == null) {
            return content;
        }
        
        String result = content;
        
        // 1. 敏感词过滤
        if (sensitiveWordFilterEnabled) {
            result = filterSensitiveWords(result);
        }
        
        // 2. 长度限制
        if (lengthLimitEnabled && result.length() > maxContentLength) {
            result = truncateContent(result);
        }
        
        // 3. 内容验证
        List<String> violations = validateContent(result);
        if (!violations.isEmpty()) {
            logger.warn("Content validation violations for session {}: {}", 
                       context.getSessionKey(), violations);
        }
        
        // 如果内容被修改，记录日志
        if (!content.equals(result)) {
            logger.info("Content validated and modified for session {}", context.getSessionKey());
        }
        
        return result;
    }
    
    @Override
    public String getName() {
        return "ValidationHook";
    }
    
    // ==================== 验证方法 ====================
    
    /**
     * 验证用户输入
     */
    private String validateInput(String input) {
        if (input == null) {
            return null;
        }
        
        String result = input;
        
        // 敏感词过滤
        if (sensitiveWordFilterEnabled) {
            result = filterSensitiveWords(result);
        }
        
        // 长度限制
        if (lengthLimitEnabled && result.length() > maxContentLength) {
            result = truncateContent(result);
        }
        
        return result;
    }
    
    /**
     * 过滤敏感词
     */
    private String filterSensitiveWords(String content) {
        if (sensitiveWords.isEmpty()) {
            return content;
        }
        
        String result = content;
        for (String word : sensitiveWords) {
            if (!word.isEmpty()) {
                result = result.replaceAll(java.util.regex.Pattern.quote(word), REPLACEMENT);
            }
        }
        return result;
    }
    
    /**
     * 截断过长内容
     */
    private String truncateContent(String content) {
        if (content.length() <= maxContentLength) {
            return content;
        }
        
        String truncated = content.substring(0, maxContentLength - 3);
        return truncated + "...";
    }
    
    /**
     * 验证内容格式
     */
    private List<String> validateContent(String content) {
        List<String> violations = new ArrayList<>();
        
        for (Map.Entry<String, Pattern> entry : customRules.entrySet()) {
            String ruleName = entry.getKey();
            Pattern pattern = entry.getValue();
            
            if (!pattern.matcher(content).matches()) {
                violations.add("Rule '" + ruleName + "' violated");
            }
        }
        
        return violations;
    }
    
    // ==================== 查询方法 ====================
    
    /**
     * 获取敏感词数量
     */
    public int getSensitiveWordCount() {
        return sensitiveWords.size();
    }
    
    /**
     * 获取所有敏感词
     */
    public Set<String> getSensitiveWords() {
        return Set.copyOf(sensitiveWords);
    }
    
    /**
     * 获取自定义规则数量
     */
    public int getRuleCount() {
        return customRules.size();
    }
    
    /**
     * 获取所有自定义规则名称
     */
    public Set<String> getRuleNames() {
        return customRules.keySet();
    }
    
    // ==================== 预定义敏感词 ====================
    
    /**
     * 加载默认敏感词列表
     */
    public void loadDefaultSensitiveWords() {
        // 添加一些常见的敏感词示例
        addSensitiveWords(
            "敏感词1", "敏感词2", "敏感词3",
            "测试敏感词", "示例敏感词"
        );
    }
    
    // ==================== 便捷工厂方法 ====================
    
    /**
     * 创建默认配置的验证钩子
     */
    public static ValidationHook createDefault() {
        ValidationHook hook = new ValidationHook();
        hook.loadDefaultSensitiveWords();
        hook.setMaxContentLength(8192);
        return hook;
    }
    
    /**
     * 创建严格模式的验证钩子
     */
    public static ValidationHook createStrict() {
        ValidationHook hook = new ValidationHook();
        hook.loadDefaultSensitiveWords();
        hook.setMaxContentLength(2048);
        return hook;
    }
}