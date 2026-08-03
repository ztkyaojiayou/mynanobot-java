package com.nanobot.core;

import com.nanobot.hook.HookManager;
import com.nanobot.hook.HookContext;
import com.nanobot.hook.HookEvent;
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
 * <p>
 * 本类是 Agent 的核心执行引擎，负责：
 * 1. 管理 LLM 调用循环
 * 2. 处理工具调用
 * 3. 管理消息上下文
 * <p>
 * **工作流程**：
 * <p>
 * ```
 * ┌─────────────┐
 * │   接收消息   │
 * └──────┬──────┘
 * ▼
 * ┌─────────────┐
 * │  调用 LLM   │
 * └──────┬──────┘
 * ▼
 * ┌───────┐
 * │ 需要   │──YES──▶ 执行工具
 * │ 工具？ │           │
 * └───────┘           │
 * NO │               ▼
 * │         ┌─────────────┐
 * ▼         │  收集结果   │
 * ┌────────┐    └──────┬──────┘
 * │ 返回   │           │
 * │ 响应   │◀──────────┘
 * └────────┘
 * ```
 * <p>
 * **设计思想**：
 * <p>
 * 1. **循环执行**：
 * - 支持多轮工具调用
 * - 每轮调用后检查是否需要继续
 * - 有最大迭代次数保护
 * <p>
 * 2. **上下文治理**：
 * - 工具结果清理
 * - 历史截断
 * - Token 预算管理
 * <p>
 * 3. **工具执行**：
 * - 支持并行执行
 * - 支持独占执行
 * - 完善的错误处理
 * <p>
 * **使用示例**：
 * <p>
 * ```java
 * // 1. 创建 Runner
 * AgentRunner runner = new AgentRunner(provider, registry);
 * <p>
 * // 2. 准备上下文
 * List<Message> messages = List.of(
 * Message.ofSystem("You are a helpful assistant."),
 * Message.ofUser("Read the file /tmp/test.txt")
 * );
 * <p>
 * // 3. 执行
 * String result = runner.run(context, messages, null).join();
 * <p>
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

    /**
     * Hook 管理器 — 工具级事件（PRE_TOOL_USE/POST_TOOL_USE）在此触发.
     * 可为 null（未配置 hook 时）.
     */
    private HookManager hookManager;

    // ==================== 配置 ====================

    /**
     * 最大工具结果字符数
     */
    private int maxToolResultChars = 16_000;

    /**
     * 工具提示最大长度
     */
    private int toolHintMaxLength = 40;

    /**
     * 工具执行超时时间（秒）
     */
    private int toolTimeoutSeconds = 90;

    /**
     * 工具执行最大重试次数
     */
    private int maxToolRetries = 3;

    /**
     * 重试间隔时间（毫秒）
     */
    private int retryDelayMs = 1000;

    /**
     * 工具执行线程池
     */
    private final ExecutorService toolExecutor;
    private com.nanobot.bus.MessageBus messageBus;

    // ==================== 构造函数 ====================

    public AgentRunner(LLMProvider provider, ToolRegistry registry) {
        this(provider, registry, null);
    }

    public AgentRunner(LLMProvider provider, ToolRegistry registry, com.nanobot.bus.MessageBus messageBus) {
        this.messageBus = messageBus;
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

    /**
     * 注入 Hook 管理器（在 AgentLoop 初始化时调用）
     */
    public void setHookManager(HookManager hookManager) {
        this.hookManager = hookManager;
    }

    // ==================== 核心方法 ====================

    /**
     * 运行 Agent
     * <p>
     * 执行 LLM 调用循环，直到返回最终响应或达到最大迭代。
     *
     * @param context  会话上下文
     * @param messages 消息列表（会被修改）
     * @param onDelta  流式输出回调
     * @return 最终响应内容
     */
    public CompletableFuture<String> run(
            TurnContext context,
            List<Map<String, Object>> messages,
            Consumer<String> onDelta) {
        //异步执行
        return runInternal(context, messages, onDelta, 0, 0);
    }

    /**
     * 内部递归执行 — 核心 LLM 调用循环.
     * 务必掌握！！！
     * <h3>执行流程（5 个步骤）</h3>
     * <ol>
     *   <li>{@link #checkGuardConditions} — 守护条件检查（失败降级/轮次限制/费用/取消）</li>
     *   <li>{@link #prepareMessages} — 消息预处理（清理孤立工具结果 + 修复不完整 tool_calls）</li>
     *   <li>{@link #logLLMPrompt} — 提示词日志输出</li>
     *   <li>{@link #callLLM} — 调用 LLM（流式或非流式）</li>
     *   <li>{@link #processLLMResponse} — 响应分发（错误 / 工具调用 / 最终文本）</li>
     * </ol>
     */
    private CompletableFuture<String> runInternal(
            TurnContext context,
            List<Map<String, Object>> messages,
            Consumer<String> onDelta,
            int iteration,
            int consecutiveToolFailures) {

        // ── ① 守护条件检查 ──
        var earlyReturn = checkGuardConditions(context, messages, onDelta, iteration, consecutiveToolFailures);
        if (earlyReturn.isPresent()) {
            return earlyReturn.get();
        }

        // ── ② 消息预处理 ──
        List<Map<String, Object>> prepared = prepareMessages(messages);
        List<LLMProvider.Message> llmMessages = convertToLLMMessages(prepared);

        // ── ③ 日志输出 ──
        logLLMPrompt(context, llmMessages, iteration);

        // ── ④ 调用 LLM ──
        CompletableFuture<LLMResponse> llmFuture = callLLM(context, onDelta, llmMessages);

        // ── ⑤ 处理响应 + 递归 ──
        final List<Map<String, Object>> workingMessages = new ArrayList<>(prepared);
        return llmFuture
                .thenCompose(response -> processLLMResponse(
                        context, response, workingMessages, onDelta, iteration, consecutiveToolFailures))
                .exceptionally(error -> {
                    logger.error("Exception in AgentRunner for session {}: {}",
                            context.getSessionKey(), error.getMessage(), error);
                    return "发生异常：" + error.getMessage();
                });
    }

    // ==================== runInternal 子步骤 ====================

    /**
     * ① 守护条件检查 — 满足任一条件则提前返回，不调用 LLM.
     * <ol>
     *   <li>连续工具失败 ≥3 → 降级为无工具 LLM 调用</li>
     *   <li>达到 maxTurns 或 maxIterations</li>
     *   <li>超出 maxCost 费用预算</li>
     *   <li>用户取消（cancelled）</li>
     * </ol>
     *
     * @return 需要提前返回时包含 CompletableFuture，否则 {@code Optional.empty()}
     */
    private Optional<CompletableFuture<String>> checkGuardConditions(
            TurnContext context,
            List<Map<String, Object>> messages,
            Consumer<String> onDelta,
            int iteration,
            int consecutiveToolFailures) {

        // 降级兜底：连续工具失败 3 次 → 强制 LLM 无工具直接回答
        if (consecutiveToolFailures >= 3) {
            logger.warn("{} consecutive tool failures, forcing fallback (no tools) for session: {}",
                    consecutiveToolFailures, context.getSessionKey());
            return Optional.of(callLLMWithoutTools(context, messages, onDelta));
        }

        // 检查 maxTurns（优先）或 maxToolIterations
        int maxTurns = context.getMaxTurns();
        if (maxTurns > 0 && iteration >= maxTurns) {
            logger.warn("Max turns ({}) reached: {}", maxTurns, context.getSessionKey());
            return Optional.of(CompletableFuture.completedFuture(
                    "已到达最大轮次限制（" + maxTurns + "）。请简化请求或增加限制。"));
        }
        if (maxTurns <= 0 && iteration >= context.getMaxIterations()) {
            logger.warn("Max iterations reached: {}", context.getSessionKey());
            return Optional.of(CompletableFuture.completedFuture(
                    "抱歉，已达到最大处理次数限制。请重新开始或简化您的请求。"));
        }

        // 检查 maxCost（费用预算）
        double maxCost = context.getMaxCost();
        if (maxCost > 0 && context.getCumulativeCost() >= maxCost) {
            logger.warn("Max cost (${}) exceeded: ${}", maxCost, context.getCumulativeCost());
            return Optional.of(CompletableFuture.completedFuture(
                    "已超出费用预算（$" + String.format("%.4f", maxCost)
                            + "）。当前累计: $" + String.format("%.4f", context.getCumulativeCost())));
        }

        // 检查取消
        if (context.isCancelled()) {
            return Optional.of(CompletableFuture.completedFuture("处理已取消。"));
        }

        return Optional.empty();
    }

    /**
     * ② 消息预处理 — 清理孤立工具结果 + 修复不完整 tool_calls.
     * <p>
     * 这两个步骤确保传给 LLM 的消息历史格式正确，
     * 避免 DeepSeek 等严格后端因 tool_call/tool 不配对而报错.
     *
     * <h3>为什么需要这两个步骤？具体例子</h3>
     * 假设消息历史如下（模拟上下文压缩导致的不一致）：
     * <pre>{@code
     * // 第一轮对话（完整）
     * [0] {role: "user",      content: "北京天气怎么样？"}
     * [1] {role: "assistant", content: "", tool_calls: [{id:"call_1", function:{name:"get_weather", arguments:"{\"city\":\"北京\"}"}}]}
     * [2] {role: "tool",      tool_call_id: "call_1", content: "北京晴 25°C"}
     * [3] {role: "assistant", content: "北京今天天气晴朗，气温25°C。"}
     *
     * // 第二轮对话（上下文压缩后 assistant 的 tool_calls 消息被丢弃了！）
     * [4] {role: "user",      content: "那上海呢？"}
     * [5] {role: "tool",      tool_call_id: "call_2", content: "上海阴 22°C"}  ← 孤儿工具结果！
     * }</pre>
     *
     * <b>问题1 — 孤儿工具结果 (dropOrphanToolResults 解决)：</b><br>
     * 消息[5]是 tool 角色，但它前面没有带 tool_calls 的 assistant 消息（[4]是 user），
     * 这是"孤儿工具结果"——可能因上下文压缩时 assistant 消息被截断而产生。
     * 如果直接发给 DeepSeek，它会报错：
     * "Tool result must be preceded by a tool call"。<br>
     * → 解决方法：向前扫描，确认 tool 结果前面是否有带 tool_calls 的 assistant 消息。
     * 没有则删除这些孤儿 tool 消息。
     *
     * <p><b>问题2 — 不完整的 tool_calls (sanitizeToolCallHistory 解决)：</b><br>
     * 假设 assistant 消息声明了 3 个 tool_calls（call_1, call_2, call_3），
     * 但后面只有 2 个 tool 结果（call_3 的上下文被压缩丢弃了）。
     * DeepSeek 会报错："tool_call and tool result count mismatch"。<br>
     * → 解决方法：遍历所有 assistant 消息，检查其 tool_calls 数量是否与后续 tool 结果匹配。
     * 不匹配则移除该 assistant 消息的 tool_calls 字段，降级为普通文本消息。
     *
     * <p><b>总结：</b>这两个步骤是防御性编程——不是修复根本原因，而是保证
     * <b>即使上游产生了一致性问题，传给 LLM 的消息也永远是合法的</b>。
     */
    private List<Map<String, Object>> prepareMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> cleaned = dropOrphanToolResults(messages);
        return sanitizeToolCallHistory(cleaned);
    }

    /**
     * ③ 输出 LLM 提示词日志（DEBUG/INFO 级别）.
     */
    private void logLLMPrompt(TurnContext context, List<LLMProvider.Message> llmMessages, int iteration) {
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
            if (systemContent.length() > 2000) {
                systemContent = systemContent.substring(0, 2000) + "...(截断)";
            }
            logger.info("LLM 系统提示词:\n{}", systemContent);
        }

        logger.debug("===== LLM 提示词结束 =====");
    }

    /**
     * ④ 调用 LLM（流式或非流式）.
     */
    private CompletableFuture<LLMResponse> callLLM(
            TurnContext context, Consumer<String> onDelta, List<LLMProvider.Message> llmMessages) {
        CompletableFuture<LLMResponse> llmFuture;
        final boolean finalUseStreaming = onDelta != null && provider.supportsStreaming();
        if (finalUseStreaming) {
            llmFuture = provider.chatStream(llmMessages, context.getToolDefinitions(), onDelta);
        } else {
            llmFuture = provider.chat(llmMessages, context.getToolDefinitions());
        }
        return llmFuture;
    }

    // ── ⑤ 响应处理（分发器 + 三条路径）──

    /**
     * ⑤ 处理 LLM 响应 — 分发到三条路径之一.
     * <ul>
     *   <li>错误 → {@link #handleErrorResponse}</li>
     *   <li>工具调用 → {@link #handleToolCallResponse}</li>
     *   <li>最终文本 → {@link #handleFinalResponse}</li>
     * </ul>
     */
    private CompletableFuture<String> processLLMResponse(
            TurnContext context,
            LLMResponse response,
            List<Map<String, Object>> workingMessages,
            Consumer<String> onDelta,
            int iteration,
            int consecutiveToolFailures) {

        context.addUsage(response.getPromptTokens(), response.getCompletionTokens());
        logLLMResponse(context, response);

        if (response.isError()) {
            return handleErrorResponse(context, response.getError());
        }

        // 路径A：LLM 决定调用工具 → 执行工具 → 结果追加到 workingMessages → 递归回 agentLoopInner 继续下一轮
        if (response.shouldExecuteTools()) {
            return handleToolCallResponse(
                    context, response, workingMessages, onDelta, iteration, consecutiveToolFailures);
        }

        // 路径B：LLM 产出最终文本 → 追加 assistant 消息 → 返回 CompletableFuture<String>，Agent Loop 结束
        return handleFinalResponse(response.getContent(), workingMessages);
    }

    /**
     * 输出 LLM 响应日志（DEBUG/INFO 级别）.
     */
    private void logLLMResponse(TurnContext context, LLMResponse response) {
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

            if (content != null && !content.isBlank()) {
                if (content.length() > 2000) {
                    content = content.substring(0, 2000) + "...(截断)";
                }
                logger.info("LLM 原始响应:\n{}", content);
            }

            if (response.shouldExecuteTools() && response.getToolCalls() != null) {
                logger.info("LLM 工具调用: {}",
                        response.getToolCalls().stream()
                                .map(t -> t.getName() + "(" + t.getArguments() + ")")
                                .collect(Collectors.joining(", ")));
            }
        }
        logger.debug("===== LLM 响应结束 =====");
    }

    /**
     * ⑤-a 处理工具调用响应 — 过滤 web 工具 → 执行 → 递归.
     *
     * <h3>子路径</h3>
     * <ol>
     *   <li>联网被禁 + 无其他工具 → 重试不带工具的 LLM 调用</li>
     *   <li>无工具调用 → 作为最终文本返回</li>
     *   <li>有工具调用 → 执行工具 → 递归 {@link #runInternal}</li>
     * </ol>
     */
    private CompletableFuture<String> handleToolCallResponse(
            TurnContext context,
            LLMResponse response,
            List<Map<String, Object>> workingMessages,
            Consumer<String> onDelta,
            int iteration,
            int consecutiveToolFailures) {

        List<LLMResponse.ToolCallRequest> toolCalls = response.getToolCalls();
        boolean webSearchDisabled = false;

        // 检查是否禁用了联网搜索（仅禁用 web，本地工具始终可用）
        Boolean useSearch = (Boolean) context.getMessage().getMetadata().get("useSearch");
        if (useSearch != null && !useSearch) {
            webSearchDisabled = true;
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

        // 路径1: 无工具调用 + 联网被禁 → 重试不带工具的 LLM
        if (toolCalls.isEmpty() && webSearchDisabled) {
            logger.info("Web search disabled and no tool calls allowed, calling LLM without tools");
            return retryLLMWithoutWebTools(context, workingMessages, onDelta);
        }

        // 路径2: 无工具调用 → 直接返回文本
        if (toolCalls.isEmpty()) {
            return handleFinalResponse(response.getContent(), workingMessages);
        }

        // 路径3: 有工具调用 → 添加助手消息 + 执行工具 + 递归
        workingMessages.add(createAssistantMessage(response.getContent(), toolCalls));

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

    /**
     * ⑤-b 处理 LLM 错误响应.
     */
    private CompletableFuture<String> handleErrorResponse(TurnContext context, String error) {
        logger.error("LLM error for session {}: {}", context.getSessionKey(), error);
        context.setError(error);
        return CompletableFuture.completedFuture("发生错误：" + error);
    }

    /**
     * ⑤-c 处理最终文本响应 — 添加到 workingMessages 并返回.
     */
    private CompletableFuture<String> handleFinalResponse(
            String content, List<Map<String, Object>> workingMessages) {
        if (content == null || content.isBlank()) {
            content = "(无内容)";
        }
        workingMessages.add(Map.of("role", "assistant", "content", content));
        return CompletableFuture.completedFuture(content);
    }

    /**
     * 联网搜索被禁用后的回退 — 不带工具定义重新调用 LLM 并记录助手消息.
     */
    private CompletableFuture<String> retryLLMWithoutWebTools(
            TurnContext context,
            List<Map<String, Object>> workingMessages,
            Consumer<String> onDelta) {

        List<LLMProvider.Message> llmMsgs = convertToLLMMessages(workingMessages);
        CompletableFuture<LLMResponse> future;
        if (onDelta != null && provider.supportsStreaming()) {
            future = provider.chatStream(llmMsgs, Collections.emptyList(), onDelta);
        } else {
            future = provider.chat(llmMsgs, Collections.emptyList());
        }

        return future.thenCompose(response -> {
            context.addUsage(response.getPromptTokens(), response.getCompletionTokens());
            String content = response.getContent();
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

    // ==================== 工具执行 ====================

    /**
     * 执行工具调用 — 只读工具并行，写工具保持顺序.
     *
     * <h3>Hook 嵌入点</h3>
     * <ol>
     *   <li><b>PRE_TOOL_USE</b>（可拦截）：每个工具执行前同步检查.
     *       reject Hook 匹配 → 跳过该工具，结果设为 "[HOOK BLOCKED]".</li>
     *   <li><b>POST_TOOL_USE</b>：每个工具执行后触发（含耗时统计）.</li>
     * </ol>
     * <p>
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

        String sessionId = context.getSessionKey();

        // 提交所有工具到线程池并行执行，结果收集到有序数组
        String[] results = new String[toolCalls.size()];
        CompletableFuture<?>[] futures = new CompletableFuture<?>[toolCalls.size()];

        for (int i = 0; i < toolCalls.size(); i++) {
            final int idx = i;
            LLMResponse.ToolCallRequest call = toolCalls.get(i);
            String toolName = call.getName();
            Map<String, Object> toolArgs = call.getArguments();

            // ── Hook: PRE_TOOL_USE（可拦截）──
            if (hookManager != null) {
                HookContext preCtx = HookContext.tool(HookEvent.PRE_TOOL_USE, toolName, toolArgs, sessionId);
                boolean blocked = hookManager.runPreToolHooks(preCtx);
                if (blocked) {
                    results[idx] = "[HOOK BLOCKED]";
                    continue;
                }
            }

            // ── 通知各通道：即将调用工具 ──
            if (messageBus != null) {
                try {
                    messageBus.publishToOutboundQueue(com.nanobot.bus.OutboundMessage.builder()
                            .sessionId(sessionId).channel("system")
                            .content("🔧 " + toolName)
                            .metadata(java.util.Map.of("_tool_call", true))
                            .build());
                } catch (Exception ignored) {
                }
            }

            futures[i] = CompletableFuture.runAsync(() -> {
                long toolStart = System.currentTimeMillis();
                Object result = executeToolWithRetry(toolName, toolArgs, call.getId());
                long duration = System.currentTimeMillis() - toolStart;

                String resultStr = result != null ? result.toString() : "";
                if (resultStr.length() > maxToolResultChars) {
                    resultStr = resultStr.substring(0, maxToolResultChars)
                            + "\n\n[结果已截断，超出最大长度限制]";
                }
                results[idx] = resultStr; // 按索引写入，保证顺序

                // ── Hook: POST_TOOL_USE + 工具耗时统计 ──
                if (hookManager != null) {
                    hookManager.recordToolTiming(toolName, duration);
                    hookManager.runHooks(HookContext.toolResult(
                            HookEvent.POST_TOOL_USE, toolName, toolArgs, sessionId, resultStr));
                }
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
            // ── Hook: ON_ERROR ──
            if (hookManager != null) {
                hookManager.runHooks(HookContext.error(sessionId, error.getMessage()));
            }
            return null;
        });
    }

    /**
     * 执行工具调用（带重试机制）
     *
     * @param toolName 工具名称
     * @param params   工具参数
     * @param callId   工具调用ID
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
     * <p>
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
     * 转换单条消息 — 将内部 Map 表示转为 LLMProvider.Message。
     *
     * <h3>三种消息类型及转换结果</h3>
     *
     * <b>① 带工具调用的 assistant 消息：</b>
     * <pre>{@code
     * // 输入 Map:
     * {
     *   "role": "assistant",
     *   "content": "",
     *   "tool_calls": [
     *     {
     *       "id": "call_abc123",
     *       "type": "function",
     *       "function": {
     *         "name": "get_weather",
     *         "arguments": "{\"city\":\"北京\"}"
     *       }
     *     }
     *   ]
     * }
     *
     * // 输出 LLMProvider.Message:
     * LLMProvider.Message{
     *   role: "assistant",
     *   content: "",
     *   toolCalls: [
     *     ToolCallInfo{id: "call_abc123", name: "get_weather", args: {city: "北京"}}
     *   ]
     * }
     * }</pre>
     *
     * <b>② 工具结果消息：</b>
     * <pre>{@code
     * // 输入 Map:
     * {
     *   "role": "tool",
     *   "tool_call_id": "call_abc123",
     *   "content": "北京今天晴，25°C，湿度40%"
     * }
     *
     * // 输出 LLMProvider.Message:
     * LLMProvider.Message{
     *   role: "tool",
     *   content: "北京今天晴，25°C，湿度40%",
     *   toolCallId: "call_abc123"
     * }
     * }</pre>
     *
     * <b>③ 普通消息（system/user/纯文本assistant）：</b>
     * <pre>{@code
     * // 输入 Map:                    // 输出 LLMProvider.Message:
     * {"role": "system",             LLMProvider.Message{
     *  "content": "你是一个助手"}       role: "system",
     *                                  content: "你是一个助手"}
     *
     * {"role": "user",               LLMProvider.Message{
     *  "content": "北京天气怎么样？"}    role: "user",
     *                                  content: "北京天气怎么样？"}
     *
     * {"role": "assistant",          LLMProvider.Message{
     *  "content": "北京今天晴朗。"}      role: "assistant",
     *                                  content: "北京今天晴朗。"}
     * }</pre>
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
                                args = objectMapper.readValue((String) argsObj,
                                        new TypeReference<Map<String, Object>>() {
                                });
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
