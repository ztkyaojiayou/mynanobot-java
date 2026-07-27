package com.nanobot.mcp;

import com.nanobot.config.Config;
import com.nanobot.config.ConfigLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 config.yaml 中 MCP 配置能被正确解析 */
class McpConfigParsingTest {

    @Test
    @DisplayName("解析 mcp_servers 配置 — 内存字符串")
    void testMcpServerParsing() {
        String yaml = """
                tools:
                  mcp_servers:
                    filesystem:
                      type: stdio
                      command: npx
                      args: ["-y", "test"]
                      enabled_tools: ["*"]
                      enable: true
                """;
        Config config = ConfigLoader.parse(yaml, "test");
        assertNotNull(config.getTools());
        assertNotNull(config.getTools().getMcpServers());
        assertFalse(config.getTools().getMcpServers().isEmpty(),
                "Expected mcpServers to have entries, got: " + config.getTools().getMcpServers());

        Config.MCPServerConfig fs = config.getTools().getMcpServers().get("filesystem");
        assertNotNull(fs);
        assertEquals("stdio", fs.getType());
        assertEquals("npx", fs.getCommand());
        assertTrue(fs.isEnable());
    }

    @Test
    @DisplayName("解析 mcp_servers 配置 — 实际 config.yaml 文件")
    void testMcpServerFromFile() throws Exception {
        java.nio.file.Path f = java.nio.file.Paths.get("config.yaml");
        if (!java.nio.file.Files.exists(f)) { System.out.println("config.yaml not found, skipping"); return; }

        Config config = ConfigLoader.load(f);
        System.out.println("tools: " + (config.getTools() != null));
        System.out.println("tools.mcpServers: " + config.getTools().getMcpServers());
        System.out.println("keys: " + config.getTools().getMcpServers().keySet());

        // 也检查顶层
        System.out.println("top-level mcpServers: " + config.getMcpServers());
        System.out.println("top-level keys: " + config.getMcpServers().keySet());
    }
}
