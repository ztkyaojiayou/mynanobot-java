package com.nanobot.subagent;

import com.nanobot.core.TurnContext;
import com.nanobot.subagent.impl.SimpleSubagent;
import com.nanobot.providers.LLMProvider;
import com.nanobot.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * AgentCoordinator - Agent 协调器
 * ==============================
 * <p>
 * 负责管理多个子 Agent，提供：
 * - 任务分配与调度
 * - 并行执行管理
 * - 结果汇总与整合
 * - Agent 生命周期管理
 * <p>
 * **协调策略**：
 * <p>
 * 1. **直接分配**：根据任务类型直接分配给特定 Agent
 * 2. **能力匹配**：根据 Agent 能力自动选择最合适的 Agent
 * 3. **并行执行**：将任务分解为多个子任务并行执行
 * 4. **结果汇总**：汇总多个 Agent 的执行结果
 * <p>
 * **使用示例**：
 * <p>
 * ```java
 * // 创建协调器
 * AgentCoordinator coordinator = new AgentCoordinator(provider, toolRegistry);
 * <p>
 * // 注册子 Agent
 * coordinator.registerSubagent("search", "搜索助手",
 * Map.of("web_search", true, "summarization", false));
 * coordinator.registerSubagent("writer", "写作助手",
 * Map.of("web_search", false, "summarization", true));
 * <p>
 * // 启动所有 Agent
 * coordinator.startAll();
 * <p>
 * // 分配任务
 * String result = coordinator.assignTask(
 * "写一篇关于人工智能的文章",
 * TurnContext.from(message)
 * ).join();
 * ```
 */
