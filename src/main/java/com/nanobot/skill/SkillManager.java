package com.nanobot.skill;

import com.nanobot.config.Config;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Skill 管理器
 * ============
 * 
 * 负责技能的加载、管理和执行。
 * 
 * **技能搜索路径**（按优先级）：
 * 1. 项目级：./.nanobot/skills/
 * 2. 用户级：~/.nanobot/skills/
 * 
 * **使用示例**：
 * ```java
 * SkillManager manager = new SkillManager(config);
 * manager.loadSkills();
 * 
 * // 手动调用技能
 * String result = manager.executeSkill("code-review", context, "src/main/java/");
 * 
 * // 自动触发匹配
 * List<Skill> matches = manager.findMatchingSkills("帮我审查代码");
 * ```
 */
@Getter
public class SkillManager {
    
    private static final Logger logger = LoggerFactory.getLogger(SkillManager.class);
    
    private final SkillRegistry registry = new SkillRegistry();
    private final Config config;
    private final List<Path> skillPaths = new ArrayList<>();
    
    public SkillManager(Config config) {
        this.config = config;
        initSkillPaths();
    }
    
    /**
     * 初始化技能搜索路径
     */
    private void initSkillPaths() {
        // 1. 项目级技能目录 {workspace}/.nanobot/skills/
        Path projectSkills = Paths.get(config.getNanobotDir(), "skills");
        if (Files.exists(projectSkills)) {
            skillPaths.add(projectSkills.toAbsolutePath().normalize());
        }

        // 2. 用户级技能目录 ~/.nanobot/skills/（跨项目共享,不变）
        Path userSkills = Paths.get(System.getProperty("user.home"), ".nanobot", "skills");
        if (Files.exists(userSkills)) {
            skillPaths.add(userSkills.toAbsolutePath().normalize());
        }
        
        // 3. 配置文件中指定的额外路径
        if (config != null && config.getSkills() != null) {
            List<String> extraPaths = config.getSkills().getPaths();
            if (extraPaths != null) {
                for (String path : extraPaths) {
                    Path p = Paths.get(path);
                    if (Files.exists(p)) {
                        skillPaths.add(p.toAbsolutePath().normalize());
                    }
                }
            }
        }
        
        logger.info("Skill paths configured: {}", skillPaths);
    }
    
    /**
     * 加载所有技能
     */
    public void loadSkills() {
        int loadedCount = 0;
        
        for (Path skillPath : skillPaths) {
            loadedCount += loadSkillsFromPath(skillPath);
        }
        
        // 加载内置技能
        loadBuiltinSkills();
        
        logger.info("Loaded {} skills from {} paths", loadedCount, skillPaths.size());
    }
    
