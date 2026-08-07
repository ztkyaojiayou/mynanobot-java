package com.nanocode.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanocode.config.Config;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 集成测试：StreamableHttpMCPClient + SseMCPClient 对真实 HTTP server */
class McpHttpClientTest {

    private static Process httpServer;
    private static Process sseServer;

    @BeforeAll
    static void startServers() throws Exception {
        String scriptDir = "scripts";
        httpServer = new ProcessBuilder("python3", scriptDir + "/mcp-http-test-server.py", "18765")
                .inheritIO().start();
        Thread.sleep(500);

        sseServer = new ProcessBuilder("python3", scriptDir + "/mcp-sse-test-server.py", "18766")
                .inheritIO().start();
        // Wait for server to be ready (try connecting up to 10 times)
        for (int i = 0; i < 20; i++) {
            Thread.sleep(200);
            try {
                new java.net.Socket("127.0.0.1", 18766).close();
                break;
            } catch (Exception e) { /* not ready yet */ }
        }
    }

    @AfterAll
    static void stopServers() {
        if (httpServer != null) httpServer.destroyForcibly();
        if (sseServer != null) sseServer.destroyForcibly();
    }

    @Test
    @DisplayName("Streamable HTTP: initialize + listTools + callTool")
    void testStreamableHttp() throws Exception {
        Config.MCPServerConfig cfg = new Config.MCPServerConfig();
        cfg.setType("streamableHttp");
        cfg.setUrl("http://127.0.0.1:18765/mcp");
        cfg.setEndpoint("http://127.0.0.1:18765/mcp");
        cfg.setToolTimeout(10);

        StreamableHttpMCPClient client = new StreamableHttpMCPClient("http-test", cfg);
        try {
            List<MCPToolInfo> tools = client.listTools().get();
            assertEquals(1, tools.size());
            assertEquals("echo", tools.get(0).getName());
            assertTrue(tools.get(0).isReadOnly(), "echo should be read-only");

            MCPResult result = client.callTool("echo", Map.of("message", "hello")).get();
            assertTrue(result.isSuccess());
            assertTrue(result.getTextContent().contains("HTTP ECHO: hello"),
                    "Expected HTTP ECHO prefix, got: " + result.getTextContent());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("SSE: initialize + listTools + callTool")
    void testSse() throws Exception {
        Config.MCPServerConfig cfg = new Config.MCPServerConfig();
        cfg.setType("sse");
        cfg.setUrl("http://127.0.0.1:18766");
        cfg.setToolTimeout(10);

        SseMCPClient client = new SseMCPClient("sse-test", cfg);
        try {
            List<MCPToolInfo> tools = client.listTools().get();
            assertEquals(1, tools.size());
            assertEquals("echo", tools.get(0).getName());

            MCPResult result = client.callTool("echo", Map.of("message", "world")).get();
            assertTrue(result.isSuccess());
            assertTrue(result.getTextContent().contains("SSE ECHO: world"),
                    "Expected SSE ECHO prefix, got: " + result.getTextContent());
        } finally {
            client.close();
        }
    }
}