public class AgentCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(AgentCoordinator.class);

    /**
     * 子 Agent 注册表
     */
    private final ConcurrentHashMap<String, Subagent> subagents = new ConcurrentHashMap<>();

    /**
     * LLM 提供商
     */
    private final LLMProvider provider;

    /**
     * 工具注册表
     */
    private final ToolRegistry toolRegistry;

    /**
     * 是否正在运行
     */
    private volatile boolean running = false;

    /**
     * 任务分配策略
     */
    private TaskAssignmentStrategy assignmentStrategy = TaskAssignmentStrategy.CAPABILITY_MATCH;

    /**
     * 结果汇总器
     */
    private ResultAggregator resultAggregator = new DefaultResultAggregator();

    // ==================== 构造函数 ====================

    public AgentCoordinator(LLMProvider provider, ToolRegistry toolRegistry) {
        this.provider = provider;
        this.toolRegistry = toolRegistry;
    }

    // ==================== Agent 管理 ====================

    /**
     * 注册子 Agent
     */
    public Subagent registerSubagent(String id, String name, Map<String, Boolean> capabilities) {
        if (subagents.containsKey(id)) {
            logger.warn("Subagent {} already exists, replacing", id);
        }

        SimpleSubagent subagent = new SimpleSubagent(id, name, provider, toolRegistry);

        // 设置能力
        if (capabilities != null) {
            capabilities.forEach(subagent::setCapability);
        }

        subagents.put(id, subagent);
        logger.info("Registered subagent: {} ({})", id, name);

        return subagent;
    }

    /**
     * 注册子 Agent（自定义实现）
     */
    public void registerSubagent(Subagent subagent) {
        if (subagents.containsKey(subagent.getId())) {
            logger.warn("Subagent {} already exists, replacing", subagent.getId());
        }

        subagents.put(subagent.getId(), subagent);
        logger.info("Registered subagent: {}", subagent.getId());
    }

    /**
     * 获取子 Agent
     */
    public Optional<Subagent> getSubagent(String id) {
        return Optional.ofNullable(subagents.get(id));
    }

    /**
     * 移除子 Agent
     */
    public Subagent removeSubagent(String id) {
        Subagent removed = subagents.remove(id);
        if (removed != null) {
            removed.stop();
            logger.info("Removed subagent: {}", id);
        }
        return removed;
    }

    /**
     * 获取所有子 Agent
     */
    public Collection<Subagent> getAllSubagents() {
        return Collections.unmodifiableCollection(subagents.values());
    }

    /**
     * 获取子 Agent 数量
     */
    public int getSubagentCount() {
        return subagents.size();
    }

    // ==================== 生命周期管理 ====================

    /**
     * 启动所有子 Agent
     */
    public void startAll() {
        if (running) {
            return;
        }

        subagents.values().forEach(Subagent::start);
        running = true;
        logger.info("Started {} subagents", subagents.size());
    }

    /**
     * 停止所有子 Agent
     */
    public void stopAll() {
        if (!running) {
            return;
        }

        subagents.values().forEach(Subagent::stop);
        running = false;
        logger.info("Stopped {} subagents", subagents.size());
    }

    /**
     * 重启所有子 Agent
     */
    public void restartAll() {
        stopAll();
        startAll();
    }

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return running;
    }

    // ==================== 任务分配 ====================

    /**
     * 分配任务给合适的 Agent
     */
    public CompletableFuture<String> assignTask(String task, TurnContext context) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Assigning task: {}", task);

            // 根据策略选择 Agent
            List<Subagent> selectedAgents = selectAgents(task, context);

            if (selectedAgents.isEmpty()) {
                logger.warn("No suitable agent found for task: {}", task);
                throw new RuntimeException("No suitable agent available");
            }

            // 执行任务
            List<CompletableFuture<String>> futures = new ArrayList<>();

            for (Subagent agent : selectedAgents) {
                futures.add(agent.execute(task, context != null ?
                        context.getMessage() != null ? context.getMessage().getMetadata() : null : null));
            }

            // 等待所有任务完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 收集结果
            List<String> results = new ArrayList<>();
            for (CompletableFuture<String> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    results.add("Error: " + e.getMessage());
                }
            }

            // 汇总结果
            return resultAggregator.aggregate(task, selectedAgents, results);
        });
    }

    /**
     * 并行执行多个任务
     */
    public CompletableFuture<List<String>> executeParallel(List<String> tasks, TurnContext context) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Executing {} tasks in parallel", tasks.size());

            List<CompletableFuture<String>> futures = new ArrayList<>();

            for (String task : tasks) {
                futures.add(assignTask(task, context));
            }

            // 等待所有任务完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 收集结果
            List<String> results = new ArrayList<>();
            for (CompletableFuture<String> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    results.add("Error: " + e.getMessage());
                }
            }

            return results;
        });
    }

    /**
     * 顺序执行任务
     */
    public CompletableFuture<String> executeSequential(List<String> tasks, TurnContext context) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Executing {} tasks sequentially", tasks.size());

            String previousResult = null;

            for (int i = 0; i < tasks.size(); i++) {
                String task = tasks.get(i);

                // 如果不是第一个任务，将前一个结果作为上下文传递
                if (previousResult != null) {
                    task = "根据以下信息：\n" + previousResult + "\n\n" + task;
                }

                CompletableFuture<String> future = assignTask(task, context);

                try {
                    previousResult = future.get();
                } catch (Exception e) {
                    previousResult = "Error at step " + (i + 1) + ": " + e.getMessage();
                    break;
                }
            }

            return previousResult;
        });
    }

    /**
     * 选择合适的 Agent
     */
    private List<Subagent> selectAgents(String task, TurnContext context) {
        switch (assignmentStrategy) {
            case DIRECT:
                return selectByDirectAssignment(task);
            case CAPABILITY_MATCH:
                return selectByCapability(task);
            case ROUND_ROBIN:
                return selectByRoundRobin();
            case PARALLEL:
                return new ArrayList<>(subagents.values());
            default:
                return selectByCapability(task);
        }
    }

    /**
     * 根据直接分配选择 Agent
     */
    private List<Subagent> selectByDirectAssignment(String task) {
        // 简单实现：根据关键词匹配
        if (task.contains("搜索") || task.contains("查找") || task.contains("查询")) {
            return subagents.values().stream()
                    .filter(a -> a.hasCapability("web_search"))
                    .limit(1)
                    .collect(Collectors.toList());
        }

        if (task.contains("写") || task.contains("文章") || task.contains("总结")) {
            return subagents.values().stream()
                    .filter(a -> a.hasCapability("summarization"))
                    .limit(1)
                    .collect(Collectors.toList());
        }

        // 默认返回第一个可用的 Agent
        return subagents.values().stream().limit(1).collect(Collectors.toList());
    }

    /**
     * 根据能力匹配选择 Agent
     */
    private List<Subagent> selectByCapability(String task) {
        List<Subagent> candidates = new ArrayList<>();

        for (Subagent agent : subagents.values()) {
            if (matchesCapability(agent, task)) {
                candidates.add(agent);
            }
        }

        if (candidates.isEmpty()) {
            // 如果没有匹配的，返回所有 Agent
            candidates.addAll(subagents.values());
        }

        return candidates.stream().limit(3).collect(Collectors.toList());
    }

    /**
     * 检查 Agent 是否匹配任务
     */
    private boolean matchesCapability(Subagent agent, String task) {
        Map<String, Boolean> capabilities = agent.getCapabilities();

        // 检查关键词与能力的匹配
        if (capabilities.getOrDefault("web_search", false) &&
                (task.contains("搜索") || task.contains("查找") || task.contains("查询"))) {
            return true;
        }

        if (capabilities.getOrDefault("summarization", false) &&
                (task.contains("总结") || task.contains("摘要") || task.contains("文章"))) {
            return true;
        }

        if (capabilities.getOrDefault("calculation", false) &&
                (task.contains("计算") || task.contains("数学") || task.contains("公式"))) {
            return true;
        }

        if (capabilities.getOrDefault("code", false) &&
                (task.contains("代码") || task.contains("编程") || task.contains("开发"))) {
            return true;
        }

        return false;
    }

    /**
     * 轮询选择 Agent
     */
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    private List<Subagent> selectByRoundRobin() {
        List<Subagent> list = new ArrayList<>(subagents.values());
        if (list.isEmpty()) {
            return list;
        }

        int index = roundRobinIndex.getAndIncrement() % list.size();
        return Collections.singletonList(list.get(index));
    }

    // ==================== 配置 ====================

    /**
     * 设置任务分配策略
     */
    public void setAssignmentStrategy(TaskAssignmentStrategy strategy) {
        this.assignmentStrategy = strategy;
        logger.info("Set assignment strategy to: {}", strategy);
    }

    /**
     * 设置结果汇总器
     */
    public void setResultAggregator(ResultAggregator aggregator) {
        this.resultAggregator = aggregator;
    }

    // ==================== 统计 ====================

    /**
     * 获取所有 Agent 的统计信息
     */
    public Map<String, Subagent.SubagentStats> getAllStats() {
        Map<String, Subagent.SubagentStats> stats = new LinkedHashMap<>();
        subagents.forEach((id, agent) -> stats.put(id, agent.getStats()));
        return stats;
    }

    /**
     * 重置所有统计信息
     */
    public void resetAllStats() {
        subagents.values().forEach(Subagent::resetStats);
    }

    // ==================== 任务分配策略枚举 ====================

    public enum TaskAssignmentStrategy {
        /**
         * 直接分配（基于关键词）
         */
        DIRECT,
        /**
         * 能力匹配
         */
        CAPABILITY_MATCH,
        /**
         * 轮询
         */
        ROUND_ROBIN,
        /**
         * 并行执行（所有 Agent）
         */
        PARALLEL
    }

    // ==================== 结果汇总器接口 ====================

    public interface ResultAggregator {
        /**
         * 汇总多个 Agent 的结果
         *
         * @param task    原始任务
         * @param agents  参与的 Agent
         * @param results 各 Agent 的结果
         * @return 汇总后的结果
         */
        String aggregate(String task, List<Subagent> agents, List<String> results);
    }

    /**
     * 默认结果汇总器
     */
    public static class DefaultResultAggregator implements ResultAggregator {
        @Override
        public String aggregate(String task, List<Subagent> agents, List<String> results) {
            if (results.isEmpty()) {
                return "没有获取到结果。";
            }

            if (results.size() == 1) {
                return results.get(0);
            }

            // 汇总多个结果
            StringBuilder sb = new StringBuilder();
            sb.append("根据多个助手的分析，以下是关于 \"").append(task).append("\" 的综合结果：\n\n");

            for (int i = 0; i < results.size(); i++) {
                Subagent agent = agents.get(i);
                String result = results.get(i);

                sb.append("【").append(agent.getName()).append("】\n");
                sb.append(result).append("\n\n");
            }

            // 添加总结
            sb.append("---\n");
            sb.append("以上是各助手的分析结果，请参考。");

            return sb.toString();
        }
    }

    @Override
    public String toString() {
        return "AgentCoordinator{" +
                "subagents=" + subagents.size() +
                ", strategy=" + assignmentStrategy +
                ", running=" + running +
                '}';
    }
}