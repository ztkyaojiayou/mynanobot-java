package com.nanobot.core;

import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import com.nanobot.tools.Tool;
import com.nanobot.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Agent Runner - LLM 调用循环核心
 * =================================
 * 
 * 本类是 Agent 的核心执行引擎，负责：
 * 1. 管理 LLM 调用循环
 * 2. 处理工具调用
 * 3. 管理消息上下文
 * 
 * **工作流程**：
 * 
 * ```
 * ┌─────────────┐
 * │   接收消息   │
 * └──────┬──────┘
 *        ▼
 * ┌─────────────┐
 * │  调用 LLM   │
 * └──────┬──────┘
 *        ▼
 *    ┌───────┐
 *    │ 需要   │──YES──▶ 执行工具
 *    │ 工具？ │           │
 *    └───────┘           │
 *     NO │               ▼
 *        │         ┌─────────────┐
 *        ▼         │  收集结果   │
 *    ┌────────┐    └──────┬──────┘
 *    │ 返回   │           │
 *    │ 响应   │◀──────────┘
 *    └────────┘
 * ```
 * 
 * **设计思想**：
 * 
 * 1. **循环执行**：
 *    - 支持多轮工具调用
 *    - 每轮调用后检查是否需要继续
 *    - 有最大迭代次数保护
 * 
 * 2. **上下文治理**：
 *    - 工具结果清理
 *    - 历史截断
 *    - Token 预算管理
 * 
 * 3. **工具执行**：
 *    - 支持并行执行
 *    - 支持独占执行
 *    - 完善的错误处理
 * 
 * **使用示例**：
 * 
 * ```java
 * // 1. 创建 Runner
 * AgentRunner runner = new AgentRunner(provider, registry);
 * 
 * // 2. 准备上下文
 * List<Message> messages = List.of(
 *     Message.ofSystem("You are a helpful assistant."),
 *     Message.ofUser("Read the file /tmp/test.txt")
 * );
 * 
 * // 3. 执行
 * String result = runner.run(context, messages, null).join();
 * 
 * // 4. 打印结果
 * System.out.println(result);
 * ```
 */
public class AgentRunner {
    
    // ==================== 日志 ====================
    
    private static final Logger logger = LoggerFactory.getLogger(AgentRunner.class);
    
    // ==================== 依赖 ====================
    
    private final LLMProvider provider;
    private final ToolRegistry registry;
    private final ObjectMapper objectMapper;
    
    // ==================== 配置 ====================
    
    /** 最大工具结果字符数 */
    private int maxToolResultChars = 16_000;
    
    /** 工具提示最大长度 */
    private int toolHintMaxLength = 40;
    
    /** 工具执行超时时间（秒） */
    private int toolTimeoutSeconds = 90;
    
    /** 工具执行最大重试次数 */
    private int maxToolRetries = 3;
    
    /** 重试间隔时间（毫秒） */
    private int retryDelayMs = 1000;
    
    /** 工具执行线程池 */
    private final ExecutorService toolExecutor;
    
    // ==================== 构造函数 ====================
    
