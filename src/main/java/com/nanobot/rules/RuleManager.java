package com.nanobot.rules;

import com.nanobot.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule 管理器
 * ============
 * 
 * 负责规则的加载、管理和提示词生成。
 * 
 * **规则搜索路径**（按优先级）：
 * 1. 项目级：CLAUDE.md（项目根目录）
 * 2. 项目级：.nanobot/rules/*.md
 * 3. 用户级：~/.nanobot/rules/*.md
 * 4. 全局级：内置默认规则
 * 
 * **使用示例**：
 * ```java
 * RuleManager manager = new RuleManager(config);
 * manager.loadRules();
 * 
 * // 获取合并后的规则提示词（用于注入系统提示词）
 * String rulesPrompt = manager.getRulesPrompt();
 * 
 * // 获取规则摘要
 * String summary = manager.getRulesSummary();
 * ```
 */
public class RuleManager {
    
    private static final Logger logger = LoggerFactory.getLogger(RuleManager.class);
    
    private final RuleRegistry registry = new RuleRegistry();
    private final Config config;
    private final List<Path> rulePaths = new ArrayList<>();
    
    public RuleManager(Config config) {
        this.config = config;
        initRulePaths();
    }
    
    /**
     * 初始化规则搜索路径
     */
    private void initRulePaths() {
        // 1. 项目级规则文件 CLAUDE.md
        Path claudeMd = Paths.get("CLAUDE.md");
        if (Files.exists(claudeMd)) {
            rulePaths.add(claudeMd.toAbsolutePath().normalize());
        }
        
        // 2. 项目级规则目录
        Path projectRules = Paths.get(".nanobot", "rules");
        if (Files.exists(projectRules)) {
            rulePaths.add(projectRules.toAbsolutePath().normalize());
        }
        
        // 3. 用户级规则目录
        Path userRules = Paths.get(System.getProperty("user.home"), ".nanobot", "rules");
        if (Files.exists(userRules)) {
            rulePaths.add(userRules.toAbsolutePath().normalize());
        }
        
        logger.info("Rule paths configured: {}", rulePaths);
    }
    
    /**
     * 加载所有规则
     */
    public void loadRules() {
        int loadedCount = 0;
        
        // 加载文件规则
        for (Path rulePath : rulePaths) {
            if (Files.isDirectory(rulePath)) {
                loadedCount += loadRulesFromDirectory(rulePath, Rule.RuleType.PROJECT);
            } else if (Files.isRegularFile(rulePath)) {
                loadedCount += loadRuleFromFile(rulePath, Rule.RuleType.PROJECT);
            }
        }
        
        // 加载内置规则
        loadBuiltinRules();
        
        logger.info("Loaded {} rules from {} paths", loadedCount, rulePaths.size());
    }
    
    /**
     * 从目录加载规则
     */
    private int loadRulesFromDirectory(Path dir, Rule.RuleType type) {
        int count = 0;
        
        try {
            List<Path> ruleFiles = Files.list(dir)
                .filter(p -> p.toString().endsWith(".md"))
                .toList();
            
            for (Path file : ruleFiles) {
                count += loadRuleFromFile(file, type);
            }
        } catch (IOException e) {
            logger.warn("Failed to scan rules directory {}: {}", dir, e.getMessage());
        }
        
        return count;
    }
    
    /**
     * 从文件加载规则
     */
    private int loadRuleFromFile(Path file, Rule.RuleType type) {
        try {
            Rule rule = RuleLoader.loadFromFile(file, type);
            registry.register(rule);
            logger.debug("Loaded rule: {} - {}", rule.getName(), rule.getDescription());
            return 1;
        } catch (IOException e) {
            logger.warn("Failed to load rule from {}: {}", file, e.getMessage());
            return 0;
        }
    }
    
    /**
     * 加载内置规则
     */
    private void loadBuiltinRules() {
        // 代码风格规则
        registerBuiltinRule("coding-style", 
            "Java 代码风格规范",
            """
            # Java 代码风格规范
            
            ## 命名规范
            - 类名：使用 PascalCase（首字母大写）
            - 方法名：使用 camelCase（首字母小写）
            - 变量名：使用 camelCase
            - 常量名：使用 UPPER_SNAKE_CASE
            
            ## 格式规范
            - 每行代码不超过 120 字符
            - 使用 4 空格缩进（不使用 Tab）
            - 左大括号与语句同行
            - 运算符前后各有一个空格
            
            ## 注释规范
            - 类和公共方法必须有 Javadoc 注释
            - 复杂逻辑需要注释说明
            - 避免冗余注释
            """, 100);
        
        // 安全规则
        registerBuiltinRule("security",
            "安全编码规范",
            """
            # 安全编码规范
            
            ## 输入验证
            - 所有外部输入必须进行验证
            - 防止 SQL 注入攻击
            - 防止 XSS 攻击
            
            ## 敏感信息
            - 禁止硬编码密码和密钥
            - 禁止在日志中打印敏感信息
            - 使用环境变量存储敏感配置
            
            ## 文件操作
            - 限制文件读写路径
            - 验证文件路径安全性
            - 禁止删除系统文件
            
            ## 网络请求
            - 验证 URL 安全性
            - 使用 HTTPS 协议
            - 设置合理的超时时间
            """, 50);
        
        // 响应规则
        registerBuiltinRule("response-style",
            "响应格式规范",
            """
            # 响应格式规范
            
            ## 语言要求
            - 使用用户提问的语言进行回复
            - 默认使用中文回复
            
            ## 格式要求
            - 使用 Markdown 格式
            - 代码块使用正确的语言标识
            - 保持回复简洁明了
            
            ## 输出质量
            - 提供完整的代码示例
            - 解释代码逻辑
            - 给出使用建议
            """, 150);
    }
    
    /**
     * 注册内置规则
     */
    private void registerBuiltinRule(String name, String description, String content, int priority) {
        Rule rule = new Rule() {
            @Override
            public String getName() {
                return name;
            }
            
            @Override
            public String getDescription() {
                return description;
            }
            
            @Override
            public String getContent() {
                return content;
            }
            
            @Override
            public RuleType getType() {
                return RuleType.BUILTIN;
            }
            
            @Override
            public int getPriority() {
                return priority;
            }
            
            @Override
            public boolean isEnabled() {
                return true;
            }
            
            @Override
            public String getSourcePath() {
                return "builtin";
            }
        };
        
        registry.register(rule);
    }
    
    /**
     * 获取规则提示词（用于注入系统提示词）
     */
    public String getRulesPrompt() {
        return registry.getRulesPrompt();
    }
    
    /**
     * 获取规则摘要
     */
    public String getRulesSummary() {
        return registry.getRulesSummary();
    }
    
    /**
     * 获取规则注册中心
     */
    public RuleRegistry getRegistry() {
        return registry;
    }
    
    /**
     * 重新加载规则
     */
    public void reloadRules() {
        registry.clear();
        loadRules();
    }
}