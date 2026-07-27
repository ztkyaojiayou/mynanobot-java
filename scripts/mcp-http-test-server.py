#!/usr/bin/env python3
"""Streamable HTTP MCP 测试 server — 单 POST 端点"""
import json, sys, uuid
from http.server import HTTPServer, BaseHTTPRequestHandler

TOOLS = [
    {"name": "echo", "description": "Echo the message",
     "inputSchema": {"type": "object", "properties": {"message": {"type": "string"}}}},
]

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length)) if length else {}
        method = body.get("method", "")
        rid = body.get("id")
        params = body.get("params", {}) or {}

        if method == "initialize":
            resp = {"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": True},
                "serverInfo": {"name": "http-test-server", "version": "1.0.0"}}}
        elif method == "tools/list":
            resp = {"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}}
        elif method == "tools/call":
            name = params.get("name", "")
            args = params.get("arguments", {}) or {}
            if name == "echo":
                msg = args.get("message", "")
                resp = {"jsonrpc": "2.0", "id": rid,
                        "result": {"content": [{"type": "text", "text": f"HTTP ECHO: {msg}"}]}}
            else:
                resp = {"jsonrpc": "2.0", "id": rid,
                        "error": {"code": -32601, "message": f"Unknown tool: {name}"}}
        elif method == "notifications/initialized":
            self.send_response(200); self.end_headers(); return
        else:
            resp = {"jsonrpc": "2.0", "id": rid,
                    "error": {"code": -32601, "message": f"Unknown: {method}"}}

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(resp).encode())

    def log_message(self, *args): pass  # 静默

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    srv = HTTPServer(("127.0.0.1", port), Handler)
    print(f"MCP HTTP test server on http://127.0.0.1:{port}/mcp", flush=True)
    srv.serve_forever()
