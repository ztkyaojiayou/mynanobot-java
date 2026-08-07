package com.nanocode.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Hook 管理器 — ECA 声明式钩子系统的核心.
 *
 * <h1>全生命周期：从定义到生效</h1>
 *
 * <h2>阶段一：定义（YAML）</h2>
 * <pre>
 * hooks:
 *   enabled: true
 *   list:
 *     - id: "block-rm"
 *       event: PRE_TOOL_USE
 *       condition: "tool==bash"
 *       action: { type: COMMAND, command: "echo blocked" }
 *       reject: true
 * </pre>
 *
 * <h2>阶段二：加载注册（Spring 启动时）</h2>
 * <ol>
 *   <li>Spring 启动 → ConfigLoader 读 config.yaml → Jackson 反序列化为 Config</li>
 *   <li>{@link com.nanocode.v2.NanobotConfig#hookManager} 创建 @Bean：
 *       {@code HookLoader.load(config.getHooks())} → {@code List<Hook>} →
 *       {@code hookManager.load(list)}，存入 {@link #hooks}</li>
 *   <li>日志输出每个加载的 Hook：{@code "Loaded hook: block-rm [PRE_TOOL_USE: tool==bash]"}</li>
 * </ol>
 *
 * <h2>阶段三：事件触发（Agent 运行时）</h2>
 * AgentLoop/AgentRunner/RunState/NanobotRunner 在关键生命周期位置调用：
 * <ul>
 *   <li>{@code hookManager.runHooks(HookContext.of(SESSION_START, null))}</li>
 *   <li>{@code hookManager.runTurnStartHooks(HookContext.message(TURN_START, sid, msg))}</li>
 *   <li>{@code hookManager.runPreToolHooks(HookContext.tool(PRE_TOOL_USE, "bash", args, sid))}</li>
 *   <li>{@code hookManager.runHooks(HookContext.of(TURN_END, sid))}</li>
 * </ul>
 *
 * <h2>阶段四：匹配执行（HookManager 内部）</h2>
 * <ol>
 *   <li><b>遍历</b>：for (Hook hook : hooks) — O(n)，n 通常 < 20，性能足够</li>
 *   <li><b>事件匹配</b>：{@code if (hook.event() != ctx.event()) continue;}</li>
 *   <li><b>条件匹配</b>：
 *       {@code if (!hook.condition().isEmpty() && !evaluateCondition(hook.condition(), ctx)) continue;}
 *       <ul>
 *         <li>DSL 解析：{@code tool==bash}, {@code tool=~mcp__.*}, {@code args.key==val}</li>
 *       </ul>
 *   </li>
 *   <li><b>执行动作</b>：{@code executeAction(hook, ctx)} →
 *       <ul>
 *         <li>COMMAND → {@code Runtime.exec("bash -c " + command)}，10s 超时</li>
 *         <li>PROMPT → 直接返回 message 文本</li>
 *         <li>SCRIPT → {@code Runtime.exec(scriptPath)}，10s 超时</li>
 *       </ul>
 *   </li>
 *   <li><b>更新统计</b>：totalRuns++, 若 reject 再 totalRejects++</li>
 *   <li><b>返回结果</b>：{@code List<HookResult>}，每个匹配的 Hook 一个结果</li>
 * </ol>
 *
 * <h2>关键设计决策</h2>
 * <ul>
 *   <li><b>同步执行</b>：在当前线程同步执行，不做异步调度.
 *       PRE_TOOL_USE 的 reject 需要同步返回结果，引入线程池会增加复杂度.</li>
 *   <li><b>CopyOnWriteArrayList</b>：读远多于写的场景（运行时只读，启动时写入），
 *       适合 COW 结构.</li>
 *   <li><b>COMMAND 超时 10s</b>：{@code process.waitFor(10, SECONDS)}，
 *       超时则 force destroy，防止 Hook 脚本卡住 Agent 主流程.</li>
 *   <li><b>PROMPT 零开销</b>：不执行外部进程，只返回 message 文本，
 *       适合 ON_STREAM 等高频事件.</li>
 *   <li><b>空列表=零开销</b>：无配置时 hooks 为空，runHooks 循环一次都不执行.</li>
 * </ul>
 *
 * <h2>与旧 Hook 系统的区别</h2>
 * 旧：AgentHook 接口（7 个方法）+ CompositeHook + 3 个实现类 — 方法名=事件，条件/动作焊死.
 * 新：Hook record + HookManager — 声明式，YAML 可配，1 个引擎替代 7 个类.
 *
 * @see Hook
 * @see HookEvent
 * @see HookContext
 * @see com.nanocode.v2.NanobotConfig#hookManager
 */
public class HookManager {

    private static final Logger logger = LoggerFactory.getLogger(HookManager.class);

    // ═══════════════════════════════════════════════════════════════════
    // Hook 注册表
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 已注册的 Hook 列表.
     * 使用 CopyOnWriteArrayList：运行时只读（遍历匹配），启动时写入（加载配置），
     * 免去读锁开销。遍历 O(n)，n 通常 < 20.
     */
    private final List<Hook> hooks = new java.util.concurrent.CopyOnWriteArrayList<>();

    // ═══════════════════════════════════════════════════════════════════
    // 内置统计（替代旧 MetricsHook，供 /stats 查询）
    // ═══════════════════════════════════════════════════════════════════

    /** 各事件累计触发次数 */
    private final ConcurrentHashMap<HookEvent, AtomicInteger> totalRuns = new ConcurrentHashMap<>();

    /** 累计拦截次数（reject=true 且条件匹配的次数） */
    private final AtomicInteger totalRejects = new AtomicInteger(0);

    /** 工具耗时统计（按工具名） */
    private final ConcurrentHashMap<String, ToolTiming> toolTimings = new ConcurrentHashMap<>();

    /** 最近 20 条执行结果（环形缓冲） */
    private final ConcurrentLinkedQueue<HookResult> recentResults = new ConcurrentLinkedQueue<>();

    private static final int MAX_RECENT_RESULTS = 20;

    // ═══════════════════════════════════════════════════════════════════
    // 生命周期：加载注册
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 加载 Hook 列表（由 {@link HookLoader} 在启动时调用）.
     *
     * <h3>职责分离</h3>
     * <b>HookLoader</b> 负责"从哪加载"（config.yaml / BuiltinHooks）→ 产出 List<Hook>.
     * <b>HookManager</b> 负责"何时执行"（事件触发、匹配、动作）.
     *
     * <h3>调用链</h3>
     * <pre>
     *   NanobotConfig.hookManager(config)
     *     → HookLoader.load(config.getHooks())
     *       → List<Hook>
     *     → hookManager.load(list)
     * </pre>
     */
    public void load(List<Hook> hookList) {
        hooks.clear();
        if (hookList == null || hookList.isEmpty()) {
            logger.info("HookManager: no hooks loaded (empty list)");
            return;
        }
        for (Hook hook : hookList) {
            addHook(hook);
        }
        logger.info("HookManager loaded {} hook(s)", hooks.size());
    }

    /**
     * 运行时注册一个 Hook（可编程式）.
     * 也用于从配置批量加载时的逐条注册.
     */
    public void addHook(Hook hook) {
        hooks.add(hook);
        logger.info("  Hook [{}] registered: event={}, condition=\"{}\", action={}, reject={}",
                hook.id(), hook.event(), hook.condition(), hook.action().type(), hook.reject());
    }

    // ═══════════════════════════════════════════════════════════════════
    // 核心执行：匹配 + 执行
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 遍历所有 Hook，匹配事件+条件，执行动作.
     *
     * <h3>执行流程</h3>
     * <pre>
     *   for (Hook hook : hooks) {
     *       if hook.event != ctx.event → skip
     *       if !evaluateCondition(hook.condition, ctx) → skip
     *       result = executeAction(hook, ctx)
     *       updateStats(hook, result)
     *       results.add(result)
     *   }
     *   return results
     * </pre>
     *
     * @param ctx 事件上下文
     * @return 所有匹配+执行的 HookResult 列表（可能为空）
     */
    public List<HookResult> runHooks(HookContext ctx) {
        List<HookResult> results = new ArrayList<>();
        for (Hook hook : hooks) {
            // ── 1. 事件匹配 ──
            if (hook.event() != ctx.event()) continue;

            // ── 2. 条件匹配 ──
            if (!hook.condition().isEmpty()
                    && !evaluateCondition(hook.condition(), ctx)) {
                continue;
            }

            // ── 3. 执行动作 ──
            HookResult result = executeAction(hook, ctx);
            results.add(result);

            // ── 4. 更新统计 ──
            updateStats(hook, result);
        }
        return results;
    }

    /**
     * PRE_TOOL_USE 专用执行 — 返回 true 表示该工具调用被拦截.
     *
     * 仅执行 reject=true 且条件匹配的 Hook（拦截型），非拦截型 Hook 仍通过
     * {@link #runHooks(HookContext)} 在 POST_TOOL_USE 时执行.
     *
     * @return true = 工具调用被拦截，应跳过执行
     */
    public boolean runPreToolHooks(HookContext ctx) {
        for (Hook hook : hooks) {
            if (hook.event() != HookEvent.PRE_TOOL_USE) continue;
            if (!hook.reject()) continue; // 非拦截型跳过

            if (!hook.condition().isEmpty()
                    && !evaluateCondition(hook.condition(), ctx)) {
                continue;
            }

            HookResult result = executeAction(hook, ctx);
            updateStats(hook, result);
            recentResults.offer(result);
            trimRecentResults();
            return true; // 被拦截！
        }
        return false; // 放行
    }

    /**
     * TURN_START 专用执行 — 返回 true 表示该轮对话被拦截.
     *
     * 仅检查 reject=true 的 Hook。非拦截型 Hook（如注入上下文）
     * 仍通过 {@link #runHooks(HookContext)} 执行，其 PROMPT 结果追加到消息上下文.
     *
     * @return true = 该消息被拦截，不应进入状态机处理
     */
    public boolean runTurnStartHooks(HookContext ctx) {
        for (Hook hook : hooks) {
            if (hook.event() != HookEvent.TURN_START) continue;
            if (!hook.reject()) continue;

            if (!hook.condition().isEmpty()
                    && !evaluateCondition(hook.condition(), ctx)) {
                continue;
            }

            HookResult result = executeAction(hook, ctx);
            updateStats(hook, result);
            recentResults.offer(result);
            trimRecentResults();
            return true;
        }
        return false;
    }

    /**
     * 收集 TURN_START 时所有匹配的非拦截 PROMPT 钩子的上下文文本.
     * 返回拼接后的文本，用于注入到 LLM 系统消息中.
     */
    public String collectPrompts(HookContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (Hook hook : hooks) {
            if (hook.event() != ctx.event()) continue;
            if (hook.reject()) continue; // 拦截型不在这里收集
            if (hook.action().type() != ActionType.PROMPT) continue;
            if (hook.action().message() == null || hook.action().message().isBlank()) continue;

            if (!hook.condition().isEmpty()
                    && !evaluateCondition(hook.condition(), ctx)) {
                continue;
            }

            sb.append(hook.action().message()).append("\n");
            updateStats(hook, HookResult.ok(hook.id(), hook.action().message()));
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 条件 DSL 解析
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 解析条件 DSL 表达式，判断上下文是否匹配.
     *
     * <h3>支持的语法</h3>
     * <table>
     *   <tr><th>语法</th><th>含义</th><th>示例</th></tr>
     *   <tr><td>{@code var==val}</td><td>精确相等</td><td>{@code tool==bash}</td></tr>
     *   <tr><td>{@code var=~regex}</td><td>正则匹配</td><td>{@code tool=~mcp__.*}</td></tr>
     *   <tr><td>{@code cond1 && cond2}</td><td>AND 组合</td><td>{@code tool==bash && args.cmd=~rm.*}</td></tr>
     * </table>
     *
     * <h3>可用的变量名</h3>
     * <ul>
     *   <li>{@code tool} — 工具名（来自 {@link HookContext#toolName()}）</li>
     *   <li>{@code event} — 事件名（来自 {@link HookContext#event()}）</li>
     *   <li>{@code args.xxx} — 工具参数值（来自 {@link HookContext#toolArgs()}）</li>
     * </ul>
     *
     * @param condition 条件表达式，空字符串直接返回 true
     * @param ctx 运行时上下文
     * @return true 表示条件匹配
     */
    boolean evaluateCondition(String condition, HookContext ctx) {
        if (condition == null || condition.isBlank()) return true;

        // ── 处理 AND 组合：先拆分为子条件，逐个求值 ──
        if (condition.contains("&&")) {
            for (String part : condition.split("&&")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !evaluateSingle(trimmed, ctx)) {
                    return false; // AND 短路
                }
            }
            return true;
        }

        return evaluateSingle(condition.trim(), ctx);
    }

    /** 求值单个条件（不含 &&） */
    private boolean evaluateSingle(String cond, HookContext ctx) {
        // ── 正则匹配：var=~pattern ──
        if (cond.contains("=~")) {
            String[] parts = cond.split("=~", 2);
            String varName = parts[0].trim();
            String pattern = stripQuotes(parts[1].trim());
            String varValue = resolveVar(varName, ctx);
            try {
                return Pattern.matches(pattern, varValue);
            } catch (PatternSyntaxException e) {
                logger.warn("Invalid regex pattern in hook condition: {}", pattern);
                return false;
            }
        }

        // ── 精确相等：var==val ──
        if (cond.contains("==")) {
            String[] parts = cond.split("==", 2);
            String varName = parts[0].trim();
            String expected = stripQuotes(parts[1].trim());
            String varValue = resolveVar(varName, ctx);
            return varValue.equals(expected);
        }

        // ── 未知运算符 → 宽松匹配（视为 truthy）──
        return true;
    }

    /** 解析变量名为实际值 */
    private String resolveVar(String name, HookContext ctx) {
        return switch (name) {
            case "tool"  -> ctx.toolName() != null ? ctx.toolName() : "";
            case "event" -> ctx.event() != null ? ctx.event().name() : "";
            default -> {
                // args.xxx → 从工具参数中取值
                if (name.startsWith("args.") && ctx.toolArgs() != null) {
                    String key = name.substring("args.".length());
                    Object v = ctx.toolArgs().get(key);
                    yield v != null ? String.valueOf(v) : "";
                }
                yield "";
            }
        };
    }

    /** 去除首尾引号（单引号/双引号/斜杠） */
    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '/' && last == '/')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 动作执行
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 根据 Hook 的 ActionType 执行对应动作.
     *
     * <h3>COMMAND</h3>
     * {@code bash -c "command"}，通过 ProcessBuilder 同步执行，10s 超时.
     * 环境变量注入：NANOBOT_EVENT, NANOBOT_TOOL, NANOBOT_SESSION.
     *
     * <h3>PROMPT</h3>
     * 不执行外部进程，直接返回 message 文本. 调用方（AgentLoop）
     * 负责将其注入到 LLM 上下文.
     *
     * <h3>SCRIPT</h3>
     * 执行 scriptPath 指定的脚本文件，通过 ProcessBuilder，10s 超时.
     * 环境变量同 COMMAND.
     */
    private HookResult executeAction(Hook hook, HookContext ctx) {
        return switch (hook.action().type()) {
            case COMMAND -> executeCommand(hook, ctx);
            case PROMPT  -> new HookResult(hook.id(), hook.action().message(), true, hook.reject());
            case SCRIPT  -> executeScript(hook, ctx);
        };
    }

    /** 执行 shell 命令 */
    private HookResult executeCommand(Hook hook, HookContext ctx) {
        String cmd = hook.action().command();
        if (cmd == null || cmd.isBlank()) {
            return new HookResult(hook.id(), "(empty command)", true, hook.reject());
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.environment().put("NANOBOT_EVENT", ctx.event() != null ? ctx.event().name() : "");
            pb.environment().put("NANOBOT_TOOL", ctx.toolName() != null ? ctx.toolName() : "");
            pb.environment().put("NANOBOT_SESSION", ctx.sessionId() != null ? ctx.sessionId() : "");
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            boolean finished = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return new HookResult(hook.id(), "Hook timed out after 10s", false, hook.reject());
            }

            String stdout = new String(proc.getInputStream().readAllBytes()).strip();
            return new HookResult(hook.id(), stdout, proc.exitValue() == 0, hook.reject());
        } catch (Exception e) {
            logger.warn("Hook [{}] command failed: {}", hook.id(), e.getMessage());
            return new HookResult(hook.id(), "Error: " + e.getMessage(), false, hook.reject());
        }
    }

    /** 执行脚本文件 */
    private HookResult executeScript(Hook hook, HookContext ctx) {
        String scriptPath = hook.action().scriptPath();
        if (scriptPath == null || scriptPath.isBlank()) {
            return new HookResult(hook.id(), "(empty script path)", true, hook.reject());
        }
        // 展开环境变量引用
        String expanded = scriptPath
                .replace("${NANOBOT_DIR}", System.getProperty("nanobot.dir", ".nanobot"));
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", expanded);
            pb.environment().put("NANOBOT_EVENT", ctx.event() != null ? ctx.event().name() : "");
            pb.environment().put("NANOBOT_TOOL", ctx.toolName() != null ? ctx.toolName() : "");
            pb.environment().put("NANOBOT_SESSION", ctx.sessionId() != null ? ctx.sessionId() : "");
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            boolean finished = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return new HookResult(hook.id(), "Script timed out after 10s", false, hook.reject());
            }

            String stdout = new String(proc.getInputStream().readAllBytes()).strip();
            return new HookResult(hook.id(), stdout, proc.exitValue() == 0, hook.reject());
        } catch (Exception e) {
            logger.warn("Hook [{}] script failed: {}", hook.id(), e.getMessage());
            return new HookResult(hook.id(), "Error: " + e.getMessage(), false, hook.reject());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 内置统计
    // ═══════════════════════════════════════════════════════════════════

    private void updateStats(Hook hook, HookResult result) {
        totalRuns.computeIfAbsent(hook.event(), k -> new AtomicInteger(0)).incrementAndGet();
        if (result.reject()) {
            totalRejects.incrementAndGet();
        }
        recentResults.offer(result);
        trimRecentResults();
    }

    /** 记录工具耗时（由 POST_TOOL_USE 的调用方传入耗时） */
    public void recordToolTiming(String toolName, long durationMs) {
        toolTimings.compute(toolName, (k, v) -> {
            if (v == null) v = new ToolTiming(toolName);
            v.addCall(durationMs);
            return v;
        });
    }

    private void trimRecentResults() {
        while (recentResults.size() > MAX_RECENT_RESULTS) {
            recentResults.poll();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 统计查询（供 /stats 和 HealthController 使用）
    // ═══════════════════════════════════════════════════════════════════

    public int getHookCount() { return hooks.size(); }

    public Map<HookEvent, Integer> getRunCounts() {
        Map<HookEvent, Integer> counts = new LinkedHashMap<>();
        totalRuns.forEach((e, c) -> counts.put(e, c.get()));
        return counts;
    }

    public int getRejectCount() { return totalRejects.get(); }

    public Map<String, ToolTiming> getToolTimings() {
        return new LinkedHashMap<>(toolTimings);
    }

    public List<HookResult> getRecentResults() {
        return List.copyOf(recentResults);
    }

    /** 获取所有已注册的 Hook（只读） */
    public List<Hook> getHooks() {
        return List.copyOf(hooks);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 内部类：工具耗时统计
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 单个工具的耗时统计.
     * 线程安全 — 所有方法 synchronized.
     */
    public static class ToolTiming {
        private final String toolName;
        private int calls;
        private long totalMs;
        private long maxMs;

        ToolTiming(String toolName) { this.toolName = toolName; }

        synchronized void addCall(long ms) {
            calls++;
            totalMs += ms;
            if (ms > maxMs) maxMs = ms;
        }

        public String toolName() { return toolName; }
        public synchronized int calls() { return calls; }
        public synchronized long totalMs() { return totalMs; }
        public synchronized long avgMs() { return calls > 0 ? totalMs / calls : 0; }
        public synchronized long maxMs() { return maxMs; }

        @Override
        public synchronized String toString() {
            return String.format("%s: %d calls, avg %dms, max %dms", toolName, calls, avgMs(), maxMs);
        }
    }
}
