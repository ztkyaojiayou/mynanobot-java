package com.nanobot.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 工具接口 - Agent 能力的核心抽象
 * ====================================
 * 
 * 工具（Tool）是 Agent 与外部世界交互的主要方式。当 Agent 需要执行特定操作时，
 * 它会调用相应的工具。例如：读取文件、执行命令、搜索网页等。
 * 
 * **设计思想**：
 * 
 * 1. **接口驱动**：
 *    - 定义工具的标准契约
 *    - 所有工具必须实现这个接口
 *    - 确保 Agent 与工具之间的解耦
 * 
 * 2. **声明式**：
 *    - 工具通过属性描述自己的能力
 *    - 工具名称、描述、参数 schema 都是声明式的
 *    - 这些信息用于生成系统提示和函数调用
 * 
 * 3. **异步执行**：
 *    - 工具执行是异步的
 *    - 支持长时间运行的操作（如网络请求）
 *    - 使用 CompletableFuture 实现
 * 
 * **工具生命周期**：
 * 
 * 1. **注册**：工具实例注册到 ToolRegistry
 * 2. **发现**：Agent 通过 ToolRegistry 获取工具定义
 * 3. **调用**：Agent 决定调用哪个工具
 * 4. **执行**：ToolRegistry 执行工具的 execute 方法
 * 5. **结果**：工具返回执行结果
 * 
 * **内置工具列表**：
 * 
 * | 工具名 | 描述 | 类别 |
 * |--------|------|------|
 * | read_file | 读取文件内容 | 文件系统 |
 * | write_file | 写入文件内容 | 文件系统 |
 * | edit_file | 编辑文件（修改部分内容） | 文件系统 |
 * | list_dir | 列出目录内容 | 文件系统 |
 * | glob | 文件路径模式匹配 | 搜索 |
 * | grep | 文件内容搜索 | 搜索 |
 * | exec | 执行系统命令 | Shell |
 * | web_search | 网页搜索 | Web |
 * | web_fetch | 获取网页内容 | Web |
 * | ask_user | 向用户提问 | 交互 |
 * | spawn | 启动子代理 | 子代理 |
 * 
 * **使用示例**：
 * 
 * 定义一个自定义工具：
 * ```java
 * public class MyTool implements Tool {
 *     
 *     @Override
 *     public String getName() {
 *         return "my_custom_tool";
 *     }
 *     
 *     @Override
 *     public String getDescription() {
 *         return "执行自定义操作的工具";
 *     }
 *     
 *     @Override
 *     public JsonNode getParameters() {
 *         // 返回 JSON Schema 格式的参数定义
 *         ObjectMapper mapper = new ObjectMapper();
 *         return mapper.readTree("""
 *         {
 *           "type": "object",
 *           "properties": {
 *             "input": {
 *               "type": "string",
 *               "description": "输入参数"
 *             }
 *           },
 *           "required": ["input"]
 *         }
 *         """);
 *     }
 *     
 *     @Override
 *     public CompletableFuture<Object> execute(Map<String, Object> params) {
 *         String input = (String) params.get("input");
 *         // 执行逻辑...
 *         return CompletableFuture.completedFuture("结果: " + input);
 *     }
 *     
 *     @Override
 *     public boolean isReadOnly() {
 *         return false;  // 这个工具会修改状态
 *     }
 * }
 * 
 * // 注册工具
 * toolRegistry.register(new MyTool());
 * ```
 */
public interface Tool {
    
    // ==================== 工具标识 ====================
    
    /**
     * 获取工具名称
     * 
     * 工具名称是工具的唯一标识符，用于：
     * 1. LLM 函数调用时的函数名
     * 2. 工具注册和查找的键
     * 3. 日志和调试信息
     * 
     * 命名规范：
     * - 使用小写字母和下划线
     * - 使用有意义的名称
     * - 避免与内置工具重名
     * 
     * @return 工具名称，如 "read_file", "web_search"
     */
    String getName();
    
    /**
     * 获取工具描述
     * 
     * 工具描述用于：
     * 1. 帮助 LLM 理解何时应该使用这个工具
     * 2. 生成用户可见的帮助文档
     * 
     * 描述编写建议：
     * - 说明工具的功能
     * - 说明输入参数
     * - 说明返回值
     * - 说明使用场景
     * 
     * @return 工具的功能描述
     */
    String getDescription();
    
