package com.nanobot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.nanobot.security.PermissionManager;
import com.nanobot.security.PermissionResult;
import com.nanobot.security.guard.CommandGuard;
import com.nanobot.security.guard.NetworkGuard;
import com.nanobot.security.guard.PathGuard;
import com.nanobot.security.guard.SecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 工具注册中心 - 工具的管理和执行中心
 * =====================================
 * 
 * ToolRegistry 是工具系统的核心组件，负责：
 * 1. 工具的注册和注销
 * 2. 工具的查找和获取
 * 3. 工具的执行和参数验证
 * 4. 工具定义的导出（用于 LLM）
 * 
 * **设计思想**：
 * 
 * 1. **注册表模式**：
 *    - 使用 Map 存储工具实例
 *    - 工具名称作为唯一键
 *    - 支持动态注册和注销
 * 
 * 2. **工厂模式**：
 *    - 提供工具的创建和获取接口
 *    - 封装工具执行的复杂性
 *    - 支持工具执行的前后处理
 * 
 * 3. **并发安全**：
 *    - 使用 ConcurrentHashMap 存储工具
 *    - 支持多线程环境下的工具管理
 *    - 提供并发执行工具的能力
 * 
 * 4. **错误处理**：
 *    - 参数验证
 *    - 执行异常捕获
 *    - 详细的错误信息返回
 * 
 * **消息流转**：
 * 
 * ```
 * ┌─────────────┐     ┌──────────────┐     ┌─────────────┐
 * │   LLM       │────▶│ ToolRegistry │────▶│    Tool     │
 * │ 决定调用工具 │     │  参数验证    │     │  执行操作   │
 * └─────────────┘     │  结果处理    │     └─────────────┘
 *                     └──────────────┘
 * ```
 * 
 * **使用示例**：
 * 
 * ```java
 * // 1. 创建工具注册中心
 * ToolRegistry registry = new ToolRegistry();
 * 
 * // 2. 注册内置工具
 * registry.register(new ReadFileTool("/workspace"));
 * registry.register(new WriteFileTool("/workspace"));
 * registry.register(new ExecTool());
 * 
 * // 3. 获取工具定义（用于 LLM）
 * List<JsonNode> definitions = registry.getDefinitions();
 * 
 * // 4. 执行工具
 * Map<String, Object> params = Map.of("path", "/tmp/test.txt");
 * Object result = registry.execute("read_file", params);
 * 
 * // 5. 注销工具
 * registry.unregister("read_file");
 * ```
 * 
 * **并发执行示例**：
 * 
 * ```java
 * // 假设有两个只读工具同时执行
 * ToolCall call1 = new ToolCall("read_file", Map.of("path", "/tmp/a.txt"));
 * ToolCall call2 = new ToolCall("read_file", Map.of("path", "/tmp/b.txt"));
 * 
 * // 并发执行（如果工具是 concurrencySafe 的）
 * List<CompletableFuture<Object>> futures = registry.executeAll(
 *     List.of(call1, call2),
 *     true  // 启用并发
 * );
 * ```
 */
public class ToolRegistry {
    
