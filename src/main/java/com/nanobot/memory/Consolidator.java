package com.nanobot.memory;

import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 记忆压缩器
 * ==================
 * 
 * 本类负责压缩对话历史，以避免上下文窗口超限。
 * 通过智能总结历史消息，保留关键信息同时减少 token 消耗。
 * 
 * **压缩策略**：
 * 1. 计算当前对话的 token 使用量
 * 2. 如果超过预算，选择最不重要的消息进行压缩
 * 3. 使用 LLM 对选中的消息进行总结
 * 4. 用总结替换原始消息
 * 
 * **优先级规则**：
 * - 系统消息：最高优先级，不压缩
 * - 工具调用结果：高优先级，保留关键结果
 * - 用户消息：中等优先级，可适当压缩
 * - 助手消息：低优先级，优先压缩
 */
public class Consolidator {
    
    private static final Logger logger = LoggerFactory.getLogger(Consolidator.class);
    
    private final LLMProvider llmProvider;
    private final int tokenBudget;
    private final int tokenMargin;
    
    /**
     * 创建压缩器
     * 
     * @param llmProvider LLM 提供商，用于生成总结
     * @param tokenBudget 最大 token 预算
     * @param tokenMargin 安全边际，默认 10%
     */
    public Consolidator(LLMProvider llmProvider, int tokenBudget) {
        this(llmProvider, tokenBudget, (int) (tokenBudget * 0.1));
    }
    
    public Consolidator(LLMProvider llmProvider, int tokenBudget, int tokenMargin) {
        this.llmProvider = llmProvider;
        this.tokenBudget = tokenBudget;
        this.tokenMargin = tokenMargin;
        logger.info("Consolidator initialized with token budget: {}, margin: {}", tokenBudget, tokenMargin);
    }
    
    /**
     * 压缩消息列表
     * 
     * @param messages 原始消息列表
     * @return 压缩后的消息列表
     */
    public CompletableFuture<List<Map<String, Object>>> consolidate(
            List<Map<String, Object>> messages) {
        
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        
        int currentTokens = countTokens(messages);
        
        // 如果没有超限，直接返回
        if (currentTokens <= tokenBudget - tokenMargin) {
            return CompletableFuture.completedFuture(new ArrayList<>(messages));
        }
        
        logger.info("Memory consolidation needed: current={}, budget={}", currentTokens, tokenBudget);
        
        return doConsolidate(messages, currentTokens);
    }
    
    /**
     * 执行压缩
     */
    private CompletableFuture<List<Map<String, Object>>> doConsolidate(
            List<Map<String, Object>> messages, int currentTokens) {
        
        // 计算需要释放的 token 数量
        int tokensToFree = currentTokens - (tokenBudget - tokenMargin);
        
        // 选择要压缩的消息组
        List<Map<String, Object>> toCompress = selectMessagesToCompress(messages, tokensToFree);
        
        if (toCompress.isEmpty()) {
            logger.warn("No messages selected for compression, returning original");
            return CompletableFuture.completedFuture(new ArrayList<>(messages));
        }
        
        // 生成总结
        return generateSummary(toCompress)
            .thenApply(summary -> replaceWithSummary(messages, toCompress, summary));
    }
    
    /**
     * 选择要压缩的消息
     */
    private List<Map<String, Object>> selectMessagesToCompress(
            List<Map<String, Object>> messages, int targetTokens) {
        
        List<Map<String, Object>> candidates = new ArrayList<>();
        int freedTokens = 0;
        
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.get("role");
            
            // 跳过系统消息
            if ("system".equals(role)) {
                continue;
            }
            
            // 优先选择助手消息和工具结果
            if ("assistant".equals(role) || "tool".equals(role)) {
                candidates.add(0, msg);
                freedTokens += countMessageTokens(msg);
                
                if (freedTokens >= targetTokens) {
                    break;
                }
            }
        }
        
        return candidates;
    }
    
    /**
     * 生成消息总结
     */
    private CompletableFuture<String> generateSummary(List<Map<String, Object>> messages) {
        if (messages.isEmpty()) {
            return CompletableFuture.completedFuture("");
        }
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("请对以下对话历史进行简洁总结，保留关键信息：\n\n");
        
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            String content = (String) msg.get("content");
            prompt.append(role).append(": ").append(content).append("\n");
        }
        
        prompt.append("\n总结：");
        
        List<LLMProvider.Message> llmMessages = List.of(
            LLMProvider.Message.ofUser(prompt.toString())
        );
        
        return llmProvider.chat(llmMessages, Collections.emptyList())
            .thenApply(response -> {
                String summary = response.getContent();
                if (summary == null || summary.isBlank()) {
                    return "(无法生成总结)";
                }
                return summary;
            })
            .exceptionally(e -> {
                logger.error("Failed to generate summary", e);
                return "(总结生成失败)";
            });
    }
    
    /**
     * 用总结替换原始消息
     */
    private List<Map<String, Object>> replaceWithSummary(
            List<Map<String, Object>> original,
            List<Map<String, Object>> compressed,
            String summary) {
        
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Map<String, Object>> compressedSet = new HashSet<>(compressed);
        
        for (Map<String, Object> msg : original) {
            if (compressedSet.contains(msg)) {
                // 跳过被压缩的消息
                continue;
            }
            result.add(msg);
        }
        
        // 添加压缩后的总结消息
        Map<String, Object> summaryMsg = new HashMap<>();
        summaryMsg.put("role", "system");
        summaryMsg.put("content", "[对话历史总结] " + summary);
        summaryMsg.put("compressed", true);
        
        // 插入到系统消息之后
        int insertIndex = 0;
        for (int i = 0; i < result.size(); i++) {
            String role = (String) result.get(i).get("role");
            if (!"system".equals(role)) {
                insertIndex = i;
                break;
            }
        }
        result.add(insertIndex, summaryMsg);
        
        logger.info("Memory consolidation completed: {} messages compressed into summary", compressed.size());
        
        return result;
    }
    
    /**
     * 计算消息列表的 token 数量
     */
    public int countTokens(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> msg : messages) {
            total += countMessageTokens(msg);
        }
        return total;
    }
    
    /**
     * 计算单条消息的 token 数量
     */
    private int countMessageTokens(Map<String, Object> msg) {
        String content = (String) msg.get("content");
        if (content == null) {
            content = "";
        }
        
        // 简单的 token 估算：按字符数计算
        // 实际应用中应该使用模型特定的 tokenizer
        int charCount = content.length();
        
        // 平均每个 token 约 4 个字符
        int tokens = (int) Math.ceil(charCount / 4.0);
        
        // 添加固定开销（消息结构）
        tokens += 4; // role, content 等字段
        
        return tokens;
    }
    
    /**
     * 检查是否需要压缩
     */
    public boolean needsConsolidation(List<Map<String, Object>> messages) {
        return countTokens(messages) > tokenBudget - tokenMargin;
    }
    
    /**
     * 获取当前 token 使用量
     */
    public int getCurrentUsage(List<Map<String, Object>> messages) {
        return countTokens(messages);
    }
    
    /**
     * 获取剩余 token 预算
     */
    public int getRemainingBudget(List<Map<String, Object>> messages) {
        return tokenBudget - countTokens(messages);
    }
}
