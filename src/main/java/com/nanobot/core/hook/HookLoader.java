package com.nanobot.core.hook;

import com.nanobot.core.hook.impl.MetricsHook;
import com.nanobot.core.hook.impl.TracingHook;
import com.nanobot.core.hook.impl.ValidationHook;
import com.nanobot.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 钩子加载器 — 纯静态工具，从配置创建 {@link CompositeHook}。
 *
 * <pre>
 * CompositeHook hooks = HookLoader.loadHooks(config.getHooks());
 * </pre>
 */
public class HookLoader {

    private static final Logger logger = LoggerFactory.getLogger(HookLoader.class);

    private HookLoader() { /* 纯静态工具，禁止实例化 */ }

    /** 内置钩子工厂 */
    private static final Map<String, HookFactory> BUILTIN_HOOKS = Map.of(
        "logging", context -> new CompositeHook.LoggingHook(),
        "metrics", context -> new MetricsHook(),
        "tracing", context -> new TracingHook(),
        "validation", HookLoader::createValidationHook
    );

    // ═══════════ 公开 API ═══════════

    /** 从配置加载所有钩子 */
    public static CompositeHook loadHooks(Config.HooksConfig config) {
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

    /** 加载单个钩子（供外部扩展使用） */
    public static AgentHook loadHook(Map<String, Object> hookConfig) {
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

        // 尝试内置钩子
        HookFactory factory = BUILTIN_HOOKS.get(name.toLowerCase());
        if (factory != null) {
            return factory.create(hookConfig);
        }

        // 尝试通过全限定类名加载自定义钩子
        return loadCustomHook(name, hookConfig);
    }

    // ═══════════ 内部实现 ═══════════

    private static void loadDefaultHooks(CompositeHook composite) {
        composite.add(new CompositeHook.LoggingHook());
        composite.add(new MetricsHook());
        logger.info("Loaded default hooks: logging, metrics");
    }

    @SuppressWarnings("unchecked")
    private static AgentHook loadCustomHook(String className, Map<String, Object> config) {
        try {
            Class<?> clazz = Class.forName(className);
            if (!AgentHook.class.isAssignableFrom(clazz)) {
                logger.error("Class {} does not implement AgentHook", className);
                return null;
            }
            try {
                var constructor = clazz.getConstructor(Map.class);
                return (AgentHook) constructor.newInstance(config);
            } catch (NoSuchMethodException e) {
                return (AgentHook) clazz.getDeclaredConstructor().newInstance();
            }
        } catch (Exception e) {
            logger.error("Failed to load custom hook: {}", className, e);
            return null;
        }
    }

    private static AgentHook createValidationHook(Map<String, Object> config) {
        ValidationHook hook = new ValidationHook();

        Object maxLength = config.get("maxContentLength");
        if (maxLength instanceof Number n) hook.setMaxContentLength(n.intValue());

        Object sensitiveWords = config.get("sensitiveWords");
        if (sensitiveWords instanceof List<?> words) {
            hook.addSensitiveWords(words.stream()
                    .filter(o -> o instanceof String).map(o -> (String) o).toList());
        }

        Object filterEnabled = config.get("sensitiveWordFilterEnabled");
        if (filterEnabled instanceof Boolean b) hook.setSensitiveWordFilterEnabled(b);

        Object lengthEnabled = config.get("lengthLimitEnabled");
        if (lengthEnabled instanceof Boolean b) hook.setLengthLimitEnabled(b);

        return hook;
    }

    @FunctionalInterface
    private interface HookFactory {
        AgentHook create(Map<String, Object> config);
    }
}
