package com.nanobot.core.hook;

import com.nanobot.core.hook.impl.MetricsHook;
import com.nanobot.core.hook.impl.TracingHook;
import com.nanobot.core.hook.impl.ValidationHook;
import com.nanobot.config.Config;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钩子加载器 - 从配置动态加载钩子
 * ================================
 * 
 * 支持从配置文件加载并实例化钩子。
 * 
 * **配置格式**：
 * 
 * ```yaml
 * hooks:
 *   enabled: true
 *   list:
 *     - name: logging
 *       enabled: true
 *     - name: metrics
 *       enabled: true
 *       config:
 *         sampleRate: 1.0
 *     - name: tracing
 *       enabled: true
 *     - name: validation
 *       enabled: true
 *       config:
 *         maxContentLength: 8192
 *         sensitiveWords:
 *           - 敏感词1
 *           - 敏感词2
 *     - name: com.example.CustomHook
 *       enabled: true
 *       config:
 *         customParam: value
 * ```
 * 
 * **内置钩子**：
 * - logging - 日志钩子（CompositeHook.LoggingHook）
 * - metrics - 指标收集钩子
 * - tracing - 链路追踪钩子
 * - validation - 内容验证钩子
 * 
 * **自定义钩子**：
 * 可以通过全限定类名加载自定义钩子实现。
 */
@Getter
public class HookLoader {
    
    private static final Logger logger = LoggerFactory.getLogger(HookLoader.class);
    
    /** 内置钩子工厂 */
    private static final Map<String, HookFactory> BUILTIN_HOOKS = Map.of(
        "logging", context -> new CompositeHook.LoggingHook(),
        "metrics", context -> new MetricsHook(),
        "tracing", context -> new TracingHook(),
        "validation", HookLoader::createValidationHook
    );
    
    /** 已加载的钩子实例缓存 */
    private final Map<String, AgentHook> hookCache = new ConcurrentHashMap<>();
    
    /** 配置 */
    private final Config.HooksConfig config;
    
    /**
     * 构造函数
     */
    public HookLoader(Config.HooksConfig config) {
        this.config = config;
    }
    
    /**
     * 加载所有配置的钩子并创建组合钩子
     */
    public CompositeHook loadHooks() {
        CompositeHook composite = new CompositeHook();
        
        if (!config.isEnabled()) {
            logger.info("Hooks are disabled in config");
            return composite;
        }
        
        List<Map<String, Object>> hookConfigs = config.getList();
        if (hookConfigs == null || hookConfigs.isEmpty()) {
            logger.info("No hooks configured, loading default hooks");
            loadDefaultHooks(composite);
            return composite;
        }
        
        for (Map<String, Object> hookConfig : hookConfigs) {
            try {
                AgentHook hook = loadHook(hookConfig);
                if (hook != null) {
                    composite.add(hook);
                    logger.info("Loaded hook: {}", hook.getName());
                }
            } catch (Exception e) {
                logger.error("Failed to load hook: {}", hookConfig, e);
            }
        }
        
        return composite;
    }
    
    /**
     * 加载单个钩子
     */
    public AgentHook loadHook(Map<String, Object> hookConfig) {
        String name = (String) hookConfig.get("name");
        if (name == null || name.isEmpty()) {
            logger.warn("Hook config missing 'name' field");
            return null;
        }
        
        Boolean enabled = (Boolean) hookConfig.get("enabled");
        if (enabled != null && !enabled) {
            logger.debug("Hook '{}' is disabled", name);
            return null;
        }
        
        // 检查缓存
        if (hookCache.containsKey(name)) {
            return hookCache.get(name);
        }
        
        AgentHook hook;
        
        // 尝试加载内置钩子
        HookFactory factory = BUILTIN_HOOKS.get(name.toLowerCase());
        if (factory != null) {
            hook = factory.create(hookConfig);
        } else {
            // 尝试加载自定义钩子（通过类名）
            hook = loadCustomHook(name, hookConfig);
        }
        
        if (hook != null) {
            hookCache.put(name, hook);
        }
        
        return hook;
    }
    
    /**
     * 加载默认钩子
     */
    private void loadDefaultHooks(CompositeHook composite) {
        // 默认加载日志和指标钩子
        composite.add(new CompositeHook.LoggingHook());
        composite.add(new MetricsHook());
        logger.info("Loaded default hooks: logging, metrics");
    }
    
    /**
     * 加载自定义钩子
     */
    private AgentHook loadCustomHook(String className, Map<String, Object> config) {
        try {
            Class<?> clazz = Class.forName(className);
            if (!AgentHook.class.isAssignableFrom(clazz)) {
                logger.error("Class {} does not implement AgentHook", className);
                return null;
            }
            
            // 尝试通过构造函数实例化
            // 优先尝试带 Map<String, Object> 参数的构造函数
            try {
                var constructor = clazz.getConstructor(Map.class);
                return (AgentHook) constructor.newInstance(config);
            } catch (NoSuchMethodException e) {
                // 尝试无参构造函数
                return (AgentHook) clazz.getDeclaredConstructor().newInstance();
            }
        } catch (Exception e) {
            logger.error("Failed to load custom hook: {}", className, e);
            return null;
        }
    }
    
    /**
     * 创建验证钩子（带配置）
     */
    private static AgentHook createValidationHook(Map<String, Object> config) {
        ValidationHook hook = new ValidationHook();
        
        // 配置最大长度
        Object maxLength = config.get("maxContentLength");
        if (maxLength instanceof Number) {
            hook.setMaxContentLength(((Number) maxLength).intValue());
        }
        
        // 配置敏感词
        Object sensitiveWords = config.get("sensitiveWords");
        if (sensitiveWords instanceof List) {
            List<?> words = (List<?>) sensitiveWords;
            List<String> wordList = words.stream()
                .filter(o -> o instanceof String)
                .map(o -> (String) o)
                .toList();
            hook.addSensitiveWords(wordList);
        }
        
        // 配置是否启用敏感词过滤
        Object filterEnabled = config.get("sensitiveWordFilterEnabled");
        if (filterEnabled instanceof Boolean) {
            hook.setSensitiveWordFilterEnabled((Boolean) filterEnabled);
        }
        
        // 配置是否启用长度限制
        Object lengthEnabled = config.get("lengthLimitEnabled");
        if (lengthEnabled instanceof Boolean) {
            hook.setLengthLimitEnabled((Boolean) lengthEnabled);
        }
        
        return hook;
    }
    
    /**
     * 获取已加载的钩子
     */
    public Map<String, AgentHook> getLoadedHooks() {
        return Map.copyOf(hookCache);
    }
    
    /**
     * 获取钩子数量
     */
    public int getLoadedHookCount() {
        return hookCache.size();
    }
    
    /**
     * 清除缓存
     */
    public void clearCache() {
        hookCache.clear();
    }
    
    // ==================== 内部接口 ====================
    
    @FunctionalInterface
    private interface HookFactory {
        AgentHook create(Map<String, Object> config);
    }
    
    // ==================== 便捷方法 ====================
    
    /**
     * 创建默认配置的钩子加载器
     */
    public static HookLoader createDefault() {
        Config.HooksConfig config = new Config.HooksConfig();
        config.setEnabled(true);
        return new HookLoader(config);
    }
}