    /**
     * 从指定路径加载技能
     */
    private int loadSkillsFromPath(Path basePath) {
        int count = 0;
        
        try {
            if (!Files.exists(basePath)) {
                return 0;
            }
            
            List<Path> skillDirs = Files.list(basePath)
                .filter(Files::isDirectory)
                .toList();
            
            for (Path skillDir : skillDirs) {
                try {
                    Skill skill = SkillLoader.loadFromDirectory(skillDir);
                    registry.register(skill);
                    count++;
                    logger.debug("Loaded skill: {} - {}", skill.getName(), skill.getDescription());
                } catch (IOException e) {
                    logger.warn("Failed to load skill from {}: {}", skillDir, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to scan skills directory {}: {}", basePath, e.getMessage());
        }
        
        return count;
    }
    
    /**
     * 加载内置技能
     */
    private void loadBuiltinSkills() {
        // 代码审查技能
        registerBuiltinSkill("code-review", 
            "代码审查：检查代码质量、安全性和最佳实践",
            """
            # 代码审查指南
            
            ## 审查要点
            
            1. **代码风格**
               - 检查命名规范是否符合项目标准
               - 检查代码格式是否统一
               - 检查注释是否完整
            
            2. **安全性**
               - 检查 SQL 注入风险
               - 检查敏感信息泄露
               - 检查输入验证
            
            3. **性能**
               - 检查不必要的循环
               - 检查重复计算
               - 检查资源泄漏
            
            4. **可维护性**
               - 检查代码复杂度
               - 检查重复代码
               - 检查方法长度
            
            ## 输出格式
            - 使用 Markdown 格式输出
            - 分点列出问题和建议
            - 提供优化建议
            """);
        
        // 文档生成技能
        registerBuiltinSkill("doc-generator",
            "文档生成：为代码生成文档",
            """
            # 文档生成指南
            
            ## 生成规则
            
            1. **类文档**
               - 类的职责和用途
               - 核心设计思想
               - 关键依赖关系
            
            2. **方法文档**
               - 方法功能描述
               - 参数说明
               - 返回值说明
               - 异常说明
            
            3. **使用示例**
               - 提供代码示例
               - 说明典型用例
            
            ## 输出格式
            - 使用 Javadoc 格式
            - 保持简洁清晰
            """);
        
        // 重构技能
        registerBuiltinSkill("refactor",
            "结构化重构：帮助改进代码结构",
            """
            # 重构指南
            
            ## 重构策略
            
            1. **识别问题**
               - 识别代码异味
               - 识别重复代码
               - 识别复杂逻辑
            
            2. **重构步骤**
               - 小步重构
               - 保持测试通过
               - 保持功能不变
            
            3. **常见重构模式**
               - 提取方法
               - 提取类
               - 简化条件
               - 消除重复
            
            ## 输出格式
            - 列出重构建议
            - 提供重构后代码
            - 说明重构收益
            """);
    }
    
    /**
     * 注册内置技能
     */
    private void registerBuiltinSkill(String name, String description, String content) {
        SkillMetadata metadata = new SkillMetadata();
        metadata.setName(name);
        metadata.setDescription(description);
        metadata.setAutoTrigger(true);
        
        Skill skill = new Skill() {
            @Override
            public String getName() {
                return name;
            }
            
            @Override
            public String getDescription() {
                return description;
            }
            
            @Override
            public String getArgumentHint() {
                return "[file-path]";
            }
            
            @Override
            public boolean isAutoTrigger() {
                return true;
            }
            
            @Override
            public String getContent() {
                return content;
            }
            
            @Override
            public String execute(Map<String, Object> context, String... args) {
                StringBuilder result = new StringBuilder();
                result.append("【技能: ").append(name).append("】\n\n");
                result.append(content);
                
                if (args.length > 0) {
                    result.append("\n\n【目标文件】: ").append(String.join(", ", args));
                }
                
                return result.toString();
            }
        };
        
        registry.register(skill);
    }
    
    /**
     * 执行技能
     */
    public String executeSkill(String name, Map<String, Object> context, String... args) {
        Skill skill = registry.get(name);
        if (skill == null) {
            return "技能 '" + name + "' 未找到。使用 /skills 查看可用技能。";
        }
        
        return skill.execute(context, args);
    }
    
    /**
     * 查找匹配的技能（用于自动触发）
     */
    public List<Skill> findMatchingSkills(String query) {
        return registry.findMatchingSkills(query, 0.3);
    }
    
    /**
     * 解析斜杠命令
     * 
     * @param input 用户输入
     * @return 技能名称和参数，或 null 如果不是技能调用
     */
    public SkillCall parseSlashCommand(String input) {
        if (!input.startsWith("/")) {
            return null;
        }
        
        // 去掉开头的 /
        String command = input.substring(1).trim();
        
        // 分割命令名和参数
        int spaceIndex = command.indexOf(' ');
        String skillName;
        String[] args;
        
        if (spaceIndex > 0) {
            skillName = command.substring(0, spaceIndex).trim();
            String argStr = command.substring(spaceIndex + 1).trim();
            args = argStr.isEmpty() ? new String[0] : argStr.split("\\s+");
        } else {
            skillName = command;
            args = new String[0];
        }
        
        if (registry.contains(skillName)) {
            return new SkillCall(skillName, args);
        }
        
        return null;
    }
    
    /**
     * 技能调用封装
     */
    public record SkillCall(String skillName, String[] args) {}
}