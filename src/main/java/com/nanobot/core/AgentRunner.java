package com.nanobot.core;

import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import com.nanobot.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    
    // ==================== 配置 ====================
    
    /** 最大工具结果字符数 */
    private int maxToolResultChars = 16_000;
    
    /** 工具提示最大长度 */
    private int toolHintMaxLength = 40;
    
    /** 工具执行线程池 */
    private final ExecutorService toolExecutor;
    
    // ==================== 构造函数 ====================
    
    public AgentRunner(LLMProvider provider, ToolRegistry registry) {
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
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
        
        return runInternal(context, messages, onDelta, 0);
    }
    
    /**
     * 内部递归执行
     */
    private CompletableFuture<String> runInternal(
            TurnContext context,
            List<Map<String, Object>> messages,
            Consumer<String> onDelta,
            int iteration) {
        
        // 检查最大迭代
        if (iteration >= context.getMaxIterations()) {
            logger.warn("Max iterations reached for session: {}", context.getSessionKey());
            return CompletableFuture.completedFuture(
                "抱歉，我已经达到了最大处理次数限制。请重新开始或简化您的请求。"
            );
        }
        
        // 检查取消
        if (context.isCancelled()) {
            return CompletableFuture.completedFuture("处理已取消。");
        }
        
        // 清理孤立工具结果
        final List<Map<String, Object>> finalMessages = dropOrphanToolResults(messages);
        
        // 调用 LLM
        List<LLMProvider.Message> llmMessages = convertToLLMMessages(finalMessages);
        
        logger.debug("Calling LLM with {} messages (iteration {})", 
                    llmMessages.size(), iteration);
        
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
                logger.info("LLM requested {} tool calls: {}", 
                          toolCalls.size(),
                          toolCalls.stream()
                              .map(LLMResponse.ToolCallRequest::getName)
                              .collect(Collectors.joining(", ")));
                
                // 添加助手消息（带工具调用）
                workingMessages.add(createAssistantMessage(response.getContent(), toolCalls));
                
                // 执行工具
                return executeTools(context, workingMessages, toolCalls)
                    .thenCompose(v -> runInternal(context, workingMessages, onDelta, iteration + 1));
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
    private CompletableFuture<Void> executeTools(
            TurnContext context,
            List<Map<String, Object>> messages,
            List<LLMResponse.ToolCallRequest> toolCalls) {
        
        logger.debug("Executing {} tool calls", toolCalls.size());
        
        // 记录开始时间
        context.setResponse(context.getResponse());
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (LLMResponse.ToolCallRequest call : toolCalls) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // 执行工具
                    String toolName = call.getName();
                    Map<String, Object> params = call.getArguments();
                    
                    logger.debug("Executing tool: {} with params: {}", toolName, params);
                    
                    Object result = registry.execute(toolName, params);
                    
                    // 处理结果
                    String resultStr = result != null ? result.toString() : "";
                    
                    // 截断过长的结果
                    if (resultStr.length() > maxToolResultChars) {
                        resultStr = resultStr.substring(0, maxToolResultChars) + 
                                 "\n\n[结果已截断，超出最大长度限制]";
                    }
                    
                    // 添加结果消息
                    messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", call.getId(),
                        "name", toolName,
                        "content", resultStr
                    ));
                    
                } catch (Exception e) {
                    logger.error("Tool execution failed: {}", e.getMessage(), e);
                    messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", call.getId(),
                        "name", call.getName(),
                        "content", "Error: " + e.getMessage()
                    ));
                }
            }, toolExecutor);
            
            futures.add(future);
        }
        
        // 等待所有工具执行完成
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .exceptionally(error -> {
                logger.error("Tool execution failed: {}", error.getMessage());
                return null;
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
        // 如果是，保留最近的连续工具结果块
        List<Map<String, Object>> result = new ArrayList<>();
        boolean hasRecentToolResult = false;
        
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.get("role");
            
            if ("tool".equals(role)) {
                hasRecentToolResult = true;
            } else if ("assistant".equals(role)) {
                // 如果助手消息有 tool_calls，之前是工具结果是正常的
                if (msg.containsKey("tool_calls")) {
                    // 保留工具结果
                    break;
                } else if (hasRecentToolResult) {
                    // 没有 tool_calls 但之前有工具结果，说明工具结果孤立了
                    // 保留之前的消息，跳过孤立结果
                    break;
                } else {
                    break;
                }
            } else {
                break;
            }
            
            result.add(0, msg);
        }
        
        // 添加之前的消息
        for (int i = 0; i < messages.size() - result.size(); i++) {
            result.add(messages.get(i));
        }
        
        return result;
    }
    
    /**
     * 创建助手消息
     */
    private Map<String, Object> createAssistantMessage(
            String content,
            List<LLMResponse.ToolCallRequest> toolCalls) {
        
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        
        if (content != null && !content.isBlank()) {
            message.put("content", content);
        }
        
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<Map<String, Object>> tcList = toolCalls.stream()
                .map(tc -> {
                    Map<String, Object> tcMap = new HashMap<>();
                    tcMap.put("id", tc.getId());
                    tcMap.put("type", "function");
                    Map<String, Object> func = Map.of(
                        "name", tc.getName(),
                        "arguments", tc.getArguments()
                    );
                    tcMap.put("function", func);
                    return tcMap;
                })
                .toList();
            message.put("tool_calls", tcList);
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
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = (Map<String, Object>) 
                        ((Map<String, Object>) tc.get("function")).get("arguments");
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
