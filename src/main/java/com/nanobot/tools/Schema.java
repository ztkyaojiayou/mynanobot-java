package com.nanobot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * JSON Schema 验证工具类
 * =========================
 * 
 * 本类提供了完整的 JSON Schema 验证功能，用于验证工具参数是否符合工具定义的 schema。
 * 这是实现类型安全的工具调用系统的核心组件。
 * 
 * **设计思想**：
 * 
 * 1. **简化实现**：
 *    - 不引入完整的 JSON Schema 库（如 Jackson Schema 模块）
 *    - 实现常用的验证规则，足以满足工具参数验证需求
 *    - 代码简洁易懂，适合学习目的
 * 
 * 2. **验证规则**：
 *    - 类型验证（string, number, integer, boolean, array, object）
 *    - 必填字段验证
 *    - 枚举值验证
 *    - 数值范围验证（minimum, maximum）
 *    - 字符串长度验证（minLength, maxLength）
 *    - 数组长度验证（minItems, maxItems）
 *    - 对象属性验证（properties, required）
 * 
 * 3. **验证结果**：
 *    - 返回验证错误列表
 *    - 包含字段路径和错误描述
 *    - 便于用户理解和修复
 * 
 * **使用示例**：
 * 
 * ```java
 * // 定义工具参数 schema
 * JsonNode schema = mapper.readTree("""
 * {
 *   "type": "object",
 *   "properties": {
 *     "name": {
 *       "type": "string",
 *       "minLength": 1,
 *       "maxLength": 100
 *     },
 *     "age": {
 *       "type": "integer",
 *       "minimum": 0,
 *       "maximum": 150
 *     },
 *     "tags": {
 *       "type": "array",
 *       "items": {"type": "string"},
 *       "minItems": 1,
 *       "maxItems": 10
 *     }
 *   },
 *   "required": ["name"]
 * }
 * """);
 * 
 * // 验证参数
 * Schema schemaValidator = new Schema();
 * List<String> errors = schemaValidator.validate(params, schema);
 * 
 * if (!errors.isEmpty()) {
 *     System.out.println("Validation failed:");
 *     errors.forEach(e -> System.out.println("  - " + e));
 * }
 * ```
 */
public class Schema {
    
    /**
     * 验证参数是否符合 schema
     * 
     * @param params 要验证的参数（JSON 对象）
     * @param schema JSON Schema 定义
     * @return 验证错误列表，如果验证通过则为空列表
     */
    public static List<String> validate(Map<String, Object> params, JsonNode schema) {
        List<String> errors = new ArrayList<>();
        validateValue(params, schema, "", errors);
        return errors;
    }
    
    /**
     * 验证单个值
     * 
     * @param value 要验证的值
     * @param schema 当前层级的 schema
     * @param path 当前字段的路径（用于错误信息）
     * @param errors 错误列表
     */
    public static void validateValue(Object value, JsonNode schema, String path, List<String> errors) {
        if (schema == null || schema.isNull()) {
            return;
        }
        
        String type = getJsonType(schema);
        if (type == null) {
            return;  // 没有类型定义，不验证
        }
        
        // 处理 nullable 类型
        boolean isNullable = isNullable(schema);
        if (value == null) {
            if (!isNullable && !isRequiredMissing(schema)) {
                errors.add(formatPath(path) + "cannot be null");
            }
            return;
        }
        
        // 类型验证
        switch (type) {
            case "string" -> validateString(value, schema, path, errors);
            case "number" -> validateNumber(value, schema, path, errors);
            case "integer" -> validateInteger(value, schema, path, errors);
            case "boolean" -> validateBoolean(value, path, errors);
            case "array" -> validateArray(value, schema, path, errors);
            case "object" -> validateObject(value, schema, path, errors);
            default -> {
                // 未知类型，跳过验证
            }
        }
    }
    
    // ==================== 字符串验证 ====================
    
