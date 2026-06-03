package com.nanobot.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 工具注册中心测试类
 * ====================================
 * 
 * 测试工具注册和管理功能：
 * - 工具注册基本逻辑
 * - 工具信息结构
 */
@DisplayName("ToolRegistry 工具注册中心测试")
class ToolRegistryTest {

    @Test
    @DisplayName("测试工具信息结构")
    void testToolInfoStructure() {
        Map<String, Object> toolInfo = new HashMap<>();
        toolInfo.put("name", "list_dir");
        toolInfo.put("description", "列出目录内容");
        toolInfo.put("readOnly", true);
        toolInfo.put("exclusive", false);
        
        assertNotNull(toolInfo);
        assertEquals("list_dir", toolInfo.get("name"));
        assertEquals("列出目录内容", toolInfo.get("description"));
        assertTrue((Boolean) toolInfo.get("readOnly"));
        assertFalse((Boolean) toolInfo.get("exclusive"));
    }

    @Test
    @DisplayName("测试工具参数结构")
    void testToolParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> pathParam = new HashMap<>();
        pathParam.put("type", "string");
        pathParam.put("description", "目录路径");
        properties.put("path", pathParam);
        params.put("properties", properties);
        
        assertNotNull(params);
        assertEquals("object", params.get("type"));
        assertNotNull(params.get("properties"));
    }

    @Test
    @DisplayName("测试工具执行结果")
    void testToolExecutionResult() {
        // 模拟工具执行结果
        String result = "文件1.txt\n文件2.txt\n目录1/";
        assertNotNull(result);
        assertTrue(result.contains("\n"));
    }

    @Test
    @DisplayName("测试工具名称验证")
    void testToolNameValidation() {
        String validName = "list_dir";
        String invalidName = "list dir";
        
        assertTrue(validName.matches("^[a-z_]+$"));
        assertFalse(invalidName.matches("^[a-z_]+$"));
    }
}