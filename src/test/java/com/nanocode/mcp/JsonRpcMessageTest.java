package com.nanocode.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 JSON-RPC 2.0 消息序列化/反序列化 */
class JsonRpcMessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("initialize 请求格式正确")
    void testInitializeRequest() throws Exception {
        JsonRpcMessage.Request req = JsonRpcMessage.Request.initialize();
        String json = mapper.writeValueAsString(req);

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"method\":\"initialize\""));
        assertTrue(json.contains("\"protocolVersion\":\"2024-11-05\""));
        assertTrue(json.contains("\"capabilities\""));
        assertTrue(json.contains("\"clientInfo\""));
        assertNotNull(req.id());  // initialize 有 id
    }

    @Test
    @DisplayName("tools/list 请求格式正确")
    void testListToolsRequest() throws Exception {
        JsonRpcMessage.Request req = JsonRpcMessage.Request.listTools();
        String json = mapper.writeValueAsString(req);

        assertTrue(json.contains("\"method\":\"tools/list\""));
        assertNotNull(req.id());
    }

    @Test
    @DisplayName("tools/call 请求格式正确")
    void testCallToolRequest() throws Exception {
        JsonRpcMessage.Request req = JsonRpcMessage.Request.callTool(
                "read_file", Map.of("path", "/tmp/test.txt"));
        String json = mapper.writeValueAsString(req);

        assertTrue(json.contains("\"method\":\"tools/call\""));
        assertTrue(json.contains("\"name\":\"read_file\""));
        assertTrue(json.contains("\"arguments\""));
        assertTrue(json.contains("\"path\":\"/tmp/test.txt\""));
    }

    @Test
    @DisplayName("通知无 id")
    void testNotification() throws Exception {
        JsonRpcMessage.Request notif = JsonRpcMessage.Request.notification(JsonRpcMessage.INITIALIZED);
        String json = mapper.writeValueAsString(new ObjectMapper().valueToTree(notif).toString());

        assertNull(notif.id());
        assertTrue(notif.isNotification());
    }

    @Test
    @DisplayName("标准 tools/list 响应反序列化")
    void testParseToolsListResponse() throws Exception {
        String resp = """
                {"jsonrpc":"2.0","id":"1","result":{"tools":[
                  {"name":"read_file","description":"Read a file","inputSchema":{"type":"object","properties":{"path":{"type":"string"}}}}
                ]}}""";
        JsonRpcMessage.Response response = mapper.readValue(resp, JsonRpcMessage.Response.class);

        assertTrue(response.isSuccess());
        assertNotNull(response.result());
        assertEquals("1", response.id());
        assertEquals(1, response.result().get("tools").size());
        assertEquals("read_file", response.result().get("tools").get(0).get("name").asText());
    }

    @Test
    @DisplayName("错误响应反序列化")
    void testParseErrorResponse() throws Exception {
        String resp = """
                {"jsonrpc":"2.0","id":"1","error":{"code":-32601,"message":"Method not found"}}""";
        JsonRpcMessage.Response response = mapper.readValue(resp, JsonRpcMessage.Response.class);

        assertFalse(response.isSuccess());
        assertNotNull(response.error());
        assertEquals(-32601, response.error().code());
        assertTrue(response.error().message().contains("Method not found"));
    }

    @Test
    @DisplayName("MCPResult 从标准响应解析工具结果")
    void testMCPResultFromToolCall() {
        String resp = """
                {"jsonrpc":"2.0","id":"1","result":{"content":[{"type":"text","text":"Hello World"}]}}""";
        JsonRpcMessage.Response response;
        try {
            response = mapper.readValue(resp, JsonRpcMessage.Response.class);
        } catch (Exception e) {
            fail(e);
            return;
        }
        MCPResult result = MCPResult.fromJsonRpcResponse(response);

        assertTrue(result.isSuccess());
        assertEquals("Hello World", result.getTextContent());
    }
}
