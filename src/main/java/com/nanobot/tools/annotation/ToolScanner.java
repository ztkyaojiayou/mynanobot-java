package com.nanobot.tools.annotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.tools.Tool;
import com.nanobot.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 工具扫描器 — 扫描带 @ToolDef 的方法，包装为 Tool 后自动注册。
 *
 * 支持两种方式：
 * 1. 类实现 Tool 接口 + @ToolDef 注解（类级别）
 * 2. 任意类的方法上标注 @ToolDef（方法级别，无需实现 Tool 接口）
 */
public class ToolScanner {

    private static final Logger logger = LoggerFactory.getLogger(ToolScanner.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 扫描并注册 */
    public int scanAndRegister(ToolRegistry registry, String... basePackages) {
        int count = 0;
        for (String pkg : basePackages) {
            count += scanMethodLevel(registry, pkg);
        }
        logger.info("Auto-registered {} tools via @ToolDef", count);
        return count;
    }

    /** 扫描方法级别的 @ToolDef 注解 */
    @SuppressWarnings("unchecked")
    private int scanMethodLevel(ToolRegistry registry, String basePackage) {
        int count = 0;

        // 扫描实现了 Tool 接口的类（类级别 @ToolDef）
        ClassPathScanningCandidateComponentProvider interfaceScanner =
                new ClassPathScanningCandidateComponentProvider(false);
        interfaceScanner.addIncludeFilter(new AssignableTypeFilter(Tool.class));

        // 扫描所有类（查找方法级别 @ToolDef）
        ClassPathScanningCandidateComponentProvider methodScanner =
                new ClassPathScanningCandidateComponentProvider(false);
        methodScanner.addIncludeFilter(new AnnotationTypeFilter(
                org.springframework.stereotype.Component.class));
        // 也扫描非 @Component 的普通类：用 AssignableTypeFilter(Object.class) 不现实，
        // 改用扫描包下所有类的方式：添加一个总是匹配的 filter
        methodScanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);

        java.util.Set<String> scanned = new java.util.HashSet<>();

        // 先处理类级别（实现 Tool 接口 + @ToolDef）
        for (BeanDefinition bd : interfaceScanner.findCandidateComponents(basePackage)) {
            try {
                Class<?> clazz = Class.forName(bd.getBeanClassName());
                if (!Tool.class.isAssignableFrom(clazz)) continue;
                ToolDef def = clazz.getAnnotation(ToolDef.class);
                if (def != null) {
                    if (!def.enabled()) continue;
                    Tool tool = (Tool) clazz.getDeclaredConstructor().newInstance();
                    registry.register(tool);
                    scanned.add(bd.getBeanClassName());
                    count++;
                    logger.debug("Registered class-level @ToolDef: {}", clazz.getSimpleName());
                }
            } catch (Exception e) {
                logger.warn("Failed to register class-level @ToolDef {}: {}",
                        bd.getBeanClassName(), e.getMessage());
            }
        }

        // 再处理方法级别 @ToolDef
        for (BeanDefinition bd : methodScanner.findCandidateComponents(basePackage)) {
            try {
                String className = bd.getBeanClassName();
                if (scanned.contains(className)) continue;
                Class<?> clazz = Class.forName(className);
                Object instance = null;

                for (Method method : clazz.getDeclaredMethods()) {
                    ToolDef def = method.getAnnotation(ToolDef.class);
                    if (def == null || !def.enabled()) continue;

                    if (instance == null) {
                        try {
                            instance = clazz.getDeclaredConstructor().newInstance();
                        } catch (Exception e) {
                            logger.warn("Cannot instantiate {} (needs no-arg constructor), skipping",
                                    clazz.getSimpleName());
                            break;
                        }
                    }

                    Tool tool = createMethodTool(instance, method, def);
                    registry.register(tool);
                    count++;
                    logger.debug("Registered method-level @ToolDef: {}.{}",
                            clazz.getSimpleName(), method.getName());
                }
            } catch (Exception e) {
                logger.warn("Failed to scan {}: {}", bd.getBeanClassName(), e.getMessage());
            }
        }
        return count;
    }

    /** 将 @ToolDef 方法包装为 Tool 接口 */
    private Tool createMethodTool(Object instance, Method method, ToolDef def) {
        String name = !def.name().isBlank() ? def.name() : camelToSnake(method.getName());
        String desc = !def.description().isBlank() ? def.description() : name;

        // 构建 JSON Schema
        JsonNode paramsSchema = buildParamsSchema(method);

        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return desc; }
            @Override public JsonNode getParameters() { return paramsSchema; }
            @Override public boolean isReadOnly() { return true; }

            @Override
            public CompletableFuture<Object> execute(Map<String, Object> params) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Object[] args = resolveArgs(method, params);
                        Object result = method.invoke(instance, args);
                        return result != null ? result.toString() : "(done)";
                    } catch (Exception e) {
                        logger.error("Tool {}.{} failed: {}", instance.getClass().getSimpleName(),
                                method.getName(), e.getMessage());
                        return "Error: " + e.getMessage();
                    }
                });
            }
        };
    }

    /** 根据方法参数构建 JSON Schema */
    private JsonNode buildParamsSchema(Method method) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        List<String> required = new ArrayList<>();

        for (Parameter param : method.getParameters()) {
            ToolParam tp = param.getAnnotation(ToolParam.class);
            ObjectNode prop = mapper.createObjectNode();
            String type = javaTypeToJsonType(param.getType());
            prop.put("type", type);
            prop.put("description", tp != null && !tp.description().isBlank()
                    ? tp.description() : param.getName());
            props.set(param.getName(), prop);
            if (tp != null && tp.required()) required.add(param.getName());
        }

        root.set("properties", props);
        if (!required.isEmpty()) {
            root.set("required", mapper.valueToTree(required));
        }
        return root;
    }

    /** 将 Map 参数解析为方法实参 */
    private Object[] resolveArgs(Method method, Map<String, Object> params) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            String name = parameters[i].getName();
            Object val = params.get(name);
            if (val == null) continue;
            Class<?> type = parameters[i].getType();
            if (type == int.class || type == Integer.class) {
                args[i] = val instanceof Number ? ((Number) val).intValue() : Integer.parseInt(val.toString());
            } else if (type == long.class || type == Long.class) {
                args[i] = val instanceof Number ? ((Number) val).longValue() : Long.parseLong(val.toString());
            } else if (type == double.class || type == Double.class) {
                args[i] = val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = val instanceof Boolean ? val : Boolean.parseBoolean(val.toString());
            } else {
                args[i] = val.toString();
            }
        }
        return args;
    }

    private String javaTypeToJsonType(Class<?> type) {
        if (type == int.class || type == long.class || type == Integer.class || type == Long.class) return "integer";
        if (type == double.class || type == float.class || type == Double.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        return "string";
    }

    private String camelToSnake(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