    public AgentRunner(LLMProvider provider, ToolRegistry registry) {
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.objectMapper = new ObjectMapper();
        this.toolExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r, "ToolExecutor");
                t.setDaemon(true);
                return t;
            }
        );
    }
    
    // ==================== 核心方法 ====================
    
    /**
     * 运行 Agent
     * 
     * 执行 LLM 调用循环，直到返回最终响应或达到最大迭代。
     * 
     * @param context 会话上下文
     * @param messages 消息列表（会被修改）
     * @param onDelta 流式输出回调
     * @return 最终响应内容
     */
    public CompletableFuture<String> run(
            TurnContext context,
            List<Map<String, Object>> messages,
            Consumer<String> onDelta) {
        
        return runInternal(context, messages, onDelta, 0, 0);
    }
    
    /**
     * 内部递归执行
     */
    private CompletableFuture<String> runInternal(
            TurnContext context,
            List<Map<String, Object>> messages,
            Consumer<String> onDelta,
            int iteration,
            int consecutiveToolFailures) {

        // 降级兜底：连续工具失败3次，强制 LLM 不使用工具直接回答
        if (consecutiveToolFailures >= 3) {
            logger.warn("{} consecutive tool failures, forcing fallback (no tools) for session: {}",
                    consecutiveToolFailures, context.getSessionKey());
            return callLLMWithoutTools(context, messages, onDelta);
        }

        // 检查 maxTurns（优先）或 maxToolIterations
        int maxTurns = context.getMaxTurns();
        if (maxTurns > 0 && iteration >= maxTurns) {
            logger.warn("Max turns ({}) reached: {}", maxTurns, context.getSessionKey());
            return CompletableFuture.completedFuture(
                "已到达最大轮次限制（" + maxTurns + "）。请简化请求或增加限制。");
        }
        if (maxTurns <= 0 && iteration >= context.getMaxIterations()) {
            logger.warn("Max iterations reached: {}", context.getSessionKey());
            return CompletableFuture.completedFuture(
                "抱歉，已达到最大处理次数限制。请重新开始或简化您的请求。");
        }

        // 检查 maxCost（费用预算）
        double maxCost = context.getMaxCost();
        if (maxCost > 0 && context.getCumulativeCost() >= maxCost) {
            logger.warn("Max cost (${}) exceeded: ${}", maxCost, context.getCumulativeCost());
            return CompletableFuture.completedFuture(
                "已超出费用预算（$" + String.format("%.4f", maxCost) + "）。当前累计: $" + String.format("%.4f", context.getCumulativeCost()));
        }

        // 检查取消
        if (context.isCancelled()) {
            return CompletableFuture.completedFuture("处理已取消。");
        }

        // 清理孤立工具结果 + 不完整 tool_calls（DeepSeek 要求 tool_call 必须有对应结果）
        List<Map<String, Object>> finalMessages = dropOrphanToolResults(messages);
        finalMessages = sanitizeToolCallHistory(finalMessages);
        
        // 调用 LLM
        List<LLMProvider.Message> llmMessages = convertToLLMMessages(finalMessages);
        
        // 输出完整的 LLM 提示词（DEBUG 级别）
        logger.debug("===== LLM 调用开始 (迭代 {}) =====", iteration);
        logger.debug("会话ID: {}", context.getSessionKey());
        logger.debug("消息数量: {}", llmMessages.size());
        for (int i = 0; i < llmMessages.size(); i++) {
            LLMProvider.Message msg = llmMessages.get(i);
            logger.debug("[{}] role={}, content_length={}", 
                        i, msg.getRole(), 
                        msg.getContent() != null ? msg.getContent().length() : 0);
        }
        
        // 输出完整的系统提示词（INFO 级别，方便调试）
        Optional<LLMProvider.Message> systemMsg = llmMessages.stream()
            .filter(m -> "system".equals(m.getRole()))
            .findFirst();
        if (systemMsg.isPresent()) {
            String systemContent = systemMsg.get().getContent();
            // 限制长度，避免日志过长
            if (systemContent.length() > 2000) {
                systemContent = systemContent.substring(0, 2000) + "...(截断)";
            }
            logger.info("LLM 系统提示词:\n{}", systemContent);
        }
        
        logger.debug("===== LLM 提示词结束 =====");
        
        // 决定是否使用流式
        final boolean finalUseStreaming = onDelta != null && provider.supportsStreaming();
        
        CompletableFuture<LLMResponse> llmFuture;
        if (finalUseStreaming) {
            llmFuture = provider.chatStream(llmMessages, context.getToolDefinitions(), onDelta);
        } else {
            llmFuture = provider.chat(llmMessages, context.getToolDefinitions());
        }
        
        final List<Map<String, Object>> workingMessages = new java.util.ArrayList<>(finalMessages);
        
        return llmFuture.thenCompose(response -> {
            // 更新统计
            context.addUsage(response.getPromptTokens(), response.getCompletionTokens());
            
            // 输出 LLM 原始响应（DEBUG 级别）
            logger.debug("===== LLM 响应开始 =====");
            logger.debug("会话ID: {}", context.getSessionKey());
            logger.debug("PromptTokens: {}, CompletionTokens: {}", 
                        response.getPromptTokens(), response.getCompletionTokens());
            
            if (response.isError()) {
                logger.debug("响应类型: ERROR");
                logger.debug("错误信息: {}", response.getError());
            } else {
                logger.debug("响应类型: {}", response.shouldExecuteTools() ? "工具调用" : "直接响应");
                String content = response.getContent();
                logger.debug("响应内容长度: {}", content != null ? content.length() : 0);
                
                // 输出完整响应内容（INFO 级别，方便调试）
                if (content != null && !content.isBlank()) {
                    // 限制长度，避免日志过长
                    if (content.length() > 2000) {
                        content = content.substring(0, 2000) + "...(截断)";
                    }
                    logger.info("LLM 原始响应:\n{}", content);
                }
                
                // 输出工具调用信息
                if (response.shouldExecuteTools() && response.getToolCalls() != null) {
                    logger.info("LLM 工具调用: {}", 
                              response.getToolCalls().stream()
                                  .map(t -> t.getName() + "(" + t.getArguments() + ")")
                                  .collect(Collectors.joining(", ")));
                }
            }
            logger.debug("===== LLM 响应结束 =====");
            
            // 检查错误
            if (response.isError()) {
                logger.error("LLM error for session {}: {}", 
                           context.getSessionKey(), response.getError());
                context.setError(response.getError());
                return CompletableFuture.completedFuture(
                    "发生错误：" + response.getError()
                );
            }
            
            // 检查工具调用
            if (response.shouldExecuteTools()) {
                List<LLMResponse.ToolCallRequest> toolCalls = response.getToolCalls();
                boolean webSearchDisabled = false;
                
                // 检查是否禁用了联网搜索（仅禁用 web，本地工具始终可用）
                Boolean useSearch = (Boolean) context.getMessage().getMetadata().get("useSearch");
                if (useSearch != null && !useSearch) {
                    webSearchDisabled = true;
                    // 仅过滤 web_search / web_fetch，保留 get_current_time / 文件 / shell 等本地工具
                    List<LLMResponse.ToolCallRequest> filteredCalls = toolCalls.stream()
                        .filter(tc -> !"web_search".equals(tc.getName())
                                   && !"web_fetch".equals(tc.getName()))
                        .collect(Collectors.toList());

                    int removed = toolCalls.size() - filteredCalls.size();
                    if (removed > 0) {
                        logger.info("Web search disabled, removed {} web tool call(s)", removed);
                    }
                    toolCalls = filteredCalls;
                }
                
                logger.info("LLM requested {} tool calls (after filtering): {}", 
                          toolCalls.size(),
                          toolCalls.stream()
                              .map(LLMResponse.ToolCallRequest::getName)
                              .collect(Collectors.joining(", ")));
                
                // 如果没有工具调用了且禁用了联网搜索，重新调用LLM（不提供工具定义）让它直接回答
                if (toolCalls.isEmpty() && webSearchDisabled) {
                    logger.info("Web search disabled and no tool calls allowed, calling LLM without tools");
                    List<LLMProvider.Message> messagesWithoutTools = convertToLLMMessages(workingMessages);
                    CompletableFuture<LLMResponse> directResponseFuture;
                    if (onDelta != null && provider.supportsStreaming()) {
                        directResponseFuture = provider.chatStream(messagesWithoutTools, Collections.emptyList(), onDelta);
                    } else {
                        directResponseFuture = provider.chat(messagesWithoutTools, Collections.emptyList());
                    }
                    
                    return directResponseFuture.thenCompose(directResponse -> {
                        context.addUsage(directResponse.getPromptTokens(), directResponse.getCompletionTokens());
                        String content = directResponse.getContent();
                        if (content == null || content.isBlank()) {
                            content = "(无内容)";
                        }
                        workingMessages.add(Map.of("role", "assistant", "content", content));
                        return CompletableFuture.completedFuture(content);
                    }).exceptionally(error -> {
                        logger.error("Exception when calling LLM without tools: {}", error.getMessage(), error);
                        return "发生异常：" + error.getMessage();
                    });
                }
                
                // 如果没有工具调用但不是因为禁用联网搜索，直接返回响应
                if (toolCalls.isEmpty()) {
                    String content = response.getContent();
                    if (content == null || content.isBlank()) {
                        content = "(无内容)";
                    }
                    workingMessages.add(Map.of("role", "assistant", "content", content));
                    return CompletableFuture.completedFuture(content);
                }
                
                // 添加助手消息（带工具调用）
            Map<String, Object> assistantMsg = createAssistantMessage(response.getContent(), toolCalls);
            workingMessages.add(assistantMsg);
            
            // 执行工具，跟踪连续失败次数用于降级兜底
            final int tcCount = toolCalls.size();
            return executeTools(context, workingMessages, toolCalls)
                .thenCompose(v -> {
                    boolean allFailed = checkAllToolsFailed(workingMessages, tcCount);
                    int newFailures = allFailed ? consecutiveToolFailures + 1 : 0;
                    if (allFailed) {
                        logger.warn("All {} tool(s) failed (consecutive={})", tcCount, newFailures);
                    }
                    return runInternal(context, workingMessages, onDelta, iteration + 1, newFailures);
                });
            }

            // 最终响应
            String content = response.getContent();
            if (content == null || content.isBlank()) {
                content = "(无内容)";
            }
            
            // 添加助手消息
            workingMessages.add(Map.of("role", "assistant", "content", content));
            
            return CompletableFuture.completedFuture(content);
            
        }).exceptionally(error -> {
            logger.error("Exception in AgentRunner for session {}: {}", 
                        context.getSessionKey(), error.getMessage(), error);
            return "发生异常：" + error.getMessage();
        });
    }
    
    // ==================== 工具执行 ====================
    
    /**
     * 执行工具调用
     */
    /**
     * 执行工具调用 — 只读工具并行，写工具保持顺序。
     * 所有工具通过 runAsync 提交到线程池并发执行，allOf 等待全部完成。
     * 结果按原始 tool_calls 顺序追加到 messages，保证 LLM 看到的顺序正确。
     */
    private CompletableFuture<Void> executeTools(
            TurnContext context,
            List<Map<String, Object>> messages,
            List<LLMResponse.ToolCallRequest> toolCalls) {

        int readCount = (int) toolCalls.stream().filter(tc -> {
            Tool t = registry.get(tc.getName());
            return t != null && t.isReadOnly();
        }).count();
        logger.info("Executing {} tool calls ({} read-only → parallel, {} write → serial-after-reads)",
                toolCalls.size(), readCount, toolCalls.size() - readCount);

        // 提交所有工具到线程池并行执行，结果收集到有序数组
        String[] results = new String[toolCalls.size()];
        CompletableFuture<?>[] futures = new CompletableFuture<?>[toolCalls.size()];

        for (int i = 0; i < toolCalls.size(); i++) {
            final int idx = i;
            LLMResponse.ToolCallRequest call = toolCalls.get(i);
            futures[i] = CompletableFuture.runAsync(() -> {
                String toolName = call.getName();
                Object result = executeToolWithRetry(toolName, call.getArguments(), call.getId());
                String resultStr = result != null ? result.toString() : "";
                if (resultStr.length() > maxToolResultChars) {
                    resultStr = resultStr.substring(0, maxToolResultChars)
                            + "\n\n[结果已截断，超出最大长度限制]";
                }
                results[idx] = resultStr; // 按索引写入，保证顺序
            }, toolExecutor);
        }

        // 等待全部完成
        return CompletableFuture.allOf(futures).thenRun(() -> {
            for (int i = 0; i < toolCalls.size(); i++) {
                messages.add(Map.of(
                    "role", "tool",
                    "tool_call_id", toolCalls.get(i).getId(),
                    "content", results[i] != null ? results[i] : "Error: no result"
                ));
            }
        }).exceptionally(error -> {
            logger.error("Tool execution failed: {}", error.getMessage());
            return null;
        });
    }
    
    /**
     * 执行工具调用（带重试机制）
     * 
     * @param toolName 工具名称
     * @param params 工具参数
     * @param callId 工具调用ID
     * @return 工具执行结果
     */
    private Object executeToolWithRetry(String toolName, Map<String, Object> params, String callId) {
        int attempts = 0;
        Exception lastException = null;
        
        while (attempts < maxToolRetries) {
            attempts++;
            
            try {
                logger.debug("Executing tool: {} (attempt {}/{}) with params: {}", 
                           toolName, attempts, maxToolRetries, params);
                
                // 执行工具并设置超时
                CompletableFuture<Object> toolFuture = registry.executeAsync(toolName, params);
                Object result = toolFuture.get(toolTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
                
                logger.info("Tool {} executed successfully (attempt {})", toolName, attempts);
                return result;
                
            } catch (java.util.concurrent.TimeoutException e) {
                lastException = e;
                logger.warn("Tool {} timeout on attempt {}: {}", toolName, attempts, e.getMessage());
            } catch (java.util.concurrent.ExecutionException e) {
                // 检查底层异常是否是网络相关的
                Throwable cause = e.getCause();
                lastException = e;
                
                if (cause instanceof java.net.ConnectException) {
                    logger.warn("Tool {} connection error on attempt {}: {}", toolName, attempts, cause.getMessage());
                } else if (cause instanceof java.io.IOException) {
                    logger.warn("Tool {} IO error on attempt {}: {}", toolName, attempts, cause.getMessage());
                } else {
                    // 其他 ExecutionException 不重试
                    logger.error("Tool {} failed on attempt {}: {}", toolName, attempts, e.getMessage());
                    return "Error: " + (cause != null ? cause.getMessage() : e.getMessage());
                }
            } catch (java.lang.InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Tool {} interrupted on attempt {}", toolName, attempts);
                lastException = e;
            } catch (Exception e) {
                // 其他异常不重试，直接返回错误信息
                logger.error("Tool {} failed on attempt {}: {}", toolName, attempts, e.getMessage());
                return "Error: " + e.getMessage();
            }
            
            // 如果还有重试机会，等待一段时间后重试
            if (attempts < maxToolRetries) {
                try {
                    logger.debug("Waiting {}ms before retry for tool {}", retryDelayMs, toolName);
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        // 所有重试都失败了，返回友好的错误信息
        logger.error("Tool {} failed after {} attempts", toolName, maxToolRetries);
        
        // 根据失败原因返回不同的错误信息
        String errorMsg;
        if (lastException instanceof java.net.ConnectException) {
            errorMsg = "网络连接失败，无法访问外部服务。我将基于我的知识库为您回答。";
        } else if (lastException instanceof java.util.concurrent.TimeoutException) {
            errorMsg = "请求超时，无法获取最新信息。我将基于我的知识库为您回答。";
        } else {
            errorMsg = "工具调用失败（已重试 " + maxToolRetries + " 次）。我将基于我的知识库为您回答。";
        }
        
        return errorMsg;
    }
    
    // ==================== 降级兜底 ====================

    private boolean checkAllToolsFailed(List<Map<String, Object>> messages, int tcCount) {
        int found = 0, failed = 0;
        for (int i = messages.size() - 1; i >= 0 && found < tcCount; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("tool".equals(msg.get("role"))) {
                found++;
                String c = (String) msg.get("content");
                if (c != null && (c.startsWith("Error:") || c.startsWith("Security blocked:"))) failed++;
            }
        }
        return found > 0 && failed == found;
    }

    private CompletableFuture<String> callLLMWithoutTools(
            TurnContext ctx, List<Map<String, Object>> msgs, Consumer<String> delta) {
        logger.info("Fallback LLM (no tools) for session: {}", ctx.getSessionKey());
        List<LLMProvider.Message> llmMsgs = convertToLLMMessages(msgs);
        CompletableFuture<LLMResponse> f;
        if (delta != null && provider.supportsStreaming())
            f = provider.chatStream(llmMsgs, Collections.emptyList(), delta);
        else
            f = provider.chat(llmMsgs, Collections.emptyList());
        return f.thenApply(r -> {
            String c = r.getContent();
            return (c == null || c.isBlank()) ? "[工具调用失败，请重试]" : c;
        }).exceptionally(e -> {
            logger.error("Fallback LLM call failed: {}", e.getMessage());
            return "抱歉，工具调用出现异常：" + e.getMessage();
        });
    }

    // ==================== 消息处理 ====================
    
    /**
     * 清理孤立的工具结果
     * 
     * 当 LLM 没有请求工具调用但之前有工具调用结果时，
     * 需要清理这些孤立的结果。
     */
    private List<Map<String, Object>> dropOrphanToolResults(List<Map<String, Object>> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        
        // 检查最后一条消息是否是工具结果
        // 如果是，保留最近的连续工具结果块（必须紧跟在带 tool_calls 的助手消息之后）
        List<Map<String, Object>> result = new ArrayList<>(messages);
        
        // 从后往前查找孤立的工具结果
        int lastIndex = messages.size() - 1;
        String lastRole = (String) messages.get(lastIndex).get("role");
        
        // 只有最后一条消息是 tool 角色时才需要检查
        if (!"tool".equals(lastRole)) {
            return result;
        }
        
        // 查找前面的助手消息是否有 tool_calls
        boolean hasValidPredecessor = false;
        for (int i = lastIndex - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.get("role");
            
            if ("assistant".equals(role)) {
                if (msg.containsKey("tool_calls")) {
                    // 找到带 tool_calls 的助手消息，工具结果是有效的
                    hasValidPredecessor = true;
                }
                break;
            } else if ("tool".equals(role)) {
                // 继续向前找
                continue;
            } else {
                // 遇到其他角色，工具结果是孤立的
                break;
            }
        }
        
        // 如果工具结果没有有效的前置助手消息（带 tool_calls），则移除它们
        if (!hasValidPredecessor) {
            // 移除末尾所有连续的 tool 消息
            while (!result.isEmpty()) {
                Map<String, Object> lastMsg = result.get(result.size() - 1);
                if ("tool".equals(lastMsg.get("role"))) {
                    result.remove(result.size() - 1);
                } else {
                    break;
                }
            }
        }
        
        return result;
    }

    /**
     * 清理不完整的 tool_calls。DeepSeek 要求 assistant(tool_calls) 后必须跟 tool 结果。
     * 不满足则移除 tool_calls 字段，当作普通消息。
     */
    private List<Map<String, Object>> sanitizeToolCallHistory(List<Map<String, Object>> messages) {
        List<Map<String, Object>> cleaned = new ArrayList<>(messages);
        for (int i = 0; i < cleaned.size(); i++) {
            Map<String, Object> msg = cleaned.get(i);
            if (!"assistant".equals(msg.get("role"))) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tcs = (List<Map<String, Object>>) msg.get("tool_calls");
            if (tcs == null || tcs.isEmpty()) continue;
            int found = 0;
            for (int j = i + 1; j < cleaned.size() && found < tcs.size(); j++) {
                if ("tool".equals(cleaned.get(j).get("role"))) found++;
                else break;
            }
            if (found < tcs.size()) {
                Map<String, Object> stripped = new HashMap<>(msg);
                stripped.remove("tool_calls");
                cleaned.set(i, stripped);
            }
        }
        return cleaned;
    }

    /**
     * 创建助手消息
     */
    private Map<String, Object> createAssistantMessage(
            String content,
            List<LLMResponse.ToolCallRequest> toolCalls) {
        
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // DeepSeek 要求：带工具调用的助手消息需要设置 content 为空字符串
            message.put("content", "");
            List<Map<String, Object>> tcList = toolCalls.stream()
                .map(tc -> {
                    Map<String, Object> tcMap = new HashMap<>();
                    tcMap.put("id", tc.getId());
                    tcMap.put("type", "function");
                    Map<String, Object> func = new HashMap<>();
                    func.put("name", tc.getName());
                    // DeepSeek 要求 arguments 必须是字符串格式（JSON 字符串）
                    Object args = tc.getArguments();
                    if (args instanceof String) {
                        func.put("arguments", args);
                    } else {
                        try {
                            String argsJson = objectMapper.writeValueAsString(args);
                            func.put("arguments", argsJson);
                        } catch (Exception e) {
                            func.put("arguments", "{}");
                        }
                    }
                    tcMap.put("function", func);
                    return tcMap;
                })
                .toList();
            message.put("tool_calls", tcList);
        } else {
            // 没有工具调用时设置 content
            message.put("content", content != null ? content : "");
        }
        
        return message;
    }
    
    /**
     * 转换消息格式
     */
    private List<LLMProvider.Message> convertToLLMMessages(List<Map<String, Object>> messages) {
        return messages.stream()
            .map(this::convertMessage)
            .collect(Collectors.toList());
    }
    
    /**
     * 转换单条消息
     */
    private LLMProvider.Message convertMessage(Map<String, Object> msg) {
        String role = (String) msg.get("role");
        Object contentObj = msg.get("content");
        String content = contentObj != null ? contentObj.toString() : "";
        
        // 处理工具调用
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
        
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<LLMProvider.Message.ToolCallInfo> tcList = toolCalls.stream()
                .map(tc -> {
                    String id = (String) tc.get("id");
                    String name = (String) ((Map<String, Object>) tc.get("function")).get("name");
                    Object argsObj = ((Map<String, Object>) tc.get("function")).get("arguments");
                    // arguments 可能是字符串（JSON）或 Map
                    Map<String, Object> args;
                    if (argsObj instanceof String) {
                        // 如果是字符串格式，需要解析
                        try {
                            args = objectMapper.readValue((String) argsObj, new TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                            logger.warn("Failed to parse arguments string: {}", argsObj);
                            args = new HashMap<>();
                        }
                    } else {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> argsMap = (Map<String, Object>) argsObj;
                        args = argsMap != null ? argsMap : new HashMap<>();
                    }
                    return new LLMProvider.Message.ToolCallInfo(id, name, args);
                })
                .collect(Collectors.toList());
            return LLMProvider.Message.ofAssistant(content, tcList);
        }
        
        // 处理工具结果
        if ("tool".equals(role)) {
            String toolCallId = (String) msg.get("tool_call_id");
            return LLMProvider.Message.ofTool(content, toolCallId);
        }
        
        // 普通消息
        return switch (role) {
            case "system" -> LLMProvider.Message.ofSystem(content);
            case "user" -> LLMProvider.Message.ofUser(content);
            case "assistant" -> LLMProvider.Message.ofAssistant(content);
            default -> LLMProvider.Message.ofUser(content);
        };
    }
    
    // ==================== 配置方法 ====================
    
    public void setMaxToolResultChars(int max) {
        this.maxToolResultChars = max;
    }
    
    public void setToolHintMaxLength(int max) {
        this.toolHintMaxLength = max;
    }
    
    // ==================== 生命周期 ====================
    
    public void shutdown() {
        toolExecutor.shutdown();
        logger.info("AgentRunner shutdown");
    }
}