    // ==================== 参数定义 ====================
    
    /**
     * 获取工具参数的 JSON Schema
     * 
     * JSON Schema 用于：
     * 1. 验证 LLM 生成的参数
     * 2. 告诉 LLM 如何调用工具
     * 3. 生成用户界面表单
     * 
     * 返回值示例：
     * ```json
     * {
     *   "type": "object",
     *   "properties": {
     *     "path": {
     *       "type": "string",
     *       "description": "文件路径"
     *     },
     *     "content": {
     *       "type": "string",
     *       "description": "文件内容"
     *     }
     *   },
     *   "required": ["path", "content"]
     * }
     * ```
     * 
     * @return 参数定义的 JSON Schema
     */
    JsonNode getParameters();
    
    // ==================== 工具属性 ====================
    
    /**
     * 工具是否为只读
     * 
     * 只读工具：
     * - 不会修改系统状态
     * - 可以安全地与其他工具并行执行
     * - 例如：读取文件、搜索、查询等
     * 
     * 非只读工具：
     * - 会修改系统状态
     * - 执行时需要独占资源
     * - 例如：写入文件、执行命令等
     * 
     * @return true 表示只读，false 表示会修改状态
     */
    default boolean isReadOnly() {
        return false;
    }
    
    /**
     * 工具是否为独占执行
     * 
     * 独占工具：
     * - 执行时不能与其他工具并行
     * - 即使启用并发模式也会串行执行
     * - 用于需要原子性操作的场景
     * 
     * @return true 表示独占执行
     */
    default boolean isExclusive() {
        return false;
    }
    
    /**
     * 工具是否可以并发安全执行
     * 
     * 满足以下条件的工具可以并发执行：
     * 1. 只读（isReadOnly() 返回 true）
     * 2. 非独占（isExclusive() 返回 false）
     * 3. 内部实现是线程安全的
     * 
     * @return true 表示可以与其他工具并行执行
     */
    default boolean isConcurrencySafe() {
        return isReadOnly() && !isExclusive();
    }
    
    // ==================== 工具执行 ====================
    
    /**
     * 执行工具
     * 
     * 这是工具的核心方法，实现具体的工具逻辑。
     * 
     * 参数说明：
     * - 参数已经过类型转换和验证
     * - 参数名与 JSON Schema 中的属性名一致
     * - 缺失的可选参数为 null
     * 
     * 返回值说明：
     * - 通常返回字符串（操作结果或错误信息）
     * - 可以返回其他类型，但最终会转为字符串
     * - 错误信息通常以 "Error:" 开头
     * 
     * 执行建议：
     * 1. 验证参数合法性
     * 2. 执行实际操作
     * 3. 处理异常并返回有意义的错误信息
     * 4. 清理资源
     * 
     * @param params 工具参数
     * @return 执行结果（成功或错误信息）
     */
    CompletableFuture<Object> execute(Map<String, Object> params);
    
    // ==================== 辅助方法 ====================
    
    /**
     * 验证参数是否符合 schema
     * 
     * @param params 要验证的参数
     * @return 验证错误列表，空列表表示验证通过
     */
    default java.util.List<String> validateParameters(Map<String, Object> params) {
        return Schema.validate(params, getParameters());
    }
    
    /**
     * 转换为 OpenAI 函数格式
     * 
     * 用于与 OpenAI API 兼容的函数调用接口。
     * 
     * @return OpenAI 函数定义的 JSON 表示
     */
    default JsonNode toFunctionSchema() {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = 
                new com.fasterxml.jackson.databind.ObjectMapper();
            
            com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
            result.put("type", "function");
            
            com.fasterxml.jackson.databind.node.ObjectNode function = mapper.createObjectNode();
            function.put("name", getName());
            function.put("description", getDescription());
            function.set("parameters", getParameters());
            
            result.set("function", function);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create function schema", e);
        }
    }
    
    /**
     * 获取工具的简要信息
     * 
     * 用于日志和调试。
     */
    default String getSummary() {
        return getName() + ": " + getDescription();
    }
}
