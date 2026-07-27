package com.nanobot.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;

/**
 * MCP JSON-RPC 2.0 消息定义。
 *
 * MCP 协议基于 JSON-RPC 2.0，所有消息必须包含 {@code "jsonrpc":"2.0"}。
 *
 * <h3>请求格式</h3>
 * <pre>{@code {"jsonrpc":"2.0","id":"1","method":"tools/call","params":{...}}}</pre>
 *
 * <h3>响应格式</h3>
 * <pre>{@code {"jsonrpc":"2.0","id":"1","result":{"content":[...]}}}</pre>
 */
public class JsonRpcMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonRpcMessage() {}

    // ═══════════ 标准方法名 ═══════════

    public static final String INITIALIZE = "initialize";
    public static final String INITIALIZED = "notifications/initialized";
    public static final String TOOLS_LIST = "tools/list";
    public static final String TOOLS_CALL = "tools/call";
    public static final String PROMPTS_LIST = "prompts/list";
    public static final String PROMPTS_GET = "prompts/get";
    public static final String RESOURCES_LIST = "resources/list";
    public static final String RESOURCES_READ = "resources/read";
    public static final String PING = "ping";

    /** MCP 协议版本 */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    // ═══════════ 请求 ═══════════

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
            String jsonrpc,
            String id,
            String method,
            JsonNode params
    ) {
        /** 普通请求（有 id，期待响应） */
        public static Request create(String method, JsonNode params) {
            return new Request("2.0", UUID.randomUUID().toString(), method, params);
        }

        /** 通知（无 id，不期待响应） */
        public static Request notification(String method) {
            return new Request("2.0", null, method, null);
        }

        // ── 工厂方法 ──

        public static Request initialize() {
            ObjectNode caps = MAPPER.createObjectNode();
            caps.put("tools", true); caps.put("prompts", false); caps.put("resources", false);
            ObjectNode info = MAPPER.createObjectNode();
            info.put("name", "nanobot-java"); info.put("version", "2.0.0");
            ObjectNode params = MAPPER.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.set("capabilities", caps);
            params.set("clientInfo", info);
            return create(INITIALIZE, params);
        }

        public static Request listTools() { return create(TOOLS_LIST, null); }

        public static Request callTool(String toolName, Map<String, Object> arguments) {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", toolName);
            if (arguments != null && !arguments.isEmpty()) {
                params.set("arguments", MAPPER.valueToTree(arguments));
            }
            return create(TOOLS_CALL, params);
        }

        public static Request listPrompts() { return create(PROMPTS_LIST, null); }

        public static Request getPrompt(String promptName, Map<String, Object> arguments) {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", promptName);
            if (arguments != null && !arguments.isEmpty()) {
                params.set("arguments", MAPPER.valueToTree(arguments));
            }
            return create(PROMPTS_GET, params);
        }

        public static Request listResources() { return create(RESOURCES_LIST, null); }

        public static Request readResource(String uri) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("uri", uri);
            return create(RESOURCES_READ, node);
        }

        public static Request ping() { return create(PING, null); }

        public boolean isNotification() { return id == null; }
    }

    // ═══════════ 响应 ═══════════

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            String jsonrpc,
            String id,
            JsonNode result,
            Error error
    ) {
        public record Error(int code, String message, JsonNode data) {
            public static final int PARSE_ERROR = -32700;
            public static final int INVALID_REQUEST = -32600;
            public static final int METHOD_NOT_FOUND = -32601;
            public static final int INVALID_PARAMS = -32602;
            public static final int INTERNAL_ERROR = -32603;

            public static Error methodNotFound(String method) {
                return new Error(METHOD_NOT_FOUND, "Method not found: " + method, null);
            }

            public static Error internal(String message) {
                return new Error(INTERNAL_ERROR, message, null);
            }

            @Override public String toString() { return "[" + code + "] " + message; }
        }

        public boolean isSuccess() { return error == null; }
    }
}
