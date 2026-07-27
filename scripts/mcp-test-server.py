#!/usr/bin/env python3
"""最小 MCP stdio server — 只支持 initialize + tools/list + tools/call"""
import sys, json, uuid

def send_response(req_id, result):
    """发送 JSON-RPC 2.0 响应"""
    resp = {"jsonrpc": "2.0", "id": req_id, "result": result}
    sys.stdout.write(json.dumps(resp) + "\n")
    sys.stdout.flush()

def send_error(req_id, code, message):
    resp = {"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}}
    sys.stdout.write(json.dumps(resp) + "\n")
    sys.stdout.flush()

# 我们的工具定义
TOOLS = [
    {"name": "echo", "description": "Echo back the input",
     "inputSchema": {"type": "object", "properties": {"message": {"type": "string", "description": "Message to echo"}}, "required": ["message"]}},
    {"name": "add", "description": "Add two numbers",
     "inputSchema": {"type": "object", "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}}, "required": ["a", "b"]}},
]

def handle_call(tool_name, arguments, req_id):
    if tool_name == "echo":
        msg = arguments.get("message", "") if arguments else ""
        return send_response(req_id, {"content": [{"type": "text", "text": f"ECHO: {msg}"}]})
    elif tool_name == "add":
        a = arguments.get("a", 0) if arguments else 0
        b = arguments.get("b", 0) if arguments else 0
        return send_response(req_id, {"content": [{"type": "text", "text": f"{a} + {b} = {a + b}"}]})
    else:
        return send_error(req_id, -32601, f"Unknown tool: {tool_name}")

if __name__ == "__main__":
    # 禁止 stderr 输出（干扰 stdio 协议）
    sys.stderr = open("/dev/null", "w")

    for line in sys.stdin:
        line = line.strip()
        if not line or not line.startswith("{"):
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue

        method = req.get("method", "")
        rid = req.get("id")
        params = req.get("params", {}) or {}

        if method == "initialize":
            send_response(rid, {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": True},
                "serverInfo": {"name": "test-server", "version": "1.0.0"}
            })
        elif method == "notifications/initialized":
            pass  # 通知，无响应
        elif method == "tools/list":
            send_response(rid, {"tools": TOOLS})
        elif method == "tools/call":
            name = params.get("name", "")
            args = params.get("arguments", {})
            handle_call(name, args, rid)
        elif method == "ping":
            send_response(rid, {})
        else:
            send_error(rid, -32601, f"Method not found: {method}")