    private static void validateString(Object value, JsonNode schema, String path, List<String> errors) {
        if (!(value instanceof String)) {
            errors.add(formatPath(path) + "expected string, got " + value.getClass().getSimpleName());
            return;
        }
        
        String str = (String) value;
        
        // 最小长度
        if (schema.has("minLength")) {
            int minLength = schema.get("minLength").asInt();
            if (str.length() < minLength) {
                errors.add(formatPath(path) + "must be at least " + minLength + " characters");
            }
        }
        
        // 最大长度
        if (schema.has("maxLength")) {
            int maxLength = schema.get("maxLength").asInt();
            if (str.length() > maxLength) {
                errors.add(formatPath(path) + "must be at most " + maxLength + " characters");
            }
        }
        
        // 枚举值
        if (schema.has("enum")) {
            ArrayNode enumValues = (ArrayNode) schema.get("enum");
            boolean found = false;
            for (JsonNode enumValue : enumValues) {
                if (str.equals(enumValue.asText())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add(formatPath(path) + "must be one of: " + enumValues);
            }
        }
        
        // 正则表达式
        if (schema.has("pattern")) {
            String pattern = schema.get("pattern").asText();
            if (!str.matches(pattern)) {
                errors.add(formatPath(path) + "does not match pattern: " + pattern);
            }
        }
    }
    
    // ==================== 数值验证 ====================
    
    private static void validateNumber(Object value, JsonNode schema, String path, List<String> errors) {
        if (!(value instanceof Number)) {
            errors.add(formatPath(path) + "expected number, got " + value.getClass().getSimpleName());
            return;
        }
        
        double num = ((Number) value).doubleValue();
        
        // 最小值
        if (schema.has("minimum")) {
            double minimum = schema.get("minimum").asDouble();
            if (num < minimum) {
                errors.add(formatPath(path) + "must be >= " + minimum);
            }
        }
        
        // 最大值
        if (schema.has("maximum")) {
            double maximum = schema.get("maximum").asDouble();
            if (num > maximum) {
                errors.add(formatPath(path) + "must be <= " + maximum);
            }
        }
        
        // 枚举值
        if (schema.has("enum")) {
            ArrayNode enumValues = (ArrayNode) schema.get("enum");
            boolean found = false;
            for (JsonNode enumValue : enumValues) {
                if (Math.abs(num - enumValue.asDouble()) < 0.0001) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add(formatPath(path) + "must be one of: " + enumValues);
            }
        }
    }
    
    // ==================== 整数验证 ====================
    
    private static void validateInteger(Object value, JsonNode schema, String path, List<String> errors) {
        if (!(value instanceof Integer || value instanceof Long)) {
            // 允许从字符串转换
            if (value instanceof String) {
                try {
                    Long.parseLong((String) value);
                    return;  // 转换成功，验证通过
                } catch (NumberFormatException e) {
                    // 转换失败，继续报错
                }
            }
            errors.add(formatPath(path) + "expected integer, got " + value.getClass().getSimpleName());
            return;
        }
        
        long num = ((Number) value).longValue();
        
        // 最小值
        if (schema.has("minimum")) {
            long minimum = schema.get("minimum").asLong();
            if (num < minimum) {
                errors.add(formatPath(path) + "must be >= " + minimum);
            }
        }
        
        // 最大值
        if (schema.has("maximum")) {
            long maximum = schema.get("maximum").asLong();
            if (num > maximum) {
                errors.add(formatPath(path) + "must be <= " + maximum);
            }
        }
        
        // 枚举值
        if (schema.has("enum")) {
            ArrayNode enumValues = (ArrayNode) schema.get("enum");
            boolean found = false;
            for (JsonNode enumValue : enumValues) {
                if (num == enumValue.asLong()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add(formatPath(path) + "must be one of: " + enumValues);
            }
        }
    }
    
    // ==================== 布尔验证 ====================
    
    private static void validateBoolean(Object value, String path, List<String> errors) {
        if (!(value instanceof Boolean)) {
            // 允许字符串 "true"/"false" 转换
            if (value instanceof String) {
                String str = ((String) value).toLowerCase();
                if (str.equals("true") || str.equals("false")) {
                    return;  // 可以转换，验证通过
                }
            }
            errors.add(formatPath(path) + "expected boolean, got " + value.getClass().getSimpleName());
        }
    }
    
    // ==================== 数组验证 ====================
    
    private static void validateArray(Object value, JsonNode schema, String path, List<String> errors) {
        if (!(value instanceof List)) {
            errors.add(formatPath(path) + "expected array, got " + value.getClass().getSimpleName());
            return;
        }
        
        List<?> list = (List<?>) value;
        
        // 最小元素数量
        if (schema.has("minItems")) {
            int minItems = schema.get("minItems").asInt();
            if (list.size() < minItems) {
                errors.add(formatPath(path) + "must have at least " + minItems + " items");
            }
        }
        
        // 最大元素数量
        if (schema.has("maxItems")) {
            int maxItems = schema.get("maxItems").asInt();
            if (list.size() > maxItems) {
                errors.add(formatPath(path) + "must have at most " + maxItems + " items");
            }
        }
        
        // 唯一性
        if (schema.has("uniqueItems") && schema.get("uniqueItems").asBoolean()) {
            List<?> copy = new ArrayList<>(list);
            if (copy.stream().distinct().count() != copy.size()) {
                errors.add(formatPath(path) + "must have unique items");
            }
        }
        
        // 元素类型验证
        if (schema.has("items")) {
            JsonNode itemSchema = schema.get("items");
            for (int i = 0; i < list.size(); i++) {
                validateValue(list.get(i), itemSchema, path + "[" + i + "]", errors);
            }
        }
    }
    
    // ==================== 对象验证 ====================
    
    private static void validateObject(Object value, JsonNode schema, String path, List<String> errors) {
        if (!(value instanceof Map)) {
            errors.add(formatPath(path) + "expected object, got " + value.getClass().getSimpleName());
            return;
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) value;
        
        // 必填字段
        if (schema.has("required") && schema.get("required").isArray()) {
            for (JsonNode requiredField : schema.get("required")) {
                String fieldName = requiredField.asText();
                if (!obj.containsKey(fieldName) || obj.get(fieldName) == null) {
                    errors.add(formatPath(path) + "missing required field: " + fieldName);
                }
            }
        }
        
        // 属性验证
        if (schema.has("properties")) {
            JsonNode properties = schema.get("properties");
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                String fieldName = entry.getKey();
                Object fieldValue = entry.getValue();
                
                if (properties.has(fieldName)) {
                    JsonNode fieldSchema = properties.get(fieldName);
                    validateValue(fieldValue, fieldSchema, path + "." + fieldName, errors);
                }
            }
        }
        
        // 额外属性检查
        if (schema.has("additionalProperties")) {
            JsonNode additionalProps = schema.get("additionalProperties");
            if (additionalProps.isBoolean() && !additionalProps.asBoolean()) {
                // 不允许额外属性
                if (schema.has("properties")) {
                    JsonNode properties = schema.get("properties");
                    for (String fieldName : obj.keySet()) {
                        if (!properties.has(fieldName)) {
                            errors.add(formatPath(path) + "additional property not allowed: " + fieldName);
                        }
                    }
                }
            }
        }
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 获取 JSON Schema 类型
     */
    private static String getJsonType(JsonNode schema) {
        if (schema.has("type")) {
            JsonNode type = schema.get("type");
            if (type.isTextual()) {
                return type.asText();
            }
            // 处理联合类型，如 ["string", "null"]
            if (type.isArray()) {
                for (JsonNode t : type) {
                    if (!t.isNull() && t.isTextual()) {
                        String typeStr = t.asText();
                        if (!typeStr.equals("null")) {
                            return typeStr;
                        }
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * 检查是否为 nullable
     */
    private static boolean isNullable(JsonNode schema) {
        if (schema.has("type")) {
            JsonNode type = schema.get("type");
            if (type.isArray()) {
                for (JsonNode t : type) {
                    if (t.isTextual() && t.asText().equals("null")) {
                        return true;
                    }
                }
            }
        }
        if (schema.has("nullable") && schema.get("nullable").asBoolean()) {
            return true;
        }
        return false;
    }
    
    /**
     * 检查是否为缺失的必填字段
     */
    private static boolean isRequiredMissing(JsonNode schema) {
        // 这是一个简化实现
        return false;
    }
    
    /**
     * 格式化路径
     */
    private static String formatPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        if (path.startsWith(".")) {
            return path.substring(1);
        }
        return path + ": ";
    }
}