    // ==================== 日志 ====================
    
    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);
    
    // ==================== 工具存储 ====================
    
    /**
     * 工具存储
     * 
     * 使用 ConcurrentHashMap 实现线程安全的工具存储。
     * key: 工具名称
     * value: 工具实例
     */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    
    /**
     * 缓存的工具定义列表
     * 
     * 当工具注册或注销时，缓存失效。
     * 避免频繁序列化工具定义。
     */
    private volatile List<JsonNode> cachedDefinitions = null;

    // ==================== 安全守卫 ====================

    /** 权限编排管理器（统一入口） */
    private PermissionManager permissionManager;

    /** 文件路径守卫（向后兼容，Phase 1 遗留） */
    private PathGuard pathGuard;

    /** Shell 命令守卫（向后兼容） */
    private CommandGuard commandGuard;

    /** 网络安全守卫（向后兼容） */
    private NetworkGuard networkGuard;

    // ==================== 执行器 ====================
    
    /**
     * 工具执行的线程池
     * 
     * 用于异步执行工具。
     * 线程池大小可根据工具数量动态调整。
     */
    private final ExecutorService executor;
    
    /**
     * 最大并发工具数
     */
    private static final int MAX_CONCURRENT_TOOLS = 10;
    
    // ==================== 构造函数 ====================
    
    /**
     * 创建工具注册中心
     * 
     * 使用默认配置的线程池。
     */
    public ToolRegistry() {
        this.executor = Executors.newFixedThreadPool(
            MAX_CONCURRENT_TOOLS,
            r -> {
                Thread t = new Thread(r, "ToolExecutor");
                t.setDaemon(true);
                return t;
            }
        );
        logger.info("ToolRegistry initialized");
    }
    
    /**
     * 创建带自定义线程池的工具注册中心
     * 
     * @param executor 自定义的执行器
     */
    public ToolRegistry(ExecutorService executor) {
        this.executor = executor;
        logger.info("ToolRegistry initialized with custom executor");
    }
    
    // ==================== 工具注册 ====================
    
    /**
     * 注册工具
     * 
     * 如果已存在同名工具，会覆盖旧工具。
     * 
     * @param tool 要注册的工具实例
     * @throws IllegalArgumentException 如果工具为 null
     */
    public void register(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("Tool cannot be null");
        }
        
        String name = tool.getName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be empty");
        }
        
        tools.put(name, tool);
        invalidateCache();
        
        logger.debug("Registered tool: {}", name);
    }
    
    /**
     * 批量注册工具
     * 
     * @param toolList 要注册的工具列表
     */
    public void registerAll(Collection<Tool> toolList) {
        for (Tool tool : toolList) {
            register(tool);
        }
    }
    
    /**
     * 注销工具
     * 
     * @param name 工具名称
     * @return 被注销的工具实例，如果没有则返回 null
     */
    public Tool unregister(String name) {
        Tool removed = tools.remove(name);
        if (removed != null) {
            invalidateCache();
            logger.debug("Unregistered tool: {}", name);
        }
        return removed;
    }
    
    /**
     * 清除所有工具
     */
    public void clear() {
        tools.clear();
        invalidateCache();
        logger.info("All tools cleared");
    }
    
    // ==================== 工具查询 ====================
    
    /**
     * 获取工具实例
     * 
     * @param name 工具名称
     * @return 工具实例，如果不存在则返回 null
     */
    public Tool get(String name) {
        return tools.get(name);
    }
    
    /**
     * 检查工具是否存在
     * 
     * @param name 工具名称
     * @return 如果存在返回 true
     */
    public boolean has(String name) {
        return tools.containsKey(name);
    }
    
    /**
     * 获取所有工具名称
     * 
     * @return 工具名称列表
     */
    public List<String> getToolNames() {
        return new ArrayList<>(tools.keySet());
    }
    
    /**
     * 获取注册的工具数量
     * 
     * @return 工具数量
     */
    public int size() {
        return tools.size();
    }
    
    /**
     * 检查是否为空
     * 
     * @return 如果没有注册任何工具返回 true
     */
    public boolean isEmpty() {
        return tools.isEmpty();
    }
    
    // ==================== 工具定义 ====================
    
    /**
     * 获取所有工具的定义
     * 
     * 用于生成 LLM 的函数调用 schema。
     * 返回稳定的排序顺序（内置工具优先，MCP 工具在后）。
     * 
     * @return 工具定义的 JSON 列表
     */
    public List<JsonNode> getDefinitions() {
        // 使用缓存避免频繁序列化
        if (cachedDefinitions != null) {
            return cachedDefinitions;
        }
        
        // 分类工具：内置工具 vs MCP 工具
        List<JsonNode> builtins = new ArrayList<>();
        List<JsonNode> mcpTools = new ArrayList<>();
        
        for (Tool tool : tools.values()) {
            JsonNode schema = tool.toFunctionSchema();
            if (tool.getName().startsWith("mcp_")) {
                mcpTools.add(schema);
            } else {
                builtins.add(schema);
            }
        }
        
        // 内置工具按名称排序，MCP 工具也按名称排序
        builtins.sort(Comparator.comparing(n -> n.get("function").get("name").asText()));
        mcpTools.sort(Comparator.comparing(n -> n.get("function").get("name").asText()));
        
        // 内置工具在前，MCP 工具在后
        List<JsonNode> result = new ArrayList<>(builtins);
        result.addAll(mcpTools);
        
        cachedDefinitions = result;
        return result;
    }
    
    /**
     * 使缓存失效
     * 
     * 当工具注册或注销时调用。
     */
    private void invalidateCache() {
        cachedDefinitions = null;
    }
    
    // ==================== 工具执行 ====================
    
    /**
     * 执行工具
     * 
     * 这是最常用的工具执行方法。
     * 
     * 执行流程：
     * 1. 验证参数
     * 2. 查找工具
     * 3. 执行工具
     * 4. 处理结果
     * 
     * @param name 工具名称
     * @param params 工具参数
     * @return 执行结果
     * @throws ToolNotFoundException 如果工具不存在
     * @throws ToolExecutionException 如果执行失败
     */
    public Object execute(String name, Map<String, Object> params) {
        // 1. 参数预处理
        if (params == null) {
            params = Collections.emptyMap();
        }
        
        // 2. 查找工具
        Tool tool = tools.get(name);
        if (tool == null) {
            String error = String.format("Tool '%s' not found. Available tools: %s", 
                                        name, String.join(", ", tools.keySet()));
            logger.warn(error);
            return "Error: " + error;
        }
        
        // 3. 验证参数
        List<String> errors = tool.validateParameters(params);
        if (!errors.isEmpty()) {
            String error = String.format("Invalid parameters for tool '%s': %s",
                                        name, String.join("; ", errors));
            logger.warn(error);
            return "Error: " + error;
        }

        // 4. 权限检查（Phase 2: PermissionManager 编排）
        if (permissionManager != null) {
            PermissionResult result = permissionManager.check(tool, params);
            if (result.isDenied()) {
                return "Permission denied: " + result.getReason();
            }
        } else {
            // 向后兼容：未配置 PermissionManager 时使用旧版 applyGuards
            try {
                params = applyGuards(name, params);
            } catch (SecurityException e) {
                logger.warn("Tool '{}' blocked by {}: {}", name, e.getGuard(), e.getReason());
                return "Security blocked: " + e.getMessage();
            }
        }

        // 5. 执行工具
        try {
            CompletableFuture<Object> future = tool.execute(params);
            Object result = future.join();
            
            // 5. 处理结果
            if (result instanceof String) {
                String strResult = (String) result;
                // 如果工具返回以 Error 开头的字符串，加上提示
                if (strResult.startsWith("Error:")) {
                    return strResult + "\n\n[Analyze the error above and try a different approach.]";
                }
                return result;
            }
            
            return result != null ? result.toString() : "null";
            
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String error = "Error executing " + name + ": " + cause.getMessage();
            logger.error(error, cause);
            return error + "\n\n[Analyze the error above and try a different approach.]";
        } catch (Exception e) {
            String error = "Error executing " + name + ": " + e.getMessage();
            logger.error(error, e);
            return error + "\n\n[Analyze the error above and try a different approach.]";
        }
    }
    
    /**
     * 异步执行工具
     * 
     * @param name 工具名称
     * @param params 工具参数
     * @return 表示执行结果的 CompletableFuture
     */
    public CompletableFuture<Object> executeAsync(String name, Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> execute(name, params), executor);
    }
    
    /**
     * 批量执行工具
     * 
     * 支持并发和串行两种模式。
     * 
     * 并发模式：
     * - 只读且非独占的工具会并行执行
     * - 独占工具会单独执行
     * 
     * @param calls 工具调用列表
     * @param concurrent 是否启用并发
     * @return 执行结果列表（顺序与输入对应）
     */
    public List<Object> executeAll(List<ToolCall> calls, boolean concurrent) {
        if (calls == null || calls.isEmpty()) {
            return Collections.emptyList();
        }
        
        if (!concurrent) {
            // 串行执行
            return calls.stream()
                .map(call -> execute(call.name(), call.params()))
                .collect(Collectors.toList());
        }
        
        // 并发执行
        List<CompletableFuture<Object>> futures = calls.stream()
            .map(call -> CompletableFuture.supplyAsync(
                () -> execute(call.name(), call.params()), 
                executor
            ))
            .collect(Collectors.toList());
        
        // 等待所有任务完成
        return futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
    }
    
    /**
     * 准备工具调用
     * 
     * 执行参数验证和转换，但不执行工具。
     * 
     * @param name 工具名称
     * @param params 原始参数
     * @return 预处理结果，包含工具实例和转换后的参数
     * @throws ToolNotFoundException 如果工具不存在
     * @throws ToolValidationException 如果参数验证失败
     */
    public PrepareResult prepareCall(String name, Map<String, Object> params) {
        if (params == null) {
            params = Collections.emptyMap();
        }
        
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new ToolNotFoundException(
                String.format("Tool '%s' not found. Available: %s", name, tools.keySet())
            );
        }
        
        List<String> errors = tool.validateParameters(params);
        if (!errors.isEmpty()) {
            throw new ToolValidationException(
                String.format("Invalid parameters for '%s': %s", name, String.join("; ", errors))
            );
        }
        
        return new PrepareResult(tool, params);
    }
    
    // ==================== 工具调用记录 ====================
    
    /**
     * 工具调用记录
     * 
     * 用于批量执行时记录调用信息。
     */
    public record ToolCall(String name, Map<String, Object> params) {}
    
    /**
     * 预处理结果
     * 
     * 包含验证后的工具和参数。
     */
    public record PrepareResult(Tool tool, Map<String, Object> params) {}
    
    // ==================== 异常类 ====================
    
    /**
     * 工具未找到异常
     */
    public static class ToolNotFoundException extends RuntimeException {
        public ToolNotFoundException(String message) {
            super(message);
        }
    }
    
    /**
     * 工具验证异常
     */
    public static class ToolValidationException extends RuntimeException {
        public ToolValidationException(String message) {
            super(message);
        }
    }
    
    // ==================== 安全守卫 ====================

    /**
     * 设置权限编排管理器（推荐，替代单独设置守卫）
     */
    public void setPermissionManager(PermissionManager permissionManager) {
        this.permissionManager = permissionManager;
    }

    /**
     * 设置文件路径守卫
     */
    public void setPathGuard(PathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    /**
     * 设置 Shell 命令守卫
     */
    public void setCommandGuard(CommandGuard commandGuard) {
        this.commandGuard = commandGuard;
    }

    /**
     * 设置网络安全守卫
     */
    public void setNetworkGuard(NetworkGuard networkGuard) {
        this.networkGuard = networkGuard;
    }

    /**
     * 应用安全守卫 — 在工具执行前进行安全检查
     *
     * @return 可能被守卫修改过的参数（如路径被 PathGuard 解析为绝对路径）
     * @throws SecurityException 如果被任何守卫拦截
     */
    private Map<String, Object> applyGuards(String toolName, Map<String, Object> params) {
        Map<String, Object> securedParams = params;

        // PathGuard：文件类工具
        if (isFileTool(toolName) && pathGuard != null) {
            String pathParam = (String) params.get("path");
            if (pathParam != null) {
                java.nio.file.Path validatedPath = pathGuard.resolvePath(pathParam);
                // 替换为解析后的安全绝对路径
                securedParams = new HashMap<>(params);
                securedParams.put("path", validatedPath.toString());
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

        return securedParams;
    }

    /**
     * 判断是否为文件系统类工具
     */
    private boolean isFileTool(String name) {
        return Set.of("read_file", "write_file", "edit_file",
                "list_dir", "glob", "grep").contains(name);
    }

    /**
     * 判断是否为 Shell 执行工具
     */
    private boolean isShellTool(String name) {
        return "exec".equals(name);
    }

    /**
     * 判断是否为网络类工具
     */
    private boolean isNetworkTool(String name) {
        return Set.of("web_fetch", "web_search").contains(name);
    }

    // ==================== 生命周期 ====================
    
    /**
     * 关闭工具注册中心
     * 
     * 关闭线程池，释放资源。
     */
    public void shutdown() {
        executor.shutdown();
        logger.info("ToolRegistry shutdown");
    }
    
    /**
     * 获取工具统计信息
     * 
     * @return 统计信息字符串
     */
    public String getStats() {
        long readOnlyCount = tools.values().stream()
            .filter(Tool::isReadOnly)
            .count();
        long concurrencySafeCount = tools.values().stream()
            .filter(Tool::isConcurrencySafe)
            .count();
        
        return String.format(
            "ToolRegistry: %d tools registered (%d read-only, %d concurrency-safe)",
            tools.size(), readOnlyCount, concurrencySafeCount
        );
    }
}
